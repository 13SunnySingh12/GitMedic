package dev.gitmedic.issue;

import dev.gitmedic.common.ApiException;
import dev.gitmedic.common.Paging;
import dev.gitmedic.ml.MlClient.MlUnavailableException;
import dev.gitmedic.ml.MlService;
import dev.gitmedic.repo.RepoRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/repos/{repoId}/issues")
class IssueController {

  record IssueRow(
      int number,
      String title,
      String state,
      String htmlUrl,
      String authorLogin,
      List<String> ghLabels,
      int commentsCount,
      Instant createdAt,
      String predictedLabel,
      Float predictedConfidence,
      boolean isPossibleDuplicate,
      Float duplicateScore) {}

  record Duplicate(int number, String title, String state, double score, String htmlUrl) {}

  /** Whitelisted sort keys → columns (never interpolate user input into SQL). */
  private static final Map<String, String> SORTS =
      Map.of(
          "number", "number",
          "created", "created_at_gh",
          "updated", "updated_at_gh",
          "confidence", "predicted_confidence",
          "duplicateScore", "duplicate_score",
          "comments", "comments_count");

  private static final String COLUMNS =
      "number, title, state, html_url, author_login, gh_labels, comments_count, created_at_gh,"
          + " predicted_label, predicted_confidence, is_possible_duplicate, duplicate_score";

  private final RepoRepository repos;
  private final JdbcClient jdbc;
  private final MlService ml;

  IssueController(RepoRepository repos, JdbcClient jdbc, MlService ml) {
    this.repos = repos;
    this.jdbc = jdbc;
    this.ml = ml;
  }

  /** API-13 / FR-025: filters state, predicted label, duplicate flag; sorted, paginated. */
  @GetMapping
  Paging.Page<IssueRow> list(
      @AuthenticationPrincipal Long userId,
      @PathVariable long repoId,
      @RequestParam(defaultValue = "open") String state,
      @RequestParam(required = false) String label,
      @RequestParam(required = false) Boolean duplicate,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(defaultValue = "created,desc") String sort) {
    long repo = repos.owned(userId, repoId).id();
    Paging paging = Paging.of(page, size);
    if (!List.of("open", "closed", "all").contains(state)) {
      throw ApiException.badRequest("state must be open, closed or all.");
    }
    if (label != null && !List.of("bug", "feature", "question").contains(label)) {
      throw ApiException.badRequest("label must be bug, feature or question.");
    }
    String[] s = sort.split(",", 2);
    String column = SORTS.get(s[0]);
    String direction = s.length > 1 ? s[1].toLowerCase(java.util.Locale.ROOT) : "asc";
    if (column == null || !List.of("asc", "desc").contains(direction)) {
      throw ApiException.badRequest(
          "sort must be one of " + SORTS.keySet() + " with ,asc or ,desc.");
    }

    StringBuilder where = new StringBuilder(" where repo_id = :repo");
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("repo", repo);
    if (!state.equals("all")) {
      where.append(" and state = :state");
      params.put("state", state);
    }
    if (label != null) {
      where.append(" and predicted_label = :label");
      params.put("label", label);
    }
    if (duplicate != null) {
      where.append(" and is_possible_duplicate = :dup");
      params.put("dup", duplicate);
    }
    long total =
        jdbc.sql("select count(*) from gitmedic.issues" + where)
            .params(params)
            .query(Long.class)
            .single();
    List<IssueRow> items =
        jdbc.sql(
                "select "
                    + COLUMNS
                    + " from gitmedic.issues"
                    + where
                    + " order by "
                    + column
                    + " "
                    + direction
                    + " nulls last, number desc limit :limit offset :offset")
            .params(params)
            .param("limit", paging.size())
            .param("offset", paging.offset())
            .query((rs, n) -> row(rs))
            .list();
    return paging.page(items, total);
  }

  private static IssueRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new IssueRow(
        rs.getInt("number"),
        rs.getString("title"),
        rs.getString("state"),
        rs.getString("html_url"),
        rs.getString("author_login"),
        List.of((String[]) rs.getArray("gh_labels").getArray()),
        rs.getInt("comments_count"),
        rs.getTimestamp("created_at_gh").toInstant(),
        rs.getString("predicted_label"),
        (Float) rs.getObject("predicted_confidence"),
        rs.getBoolean("is_possible_duplicate"),
        (Float) rs.getObject("duplicate_score"));
  }

  /** API-14: issue + live duplicate matches + retrieval-only suggested answer (FR-016/017). */
  @GetMapping("/{number}")
  Map<String, Object> detail(
      @AuthenticationPrincipal Long userId,
      @PathVariable long repoId,
      @PathVariable int number,
      @RequestParam(defaultValue = "false") boolean llm) {
    long repo = repos.owned(userId, repoId).id();
    var found =
        jdbc.sql(
                "select "
                    + COLUMNS
                    + ", body from gitmedic.issues where repo_id = ? and number = ?")
            .params(repo, number)
            .query((rs, n) -> Map.entry(row(rs), String.valueOf(rs.getString("body"))))
            .optional()
            .orElseThrow(ApiException::notFound);
    IssueRow issue = found.getKey();
    String body = "null".equals(found.getValue()) ? null : found.getValue();
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("issue", issue);
    out.put("body", body);
    try {
      out.put("duplicates", duplicates(repo, ml.duplicates(repo, number, issue.title(), body)));
      out.put("suggestedAnswer", answer(ml.suggest(repo, number, issue.title(), body, llm)));
      out.put("mlStatus", "OK");
    } catch (MlUnavailableException e) {
      out.put("duplicates", null);
      out.put("suggestedAnswer", null);
      out.put("mlStatus", "UNAVAILABLE");
    }
    return out;
  }

  private List<Duplicate> duplicates(long repo, JsonNode r) {
    List<Duplicate> out = new ArrayList<>();
    for (JsonNode m : r.path("matches")) {
      int n = m.path("issue_number").asInt();
      jdbc.sql(
              "select title, state, html_url from gitmedic.issues where repo_id = ? and number = ?")
          .params(repo, n)
          .query(
              (rs, i) ->
                  new Duplicate(
                      n,
                      rs.getString("title"),
                      rs.getString("state"),
                      m.path("score").asDouble(),
                      rs.getString("html_url")))
          .optional()
          .ifPresent(out::add);
    }
    return out;
  }

  private static Map<String, Object> answer(JsonNode r) {
    List<Map<String, Object>> sources = new ArrayList<>();
    for (JsonNode s : r.path("sources")) {
      sources.add(
          Map.of(
              "type", s.path("type").asString(),
              "ref", s.path("ref").asString(),
              "url", s.path("url").asString(),
              "snippet", s.path("snippet").asString(),
              "score", s.path("score").asDouble()));
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("mode", r.path("mode").asString());
    out.put("llmStatus", r.path("llm_status").asString());
    out.put("draft", r.path("draft").asString());
    out.put("sources", sources);
    return out;
  }
}
