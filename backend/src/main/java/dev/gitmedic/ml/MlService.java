package dev.gitmedic.ml;

import static dev.gitmedic.common.Db.ts;

import dev.gitmedic.ml.MlClient.MlRejectedException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Orchestrates FastAPI calls and persists what comes back (TBD-07: Spring writes predictions).
 * Callers handle {@link MlClient.MlUnavailableException} by degrading (NFR-008).
 */
@Service
public class MlService {

  private static final Logger log = LoggerFactory.getLogger(MlService.class);
  private static final int PREDICT_BATCH = 500;

  /** Serialized JSON per request, under FastAPI's 2 MB body limit with headroom (§7.2). */
  private static final int BATCH_BYTES = 1_500_000;

  /** The label model reads at most 8,000 characters of title + body; the rest is not sent. */
  private static final int LABEL_BODY_CHARS = 8000;

  /** Duplicate search and answers embed title + the first 1,500 body characters (§5.8). */
  private static final int QUERY_BODY_CHARS = 1500;

  public record Document(String source_type, String source_ref, String source_url, String text) {}

  private final MlClient ml;
  private final JdbcClient jdbc;
  private final ObjectMapper mapper;
  private final Clock clock;

  MlService(MlClient ml, JdbcClient jdbc, ObjectMapper mapper, Clock clock) {
    this.ml = ml;
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.clock = clock;
  }

  public boolean available() {
    return ml.available();
  }

  /** Sends documents in size-bounded batches; returns {upserted, skipped}. */
  public int[] index(long repoId, List<Document> docs) {
    int upserted = 0;
    int skipped = 0;
    for (List<Document> batch : batches(docs, Integer.MAX_VALUE)) {
      JsonNode r = ml.postSlow("/internal/v1/index", Map.of("repo_id", repoId, "documents", batch));
      upserted += r.path("chunks_upserted").asInt();
      skipped += r.path("chunks_skipped").asInt();
    }
    return new int[] {upserted, skipped};
  }

  /**
   * Splits items into batches of at most {@code maxItems} whose JSON stays under BATCH_BYTES.
   * Measured in UTF-8 bytes, since non-ASCII text takes up to 3 bytes per character.
   */
  private <T> List<List<T>> batches(List<T> items, int maxItems) {
    List<List<T>> out = new ArrayList<>();
    List<T> batch = new ArrayList<>();
    long bytes = 0;
    for (T item : items) {
      int size = mapper.writeValueAsBytes(item).length + 1;
      if (!batch.isEmpty() && (batch.size() == maxItems || bytes + size > BATCH_BYTES)) {
        out.add(batch);
        batch = new ArrayList<>();
        bytes = 0;
      }
      batch.add(item);
      bytes += size;
    }
    if (!batch.isEmpty()) {
      out.add(batch);
    }
    return out;
  }

  /** The first {@code max} characters, never splitting a surrogate pair (emoji etc.). */
  static String head(String s, int max) {
    if (s == null || s.length() <= max) {
      return s;
    }
    return s.substring(0, Character.isHighSurrogate(s.charAt(max - 1)) ? max - 1 : max);
  }

  /** Issues whose embedding is missing or older than their last GitHub update (self-healing). */
  public List<Document> issueDocumentsToIndex(long repoId) {
    return jdbc.sql(
            """
            select i.number, i.title, i.body, i.html_url from gitmedic.issues i
            left join gitmedic.embeddings e on e.repo_id = i.repo_id and e.source_type = 'issue'
              and e.source_ref = i.number::text and e.chunk_index = 0
            where i.repo_id = ? and (e.id is null or e.created_at < i.updated_at_gh)
            order by i.number
            """)
        .param(repoId)
        .query(
            (rs, n) ->
                new Document(
                    "issue",
                    Integer.toString(rs.getInt("number")),
                    rs.getString("html_url"),
                    issueText(rs.getString("title"), rs.getString("body"))))
        .list();
  }

  /** §5.8: an issue chunk is its title plus the first 1,500 body characters. */
  public static String issueText(String title, String body) {
    if (body == null || body.isBlank()) {
      return title;
    }
    return title + "\n" + head(body, QUERY_BODY_CHARS);
  }

  /**
   * FR-012: predicts open issues lacking a current prediction ({@code all} = every open issue,
   * after retraining). Returns false when no label model is trained.
   */
  public boolean predictLabels(long repoId, boolean all) {
    List<Map<String, Object>> issues =
        jdbc.sql(
                "select id, title, body from gitmedic.issues where repo_id = ? and state = 'open'"
                    + (all ? "" : " and (predicted_at is null or predicted_at < updated_at_gh)"))
            .param(repoId)
            .query(
                (rs, n) -> {
                  String body = rs.getString("body");
                  return Map.<String, Object>of(
                      "id",
                      rs.getLong("id"),
                      "title",
                      rs.getString("title"),
                      "body",
                      body == null ? "" : head(body, LABEL_BODY_CHARS));
                })
            .list();
    Instant now = clock.instant();
    for (List<Map<String, Object>> batch : batches(issues, PREDICT_BATCH)) {
      JsonNode r;
      try {
        r = ml.post("/internal/v1/predict/label", Map.of("repo_id", repoId, "issues", batch));
      } catch (MlRejectedException e) {
        if ("MODEL_NOT_TRAINED".equals(e.code())) {
          return false;
        }
        throw e;
      }
      for (JsonNode p : r.path("predictions")) {
        jdbc.sql(
                "update gitmedic.issues set predicted_label = ?, predicted_confidence = ?,"
                    + " predicted_at = ? where id = ? and repo_id = ?")
            .params(
                p.path("label").asString(),
                (float) p.path("confidence").asDouble(),
                ts(now),
                p.path("id").asLong(),
                repoId)
            .update();
      }
    }
    return true;
  }

  /** FR-016: top-k search for the given open issues; flags scores ≥ threshold. */
  public void detectDuplicates(long repoId, List<Integer> numbers) {
    for (int number : numbers) {
      var issue =
          jdbc.sql(
                  "select title, body from gitmedic.issues where repo_id = ? and number = ?"
                      + " and state = 'open'")
              .params(repoId, number)
              .query((rs, n) -> new String[] {rs.getString("title"), rs.getString("body")})
              .optional();
      if (issue.isEmpty()) {
        continue;
      }
      JsonNode r = duplicates(repoId, number, issue.get()[0], issue.get()[1]);
      boolean flagged = r.path("is_possible_duplicate").asBoolean();
      JsonNode top = r.path("top_score");
      jdbc.sql(
              """
              update gitmedic.issues set is_possible_duplicate = ?, duplicate_score = ?,
                duplicate_flagged_at = case when ? then coalesce(duplicate_flagged_at, ?) end
              where repo_id = ? and number = ?
              """)
          .params(
              flagged,
              top.isNumber() ? (Float) (float) top.asDouble() : null,
              flagged,
              ts(clock.instant()),
              repoId,
              number)
          .update();
    }
  }

  public JsonNode duplicates(long repoId, int number, String title, String body) {
    Map<String, Object> req = new java.util.HashMap<>();
    req.put("repo_id", repoId);
    req.put("issue_number", number);
    req.put("title", title);
    req.put("body", head(body, QUERY_BODY_CHARS));
    return ml.post("/internal/v1/duplicates", req);
  }

  public JsonNode suggest(long repoId, int number, String title, String body, boolean useLlm) {
    Map<String, Object> req = new java.util.HashMap<>();
    req.put("repo_id", repoId);
    req.put("issue_number", number);
    req.put("title", title);
    req.put("body", head(body, QUERY_BODY_CHARS));
    req.put("use_llm", useLlm);
    return ml.post("/internal/v1/suggest", req);
  }

  /** FR-013 / BR-09: the first prediction per PR is frozen; later ones update latest only. */
  public boolean predictMergeTimes(long repoId) {
    List<Map<String, Object>> prs =
        jdbc.sql(
                "select id, additions, deletions, changed_files, review_count, author_login"
                    + " from gitmedic.pull_requests where repo_id = ? and state = 'open'")
            .param(repoId)
            .query(
                (rs, n) -> {
                  Map<String, Object> m = new java.util.HashMap<>();
                  m.put("id", rs.getLong("id"));
                  m.put("additions", rs.getInt("additions"));
                  m.put("deletions", rs.getInt("deletions"));
                  m.put("changed_files", rs.getInt("changed_files"));
                  m.put("review_count", rs.getInt("review_count"));
                  m.put("author_login", rs.getString("author_login"));
                  return m;
                })
            .list();
    if (prs.isEmpty()) {
      return true;
    }
    Instant now = clock.instant();
    for (int from = 0; from < prs.size(); from += PREDICT_BATCH) {
      JsonNode r;
      try {
        r =
            ml.post(
                "/internal/v1/predict/merge-time",
                Map.of(
                    "repo_id",
                    repoId,
                    "prs",
                    prs.subList(from, Math.min(prs.size(), from + PREDICT_BATCH))));
      } catch (MlRejectedException e) {
        if ("MODEL_NOT_TRAINED".equals(e.code())) {
          return false;
        }
        throw e;
      }
      for (JsonNode p : r.path("predictions")) {
        float days = (float) Math.max(0, p.path("days").asDouble());
        jdbc.sql(
                """
                update gitmedic.pull_requests set latest_predicted_merge_days = ?,
                  predicted_merge_days = coalesce(predicted_merge_days, ?),
                  predicted_at = coalesce(predicted_at, ?)
                where id = ? and repo_id = ?
                """)
            .params(days, days, ts(now), p.path("id").asLong(), repoId)
            .update();
      }
    }
    return true;
  }

  /** FR-014: computes risk as of now and appends a repo_health row. */
  public JsonNode computeRisk(long repoId, Instant asOf) {
    JsonNode r = ml.post("/internal/v1/risk", Map.of("repo_id", repoId, "as_of", asOf.toString()));
    jdbc.sql(
            "insert into gitmedic.repo_health (repo_id, computed_at, score, level, components)"
                + " values (?, ?, ?, ?, ?::jsonb)")
        .params(
            repoId,
            // History is keyed by the as-of time, so a late (catch-up) report files correctly.
            ts(asOf.isAfter(clock.instant()) ? clock.instant() : asOf),
            r.path("score").asInt(),
            r.path("level").asString(),
            mapper.writeValueAsString(r.path("components")))
        .update();
    return r;
  }

  public JsonNode train(long repoId, String model) {
    String path =
        "label".equals(model) ? "/internal/v1/train/label" : "/internal/v1/train/merge-time";
    JsonNode r = ml.postSlow(path, Map.of("repo_id", repoId));
    if ("label".equals(model)) {
      predictLabels(repoId, true);
    } else {
      predictMergeTimes(repoId);
    }
    return r;
  }

  public JsonNode modelStatus(long repoId) {
    return ml.get("/internal/v1/repos/" + repoId + "/models");
  }

  /** Removes model files and embeddings; failure is logged (the DB cascade still removes rows). */
  public void deleteRepo(long repoId) {
    try {
      ml.delete("/internal/v1/repos/" + repoId);
    } catch (RuntimeException e) {
      log.warn("Could not delete ML artefacts for repo {} now: {}", repoId, e.getMessage());
    }
  }
}
