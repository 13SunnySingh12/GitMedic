package dev.gitmedic.analytics;

import static dev.gitmedic.common.Db.ts;

import dev.gitmedic.common.ApiException;
import dev.gitmedic.config.AppProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** FR-009/010 and dashboard stats: pure SQL aggregates over synced data (§5.12). */
@Service
public class AnalyticsService {

  public record Contributor(String login, long commits, double share) {}

  public record BusFactor(
      Integer busFactor,
      boolean risky,
      long totalCommits,
      Instant windowStart,
      List<Contributor> contributors) {}

  public record DayCount(LocalDate date, long count) {}

  public record WeekdayCount(int weekday, long count) {}

  public record Heatmap(
      LocalDate from,
      LocalDate to,
      String timezone,
      long total,
      List<DayCount> days,
      List<WeekdayCount> weekdays) {}

  public record Stats(
      long openIssues, long closedIssues7d, long openPrs, long duplicatesFlagged7d) {}

  private final JdbcClient jdbc;
  private final Clock clock;
  private final ZoneId zone;

  AnalyticsService(JdbcClient jdbc, Clock clock, AppProperties props) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.zone = ZoneId.of(props.timezone());
  }

  public ZoneId zone() {
    return zone;
  }

  /**
   * BR-06: smallest n of contributors (by commit count, descending) whose commits reach 50% of the
   * total. Null when there are no commits.
   */
  static Integer busFactor(List<Long> countsDesc) {
    long total = countsDesc.stream().mapToLong(Long::longValue).sum();
    if (total == 0) {
      return null;
    }
    long running = 0;
    for (int i = 0; i < countsDesc.size(); i++) {
      running += countsDesc.get(i);
      if (running * 2 >= total) {
        return i + 1;
      }
    }
    return countsDesc.size();
  }

  /** Commits by contributor in [from, to); login, else a short email-hash identity. */
  public List<Contributor> contributors(long repoId, Instant from, Instant to) {
    List<Object[]> rows =
        jdbc.sql(
                """
                select coalesce(author_login, 'unknown-' || left(author_email_hash, 8), 'unknown') who,
                  count(*) n
                from gitmedic.commits where repo_id = ? and committed_at >= ? and committed_at < ?
                group by who order by n desc, who
                """)
            .params(repoId, ts(from), ts(to))
            .query((rs, i) -> new Object[] {rs.getString("who"), rs.getLong("n")})
            .list();
    long total = rows.stream().mapToLong(r -> (long) r[1]).sum();
    List<Contributor> out = new ArrayList<>();
    for (Object[] r : rows) {
      out.add(
          new Contributor(
              (String) r[0], (long) r[1], total == 0 ? 0 : (long) r[1] / (double) total));
    }
    return out;
  }

  public BusFactor busFactor(long repoId, Instant asOf) {
    Instant start = asOf.minus(Duration.ofDays(365));
    List<Contributor> all = contributors(repoId, start, asOf.plusMillis(1));
    Integer bf = busFactor(all.stream().map(Contributor::commits).toList());
    long total = all.stream().mapToLong(Contributor::commits).sum();
    return new BusFactor(
        bf, bf != null && bf <= 2, total, start, all.subList(0, Math.min(10, all.size())));
  }

  public BusFactor busFactor(long repoId) {
    return busFactor(repoId, clock.instant());
  }

  public Heatmap heatmap(long repoId, LocalDate from, LocalDate to) {
    LocalDate today = LocalDate.now(clock.withZone(zone));
    LocalDate end = to == null ? today : to;
    LocalDate start = from == null ? end.minusWeeks(52).plusDays(1) : from;
    if (start.isAfter(end)) {
      throw ApiException.badRequest("from must not be after to.");
    }
    if (ChronoUnit.DAYS.between(start, end) > 731) {
      throw ApiException.badRequest("The range may span at most two years.");
    }
    Instant lo = start.atStartOfDay(zone).toInstant();
    Instant hi = end.plusDays(1).atStartOfDay(zone).toInstant();
    List<DayCount> days =
        jdbc.sql(
                """
                select (committed_at at time zone ?)::date d, count(*) n from gitmedic.commits
                where repo_id = ? and committed_at >= ? and committed_at < ? group by d order by d
                """)
            .params(zone.getId(), repoId, ts(lo), ts(hi))
            .query((rs, i) -> new DayCount(rs.getObject("d", LocalDate.class), rs.getLong("n")))
            .list();
    long[] perWeekday = new long[7];
    for (DayCount d : days) {
      perWeekday[d.date().getDayOfWeek().getValue() - 1] += d.count();
    }
    List<WeekdayCount> weekdays = new ArrayList<>();
    for (int i = 0; i < 7; i++) {
      weekdays.add(new WeekdayCount(i + 1, perWeekday[i]));
    }
    long total = days.stream().mapToLong(DayCount::count).sum();
    return new Heatmap(start, end, zone.getId(), total, days, weekdays);
  }

  public Stats stats(long repoId) {
    Instant weekAgo = clock.instant().minus(Duration.ofDays(7));
    return jdbc.sql(
            """
            select
              (select count(*) from gitmedic.issues where repo_id = :r and state = 'open') open_issues,
              (select count(*) from gitmedic.issues where repo_id = :r and closed_at_gh >= :since) closed7,
              (select count(*) from gitmedic.pull_requests where repo_id = :r and state = 'open') open_prs,
              (select count(*) from gitmedic.issues where repo_id = :r
                 and duplicate_flagged_at >= :since) dups7
            """)
        .param("r", repoId)
        .param("since", ts(weekAgo))
        .query(
            (rs, i) ->
                new Stats(
                    rs.getLong("open_issues"),
                    rs.getLong("closed7"),
                    rs.getLong("open_prs"),
                    rs.getLong("dups7")))
        .single();
  }
}
