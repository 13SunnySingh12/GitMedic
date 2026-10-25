package dev.gitmedic.github;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import dev.gitmedic.config.AppProperties;
import java.net.URI;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class GitHubClientTest {

  @RegisterExtension
  static WireMockExtension github =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  @RegisterExtension
  static WireMockExtension elsewhere =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  private GitHubClient client() {
    AppProperties props =
        new AppProperties(
            "http://localhost:5173",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            480,
            "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
            "",
            "http://127.0.0.1:1",
            "test-internal-token-000000000000000",
            "UTC",
            "0 0 6 * * MON",
            github.baseUrl(),
            github.baseUrl());
    return new GitHubClient(
        RestClient.builder(), JsonMapper.builder().build(), props, Clock.systemUTC(), 1);
  }

  private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder page(
      String body, String link) {
    var r =
        aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
    return link == null ? r : r.withHeader("Link", "<" + link + ">; rel=\"next\"");
  }

  @Test
  void followsSameOriginNextLinks() {
    github.stubFor(
        get(urlPathEqualTo("/items"))
            .withQueryParam("page", com.github.tomakehurst.wiremock.client.WireMock.absent())
            .willReturn(page("[{\"n\":1}]", github.baseUrl() + "/items?per_page=100&page=2")));
    github.stubFor(
        get(urlPathEqualTo("/items"))
            .withQueryParam("page", com.github.tomakehurst.wiremock.client.WireMock.equalTo("2"))
            .willReturn(page("[{\"n\":2}]", null)));
    var items = client().list("tok", URI.create(github.baseUrl() + "/items"), 10, i -> false);
    assertThat(items).hasSize(2);
  }

  /** F-02: a Link header pointing at another origin must not receive the user's token. */
  @Test
  void refusesNextLinksToAnotherOrigin() {
    github.stubFor(
        get(urlPathEqualTo("/items"))
            .willReturn(page("[{\"n\":1}]", elsewhere.baseUrl() + "/steal?page=2")));
    elsewhere.stubFor(get(anyUrl()).willReturn(page("[]", null)));

    assertThatThrownBy(
            () ->
                client()
                    .list("secret-token", URI.create(github.baseUrl() + "/items"), 10, i -> false))
        .isInstanceOf(GitHubException.class)
        .hasMessageContaining("pagination link");
    elsewhere.verify(0, anyRequestedFor(anyUrl()));
  }
}
