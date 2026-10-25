package dev.gitmedic.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import dev.gitmedic.auth.JwtService;
import dev.gitmedic.auth.TokenCipher;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Shared test helpers: users/cookies, fixture-backed GitHub stubs, polling. */
public final class TestSupport {

  public static final Path FIXTURES = Path.of("..", "fixtures", "github");
  public static final ObjectMapper JSON = JsonMapper.builder().build();
  public static final String REPO = "/repos/octo/demo";

  private TestSupport() {}

  public static JsonNode fixture(String name) {
    try {
      return JSON.readTree(Files.readString(FIXTURES.resolve(name)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static long insertUser(JdbcClient jdbc, TokenCipher cipher, long githubId, String token) {
    return jdbc.sql(
            "insert into gitmedic.users (github_id, login, access_token_enc) values (?, ?, ?)"
                + " on conflict (github_id) do update set access_token_enc = excluded.access_token_enc"
                + " returning id")
        .params(githubId, "user" + githubId, cipher.encrypt(token))
        .query(Long.class)
        .single();
  }

  public static Cookie session(JwtService jwt, long userId) {
    return new Cookie(JwtService.COOKIE, jwt.issue(userId));
  }

  public static void await(BooleanSupplier condition, String what) {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
    while (!condition.getAsBoolean()) {
      if (Instant.now().isAfter(deadline)) {
        throw new AssertionError("Timed out waiting for " + what);
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError(e);
      }
    }
  }

  private static String json(JsonNode node) {
    return JSON.writeValueAsString(node);
  }

  /** Serves fixtures/github as the octo/demo repository, with paginated issue and commit feeds. */
  public static void stubGitHub(WireMockExtension gh) {
    gh.stubFor(
        WireMock.get(urlPathEqualTo("/user/repos")).willReturn(okJson(fixture("user_repos.json"))));
    gh.stubFor(WireMock.get(urlPathEqualTo(REPO)).willReturn(okJson(fixture("repo.json"))));
    stubPaged(gh, REPO + "/issues", fixture("issues.json"));
    stubPaged(gh, REPO + "/commits", fixture("commits.json"));
    gh.stubFor(
        WireMock.get(urlPathEqualTo(REPO + "/pulls")).willReturn(okJson(fixture("pulls.json"))));
    JsonNode details = fixture("pull_details.json");
    JsonNode reviews = fixture("pull_reviews.json");
    details
        .propertyNames()
        .forEach(
            n -> {
              gh.stubFor(
                  WireMock.get(urlPathEqualTo(REPO + "/pulls/" + n))
                      .willReturn(okJson(details.get(n)).withHeader("ETag", "\"pr-" + n + "\"")));
              gh.stubFor(
                  WireMock.get(urlPathEqualTo(REPO + "/pulls/" + n + "/reviews"))
                      .willReturn(okJson(reviews.get(n))));
            });
    gh.stubFor(
        WireMock.get(urlPathEqualTo(REPO + "/readme")).willReturn(okJson(fixture("readme.json"))));
    gh.stubFor(
        WireMock.get(urlPathMatching(REPO + "/git/trees/.*"))
            .willReturn(okJson(fixture("tree.json"))));
    JsonNode contents = fixture("contents.json");
    contents
        .propertyNames()
        .forEach(
            p ->
                gh.stubFor(
                    WireMock.get(urlPathEqualTo(REPO + "/contents/" + p))
                        .willReturn(okJson(contents.get(p)))));
  }

  /** 100 items per page; later pages are selected by the page query parameter in the Link URL. */
  static void stubPaged(WireMockExtension gh, String path, JsonNode items) {
    List<JsonNode> all = new ArrayList<>();
    items.forEach(all::add);
    int pages = (all.size() + 99) / 100;
    for (int p = 1; p <= pages; p++) {
      var body = JSON.createArrayNode();
      all.subList((p - 1) * 100, Math.min(all.size(), p * 100)).forEach(body::add);
      var response = okJson(body);
      if (p < pages) {
        response.withHeader(
            "Link",
            "<" + gh.baseUrl() + path + "?per_page=100&page=" + (p + 1) + ">; rel=\"next\"");
      }
      var mapping = WireMock.get(urlPathEqualTo(path));
      mapping =
          p == 1
              ? mapping.withQueryParam("page", WireMock.absent())
              : mapping.withQueryParam("page", equalTo(Integer.toString(p)));
      gh.stubFor(mapping.willReturn(response));
    }
  }

  public static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder okJson(
      JsonNode body) {
    return okJson(body, 4999, Instant.now().plusSeconds(3600));
  }

  public static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder okJson(
      JsonNode body, int remaining, Instant reset) {
    return aResponse()
        .withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withHeader("X-RateLimit-Remaining", Integer.toString(remaining))
        .withHeader("X-RateLimit-Reset", Long.toString(reset.getEpochSecond()))
        .withBody(json(body));
  }

  /** A FastAPI stand-in: index/risk succeed, models are not trained, no duplicates found. */
  public static void stubMl(WireMockExtension ml) {
    // Every ML endpoint the app calls has a default answer: an unstubbed call returns WireMock's
    // non-JSON 404, which MlClient rightly treats as an outage (30 s circuit-break).
    ml.stubFor(
        WireMock.post("/internal/v1/suggest")
            .willReturn(
                WireMock.okJson(
                    "{\"sources\":[],\"draft\":\"Insufficient context.\",\"mode\":\"RETRIEVAL_ONLY\","
                        + "\"llm_status\":\"DISABLED\"}")));
    ml.stubFor(
        WireMock.get("/health")
            .willReturn(
                WireMock.okJson("{\"status\":\"ok\",\"db\":true,\"embedder_loaded\":false}")));
    ml.stubFor(
        WireMock.post("/internal/v1/index")
            .willReturn(WireMock.okJson("{\"chunks_upserted\":1,\"chunks_skipped\":0}")));
    ml.stubFor(
        WireMock.post("/internal/v1/duplicates")
            .willReturn(
                WireMock.okJson(
                    "{\"matches\":[],\"top_score\":null,\"is_possible_duplicate\":false}")));
    String notTrained =
        "{\"code\":\"MODEL_NOT_TRAINED\",\"message\":\"not trained\",\"request_id\":\"x\"}";
    ml.stubFor(
        WireMock.post("/internal/v1/predict/label")
            .willReturn(aResponse().withStatus(422).withBody(notTrained)));
    ml.stubFor(
        WireMock.post("/internal/v1/predict/merge-time")
            .willReturn(aResponse().withStatus(422).withBody(notTrained)));
    ml.stubFor(
        WireMock.post("/internal/v1/risk")
            .willReturn(
                WireMock.okJson(
                    "{\"score\":42,\"level\":\"MEDIUM\",\"components\":{\"days_since_commit\":0.1,"
                        + "\"open_issue_growth\":0.5,\"unanswered_rate\":0.6,\"contributor_dropoff\":0.2}}")));
    ml.stubFor(
        WireMock.get(urlPathMatching("/internal/v1/repos/\\d+/models"))
            .willReturn(
                WireMock.okJson(
                    "{\"label\":null,\"label_data\":{\"have\":0,\"need\":30,\"ready\":false},"
                        + "\"merge_time\":null,\"merge_time_data\":{\"have\":0,\"need\":20,"
                        + "\"ready\":false},\"chunks\":0}")));
    ml.stubFor(
        WireMock.delete(urlPathMatching("/internal/v1/repos/\\d+"))
            .willReturn(aResponse().withStatus(204)));
  }
}
