package dev.gitmedic.sync;

import static dev.gitmedic.common.Db.ts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Idempotent natural-key upserts of GitHub data (FR-005/006, QA-SYNC-04). */
@Component
public class SyncStore {

  static final int MAX_BODY = 65_535;

  private final JdbcClient jdbc;

  SyncStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** BR-02: items of the issues endpoint that carry a pull_request key are PRs. */
  public static boolean isPullRequest(JsonNode issue) {
    return issue.has("pull_request");
  }

  public void upsertIssue(long repoId, JsonNode i) {
    List<String> labels = new ArrayList<>();
    i.path("labels").forEach(l -> labels.add(l.path("name").asString()));
    String body = i.path("body").isString() ? i.path("body").asString() : null;
    if (body != null && body.length() > MAX_BODY) {
      body = body.substring(0, MAX_BODY);
    }
    jdbc.sql(
            """
                  insert into gitmedic.issues (repo_id, github_issue_id, number, title, body, state,
                    author_login, gh_labels, comments_count, html_url, created_at_gh, updated_at_gh,
                    closed_at_gh)
                  values (:repo, :gid, :number, :title, :body, :state, :author, :labels, :comments,
                    :url, :created, :updated, :closed)
                  on conflict (repo_id, number) do update set github_issue_id = excluded.github_issue_id,
                    title = excluded.title, body = excluded.body, state = excluded.state,
                    author_login = excluded.author_login, gh_labels = excluded.gh_labels,
                    comments_count = excluded.comments_count, html_url = excluded.html_url,
                    updated_at_gh = excluded.updated_at_gh, closed_at_gh = excluded.closed_at_gh
                  """)
        .param("repo", repoId)
        .param("gid", i.path("id").asLong())
        .param("number", i.path("number").asInt())
        .param("title", i.path("title").asString())
        .param("body", body)
        .param("state", i.path("state").asString())
        .param("author", login(i.path("user")))
        .param("labels", labels.toArray(String[]::new))
        .param("comments", i.path("comments").asInt())
        .param("url", i.path("html_url").asString())
        .param("created", ts(time(i, "created_at")))
        .param("updated", ts(time(i, "updated_at")))
        .param("closed", ts(time(i, "closed_at")))
        .update();
  }

  public void deleteIssue(long repoId, int number) {
    jdbc.sql("delete from gitmedic.issues where repo_id = ? and number = ?")
        .params(repoId, number)
        .update();
    jdbc.sql(
            "delete from gitmedic.embeddings where repo_id = ? and source_type = 'issue'"
                + " and source_ref = ?")
        .params(repoId, Integer.toString(number))
        .update();
  }

  /**
   * {@code pr} is a pulls detail (or webhook pull_request object, which carries the same size
   * fields); {@code reviewCount} null keeps the stored count.
   */
  public void upsertPull(long repoId, JsonNode pr, Integer reviewCount) {
    jdbc.sql(
            """
            insert into gitmedic.pull_requests (repo_id, github_pr_id, number, title, state,
              author_login, additions, deletions, changed_files, review_count, html_url,
              created_at_gh, merged_at_gh, closed_at_gh)
            values (:repo, :gid, :number, :title, :state, :author, :add, :del, :files,
              coalesce(:reviews, 0), :url, :created, :merged, :closed)
            on conflict (repo_id, number) do update set github_pr_id = excluded.github_pr_id,
              title = excluded.title, state = excluded.state, author_login = excluded.author_login,
              additions = excluded.additions, deletions = excluded.deletions,
              changed_files = excluded.changed_files,
              review_count = coalesce(:reviews, pull_requests.review_count),
              html_url = excluded.html_url, merged_at_gh = excluded.merged_at_gh,
              closed_at_gh = excluded.closed_at_gh
            """)
        .param("repo", repoId)
        .param("gid", pr.path("id").asLong())
        .param("number", pr.path("number").asInt())
        .param("title", pr.path("title").asString())
        .param("state", pr.path("state").asString())
        .param("author", login(pr.path("user")))
        .param("add", Math.max(0, pr.path("additions").asInt()))
        .param("del", Math.max(0, pr.path("deletions").asInt()))
        .param("files", Math.max(0, pr.path("changed_files").asInt()))
        .param("reviews", reviewCount)
        .param("url", pr.path("html_url").asString())
        .param("created", ts(time(pr, "created_at")))
        .param("merged", ts(time(pr, "merged_at")))
        .param("closed", ts(time(pr, "closed_at")))
        .update();
  }

  /** {@code author} is the GitHub user (may be null); email is stored only as a SHA-256 hash. */
  public void insertCommit(long repoId, String sha, String login, String email, Instant at) {
    jdbc.sql(
            "insert into gitmedic.commits (repo_id, sha, author_login, author_email_hash, committed_at)"
                + " values (?, ?, ?, ?, ?) on conflict (repo_id, sha) do nothing")
        .params(repoId, sha, login, emailHash(email), ts(at))
        .update();
  }

  public static String login(JsonNode user) {
    return user.isObject() && user.path("login").isString() ? user.path("login").asString() : null;
  }

  public static Instant time(JsonNode node, String field) {
    JsonNode v = node.path(field);
    return v.isString() && !v.asString().isEmpty() ? Instant.parse(v.asString()) : null;
  }

  static String emailHash(String email) {
    if (email == null || email.isBlank()) {
      return null;
    }
    try {
      byte[] d =
          MessageDigest.getInstance("SHA-256")
              .digest(email.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(d);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
