package dev.gitmedic.ml;

import dev.gitmedic.auth.UserTokens;
import dev.gitmedic.common.ApiException;
import dev.gitmedic.ml.MlClient.MlRejectedException;
import dev.gitmedic.ml.MlClient.MlUnavailableException;
import dev.gitmedic.ml.MlService.Document;
import dev.gitmedic.repo.JobLimiter;
import dev.gitmedic.repo.RepoRepository;
import dev.gitmedic.repo.RepoRepository.Repo;
import dev.gitmedic.sync.SyncService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/repos/{repoId}")
class ModelController {

  private static final Logger log = LoggerFactory.getLogger(ModelController.class);

  private final RepoRepository repos;
  private final MlService ml;
  private final JobLimiter limiter;
  private final SyncService sync;
  private final UserTokens tokens;
  private final JdbcClient jdbc;
  private final TaskExecutor executor;

  ModelController(
      RepoRepository repos,
      MlService ml,
      JobLimiter limiter,
      SyncService sync,
      UserTokens tokens,
      JdbcClient jdbc,
      @Qualifier("applicationTaskExecutor") TaskExecutor executor) {
    this.repos = repos;
    this.ml = ml;
    this.limiter = limiter;
    this.sync = sync;
    this.tokens = tokens;
    this.jdbc = jdbc;
    this.executor = executor;
  }

  /** API-16: train/retrain, then re-predict; synchronous (≤ 120 s) so the UI shows the metrics. */
  @PostMapping("/models/{model}/train")
  Map<String, Object> train(
      @AuthenticationPrincipal Long userId, @PathVariable long repoId, @PathVariable String model) {
    Repo repo = repos.owned(userId, repoId);
    if (!model.equals("label") && !model.equals("merge-time")) {
      throw ApiException.notFound();
    }
    limiter.acquire(userId);
    try {
      JsonNode r = ml.train(repo.id(), model);
      Map<String, Object> out = new java.util.LinkedHashMap<>();
      out.put("model", model);
      out.put("trainedAt", r.path("trained_at").asString());
      out.put("samples", r.path("n_samples").asInt());
      if (model.equals("label")) {
        out.put("macroF1", r.path("macro_f1").asDouble());
        out.put("classCounts", r.path("class_counts"));
      } else {
        out.put("maeDays", r.path("mae_days").asDouble());
      }
      return out;
    } catch (MlRejectedException e) {
      throw new ApiException(HttpStatus.valueOf(e.status()), e.code(), e.getMessage());
    } catch (MlUnavailableException e) {
      throw mlUnavailable();
    } finally {
      limiter.release(userId);
    }
  }

  /** API-17: force a re-index of README/docs and every issue (unchanged chunks are skipped). */
  @PostMapping("/index")
  ResponseEntity<Map<String, Object>> reindex(
      @AuthenticationPrincipal Long userId, @PathVariable long repoId) {
    Repo repo = repos.owned(userId, repoId);
    if (!ml.available()) {
      throw mlUnavailable();
    }
    limiter.acquire(userId);
    try {
      executor.execute(
          () -> {
            try {
              String token = tokens.githubToken(repo.userId());
              List<Document> docs =
                  new ArrayList<>(sync.fetchDocs(token, repo, repo.defaultBranch()));
              docs.addAll(allIssueDocuments(repo.id()));
              int[] r = ml.index(repo.id(), docs);
              log.info("Re-index of repo {}: {} upserted, {} skipped", repo.id(), r[0], r[1]);
            } catch (RuntimeException e) {
              log.warn("Re-index of repo {} failed: {}", repo.id(), e.getMessage());
            } finally {
              limiter.release(userId);
            }
          });
    } catch (RuntimeException rejected) {
      limiter.release(userId);
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE, "QUEUE_FULL", "The job queue is full; retry shortly.");
    }
    return ResponseEntity.accepted().body(Map.of("id", repo.id(), "status", "INDEXING"));
  }

  private List<Document> allIssueDocuments(long repoId) {
    return jdbc.sql("select number, title, body, html_url from gitmedic.issues where repo_id = ?")
        .param(repoId)
        .query(
            (rs, n) ->
                new Document(
                    "issue",
                    Integer.toString(rs.getInt("number")),
                    rs.getString("html_url"),
                    MlService.issueText(rs.getString("title"), rs.getString("body"))))
        .list();
  }

  static ApiException mlUnavailable() {
    return new ApiException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "ML_UNAVAILABLE",
        "The ML service is unavailable; retry later.");
  }
}
