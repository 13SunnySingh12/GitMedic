package dev.gitmedic.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.gitmedic.auth.JwtService;
import dev.gitmedic.auth.TokenCipher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** QA-CFG-01: required configuration missing or weak → startup fails naming the variable. */
class ConfigFailFastTest {

  @Configuration
  @EnableConfigurationProperties(AppProperties.class)
  @Import({JwtService.class, TokenCipher.class})
  static class Props {}

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withInitializer(new ConfigDataApplicationContextInitializer())
          .withUserConfiguration(Props.class)
          .withPropertyValues(
              "spring.config.import=optional:file:does-not-exist.env[.properties]",
              "FRONTEND_ORIGIN=http://localhost:5173",
              "TOKEN_ENCRYPTION_KEY=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
              "ML_BASE_URL=http://127.0.0.1:8000",
              "INTERNAL_API_TOKEN=test-internal-token-000000000000000");

  @Test
  void missingJwtSecretNamesTheVariable() {
    runner.run(
        ctx -> {
          assertThat(ctx).hasFailed();
          assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("JWT_SECRET");
        });
  }

  @Test
  void weakJwtSecretIsRejected() {
    runner
        .withPropertyValues("JWT_SECRET=c2hvcnQ=")
        .run(
            ctx ->
                assertThat(ctx.getStartupFailure())
                    .rootCause()
                    .hasMessageContaining("JWT_SECRET must decode to at least 32 bytes"));
  }

  @Test
  void validConfigStarts() {
    runner
        .withPropertyValues("JWT_SECRET=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
        .run(ctx -> assertThat(ctx).hasNotFailed());
  }

  @Test
  void shortInternalTokenIsRejected() {
    assertThatThrownBy(
            () ->
                new AppProperties(
                    "http://x", "s", 480, "k", "", "http://ml", "short", "UTC", "c", "a", "w"))
        .hasMessageContaining("INTERNAL_API_TOKEN");
  }
}
