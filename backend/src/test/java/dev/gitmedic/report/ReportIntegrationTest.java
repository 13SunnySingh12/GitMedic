package dev.gitmedic.report;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import dev.gitmedic.IntegrationTest;
import dev.gitmedic.auth.JwtService;
import dev.gitmedic.auth.TokenCipher;
import dev.gitmedic.support.Seed;
import dev.gitmedic.support.TestSupport;
import dev.gitmedic.sync.SyncStore;
import jakarta.servlet.http.Cookie;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class ReportIntegrationTest {

  @RegisterExtension
  static WireMockExtension ml =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  @DynamicPropertySource
  static void urls(DynamicPropertyRegistry r) {
    r.add("gitmedic.ml-base-url", ml::baseUrl);
  }

  // A fixture week with merged PRs, issues and commits: Monday 2026-05-11 → Sunday 2026-05-17
  // (UTC).
  static final Instant START = Instant.parse("2026-05-11T00:00:00Z");
  static final Instant END = Instant.parse("2026-05-17T23:59:59.999999Z");

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired TokenCipher cipher;
  @Autowired JwtService jwt;
  @Autowired SyncStore store;
  @Autowired ReportService reports;

  long repoId;
  Cookie cookie;

  @BeforeEach
  void setUp() {
    jdbc.sql("delete from gitmedic.repos").update();
    TestSupport.stubMl(ml);
    long userId = TestSupport.insertUser(jdbc, cipher, 95_001, "token-reports");
    cookie = TestSupport.session(jwt, userId);
    repoId = Seed.repo(jdbc, store, userId);
  }

  private static boolean in(String iso) {
    if (iso == null || iso.isEmpty()) {
      return false;
    }
    Instant t = Instant.parse(iso);
    return !t.isBefore(START) && !t.isAfter(END);
  }

  private static String text(JsonNode n, String field) {
    return n.path(field).isString() ? n.path(field).asString() : null;
  }

  @Test
  void qaRpt01_metricsMatchHandCountedFixtureWeek() {
    long newIssues = 0;
    long closed = 0;
    long open = 0;
    for (JsonNode i : TestSupport.fixture("issues.json")) {
      if (SyncStore.isPullRequest(i)) {
        continue;
      }
      if (in(text(i, "created_at"))) newIssues++;
      if (in(text(i, "closed_at"))) closed++;
      Instant created = Instant.parse(text(i, "created_at"));
      String c = text(i, "closed_at");
      if (!created.isAfter(END) && (c == null || Instant.parse(c).isAfter(END))) open++;
    }
    long merged = 0;
    double days = 0;
    for (JsonNode p : TestSupport.fixture("pulls.json")) {
      if (in(text(p, "merged_at"))) {
        merged++;
        days +=
            Duration.between(
                        Instant.parse(text(p, "created_at")), Instant.parse(text(p, "merged_at")))
                    .toSeconds()
                / 86400.0;
      }
    }
    long periodCommits = 0;
    for (JsonNode c : TestSupport.fixture("commits.json")) {
      if (in(text(c.path("commit").path("author"), "date"))) {
        periodCommits++;
      }
    }

    long id = reports.generate(repoId, new ReportService.Period(START, END));
    JsonNode m =
        TestSupport.JSON.readTree(
            jdbc.sql("select metrics::text from gitmedic.weekly_reports where id = ?")
                .param(id)
                .query(String.class)
                .single());

    assertThat(m.path("issues").path("new").asLong()).isEqualTo(newIssues);
    assertThat(m.path("issues").path("closed").asLong()).isEqualTo(closed);
    assertThat(m.path("issues").path("open").asLong()).isEqualTo(open);
    assertThat(m.path("issues").path("duplicatesCaught").asLong()).isZero();
    assertThat(m.path("pullRequests").path("merged").asLong()).isEqualTo(merged);
    assertThat(merged).isEqualTo(3); // guards the fixture week choice
    assertThat(m.path("pullRequests").path("avgMergeDaysActual").asDouble())
        .isCloseTo(days / merged, org.assertj.core.data.Offset.offset(0.01));
    assertThat(m.path("risk").path("score").asInt()).isEqualTo(42); // from the ML stub
    assertThat(m.path("risk").path("delta").isNull()).isTrue(); // first report
    assertThat(m.path("busFactor").path("value").asInt()).isPositive();
    long top5 = 0;
    for (JsonNode c : m.path("topContributors")) {
      top5 += c.path("commits").asLong();
    }
    assertThat(m.path("topContributors").size()).isLessThanOrEqualTo(5);
    assertThat(periodCommits).isEqualTo(6);
    assertThat(top5).isLessThanOrEqualTo(periodCommits).isPositive();
    assertThat(m.path("timezone").asString()).isEqualTo("UTC");
  }

  @Test
  void qaRpt02_onePerPeriodAndDeltaAgainstPreviousReport() {
    long a = reports.generate(repoId, new ReportService.Period(START, END));
    long b = reports.generate(repoId, new ReportService.Period(START, END));
    assertThat(a).isEqualTo(b);
    assertThat(
            jdbc.sql("select count(*) from gitmedic.weekly_reports where repo_id = ?")
                .param(repoId)
                .query(Long.class)
                .single())
        .isEqualTo(1);
    jdbc.sql(
            "update gitmedic.weekly_reports set metrics = jsonb_set(metrics, '{risk,score}', '30')"
                + " where id = ?")
        .param(a)
        .update();
    long next =
        reports.generate(
            repoId,
            new ReportService.Period(START.plus(Duration.ofDays(7)), END.plus(Duration.ofDays(7))));
    assertThat(
            jdbc.sql(
                    "select (metrics -> 'risk' ->> 'delta')::int from gitmedic.weekly_reports where id = ?")
                .param(next)
                .query(Integer.class)
                .single())
        .isEqualTo(12);
  }

  @Test
  void qaRpt03_catchUpCreatesMissingPreviousPeriodOnce() {
    reports.generateAllMissing();
    reports.generateAllMissing();
    ReportService.Period p = reports.period(true);
    assertThat(
            jdbc.sql(
                    "select count(*) from gitmedic.weekly_reports where repo_id = ? and period_start = ?")
                .params(repoId, p.start().atOffset(ZoneOffset.UTC))
                .query(Long.class)
                .single())
        .isEqualTo(1);
  }

  @Test
  void periodsRunMondayToSunday() {
    ReportService.Period prev = reports.period(true);
    ReportService.Period cur = reports.period(false);
    assertThat(prev.start().atZone(ZoneOffset.UTC).getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
    assertThat(prev.start().atZone(ZoneOffset.UTC).toLocalTime().toSecondOfDay()).isZero();
    assertThat(prev.end().atZone(ZoneOffset.UTC).getDayOfWeek()).isEqualTo(DayOfWeek.SUNDAY);
    assertThat(Duration.between(prev.start(), cur.start())).isEqualTo(Duration.ofDays(7));
    assertThat(cur.start()).isBeforeOrEqualTo(Instant.now());
  }

  @Test
  void qaRpt04_downloadsAndListing() throws Exception {
    long id = reports.generate(repoId, new ReportService.Period(START, END));
    mvc.perform(get("/api/v1/reports/" + id + "/download").param("format", "md").cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "text/markdown;charset=UTF-8"))
        .andExpect(
            header()
                .string(
                    "Content-Disposition",
                    "attachment; filename=\"gitmedic-octo-demo-2026-05-11.md\""));
    mvc.perform(get("/api/v1/reports/" + id + "/download").param("format", "html").cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "text/html;charset=UTF-8"))
        .andExpect(
            header()
                .string(
                    "Content-Disposition",
                    "attachment; filename=\"gitmedic-octo-demo-2026-05-11.html\""));
    mvc.perform(get("/api/v1/reports/" + id + "/download").param("format", "xml").cookie(cookie))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("UNSUPPORTED_FORMAT"));

    mvc.perform(get("/api/v1/repos/" + repoId + "/reports").cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalItems").value(1))
        .andExpect(jsonPath("$.items[0].riskScore").value(42));
    mvc.perform(get("/api/v1/reports/" + id).cookie(cookie))
        .andExpect(
            jsonPath("$.markdown")
                .value(org.hamcrest.Matchers.containsString("Weekly health report")));

    long other = TestSupport.insertUser(jdbc, cipher, 95_002, "token-other");
    mvc.perform(get("/api/v1/reports/" + id).cookie(TestSupport.session(jwt, other)))
        .andExpect(status().isNotFound());
  }

  @Test
  void generateNowCreatesCurrentPeriodReport() throws Exception {
    mvc.perform(
            post("/api/v1/repos/" + repoId + "/reports")
                .cookie(cookie)
                .header("X-Requested-With", "GitMedic"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.periodStart").value(reports.period(false).start().toString()));
    mvc.perform(
            post("/api/v1/repos/" + repoId + "/reports")
                .cookie(cookie)
                .header("X-Requested-With", "GitMedic")
                .contentType("application/json")
                .content("{\"period\":\"lastyear\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void qaSec09_untrustedValuesAreEscapedInHtml() throws Exception {
    JsonNode metrics =
        TestSupport.JSON.readTree(
            """
            {"periodStart":"2026-06-01T00:00:00Z","periodEnd":"2026-06-07T23:59:59Z","timezone":"UTC",
             "issues":{"new":1,"closed":0,"open":1,"duplicatesCaught":0},
             "pullRequests":{"merged":0,"avgMergeDaysActual":null,"avgMergeDaysPredicted":null},
             "risk":{"score":10,"level":"LOW","delta":null},"busFactor":{"value":1,"risky":true},
             "topContributors":[{"login":"<script>alert(1)</script>","commits":3}]}
            """);
    String html = ReportRenderer.html("octo/<img src=x onerror=alert(1)>", metrics);
    assertThat(html).doesNotContain("<script>").doesNotContain("<img");
    assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
    String md = ReportRenderer.markdown("octo/demo", metrics);
    assertThat(md).doesNotContain("<script>");
  }
}
