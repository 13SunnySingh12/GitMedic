package dev.gitmedic.webhook;

import dev.gitmedic.common.ApiException;
import dev.gitmedic.config.AppProperties;
import dev.gitmedic.ml.MlClient.MlUnavailableException;
import dev.gitmedic.ml.MlService;
import dev.gitmedic.repo.RepoRepository;
import dev.gitmedic.repo.RepoRepository.Repo;
import dev.gitmedic.sync.SyncStore;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * API-22 / FR-006: signature-verified GitHub webhooks. The signature is checked against the raw
 * body before anything is parsed; data is upserted synchronously by natural key (so redeliveries
 * are idempotent) and the ML follow-up runs on the job executor.
 */
@RestController
class WebhookController {

  private static final Logger log = LoggerFactory.getLogger(WebhookController.class);
  static final int MAX_BODY = 1024 * 1024;
  private static final Set<String> ISSUE_ACTIONS =
      Set.of("opened", "edited", "closed", "reopened", "labeled", "unlabeled", "deleted");
  private static final Set<String> PR_ACTIONS =
      Set.of("opened", "edited", "closed", "reopened", "synchronize");

  private final AppProperties props;
  private final ObjectMapper mapper;
  private final RepoRepository repos;
  private final SyncStore store;
  private final MlService ml;
  private final TaskExecutor executor;

  WebhookController(
      AppProperties props,
      ObjectMapper mapper,
      RepoRepository repos,
      SyncStore store,
      MlService ml,
      @Qualifier("applicationTaskExecutor") TaskExecutor executor) {
    this.props = props;
    this.mapper = mapper;
    this.repos = repos;
    this.store = store;
    this.ml = ml;
    this.executor = executor;
  }

  @PostMapping("/api/v1/webhooks/github")
  ResponseEntity<Void> receive(HttpServletRequest request) throws IOException {
    if (props.webhookSecret() == null || props.webhookSecret().isEmpty()) {
      // §8.3: without a secret the endpoint never accepts anything.
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE, "WEBHOOKS_DISABLED", "GITHUB_WEBHOOK_SECRET is not set.");
    }
    byte[] body = readCapped(request);
    if (!SignatureVerifier.valid(
        props.webhookSecret(), body, request.getHeader("X-Hub-Signature-256"))) {
      throw new ApiException(
          HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE", "Webhook signature is invalid.");
    }
    JsonNode payload;
    try {
      payload = mapper.readTree(body);
    } catch (JacksonException e) {
      throw ApiException.badRequest("Malformed JSON payload.");
    }
    String event = String.valueOf(request.getHeader("X-GitHub-Event"));
    log.info("Webhook {} delivery {}", event, request.getHeader("X-GitHub-Delivery"));

    List<Repo> tracked = repos.byGithubRepoId(payload.path("repository").path("id").asLong());
    if (tracked.isEmpty()) {
      return ResponseEntity.noContent().build(); // BR-14
    }
    boolean handled = false;
    for (Repo repo : tracked) {
      handled |=
          switch (event) {
            case "issues" -> issue(repo, payload);
            case "pull_request" -> pull(repo, payload);
            case "push" -> push(repo, payload);
            default -> false;
          };
    }
    return handled ? ResponseEntity.accepted().build() : ResponseEntity.noContent().build();
  }

  private static byte[] readCapped(HttpServletRequest request) throws IOException {
    if (request.getContentLengthLong() > MAX_BODY) {
      throw tooLarge();
    }
    try (InputStream in = request.getInputStream()) {
      byte[] body = in.readNBytes(MAX_BODY + 1);
      if (body.length > MAX_BODY) {
        throw tooLarge();
      }
      return body;
    }
  }

  private static ApiException tooLarge() {
    return new ApiException(
        HttpStatus.CONTENT_TOO_LARGE, "PAYLOAD_TOO_LARGE", "Webhook body exceeds 1 MB.");
  }

  private boolean issue(Repo repo, JsonNode p) {
    String action = p.path("action").asString();
    JsonNode issue = p.path("issue");
    if (!ISSUE_ACTIONS.contains(action) || SyncStore.isPullRequest(issue)) {
      return false;
    }
    int number = issue.path("number").asInt();
    if ("deleted".equals(action)) {
      store.deleteIssue(repo.id(), number);
      return true;
    }
    store.upsertIssue(repo.id(), issue);
    background(
        repo.id(),
        () -> {
          ml.index(repo.id(), ml.issueDocumentsToIndex(repo.id()));
          ml.detectDuplicates(repo.id(), List.of(number));
          ml.predictLabels(repo.id(), false);
        });
    return true;
  }

  private boolean pull(Repo repo, JsonNode p) {
    if (!PR_ACTIONS.contains(p.path("action").asString())) {
      return false;
    }
    store.upsertPull(repo.id(), p.path("pull_request"), null);
    background(repo.id(), () -> ml.predictMergeTimes(repo.id()));
    return true;
  }

  private boolean push(Repo repo, JsonNode p) {
    if (!("refs/heads/" + repo.defaultBranch()).equals(p.path("ref").asString())) {
      return false; // only default-branch commits count (FR-009/010)
    }
    for (JsonNode c : p.path("commits")) {
      JsonNode author = c.path("author");
      java.time.Instant at = SyncStore.time(c, "timestamp");
      store.insertCommit(
          repo.id(),
          c.path("id").asString(),
          author.path("username").isString() ? author.path("username").asString() : null,
          author.path("email").asString(null),
          at == null ? java.time.Instant.now() : at);
    }
    return true;
  }

  /**
   * Best effort: a missed ML pass is repaired by the next sync (stale embeddings are re-indexed).
   */
  private void background(long repoId, Runnable work) {
    try {
      executor.execute(
          () -> {
            try {
              work.run();
            } catch (MlUnavailableException e) {
              log.warn("ML unavailable for webhook follow-up on repo {}", repoId);
            } catch (RuntimeException e) {
              log.error("Webhook follow-up failed for repo {}", repoId, e);
            }
          });
    } catch (RuntimeException rejected) {
      log.warn("Job queue full; ML follow-up for repo {} deferred to next sync", repoId);
    }
  }
}
