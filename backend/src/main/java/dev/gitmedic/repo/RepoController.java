package dev.gitmedic.repo;

import dev.gitmedic.auth.UserTokens;
import dev.gitmedic.common.ApiException;
import dev.gitmedic.common.Paging;
import dev.gitmedic.github.GitHubClient;
import dev.gitmedic.ml.MlService;
import dev.gitmedic.repo.RepoRepository.Repo;
import dev.gitmedic.sync.SyncService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1")
class RepoController {

  /** ponytail: the add-repo picker reads at most 1,000 of the user's repos (10 pages). */
  private static final int MAX_GITHUB_REPOS = 1000;

  record GitHubRepo(
      long githubRepoId, String fullName, boolean isPrivate, String description, boolean tracked) {}

  record TrackRequest(
      @NotNull @Positive Long githubRepoId,
      @NotNull @Pattern(regexp = "^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$") String fullName) {}

  record Risk(int score, String level, Instant computedAt) {}

  record RepoSummary(
      long id,
      String fullName,
      boolean isPrivate,
      String syncStatus,
      String syncError,
      Instant lastSyncedAt,
      Risk risk) {}

  private final RepoRepository repos;
  private final GitHubClient github;
  private final UserTokens tokens;
  private final SyncService sync;
  private final MlService ml;
  private final JobLimiter limiter;
  private final JdbcClient jdbc;
  // ponytail: per-user 60 s cache for the picker only; in-memory, fine for one local instance.
  private final Map<Long, CachedRepos> pickerCache = new java.util.concurrent.ConcurrentHashMap<>();

  private record CachedRepos(List<JsonNode> repos, Instant fetchedAt) {}

  RepoController(
      RepoRepository repos,
      GitHubClient github,
      UserTokens tokens,
      SyncService sync,
      MlService ml,
      JobLimiter limiter,
      JdbcClient jdbc) {
    this.repos = repos;
    this.github = github;
    this.tokens = tokens;
    this.sync = sync;
    this.ml = ml;
    this.limiter = limiter;
    this.jdbc = jdbc;
  }

  /** API-05: the user's GitHub repos (owner/collaborator/org member), searchable by name. */
  @GetMapping("/github/repos")
  Paging.Page<GitHubRepo> githubRepos(
      @AuthenticationPrincipal Long userId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String q) {
    Paging paging = Paging.of(page, size);
    String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
    Set<Long> tracked = new HashSet<>(repos.trackedGithubIds(userId));
    List<GitHubRepo> all =
        pickerRepos(userId).stream()
            .filter(r -> r.path("full_name").asString().toLowerCase(Locale.ROOT).contains(needle))
            .map(
                r ->
                    new GitHubRepo(
                        r.path("id").asLong(),
                        r.path("full_name").asString(),
                        r.path("private").asBoolean(),
                        r.path("description").isString() ? r.path("description").asString() : null,
                        tracked.contains(r.path("id").asLong())))
            .toList();
    int from = (int) Math.min(paging.offset(), all.size());
    return paging.page(all.subList(from, Math.min(all.size(), from + paging.size())), all.size());
  }

  private List<JsonNode> pickerRepos(long userId) {
    CachedRepos cached = pickerCache.get(userId);
    if (cached == null || cached.fetchedAt().isBefore(Instant.now().minusSeconds(60))) {
      cached = new CachedRepos(userRepos(tokens.githubToken(userId)), Instant.now());
      pickerCache.put(userId, cached);
    }
    return cached.repos();
  }

  /** Fresh list from GitHub; tracking always validates against this, never the cache. */
  private List<JsonNode> userRepos(String token) {
    return github.list(
        token,
        github.uri("/user/repos").queryParam("sort", "updated").build().toUri(),
        MAX_GITHUB_REPOS,
        r -> false);
  }

  /** API-06: tracked repos with sync status and latest risk. */
  @GetMapping("/repos")
  List<RepoSummary> list(@AuthenticationPrincipal Long userId) {
    return jdbc.sql(
            """
            select r.id, r.full_name, r.is_private, r.sync_status, r.sync_error, r.last_synced_at,
              h.score, h.level, h.computed_at
            from gitmedic.repos r
            left join lateral (select score, level, computed_at from gitmedic.repo_health
              where repo_id = r.id order by computed_at desc limit 1) h on true
            where r.user_id = ? order by r.full_name
            """)
        .param(userId)
        .query(
            (rs, n) ->
                new RepoSummary(
                    rs.getLong("id"),
                    rs.getString("full_name"),
                    rs.getBoolean("is_private"),
                    rs.getString("sync_status"),
                    rs.getString("sync_error"),
                    instant(rs.getTimestamp("last_synced_at")),
                    rs.getObject("score") == null
                        ? null
                        : new Risk(
                            rs.getInt("score"),
                            rs.getString("level"),
                            instant(rs.getTimestamp("computed_at")))))
        .list();
  }

  /** API-07: track a repo the user can see on GitHub, then start the initial sync. */
  @PostMapping("/repos")
  ResponseEntity<Map<String, Object>> track(
      @AuthenticationPrincipal Long userId, @Valid @RequestBody TrackRequest req) {
    String token = tokens.githubToken(userId);
    JsonNode gh =
        userRepos(token).stream()
            .filter(
                r ->
                    r.path("id").asLong() == req.githubRepoId()
                        && r.path("full_name").asString().equalsIgnoreCase(req.fullName()))
            .findFirst()
            .orElseThrow(ApiException::notFound);
    long id =
        repos
            .insert(
                userId,
                req.githubRepoId(),
                gh.path("full_name").asString(),
                gh.path("default_branch").asString("main"),
                gh.path("private").asBoolean())
            .orElseThrow(
                () ->
                    new ApiException(
                        HttpStatus.CONFLICT,
                        "REPO_ALREADY_TRACKED",
                        "Repository is already tracked."));
    sync.submit(id, r -> {});
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            Map.of("id", id, "fullName", gh.path("full_name").asString(), "syncStatus", "PENDING"));
  }

  /** API-08: untrack; the FK cascade removes all derived rows, FastAPI removes model files. */
  @DeleteMapping("/repos/{repoId}")
  ResponseEntity<Void> untrack(@AuthenticationPrincipal Long userId, @PathVariable long repoId) {
    Repo repo = repos.owned(userId, repoId);
    ml.deleteRepo(repo.id());
    repos.delete(repo.id());
    return ResponseEntity.noContent().build();
  }

  /** API-09: manual incremental sync (since last_synced_at). */
  @PostMapping("/repos/{repoId}/sync")
  ResponseEntity<Map<String, Object>> syncNow(
      @AuthenticationPrincipal Long userId, @PathVariable long repoId) {
    Repo repo = repos.owned(userId, repoId);
    limiter.acquire(userId);
    if (!repos.claimSync(repo.id())) {
      limiter.release(userId);
      throw new ApiException(
          HttpStatus.CONFLICT,
          "SYNC_ALREADY_RUNNING",
          "A sync of this repository is already running.");
    }
    sync.submit(repo.id(), id -> limiter.release(userId));
    return ResponseEntity.accepted().body(Map.of("id", repo.id(), "syncStatus", "PENDING"));
  }

  static Instant instant(java.sql.Timestamp t) {
    return t == null ? null : t.toInstant();
  }
}
