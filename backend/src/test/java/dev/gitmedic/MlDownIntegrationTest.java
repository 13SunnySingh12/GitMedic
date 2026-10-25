package dev.gitmedic;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.gitmedic.auth.JwtService;
import dev.gitmedic.auth.TokenCipher;
import dev.gitmedic.support.Seed;
import dev.gitmedic.support.TestSupport;
import dev.gitmedic.sync.SyncStore;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * QA-RES-01 / NFR-008: with FastAPI unreachable (the test profile points it at a closed port) core
 * pages still answer 200 and AI parts report UNAVAILABLE. Also §8.3: no webhook secret → 503.
 */
@IntegrationTest
@TestPropertySource(properties = "gitmedic.webhook-secret=")
class MlDownIntegrationTest {

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired TokenCipher cipher;
  @Autowired JwtService jwt;
  @Autowired SyncStore store;

  long repoId;
  Cookie cookie;

  @BeforeEach
  void setUp() {
    jdbc.sql("delete from gitmedic.repos where github_repo_id = 123456").update();
    long userId = TestSupport.insertUser(jdbc, cipher, 90_001, "token-ml-down");
    cookie = TestSupport.session(jwt, userId);
    repoId = Seed.repo(jdbc, store, userId);
  }

  @Test
  void qaRes01_corePagesDegradeGracefully() throws Exception {
    String base = "/api/v1/repos/" + repoId;
    mvc.perform(get(base + "/dashboard").cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mlStatus").value("UNAVAILABLE"))
        .andExpect(jsonPath("$.models").isEmpty())
        .andExpect(jsonPath("$.stats.openPrs").value(5));
    mvc.perform(get(base + "/issues/42").cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.issue.number").value(42))
        .andExpect(jsonPath("$.duplicates").isEmpty())
        .andExpect(jsonPath("$.mlStatus").value("UNAVAILABLE"));
    mvc.perform(get(base + "/issues").cookie(cookie)).andExpect(status().isOk());
    mvc.perform(get(base + "/bus-factor").cookie(cookie)).andExpect(status().isOk());
    mvc.perform(
            post(base + "/models/label/train")
                .cookie(cookie)
                .header("X-Requested-With", "GitMedic"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("ML_UNAVAILABLE"));
  }

  @Test
  void webhooksAreRefusedWithoutASecret() throws Exception {
    mvc.perform(
            post("/api/v1/webhooks/github")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-GitHub-Event", "issues")
                .header("X-Hub-Signature-256", "sha256=00")
                .content("{}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("WEBHOOKS_DISABLED"));
  }
}
