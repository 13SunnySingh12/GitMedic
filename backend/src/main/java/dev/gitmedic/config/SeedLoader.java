package dev.gitmedic.config;

import dev.gitmedic.auth.TokenCipher;
import dev.gitmedic.auth.UserRepository;
import dev.gitmedic.ml.MlClient.MlRejectedException;
import dev.gitmedic.ml.MlClient.MlUnavailableException;
import dev.gitmedic.ml.MlService;
import dev.gitmedic.ml.MlService.Document;
import dev.gitmedic.repo.RepoRepository;
import dev.gitmedic.sync.SyncStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Dev-only `seed` profile (§6.6): loads the synthetic fixtures/github data for a local demo user
 * through the same upserts the sync uses, then runs the real ML pipeline on it. No GitHub calls.
 * Never enable this profile against a database holding real data.
 */
@Component
@Profile("seed")
class SeedLoader implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(SeedLoader.class);
  static final long SEED_GITHUB_USER_ID = 1;

  private final UserRepository users;
  private final RepoRepository repos;
  private final SyncStore store;
  private final MlService ml;
  private final TokenCipher cipher;
  private final JdbcClient jdbc;
  private final ObjectMapper mapper;
  private final Path dir;

  SeedLoader(
      UserRepository users,
      RepoRepository repos,
      SyncStore store,
      MlService ml,
      TokenCipher cipher,
      JdbcClient jdbc,
      ObjectMapper mapper,
      @Value("${gitmedic.seed-dir:../fixtures/github}") String dir) {
    this.users = users;
    this.repos = repos;
    this.store = store;
    this.ml = ml;
    this.cipher = cipher;
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.dir = Path.of(dir);
  }

  private JsonNode read(String name) throws IOException {
    return mapper.readTree(Files.readString(dir.resolve(name)));
  }

  @Override
  public void run(ApplicationArguments args) throws IOException {
    // The token is a placeholder: "Sync now" on the seeded repo fails with GITHUB_TOKEN_INVALID.
    long userId =
        users.upsert(
            SEED_GITHUB_USER_ID,
            "seed-maintainer",
            "Seed Maintainer",
            null,
            cipher.encrypt("seed-no-github"),
            "read:user");
    JsonNode repoJson = read("repo.json");
    long repoId =
        repos
            .insert(
                userId,
                repoJson.path("id").asLong(),
                repoJson.path("full_name").asString(),
                "main",
                false)
            .orElseGet(
                () ->
                    jdbc.sql(
                            "select id from gitmedic.repos where user_id = ? and github_repo_id = ?")
                        .params(userId, repoJson.path("id").asLong())
                        .query(Long.class)
                        .single());
    for (JsonNode i : read("issues.json")) {
      if (!SyncStore.isPullRequest(i)) {
        store.upsertIssue(repoId, i);
      }
    }
    JsonNode details = read("pull_details.json");
    JsonNode reviews = read("pull_reviews.json");
    for (String n : details.propertyNames()) {
      store.upsertPull(repoId, details.get(n), reviews.get(n).size());
    }
    for (JsonNode c : read("commits.json")) {
      JsonNode a = c.path("commit").path("author");
      store.insertCommit(
          repoId,
          c.path("sha").asString(),
          SyncStore.login(c.path("author")),
          a.path("email").asString(),
          SyncStore.time(a, "date"));
    }
    repos.markSynced(repoId, Instant.now(), repoJson.path("full_name").asString(), "main", false);
    log.info("Seeded repo {} (user {}) from {}", repoId, userId, dir.toAbsolutePath());

    try {
      List<Document> docs = new ArrayList<>();
      JsonNode readme = read("readme.json");
      docs.add(
          new Document("readme", "README.md", readme.path("html_url").asString(), decode(readme)));
      for (JsonNode f : read("contents.json")) {
        if (f.path("path").asString().startsWith("docs/")) {
          docs.add(
              new Document(
                  "doc", f.path("path").asString(), f.path("html_url").asString(), decode(f)));
        }
      }
      docs.addAll(ml.issueDocumentsToIndex(repoId));
      ml.index(repoId, docs);
      for (String model : List.of("label", "merge-time")) {
        try {
          ml.train(repoId, model);
        } catch (MlRejectedException e) {
          log.info("Seed: {} model not trained: {}", model, e.getMessage());
        }
      }
      List<Integer> open =
          jdbc.sql("select number from gitmedic.issues where repo_id = ? and state = 'open'")
              .param(repoId)
              .query(Integer.class)
              .list();
      ml.detectDuplicates(repoId, open);
      ml.computeRisk(repoId, Instant.now());
      log.info("Seed: indexed, trained and scored repo {}", repoId);
    } catch (MlUnavailableException e) {
      log.warn("Seed: ML service unavailable; seeded data has no predictions yet");
    }
  }

  private static String decode(JsonNode contents) {
    return new String(
        Base64.getMimeDecoder().decode(contents.path("content").asString()),
        StandardCharsets.UTF_8);
  }
}
