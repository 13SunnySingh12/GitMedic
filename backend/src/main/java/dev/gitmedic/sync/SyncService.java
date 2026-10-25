package dev.gitmedic.sync;

import dev.gitmedic.auth.UserTokens;
import dev.gitmedic.github.GitHubClient;
import dev.gitmedic.github.GitHubException;
import dev.gitmedic.ml.MlClient.MlUnavailableException;
import dev.gitmedic.ml.MlService;
import dev.gitmedic.ml.MlService.Document;
import dev.gitmedic.repo.RepoRepository;
import dev.gitmedic.repo.RepoRepository.Repo;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * FR-005/007: initial and incremental sync, in the §5.7 order: repo → issues → PRs (+details,
 * reviews) → commits → index → predict → risk. One active sync per repo (claimed in the DB).
 */
@Service
public class SyncService {

  private static final Logger log = LoggerFactory.getLogger(SyncService.class);

  // BR-13 caps.
  static final int MAX_ISSUES = 10_000;
  static final int MAX_PULLS = 500;
  static final int MAX_COMMITS = 5_000;
  static final int MAX_DOC_FILES = 50;
  static final int MAX_DOC_BYTES = 200 * 1024;

  private final GitHubClient github;
  private final RepoRepository repos;
  private final SyncStore store;
  private final MlService ml;
  private final UserTokens tokens;
  private final TaskExecutor executor;
  private final TaskScheduler scheduler;
  private final Clock clock;

  SyncService(
      GitHubClient github,
      RepoRepository repos,
      SyncStore store,
      MlService ml,
      UserTokens tokens,
      @Qualifier("applicationTaskExecutor") TaskExecutor executor,
      TaskScheduler scheduler,
      Clock clock) {
    this.github = github;
    this.repos = repos;
    this.store = store;
    this.ml = ml;
    this.tokens = tokens;
    this.executor = executor;
    this.scheduler = scheduler;
    this.clock = clock;
  }

  @EventListener(ApplicationReadyEvent.class)
  void failInterruptedSyncs() {
    int n = repos.failInterrupted();
    if (n > 0) {
      log.info("Marked {} interrupted sync(s) as FAILED after restart", n);
    }
  }

  /**
   * Queues a sync of an already-claimed (PENDING) repo on the bounded executor. {@code onDone} runs
   * when the job ends either way (used to release the per-user job slot).
   */
  public void submit(long repoId, LongConsumer onDone) {
    try {
      executor.execute(
          () -> {
            try {
              run(repoId);
            } finally {
              onDone.accept(repoId);
            }
          });
    } catch (RuntimeException rejected) {
      repos.setStatus(repoId, "FAILED", "The sync queue is full; try again shortly.");
      onDone.accept(repoId);
    }
  }

  void run(long repoId) {
    Repo repo = repos.find(repoId).orElse(null);
    if (repo == null) {
      return; // untracked while queued
    }
    Instant started = clock.instant();
    repos.setStatus(repoId, "RUNNING", null);
    try {
      String token = tokens.githubToken(repo.userId());
      JsonNode meta = github.get(token, github.repoUri(repo.fullName(), "", Map.of()));
      String branch = meta.path("default_branch").asString(repo.defaultBranch());
      Instant since = repo.lastSyncedAt();

      syncIssues(token, repo, since);
      syncPulls(token, repo, since);
      syncCommits(token, repo, branch, since);
      runMl(token, repo, branch);

      repos.markSynced(
          repoId,
          started,
          meta.path("full_name").asString(repo.fullName()),
          branch,
          meta.path("private").asBoolean(repo.isPrivate()));
      log.info(
          "Sync of repo {} finished in {} ms",
          repoId,
          Duration.between(started, clock.instant()).toMillis());
    } catch (GitHubException e) {
      handleGitHubFailure(repoId, e);
    } catch (RuntimeException e) {
      log.error("Sync of repo {} failed", repoId, e);
      repos.setStatus(repoId, "FAILED", "Unexpected error during sync; see server log.");
    }
  }

  private void handleGitHubFailure(long repoId, GitHubException e) {
    switch (e.kind()) {
      case RATE_LIMITED -> {
        Instant resume = e.resetAt().plusSeconds(5);
        repos.setStatus(
            repoId, "RATE_LIMITED", "GitHub rate limit reached; resumes at " + e.resetAt());
        // Progress so far is stored; the resumed run re-fetches from the previous sync point and
        // the natural-key upserts make that idempotent.
        scheduler.schedule(
            () -> {
              if (repos.claimSync(repoId)) {
                submit(repoId, id -> {});
              }
            },
            resume);
      }
      case TOKEN_INVALID ->
          repos.setStatus(
              repoId, "FAILED", "GITHUB_TOKEN_INVALID: GitHub rejected the token; sign in again.");
      case NOT_FOUND ->
          repos.setStatus(
              repoId,
              "FAILED",
              "GITHUB_NOT_FOUND: the repository is gone or no longer accessible.");
      case UNAVAILABLE ->
          repos.setStatus(repoId, "FAILED", "GitHub is unavailable; try again later.");
    }
  }

  private void syncIssues(String token, Repo repo, Instant since) {
    Map<String, Object> q = new LinkedHashMap<>();
    q.put("state", "all");
    q.put("sort", "created");
    q.put("direction", "desc");
    q.put("per_page", 100);
    if (since != null) {
      q.put("since", since.toString());
    }
    // The issues feed also contains PRs; the cap counts real issues only.
    int stored = 0;
    var next = github.repoUri(repo.fullName(), "/issues", q);
    while (next != null && stored < MAX_ISSUES) {
      GitHubClient.Page page = github.page(token, next);
      for (JsonNode item : page.body()) {
        if (SyncStore.isPullRequest(item)) {
          continue;
        }
        store.upsertIssue(repo.id(), item);
        if (++stored >= MAX_ISSUES) {
          break;
        }
      }
      next = page.next();
    }
  }

  private void syncPulls(String token, Repo repo, Instant since) {
    // Initial: newest 500 by creation. Incremental: by update time until older than the last sync.
    Map<String, Object> q =
        Map.of("state", "all", "sort", since == null ? "created" : "updated", "direction", "desc");
    List<JsonNode> pulls =
        github.list(
            token,
            github.repoUri(repo.fullName(), "/pulls", q),
            MAX_PULLS,
            pr -> since != null && SyncStore.time(pr, "updated_at").isBefore(since));
    for (JsonNode pr : pulls) {
      String n = pr.path("number").asString();
      JsonNode detail = github.get(token, github.repoUri(repo.fullName(), "/pulls/" + n, Map.of()));
      int reviews =
          github
              .list(
                  token,
                  github.repoUri(repo.fullName(), "/pulls/" + n + "/reviews", Map.of()),
                  1000,
                  r -> false)
              .size();
      store.upsertPull(repo.id(), detail, reviews);
    }
  }

  private void syncCommits(String token, Repo repo, String branch, Instant since) {
    Instant yearAgo = clock.instant().minus(Duration.ofDays(365));
    Instant from = since != null && since.isAfter(yearAgo) ? since : yearAgo;
    List<JsonNode> commits =
        github.list(
            token,
            github.repoUri(
                repo.fullName(), "/commits", Map.of("sha", branch, "since", from.toString())),
            MAX_COMMITS,
            c -> false);
    for (JsonNode c : commits) {
      JsonNode author = c.path("commit").path("author");
      store.insertCommit(
          repo.id(),
          c.path("sha").asString(),
          SyncStore.login(c.path("author")),
          author.path("email").asString(null),
          SyncStore.time(author, "date"));
    }
  }

  /** Index → duplicates → predictions → risk. ML being down never fails the sync (NFR-008). */
  private void runMl(String token, Repo repo, String branch) {
    try {
      List<Document> issueDocs = ml.issueDocumentsToIndex(repo.id());
      List<Document> docs = new ArrayList<>(fetchDocs(token, repo, branch));
      docs.addAll(issueDocs);
      ml.index(repo.id(), docs);
      ml.detectDuplicates(
          repo.id(), issueDocs.stream().map(d -> Integer.parseInt(d.source_ref())).toList());
      ml.predictLabels(repo.id(), false);
      ml.predictMergeTimes(repo.id());
      ml.computeRisk(repo.id(), clock.instant());
    } catch (MlUnavailableException e) {
      log.warn(
          "ML service unavailable during sync of repo {}; predictions retried next sync",
          repo.id());
    }
  }

  /** BR-13: README plus docs/**.md, at most 50 files of ≤ 200 KB each. */
  public List<Document> fetchDocs(String token, Repo repo, String branch) {
    List<Document> docs = new ArrayList<>();
    try {
      JsonNode readme = github.get(token, github.repoUri(repo.fullName(), "/readme", Map.of()));
      if (readme.path("size").asInt() <= MAX_DOC_BYTES) {
        docs.add(document("readme", readme));
      }
    } catch (GitHubException e) {
      if (e.kind() != GitHubException.Kind.NOT_FOUND) {
        throw e;
      }
    }
    JsonNode tree =
        github.get(
            token, github.repoUri(repo.fullName(), "/git/trees/" + branch, Map.of("recursive", 1)));
    for (JsonNode entry : tree.path("tree")) {
      String path = entry.path("path").asString();
      if (docs.size() >= MAX_DOC_FILES) {
        break;
      }
      if ("blob".equals(entry.path("type").asString())
          && path.startsWith("docs/")
          && path.toLowerCase(Locale.ROOT).endsWith(".md")
          && entry.path("size").asInt() <= MAX_DOC_BYTES) {
        JsonNode file =
            github.get(
                token, github.repoUri(repo.fullName(), "/contents/" + path, Map.of("ref", branch)));
        docs.add(document("doc", file));
      }
    }
    return docs;
  }

  private static Document document(String type, JsonNode contents) {
    String text =
        new String(
            Base64.getMimeDecoder().decode(contents.path("content").asString("")),
            StandardCharsets.UTF_8);
    return new Document(
        type, contents.path("path").asString(), contents.path("html_url").asString(), text);
  }
}
