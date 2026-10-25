package dev.gitmedic.pull;

import dev.gitmedic.common.ApiException;
import dev.gitmedic.common.Paging;
import dev.gitmedic.repo.RepoRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/repos/{repoId}/pulls")
class PullController {

  record PullRow(
      int number,
      String title,
      String state,
      String authorLogin,
      int additions,
      int deletions,
      int changedFiles,
      int reviewCount,
      String htmlUrl,
      Instant createdAt,
      Instant mergedAt,
      Instant closedAt,
      Float predictedMergeDays,
      Float firstPredictedMergeDays) {}

  private final RepoRepository repos;
  private final JdbcClient jdbc;

  PullController(RepoRepository repos, JdbcClient jdbc) {
    this.repos = repos;
    this.jdbc = jdbc;
  }

  /** API-15 / FR-013: PRs newest first, with latest and first (frozen, BR-09) predictions. */
  @GetMapping
  Paging.Page<PullRow> list(
      @AuthenticationPrincipal Long userId,
      @PathVariable long repoId,
      @RequestParam(defaultValue = "open") String state,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    long repo = repos.owned(userId, repoId).id();
    Paging paging = Paging.of(page, size);
    if (!List.of("open", "closed", "all").contains(state)) {
      throw ApiException.badRequest("state must be open, closed or all.");
    }
    String where = " where repo_id = ?" + (state.equals("all") ? "" : " and state = ?");
    Object[] params = state.equals("all") ? new Object[] {repo} : new Object[] {repo, state};
    long total =
        jdbc.sql("select count(*) from gitmedic.pull_requests" + where)
            .params(params)
            .query(Long.class)
            .single();
    List<PullRow> items =
        jdbc.sql(
                "select * from gitmedic.pull_requests"
                    + where
                    + " order by created_at_gh desc, number desc limit "
                    + paging.size()
                    + " offset "
                    + paging.offset())
            .params(params)
            .query(
                (rs, n) ->
                    new PullRow(
                        rs.getInt("number"),
                        rs.getString("title"),
                        rs.getString("state"),
                        rs.getString("author_login"),
                        rs.getInt("additions"),
                        rs.getInt("deletions"),
                        rs.getInt("changed_files"),
                        rs.getInt("review_count"),
                        rs.getString("html_url"),
                        instant(rs.getTimestamp("created_at_gh")),
                        instant(rs.getTimestamp("merged_at_gh")),
                        instant(rs.getTimestamp("closed_at_gh")),
                        (Float) rs.getObject("latest_predicted_merge_days"),
                        (Float) rs.getObject("predicted_merge_days")))
            .list();
    return paging.page(items, total);
  }

  private static Instant instant(java.sql.Timestamp t) {
    return t == null ? null : t.toInstant();
  }
}
