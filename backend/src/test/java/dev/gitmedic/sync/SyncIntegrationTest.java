package dev.gitmedic.sync;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static dev.gitmedic.support.TestSupport.REPO;
import static dev.gitmedic.support.TestSupport.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import dev.gitmedic.IntegrationTest;
import dev.gitmedic.auth.JwtService;
import dev.gitmedic.auth.TokenCipher;
import dev.gitmedic.support.MutableClock;
import dev.gitmedic.support.TestSupport;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
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

@IntegrationTest
class SyncIntegrationTest {

  @RegisterExtension
  static WireMockExtension github =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  @RegisterExtension
  static WireMockExtension ml =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  @DynamicPropertySource
  static void urls(DynamicPropertyRegistry r) {
    r.add("gitmedic.github-api-url", github::baseUrl);
    r.add("gitmedic.ml-base-url", ml::baseUrl);
    r.add("gitmedic.github-retry-base-ms", () -> "1");
  }

  private static final AtomicLong IDS = new AtomicLong(50_000);

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired TokenCipher cipher;
  @Autowired JwtService jwt;
  @Autowired MutableClock clock;

  long userId;
  Cookie cookie;

  @BeforeEach
  void setUp() {
    TestSupport.stubGitHub(github);
    TestSupport.stubMl(ml);
    // A fresh user (and token) per test isolates the client's per-token rate-limit state.
    long gh = IDS.incrementAndGet();
    userId = TestSupport.insertUser(jdbc, cipher, gh, "token-" + gh);
    cookie = TestSupport.session(jwt, userId);
  }

  @AfterEach
  void tearDown() {
    clock.reset();
  }

  private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder b) {
    return b.cookie(cookie).header("X-Requested-With", "GitMedic");
  }

  private long track() throws Exception {
    String body =
        mvc.perform(
                write(post("/api/v1/repos"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"githubRepoId\":123456,\"fullName\":\"octo/demo\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.syncStatus").value("PENDING"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return TestSupport.JSON.readTree(body).path("id").asLong();
  }

  private String syncStatus(long repoId) {
    return jdbc.sql("select sync_status from gitmedic.repos where id = ?")
        .param(repoId)
        .query(String.class)
        .single();
  }

  private void awaitStatus(long repoId, String expected) {
    await(() -> expected.equals(syncStatus(repoId)), "sync status " + expected);
  }

  private long count(String table, long repoId) {
    return jdbc.sql("select count(*) from gitmedic." + table + " where repo_id = ?")
        .param(repoId)
        .query(Long.class)
        .single();
  }

  @Test
  void qaSync01and02_initialSyncStoresFixtureCountsAndExcludesPrs() throws Exception {
    long repoId = track();
    awaitStatus(repoId, "DONE");

    assertThat(count("issues", repoId)).isEqualTo(120);
    assertThat(count("pull_requests", repoId)).isEqualTo(40);
    assertThat(count("commits", repoId)).isEqualTo(300);
    // BR-02: issue numbers 1–15 are PRs that appear in the issues feed.
    assertThat(
            jdbc.sql("select count(*) from gitmedic.issues where repo_id = ? and number <= 40")
                .param(repoId)
                .query(Long.class)
                .single())
        .isZero();
    // PR details and review counts come from the per-PR endpoints.
    var pr7 = TestSupport.fixture("pull_details.json").get("7");
    var row =
        jdbc.sql(
                "select additions, review_count from gitmedic.pull_requests where repo_id = ? and number = 7")
            .param(repoId)
            .query((rs, n) -> new int[] {rs.getInt(1), rs.getInt(2)})
            .single();
    assertThat(row[0]).isEqualTo(pr7.path("additions").asInt());
    assertThat(row[1]).isEqualTo(TestSupport.fixture("pull_reviews.json").get("7").size());
    // §6.5: emails are stored only as SHA-256 hashes.
    assertThat(
            jdbc.sql("select count(*) from gitmedic.commits where author_email_hash like '%@%'")
                .query(Long.class)
                .single())
        .isZero();
    assertThat(count("repo_health", repoId)).isEqualTo(1);
    ml.verify(WireMock.postRequestedFor(urlPathEqualTo("/internal/v1/index")));

    mvc.perform(get("/api/v1/repos").cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].fullName").value("octo/demo"))
        .andExpect(jsonPath("$[0].syncStatus").value("DONE"))
        .andExpect(jsonPath("$[0].lastSyncedAt").isNotEmpty())
        .andExpect(jsonPath("$[0].risk.score").value(42))
        .andExpect(jsonPath("$[0].risk.level").value("MEDIUM"));
  }

  @Test
  void qaSync04_resyncIsIdempotent() throws Exception {
    long repoId = track();
    awaitStatus(repoId, "DONE");
    Instant firstSync =
        jdbc.sql("select last_synced_at from gitmedic.repos where id = ?")
            .param(repoId)
            .query(java.sql.Timestamp.class)
            .single()
            .toInstant();
    github.resetRequests();
    mvc.perform(write(post("/api/v1/repos/" + repoId + "/sync"))).andExpect(status().isAccepted());
    awaitStatus(repoId, "DONE");
    assertThat(count("issues", repoId)).isEqualTo(120);
    assertThat(count("pull_requests", repoId)).isEqualTo(40);
    assertThat(count("commits", repoId)).isEqualTo(300);
    // F-09: the incremental commit read starts 7 days before the previous sync.
    var commitRequests =
        github.findAll(WireMock.getRequestedFor(urlPathEqualTo(REPO + "/commits")));
    assertThat(commitRequests).isNotEmpty();
    Instant since = Instant.parse(commitRequests.getFirst().queryParameter("since").firstValue());
    assertThat(since).isEqualTo(firstSync.minus(Duration.ofDays(7)));
  }

  @Test
  void qaSync05_concurrentSyncIsRejected() throws Exception {
    long repoId = track();
    awaitStatus(repoId, "DONE");
    jdbc.sql("update gitmedic.repos set sync_status = 'RUNNING' where id = ?")
        .param(repoId)
        .update();
    mvc.perform(write(post("/api/v1/repos/" + repoId + "/sync")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SYNC_ALREADY_RUNNING"));
  }

  @Test
  void qaSync03_rateLimitPausesAndResumesAfterReset() throws Exception {
    Instant reset = Instant.now().plus(Duration.ofMinutes(30));
    github.stubFor(
        WireMock.get(urlPathEqualTo(REPO))
            .willReturn(TestSupport.okJson(TestSupport.fixture("repo.json"), 50, reset)));
    long repoId = track();
    awaitStatus(repoId, "RATE_LIMITED");
    assertThat(
            jdbc.sql("select sync_error from gitmedic.repos where id = ?")
                .param(repoId)
                .query(String.class)
                .single())
        .contains(Instant.ofEpochSecond(reset.getEpochSecond()).toString());
    assertThat(count("issues", repoId)).isZero(); // paused before spending more quota

    clock.advance(Duration.ofMinutes(31));
    TestSupport.stubGitHub(github); // quota restored
    mvc.perform(write(post("/api/v1/repos/" + repoId + "/sync"))).andExpect(status().isAccepted());
    awaitStatus(repoId, "DONE");
    assertThat(count("issues", repoId)).isEqualTo(120);
  }

  @Test
  void revokedTokenFailsSyncWithReason() throws Exception {
    github.stubFor(WireMock.get(urlPathEqualTo(REPO)).willReturn(aResponse().withStatus(401)));
    long repoId = track();
    awaitStatus(repoId, "FAILED");
    assertThat(
            jdbc.sql("select sync_error from gitmedic.repos where id = ?")
                .param(repoId)
                .query(String.class)
                .single())
        .startsWith("GITHUB_TOKEN_INVALID");
  }

  @Test
  void trackValidationAndConflicts() throws Exception {
    track();
    mvc.perform(
            write(post("/api/v1/repos"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"githubRepoId\":123456,\"fullName\":\"octo/demo\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("REPO_ALREADY_TRACKED"));
    mvc.perform(
            write(post("/api/v1/repos"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"githubRepoId\":999,\"fullName\":\"octo/not-mine\"}"))
        .andExpect(status().isNotFound());
    mvc.perform(
            write(post("/api/v1/repos"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"githubRepoId\":1,\"fullName\":\"../../etc\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.errors[0].field").value("fullName"));
  }

  @Test
  void githubRepoListMarksTrackedAndFilters() throws Exception {
    track();
    github.resetRequests();
    mvc.perform(get("/api/v1/github/repos").cookie(cookie)).andExpect(status().isOk());
    mvc.perform(get("/api/v1/github/repos").param("q", "DEMO").cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalItems").value(1))
        .andExpect(jsonPath("$.items[0].fullName").value("octo/demo"))
        .andExpect(jsonPath("$.items[0].tracked").value(true));
    mvc.perform(get("/api/v1/github/repos").param("page", "-1").cookie(cookie))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/github/repos").param("size", "1000").cookie(cookie))
        .andExpect(jsonPath("$.size").value(100));
    // F-10: four picker requests within 60 s read the GitHub list once.
    github.verify(1, WireMock.getRequestedFor(urlPathEqualTo("/user/repos")));
  }

  @Test
  void qaSec04_otherUsersRepoIsNotFound() throws Exception {
    long repoId = track();
    awaitStatus(repoId, "DONE");
    long other = TestSupport.insertUser(jdbc, cipher, IDS.incrementAndGet(), "other-token");
    Cookie otherCookie = TestSupport.session(jwt, other);
    mvc.perform(
            post("/api/v1/repos/" + repoId + "/sync")
                .cookie(otherCookie)
                .header("X-Requested-With", "GitMedic"))
        .andExpect(status().isNotFound());
    mvc.perform(
            delete("/api/v1/repos/" + repoId)
                .cookie(otherCookie)
                .header("X-Requested-With", "GitMedic"))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/repos").cookie(otherCookie)).andExpect(jsonPath("$").isEmpty());
  }

  @Test
  void untrackCascadesAndDeletesModels() throws Exception {
    long repoId = track();
    awaitStatus(repoId, "DONE");
    mvc.perform(write(delete("/api/v1/repos/" + repoId))).andExpect(status().isNoContent());
    for (String table : new String[] {"issues", "pull_requests", "commits", "repo_health"}) {
      assertThat(count(table, repoId)).as(table).isZero();
    }
    ml.verify(deleteRequestedFor(urlPathEqualTo("/internal/v1/repos/" + repoId)));
  }
}
