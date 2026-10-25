package dev.gitmedic.auth;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import dev.gitmedic.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class AuthIntegrationTest {

  @RegisterExtension
  static WireMockExtension github =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  @DynamicPropertySource
  static void githubUrls(DynamicPropertyRegistry r) {
    r.add("gitmedic.github-web-url", github::baseUrl);
    r.add("gitmedic.github-api-url", github::baseUrl);
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired TokenCipher cipher;
  @Autowired JwtService jwt;

  /** Starts login and returns the session plus the framework-generated state. */
  private String[] startLogin(MockHttpSession session) throws Exception {
    MvcResult start =
        mvc.perform(get("/oauth2/authorization/github").session(session))
            .andExpect(status().is3xxRedirection())
            .andReturn();
    String location = start.getResponse().getRedirectedUrl();
    assertThat(location).startsWith(github.baseUrl() + "/login/oauth/authorize");
    return new String[] {
      java.net.URLDecoder.decode(
          UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("state"),
          StandardCharsets.UTF_8)
    };
  }

  @Test
  void qaAuth01_successUpsertsUserWithEncryptedTokenAndSetsCookie(CapturedOutput logs)
      throws Exception {
    github.stubFor(
        WireMock.post("/login/oauth/access_token")
            .willReturn(
                okJson(
                    "{\"access_token\":\"gho_plaintext_test\",\"token_type\":\"bearer\",\"scope\":\"read:user\"}")));
    github.stubFor(
        WireMock.get("/user")
            .willReturn(
                okJson(
                    "{\"id\":4242,\"login\":\"octo\",\"name\":\"Octo Cat\",\"avatar_url\":\"https://a/x.png\"}")));

    MockHttpSession session = new MockHttpSession();
    String state = startLogin(session)[0];
    MvcResult cb =
        mvc.perform(
                get("/login/oauth2/code/github")
                    .param("code", "abc")
                    .param("state", state)
                    .session(session))
            .andExpect(status().is3xxRedirection())
            .andReturn();

    assertThat(cb.getResponse().getRedirectedUrl()).isEqualTo("http://localhost:5173/repos");
    String setCookie = cb.getResponse().getHeader("Set-Cookie");
    assertThat(setCookie).contains("GM_SESSION=").contains("HttpOnly").contains("SameSite=Lax");

    byte[] stored =
        jdbc.sql("select access_token_enc from gitmedic.users where github_id = 4242")
            .query(byte[].class)
            .single();
    assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("gho_plaintext");
    assertThat(cipher.decrypt(stored)).isEqualTo("gho_plaintext_test");

    String token = setCookie.substring("GM_SESSION=".length(), setCookie.indexOf(';'));
    String me =
        mvc.perform(get("/api/v1/me").cookie(new Cookie("GM_SESSION", token)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.login").value("octo"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(me).doesNotContain("gho_").doesNotContain("token");
    // SEC-19: the GitHub token and the session JWT never reach the logs.
    assertThat(logs.getAll()).doesNotContain("gho_plaintext_test").doesNotContain(token);
  }

  @Test
  void qaAuth02_deniedConsentRedirectsWithErrorAndNoCookie() throws Exception {
    MockHttpSession session = new MockHttpSession();
    String state = startLogin(session)[0];
    MvcResult cb =
        mvc.perform(
                get("/login/oauth2/code/github")
                    .param("error", "access_denied")
                    .param("state", state)
                    .session(session))
            .andReturn();
    assertThat(cb.getResponse().getRedirectedUrl())
        .isEqualTo("http://localhost:5173/login?error=oauth_failed");
    assertThat(cb.getResponse().getHeader("Set-Cookie")).isNull();
  }

  @Test
  void qaSec01_badStateIsRejected() throws Exception {
    MockHttpSession session = new MockHttpSession();
    startLogin(session);
    MvcResult cb =
        mvc.perform(
                get("/login/oauth2/code/github")
                    .param("code", "abc")
                    .param("state", "forged")
                    .session(session))
            .andReturn();
    assertThat(cb.getResponse().getRedirectedUrl())
        .isEqualTo("http://localhost:5173/login?error=oauth_failed");
    github.verify(0, WireMock.postRequestedFor(WireMock.urlEqualTo("/login/oauth/access_token")));
  }

  @Test
  void githubDownDuringTokenExchangeReportsUnavailable() throws Exception {
    github.stubFor(
        WireMock.post("/login/oauth/access_token")
            .willReturn(
                aResponse()
                    .withFault(
                        com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));
    MockHttpSession session = new MockHttpSession();
    String state = startLogin(session)[0];
    MvcResult cb =
        mvc.perform(
                get("/login/oauth2/code/github")
                    .param("code", "abc")
                    .param("state", state)
                    .session(session))
            .andReturn();
    assertThat(cb.getResponse().getRedirectedUrl())
        .isEqualTo("http://localhost:5173/login?error=github_unavailable");
  }

  @Test
  void qaAuth03_expiredJwtIsRejected() throws Exception {
    long userId = insertUser(777);
    byte[] key = Base64.getDecoder().decode("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    NimbusJwtEncoder enc =
        new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(key, "HmacSHA256")));
    Instant past = Instant.now().minusSeconds(3600);
    String expired =
        enc.encode(
                JwtEncoderParameters.from(
                    JwsHeader.with(MacAlgorithm.HS256).build(),
                    JwtClaimsSet.builder()
                        .subject(Long.toString(userId))
                        .issuedAt(past.minusSeconds(60))
                        .expiresAt(past)
                        .build()))
            .getTokenValue();
    mvc.perform(get("/api/v1/me").cookie(new Cookie("GM_SESSION", expired)))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/me").cookie(new Cookie("GM_SESSION", jwt.issue(userId))))
        .andExpect(status().isOk());
  }

  @Test
  void qaSec03_tamperedJwtIsRejected() throws Exception {
    String valid = jwt.issue(insertUser(778));
    String tampered = valid.substring(0, valid.length() - 2) + "xx";
    mvc.perform(get("/api/v1/me").cookie(new Cookie("GM_SESSION", tampered)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void logoutClearsCookieAndRequiresCsrfHeader() throws Exception {
    mvc.perform(post("/api/v1/auth/logout"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CSRF_HEADER_MISSING"));
    MvcResult res =
        mvc.perform(post("/api/v1/auth/logout").header("X-Requested-With", "GitMedic"))
            .andExpect(status().isNoContent())
            .andReturn();
    assertThat(res.getResponse().getHeader("Set-Cookie"))
        .contains("GM_SESSION=")
        .contains("Max-Age=0");
  }

  private long insertUser(long githubId) {
    return jdbc.sql(
            "insert into gitmedic.users (github_id, login, access_token_enc) values (?, ?, ?)"
                + " on conflict (github_id) do update set login = excluded.login returning id")
        .params(githubId, "u" + githubId, cipher.encrypt("t"))
        .query(Long.class)
        .single();
  }
}
