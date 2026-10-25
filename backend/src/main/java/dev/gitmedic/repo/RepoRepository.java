package dev.gitmedic.repo;

import dev.gitmedic.common.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RepoRepository {

  public record Repo(
      long id,
      long userId,
      long githubRepoId,
      String fullName,
      String defaultBranch,
      boolean isPrivate,
      String syncStatus,
      String syncError,
      Instant lastSyncedAt) {}

  private static final String COLUMNS =
      "id, user_id, github_repo_id, full_name, default_branch, is_private, sync_status,"
          + " sync_error, last_synced_at";

  private final JdbcClient jdbc;

  public RepoRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** SEC-04 / BR-01: every repo-scoped request resolves the repo through its owner. */
  public Repo owned(long userId, long repoId) {
    return jdbc.sql("select " + COLUMNS + " from gitmedic.repos where id = ? and user_id = ?")
        .params(repoId, userId)
        .query(Repo.class)
        .optional()
        .orElseThrow(ApiException::notFound);
  }

  public Optional<Repo> find(long repoId) {
    return jdbc.sql("select " + COLUMNS + " from gitmedic.repos where id = ?")
        .param(repoId)
        .query(Repo.class)
        .optional();
  }

  public List<Repo> byGithubRepoId(long githubRepoId) {
    return jdbc.sql("select " + COLUMNS + " from gitmedic.repos where github_repo_id = ?")
        .param(githubRepoId)
        .query(Repo.class)
        .list();
  }

  public List<Repo> all() {
    return jdbc.sql("select " + COLUMNS + " from gitmedic.repos order by id")
        .query(Repo.class)
        .list();
  }

  public List<Long> trackedGithubIds(long userId) {
    return jdbc.sql("select github_repo_id from gitmedic.repos where user_id = ?")
        .param(userId)
        .query(Long.class)
        .list();
  }

  /** Returns the new id, or empty when the user already tracks this GitHub repo. */
  public Optional<Long> insert(
      long userId, long githubRepoId, String fullName, String defaultBranch, boolean isPrivate) {
    return jdbc.sql(
            """
            insert into gitmedic.repos (user_id, github_repo_id, full_name, default_branch, is_private)
            values (?, ?, ?, ?, ?) on conflict (user_id, github_repo_id) do nothing returning id
            """)
        .params(userId, githubRepoId, fullName, defaultBranch, isPrivate)
        .query(Long.class)
        .optional();
  }

  public void delete(long repoId) {
    jdbc.sql("delete from gitmedic.repos where id = ?").param(repoId).update();
  }

  /**
   * Atomically moves a repo to PENDING unless a sync is queued or running (one active sync per
   * repo: the UPDATE row lock makes concurrent claims race-free).
   */
  public boolean claimSync(long repoId) {
    return jdbc.sql(
                "update gitmedic.repos set sync_status = 'PENDING', sync_error = null"
                    + " where id = ? and sync_status not in ('PENDING', 'RUNNING')")
            .param(repoId)
            .update()
        == 1;
  }

  public void setStatus(long repoId, String status, String error) {
    jdbc.sql("update gitmedic.repos set sync_status = ?, sync_error = ? where id = ?")
        .params(status, error, repoId)
        .update();
  }

  public void markSynced(
      long repoId, Instant syncedAt, String fullName, String defaultBranch, boolean isPrivate) {
    jdbc.sql(
            "update gitmedic.repos set sync_status = 'DONE', sync_error = null, last_synced_at = ?,"
                + " full_name = ?, default_branch = ?, is_private = ? where id = ?")
        .params(java.sql.Timestamp.from(syncedAt), fullName, defaultBranch, isPrivate, repoId)
        .update();
  }

  /** After a restart no job is running: queued/running syncs were interrupted. */
  public int failInterrupted() {
    return jdbc.sql(
            "update gitmedic.repos set sync_status = 'FAILED',"
                + " sync_error = 'Interrupted by a restart; run Sync now to retry.'"
                + " where sync_status in ('PENDING', 'RUNNING')")
        .update();
  }
}
