package dev.gitmedic.report;

import static dev.gitmedic.common.Db.ts;

import dev.gitmedic.analytics.AnalyticsService;
import dev.gitmedic.ml.MlClient.MlUnavailableException;
import dev.gitmedic.ml.MlService;
import dev.gitmedic.repo.RepoRepository;
import dev.gitmedic.repo.RepoRepository.Repo;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** FR-019/022, BR-11/12: weekly report metrics, one row per repo and period. */
@Service
public class ReportService {

  private static final Logger log = LoggerFactory.getLogger(ReportService.class);

  /** [start, end] with end = the period's last instant (Sunday 23:59:59.999…). */
  public record Period(Instant start, Instant end) {}

  private final JdbcClient jdbc;
  private final RepoRepository repos;
  private final AnalyticsService analytics;
  private final MlService ml;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final TaskExecutor executor;

  ReportService(
      JdbcClient jdbc,
      RepoRepository repos,
      AnalyticsService analytics,
      MlService ml,
      ObjectMapper mapper,
      Clock clock,
      @Qualifier("applicationTaskExecutor") TaskExecutor executor) {
    this.jdbc = jdbc;
    this.repos = repos;
    this.analytics = analytics;
    this.ml = ml;
    this.mapper = mapper;
    this.clock = clock;
    this.executor = executor;
  }

  /** BR-11: Monday 00:00 → Sunday 23:59:59 in APP_TIMEZONE; previous or current week. */
  public Period period(boolean previous) {
    ZonedDateTime monday =
        ZonedDateTime.now(clock.withZone(analytics.zone()))
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .toLocalDate()
            .atStartOfDay(analytics.zone());
    if (previous) {
      monday = monday.minusWeeks(1);
    }
    return new Period(monday.toInstant(), monday.plusWeeks(1).toInstant().minusNanos(1000));
  }

  @Scheduled(cron = "${gitmedic.report-cron}", zone = "${gitmedic.timezone}")
  void weekly() {
    generateAllMissing();
  }

  /** BR-12: on startup, create any missing previous-period report (off the startup thread). */
  @EventListener(ApplicationReadyEvent.class)
  void catchUp() {
    executor.execute(this::generateAllMissing);
  }

  void generateAllMissing() {
    Period p = period(true);
    for (Repo repo : repos.all()) {
      boolean exists =
          jdbc.sql(
                      "select count(*) from gitmedic.weekly_reports where repo_id = ? and period_start = ?")
                  .params(repo.id(), ts(p.start()))
                  .query(Long.class)
                  .single()
              > 0;
      if (!exists) {
        try {
          generate(repo.id(), p);
        } catch (RuntimeException e) {
          // Not stored; the next startup or scheduled run retries (§3.5).
          log.error("Weekly report for repo {} failed", repo.id(), e);
        }
      }
    }
  }

  /** Computes and upserts the report; regenerating a period replaces it (unique per period). */
  public long generate(long repoId, Period p) {
    Map<String, Object> m = metrics(repoId, p);
    return jdbc.sql(
            """
            insert into gitmedic.weekly_reports (repo_id, period_start, period_end, generated_at, metrics)
            values (?, ?, ?, ?, ?::jsonb)
            on conflict (repo_id, period_start) do update set period_end = excluded.period_end,
              generated_at = excluded.generated_at, metrics = excluded.metrics
            returning id
            """)
        .params(
            repoId, ts(p.start()), ts(p.end()), ts(clock.instant()), mapper.writeValueAsString(m))
        .query(Long.class)
        .single();
  }

  Map<String, Object> metrics(long repoId, Period p) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("periodStart", p.start().toString());
    m.put("periodEnd", p.end().toString());
    m.put("timezone", analytics.zone().getId());
    m.put(
        "issues",
        jdbc.sql(
                """
                select
                  count(*) filter (where created_at_gh between :s and :e) new_issues,
                  count(*) filter (where closed_at_gh between :s and :e) closed_issues,
                  count(*) filter (where created_at_gh <= :e and (closed_at_gh is null or closed_at_gh > :e)) open_issues,
                  count(*) filter (where created_at_gh between :s and :e and duplicate_flagged_at is not null) dups
                from gitmedic.issues where repo_id = :r
                """)
            .param("r", repoId)
            .param("s", ts(p.start()))
            .param("e", ts(p.end()))
            .query(
                (rs, n) ->
                    Map.of(
                        "new", rs.getLong("new_issues"),
                        "closed", rs.getLong("closed_issues"),
                        "open", rs.getLong("open_issues"),
                        "duplicatesCaught", rs.getLong("dups")))
            .single());
    m.put(
        "pullRequests",
        jdbc.sql(
                """
                select count(*) merged,
                  avg(extract(epoch from (merged_at_gh - created_at_gh)) / 86400) actual,
                  avg(predicted_merge_days) predicted, count(predicted_merge_days) predicted_n
                from gitmedic.pull_requests where repo_id = ? and merged_at_gh between ? and ?
                """)
            .params(repoId, ts(p.start()), ts(p.end()))
            .query(
                (rs, n) -> {
                  Map<String, Object> pr = new LinkedHashMap<>();
                  pr.put("merged", rs.getLong("merged"));
                  pr.put("avgMergeDaysActual", round(rs.getObject("actual")));
                  pr.put("avgMergeDaysPredicted", round(rs.getObject("predicted")));
                  pr.put("predictedSamples", rs.getLong("predicted_n"));
                  return pr;
                })
            .single());
    m.put("risk", risk(repoId, p));
    var bf = analytics.busFactor(repoId, p.end());
    m.put(
        "busFactor",
        Map.of("value", bf.busFactor() == null ? 0 : bf.busFactor(), "risky", bf.risky()));
    m.put(
        "topContributors",
        analytics.contributors(repoId, p.start(), p.end().plusNanos(1000)).stream()
            .limit(5)
            .map(c -> Map.of("login", c.login(), "commits", c.commits()))
            .toList());
    return m;
  }

  /** Recomputed as of the period end when ML is up, else the latest stored score before it. */
  private Map<String, Object> risk(long repoId, Period p) {
    Integer score = null;
    String level = null;
    String source = "COMPUTED";
    try {
      Instant now = clock.instant();
      JsonNode r = ml.computeRisk(repoId, p.end().isAfter(now) ? now : p.end());
      score = r.path("score").asInt();
      level = r.path("level").asString();
    } catch (MlUnavailableException e) {
      source = "LAST_KNOWN";
      var row =
          jdbc.sql(
                  "select score, level from gitmedic.repo_health where repo_id = ? and computed_at <= ?"
                      + " order by computed_at desc limit 1")
              .params(repoId, ts(p.end()))
              .query((rs, n) -> new Object[] {rs.getInt("score"), rs.getString("level")})
              .optional();
      if (row.isPresent()) {
        score = (Integer) row.get()[0];
        level = (String) row.get()[1];
      }
    }
    Integer previous =
        jdbc.sql(
                "select (metrics -> 'risk' ->> 'score')::int from gitmedic.weekly_reports"
                    + " where repo_id = ? and period_start < ? order by period_start desc limit 1")
            .params(repoId, ts(p.start()))
            .query(Integer.class)
            .optional()
            .orElse(null);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("score", score);
    out.put("level", level);
    out.put("delta", score == null || previous == null ? null : score - previous);
    out.put("source", score == null ? "UNAVAILABLE" : source);
    return out;
  }

  private static Double round(Object v) {
    return v == null ? null : Math.round(((Number) v).doubleValue() * 100) / 100.0;
  }
}
