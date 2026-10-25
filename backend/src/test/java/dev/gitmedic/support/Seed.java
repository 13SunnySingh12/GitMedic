package dev.gitmedic.support;

import dev.gitmedic.sync.SyncStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** Loads fixtures/github into a tracked repo through the same upserts the sync uses. */
public final class Seed {

  private Seed() {}

  public static long repo(JdbcClient jdbc, SyncStore store, long userId) {
    long repoId =
        jdbc.sql(
                "insert into gitmedic.repos (user_id, github_repo_id, full_name, default_branch,"
                    + " is_private, sync_status) values (?, 123456, 'octo/demo', 'main', false, 'DONE')"
                    + " returning id")
            .param(userId)
            .query(Long.class)
            .single();
    for (JsonNode i : TestSupport.fixture("issues.json")) {
      if (!SyncStore.isPullRequest(i)) {
        store.upsertIssue(repoId, i);
      }
    }
    JsonNode details = TestSupport.fixture("pull_details.json");
    JsonNode reviews = TestSupport.fixture("pull_reviews.json");
    details
        .propertyNames()
        .forEach(n -> store.upsertPull(repoId, details.get(n), reviews.get(n).size()));
    for (JsonNode c : TestSupport.fixture("commits.json")) {
      JsonNode a = c.path("commit").path("author");
      store.insertCommit(
          repoId,
          c.path("sha").asString(),
          SyncStore.login(c.path("author")),
          a.path("email").asString(),
          SyncStore.time(a, "date"));
    }
    return repoId;
  }
}
