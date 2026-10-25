package dev.gitmedic;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static dev.gitmedic.support.TestSupport.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import dev.gitmedic.analytics.AnalyticsService;
import dev.gitmedic.auth.JwtService;
import dev.gitmedic.auth.TokenCipher;
import dev.gitmedic.support.Seed;
import dev.gitmedic.support.TestSupport;
import dev.gitmedic.sync.SyncStore;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Webhooks (API-22), analytics (API-10..12), issues/PRs (API-13..15), training (API-16). */
@IntegrationTest
class ApiIntegrationTest {

  @RegisterExtension
  static WireMockExtension ml =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  @DynamicPropertySource
  static void urls(DynamicPropertyRegistry r) {
    r.add("gitmedic.ml-base-url", ml::baseUrl);
  }

  private static final AtomicLong IDS = new AtomicLong(70_000);

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired TokenCipher cipher;
  @Autowired JwtService jwt;
  @Autowired SyncStore store;
  @Autowired AnalyticsService analytics;

  long userId;
  long repoId;
  Cookie cookie;

  @BeforeEach
  void setUp() {
    // Webhooks match repos by GitHub id; keep exactly one tracked octo/demo at a time.
    jdbc.sql("delete from gitmedic.repos where github_repo_id = 123456").update();
    TestSupport.stubMl(ml);
    long gh = IDS.incrementAndGet();
    userId = TestSupport.insertUser(jdbc, cipher, gh, "token-" + gh);
    cookie = TestSupport.session(jwt, userId);
    repoId = Seed.repo(jdbc, store, userId);
  }

  private String base() {
    return "/api/v1/repos/" + repoId;
  }

  private long count(String sql, Object... params) {
    return jdbc.sql(sql).params(params).query(Long.class).single();
  }

  // --- Webhooks ---------------------------------------------------------------------------

  private static String sign(byte[] body) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(
        new SecretKeySpec("test-webhook-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
  }

  private MockHttpServletRequestBuilder webhook(String event, byte[] body, String signature) {
    return post("/api/v1/webhooks/github")
        .contentType(MediaType.APPLICATION_JSON)
        .header("X-GitHub-Event", event)
        .header("X-GitHub-Delivery", "delivery-1")
        .header("X-Hub-Signature-256", signature)
        .content(body);
  }

  private static byte[] bytes(JsonNode n) {
    return TestSupport.JSON.writeValueAsBytes(n);
  }

  @Test
  void qaWh01_signedIssueOpenedIsUpsertedIdempotentlyAndAnalysed() throws Exception {
    byte[] body = bytes(TestSupport.fixture("webhook_issues_opened.json"));
    mvc.perform(webhook("issues", body, sign(body))).andExpect(status().isAccepted());
    mvc.perform(webhook("issues", body, sign(body))).andExpect(status().isAccepted()); // replay
    assertThat(
            count(
                "select count(*) from gitmedic.issues where repo_id = ? and number = 500", repoId))
        .isEqualTo(1);
    await(
        () -> !ml.findAll(postRequestedFor(urlPathEqualTo("/internal/v1/duplicates"))).isEmpty(),
        "duplicate check after webhook");
    // FastAPI requires a declared Content-Length (chunked bodies are rejected with 413).
    ml.verify(
        postRequestedFor(urlPathEqualTo("/internal/v1/index"))
            .withHeader("Content-Length", WireMock.matching("[1-9][0-9]*")));
  }

  @Test
  void qaWh02_untrackedRepoIsAcknowledgedAndIgnored() throws Exception {
    ObjectNode payload = (ObjectNode) TestSupport.fixture("webhook_issues_opened.json");
    ((ObjectNode) payload.path("repository")).put("id", 999_999);
    byte[] body = bytes(payload);
    mvc.perform(webhook("issues", body, sign(body))).andExpect(status().isNoContent());
    assertThat(count("select count(*) from gitmedic.issues where number = 500")).isZero();
  }

  @Test
  void qaSec06_badSignatureIsRejectedWithoutWriting() throws Exception {
    byte[] body = bytes(TestSupport.fixture("webhook_issues_opened.json"));
    mvc.perform(webhook("issues", body, "sha256=" + "0".repeat(64)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"));
    mvc.perform(
            post("/api/v1/webhooks/github")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-GitHub-Event", "issues")
                .content(body))
        .andExpect(status().isUnauthorized());
    assertThat(count("select count(*) from gitmedic.issues where number = 500")).isZero();
  }

  @Test
  void malformedJsonWithValidSignatureIs400() throws Exception {
    byte[] body = "{not json".getBytes(StandardCharsets.UTF_8);
    mvc.perform(webhook("issues", body, sign(body))).andExpect(status().isBadRequest());
  }

  @Test
  void oversizedWebhookIs413() throws Exception {
    byte[] body = new byte[1024 * 1024 + 1];
    mvc.perform(webhook("issues", body, sign(body))).andExpect(status().isPayloadTooLarge());
  }

  @Test
  void pushAndPullRequestEvents() throws Exception {
    byte[] push = bytes(TestSupport.fixture("webhook_push.json"));
    mvc.perform(webhook("push", push, sign(push))).andExpect(status().isAccepted());
    assertThat(
            count(
                "select count(*) from gitmedic.commits where repo_id = ? and sha = ?",
                repoId,
                "f".repeat(40)))
        .isEqualTo(1);

    ObjectNode other = (ObjectNode) TestSupport.fixture("webhook_push.json");
    other.put("ref", "refs/heads/feature");
    byte[] otherBody = bytes(other);
    mvc.perform(webhook("push", otherBody, sign(otherBody))).andExpect(status().isNoContent());

    byte[] pr = bytes(TestSupport.fixture("webhook_pull_request_opened.json"));
    mvc.perform(webhook("pull_request", pr, sign(pr))).andExpect(status().isAccepted());
    assertThat(
            count(
                "select additions from gitmedic.pull_requests where repo_id = ? and number = 501",
                repoId))
        .isEqualTo(120);

    mvc.perform(webhook("ping", pr, sign(pr))).andExpect(status().isNoContent());
  }

  // --- Analytics --------------------------------------------------------------------------

  @Test
  void heatmapEqualsSqlAggregateOfFixture() throws Exception {
    Map<LocalDate, Integer> expected = new TreeMap<>();
    for (JsonNode c : TestSupport.fixture("commits.json")) {
      LocalDate d =
          Instant.parse(c.path("commit").path("author").path("date").asString())
              .atZone(ZoneOffset.UTC)
              .toLocalDate();
      expected.merge(d, 1, Integer::sum);
    }
    String json =
        mvc.perform(
                get(base() + "/heatmap")
                    .param("from", "2025-10-01")
                    .param("to", "2026-10-31")
                    .cookie(cookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(300))
            .andExpect(jsonPath("$.timezone").value("UTC"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode body = TestSupport.JSON.readTree(json);
    Map<LocalDate, Integer> actual = new TreeMap<>();
    body.path("days")
        .forEach(
            d -> actual.put(LocalDate.parse(d.path("date").asString()), d.path("count").asInt()));
    assertThat(actual).isEqualTo(expected);
    long weekdaySum = 0;
    for (JsonNode w : body.path("weekdays")) {
      weekdaySum += w.path("count").asLong();
    }
    assertThat(body.path("weekdays")).hasSize(7);
    assertThat(weekdaySum).isEqualTo(300);

    mvc.perform(
            get(base() + "/heatmap")
                .param("from", "2026-10-31")
                .param("to", "2026-01-01")
                .cookie(cookie))
        .andExpect(status().isBadRequest());
    mvc.perform(get(base() + "/heatmap").param("from", "nope").cookie(cookie))
        .andExpect(status().isBadRequest());
  }

  @Test
  void busFactorMatchesFixtureDistribution() {
    var bf = analytics.busFactor(repoId, Instant.parse("2026-10-20T00:00:00Z"));
    assertThat(bf.totalCommits()).isEqualTo(300);
    long top = bf.contributors().getFirst().commits();
    // alice carries ~50% of fixture commits, so one contributor reaches half or two do.
    assertThat(bf.busFactor()).isEqualTo(top * 2 >= 300 ? 1 : 2);
    assertThat(bf.risky()).isTrue();
    assertThat(bf.contributors()).allMatch(c -> !c.login().contains("@"));
  }

  @Test
  void dashboardCombinesRiskDeltaStatsAndModels() throws Exception {
    jdbc.sql(
            "insert into gitmedic.repo_health (repo_id, computed_at, score, level, components) values"
                + " (?, now() - interval '8 days', 30, 'LOW', '{\"days_since_commit\":0.1}'),"
                + " (?, now(), 72, 'HIGH', '{\"days_since_commit\":0.9,\"open_issue_growth\":0.4}')")
        .params(repoId, repoId)
        .update();
    ml.stubFor(
        WireMock.get(urlPathEqualTo("/internal/v1/repos/" + repoId + "/models"))
            .willReturn(
                WireMock.okJson(
                    "{\"label\":{\"trained_at\":\"2026-10-20T00:00:00Z\",\"n_samples\":70,\"macro_f1\":0.71},"
                        + "\"label_data\":{\"have\":70,\"need\":30,\"ready\":true},"
                        + "\"merge_time\":null,\"merge_time_data\":{\"have\":12,\"need\":20,\"ready\":false},"
                        + "\"chunks\":130}")));
    mvc.perform(get(base() + "/dashboard").cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.risk.score").value(72))
        .andExpect(jsonPath("$.risk.level").value("HIGH"))
        .andExpect(jsonPath("$.risk.delta").value(42))
        .andExpect(jsonPath("$.risk.components.daysSinceCommit").value(0.9))
        .andExpect(jsonPath("$.stats.openIssues").isNumber())
        .andExpect(jsonPath("$.stats.openPrs").value(5))
        .andExpect(jsonPath("$.models.label.trained").value(true))
        .andExpect(jsonPath("$.models.label.macroF1").value(0.71))
        .andExpect(jsonPath("$.models.mergeTime.trained").value(false))
        .andExpect(jsonPath("$.models.mergeTime.reason").value("INSUFFICIENT_TRAINING_DATA"))
        .andExpect(jsonPath("$.models.mergeTime.have").value(12))
        .andExpect(jsonPath("$.models.chunks").value(130))
        .andExpect(jsonPath("$.mlStatus").value("OK"));
  }

  @Test
  void dashboardWithoutAnyHealthHasNullRisk() throws Exception {
    mvc.perform(get(base() + "/dashboard").cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.risk").isEmpty());
  }

  // --- Issues & PRs -----------------------------------------------------------------------

  @Test
  void issueListFiltersPaginatesAndValidates() throws Exception {
    long open =
        count("select count(*) from gitmedic.issues where repo_id = ? and state = 'open'", repoId);
    jdbc.sql(
            "update gitmedic.issues set predicted_label = 'bug', predicted_confidence = 0.9,"
                + " is_possible_duplicate = true, duplicate_score = 0.88 where repo_id = ? and number = 42")
        .param(repoId)
        .update();
    mvc.perform(get(base() + "/issues").cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalItems").value(open))
        .andExpect(jsonPath("$.size").value(20));
    mvc.perform(
            get(base() + "/issues").param("label", "bug").param("duplicate", "true").cookie(cookie))
        .andExpect(jsonPath("$.totalItems").value(1))
        .andExpect(jsonPath("$.items[0].number").value(42))
        .andExpect(jsonPath("$.items[0].predictedConfidence").value(0.9))
        .andExpect(jsonPath("$.items[0].duplicateScore").value(0.88));
    mvc.perform(
            get(base() + "/issues")
                .param("state", "all")
                .param("size", "5")
                .param("page", "2")
                .cookie(cookie))
        .andExpect(jsonPath("$.items.length()").value(5))
        .andExpect(jsonPath("$.page").value(2))
        .andExpect(jsonPath("$.totalItems").value(120))
        .andExpect(jsonPath("$.totalPages").value(24));
    mvc.perform(get(base() + "/issues").param("size", "0").cookie(cookie))
        .andExpect(jsonPath("$.size").value(1));
    for (String[] bad :
        new String[][] {
          {"page", "-1"},
          {"state", "x"},
          {"label", "docs"},
          {"sort", "title;drop"},
          {"sort", "number,up"}
        }) {
      mvc.perform(get(base() + "/issues").param(bad[0], bad[1]).cookie(cookie))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
  }

  @Test
  void issueDetailIncludesDuplicatesAndSuggestedAnswer() throws Exception {
    ml.stubFor(
        WireMock.post("/internal/v1/duplicates")
            .willReturn(
                WireMock.okJson(
                    "{\"matches\":[{\"issue_number\":41,\"score\":0.91}],\"top_score\":0.91,"
                        + "\"is_possible_duplicate\":true}")));
    ml.stubFor(
        WireMock.post("/internal/v1/suggest")
            .willReturn(
                WireMock.okJson(
                    "{\"sources\":[{\"type\":\"doc\",\"ref\":\"docs/faq.md#saving\",\"url\":"
                        + "\"https://github.com/octo/demo/blob/main/docs/faq.md#saving\",\"snippet\":\"upgrade\","
                        + "\"score\":0.7}],\"draft\":\"See docs\",\"mode\":\"RETRIEVAL_ONLY\","
                        + "\"llm_status\":\"DISABLED\"}")));
    mvc.perform(get(base() + "/issues/42").cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.issue.number").value(42))
        .andExpect(jsonPath("$.duplicates[0].number").value(41))
        .andExpect(jsonPath("$.duplicates[0].state").value("closed"))
        .andExpect(
            jsonPath("$.duplicates[0].htmlUrl").value("https://github.com/octo/demo/issues/41"))
        .andExpect(jsonPath("$.suggestedAnswer.mode").value("RETRIEVAL_ONLY"))
        .andExpect(jsonPath("$.suggestedAnswer.llmStatus").value("DISABLED"))
        .andExpect(jsonPath("$.suggestedAnswer.sources[0].ref").value("docs/faq.md#saving"))
        .andExpect(jsonPath("$.mlStatus").value("OK"));
    mvc.perform(get(base() + "/issues/99999").cookie(cookie)).andExpect(status().isNotFound());
  }

  @Test
  void pullListShowsPredictions() throws Exception {
    jdbc.sql(
            "update gitmedic.pull_requests set predicted_merge_days = 2.5, latest_predicted_merge_days = 3.0"
                + " where repo_id = ? and number = 40")
        .param(repoId)
        .update();
    mvc.perform(get(base() + "/pulls").cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalItems").value(5))
        .andExpect(jsonPath("$.items[?(@.number == 40)].predictedMergeDays").value(3.0))
        .andExpect(jsonPath("$.items[?(@.number == 40)].firstPredictedMergeDays").value(2.5));
    mvc.perform(get(base() + "/pulls").param("state", "all").cookie(cookie))
        .andExpect(jsonPath("$.totalItems").value(40));
  }

  // --- Training ---------------------------------------------------------------------------

  private MockHttpServletRequestBuilder train(String model) {
    return post(base() + "/models/" + model + "/train")
        .cookie(cookie)
        .header("X-Requested-With", "GitMedic");
  }

  @Test
  void trainingSurfacesMetricsOrInsufficientData() throws Exception {
    ml.stubFor(
        WireMock.post("/internal/v1/train/label")
            .willReturn(
                WireMock.okJson(
                    "{\"trained_at\":\"2026-10-20T00:00:00Z\",\"n_samples\":70,"
                        + "\"class_counts\":{\"bug\":30},\"macro_f1\":0.66}")));
    ml.stubFor(
        WireMock.post("/internal/v1/predict/label")
            .willReturn(WireMock.okJson("{\"predictions\":[]}")));
    mvc.perform(train("label"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.macroF1").value(0.66))
        .andExpect(jsonPath("$.samples").value(70));
    ml.verify(postRequestedFor(urlPathEqualTo("/internal/v1/predict/label")));

    ml.stubFor(
        WireMock.post("/internal/v1/train/merge-time")
            .willReturn(
                aResponse()
                    .withStatus(422)
                    .withBody(
                        "{\"code\":\"INSUFFICIENT_TRAINING_DATA\",\"message\":\"Need 20\"}")));
    mvc.perform(train("merge-time"))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("INSUFFICIENT_TRAINING_DATA"));
    mvc.perform(train("nonsense")).andExpect(status().isNotFound());
  }

  @Test
  void qaSec15_expensiveJobsAreRateLimitedPerUser() throws Exception {
    ml.stubFor(
        WireMock.post("/internal/v1/train/merge-time")
            .willReturn(
                aResponse().withStatus(422).withBody("{\"code\":\"INSUFFICIENT_TRAINING_DATA\"}")));
    for (int i = 0; i < 10; i++) {
      mvc.perform(train("merge-time")).andExpect(status().isUnprocessableContent());
    }
    mvc.perform(train("merge-time"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("TOO_MANY_JOBS"))
        .andExpect(jsonPath("$.retryAfter").isNumber());
  }

  @Test
  void qaSec11_apiResponsesCarrySecurityHeaders() throws Exception {
    mvc.perform(get(base() + "/dashboard").cookie(cookie))
        .andExpect(
            header()
                .string("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().exists("X-Request-Id"));
  }
}
