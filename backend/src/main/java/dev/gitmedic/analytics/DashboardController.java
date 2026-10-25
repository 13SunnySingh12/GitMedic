package dev.gitmedic.analytics;

import dev.gitmedic.ml.MlClient.MlUnavailableException;
import dev.gitmedic.ml.MlService;
import dev.gitmedic.repo.RepoRepository;
import dev.gitmedic.repo.RepoRepository.Repo;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/repos/{repoId}")
class DashboardController {

  record RepoInfo(
      long id, String fullName, Instant lastSyncedAt, String syncStatus, String syncError) {}

  record RiskView(
      int score, String level, Integer delta, Instant computedAt, Map<String, Object> components) {}

  private final RepoRepository repos;
  private final AnalyticsService analytics;
  private final MlService ml;
  private final JdbcClient jdbc;
  private final tools.jackson.databind.ObjectMapper mapper;

  DashboardController(
      RepoRepository repos,
      AnalyticsService analytics,
      MlService ml,
      JdbcClient jdbc,
      tools.jackson.databind.ObjectMapper mapper) {
    this.repos = repos;
    this.analytics = analytics;
    this.ml = ml;
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  /** API-10: each part is independent, so the page renders with partial data (FR-024). */
  @GetMapping("/dashboard")
  Map<String, Object> dashboard(@AuthenticationPrincipal Long userId, @PathVariable long repoId) {
    Repo repo = repos.owned(userId, repoId);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put(
        "repo",
        new RepoInfo(
            repo.id(), repo.fullName(), repo.lastSyncedAt(), repo.syncStatus(), repo.syncError()));
    out.put("risk", risk(repo.id()));
    out.put("stats", analytics.stats(repo.id()));
    try {
      out.put("models", models(ml.modelStatus(repo.id())));
      out.put("mlStatus", "OK");
    } catch (MlUnavailableException e) {
      out.put("models", null);
      out.put("mlStatus", "UNAVAILABLE");
    }
    return out;
  }

  /** Latest score, with the delta against the newest score at least a week older. */
  RiskView risk(long repoId) {
    var rows =
        jdbc.sql(
                """
                select h.score, h.level, h.computed_at, h.components::text components,
                  (select p.score from gitmedic.repo_health p where p.repo_id = h.repo_id
                     and p.computed_at <= h.computed_at - interval '7 days'
                     order by p.computed_at desc limit 1) previous
                from gitmedic.repo_health h where h.repo_id = ?
                order by h.computed_at desc limit 1
                """)
            .param(repoId)
            .query(
                (rs, n) -> {
                  Integer previous = (Integer) rs.getObject("previous");
                  int score = rs.getInt("score");
                  @SuppressWarnings("unchecked")
                  Map<String, Object> components =
                      mapper.readValue(rs.getString("components"), Map.class);
                  return new RiskView(
                      score,
                      rs.getString("level"),
                      previous == null ? null : score - previous,
                      rs.getTimestamp("computed_at").toInstant(),
                      camel(components));
                })
            .list();
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private static Map<String, Object> camel(Map<String, Object> snake) {
    Map<String, Object> out = new LinkedHashMap<>();
    snake.forEach(
        (k, v) -> {
          StringBuilder b = new StringBuilder();
          boolean up = false;
          for (char c : k.toCharArray()) {
            if (c == '_') {
              up = true;
            } else {
              b.append(up ? Character.toUpperCase(c) : c);
              up = false;
            }
          }
          out.put(b.toString(), v);
        });
    return out;
  }

  /** FR-026 model panel: trained metadata, or why not and how much data is still missing. */
  private static Map<String, Object> models(JsonNode s) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("label", model(s.path("label"), s.path("label_data"), "macroF1", "macro_f1"));
    out.put(
        "mergeTime", model(s.path("merge_time"), s.path("merge_time_data"), "maeDays", "mae_days"));
    out.put("chunks", s.path("chunks").asLong());
    return out;
  }

  private static Map<String, Object> model(
      JsonNode meta, JsonNode data, String metricName, String metricKey) {
    Map<String, Object> m = new LinkedHashMap<>();
    boolean trained = meta.isObject();
    m.put("trained", trained);
    if (trained) {
      m.put("trainedAt", meta.path("trained_at").asString());
      m.put(metricName, meta.path(metricKey).asDouble());
      m.put("samples", meta.path("n_samples").asInt());
    } else {
      m.put(
          "reason", data.path("ready").asBoolean() ? "NOT_TRAINED" : "INSUFFICIENT_TRAINING_DATA");
    }
    m.put("have", data.path("have").asInt());
    m.put("need", data.path("need").asInt());
    m.put("ready", data.path("ready").asBoolean());
    return m;
  }

  /** API-11. */
  @GetMapping("/heatmap")
  AnalyticsService.Heatmap heatmap(
      @AuthenticationPrincipal Long userId,
      @PathVariable long repoId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return analytics.heatmap(repos.owned(userId, repoId).id(), from, to);
  }

  /** API-12. */
  @GetMapping("/bus-factor")
  AnalyticsService.BusFactor busFactor(
      @AuthenticationPrincipal Long userId, @PathVariable long repoId) {
    return analytics.busFactor(repos.owned(userId, repoId).id());
  }
}
