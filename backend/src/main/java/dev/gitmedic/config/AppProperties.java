package dev.gitmedic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("gitmedic")
public record AppProperties(
    String frontendOrigin,
    String jwtSecret,
    int jwtTtlMinutes,
    String tokenEncryptionKey,
    String webhookSecret,
    String mlBaseUrl,
    String internalApiToken,
    String timezone,
    String reportCron,
    String githubApiUrl,
    String githubWebUrl) {

  public AppProperties {
    if (internalApiToken == null || internalApiToken.length() < 32) {
      throw new IllegalStateException("INTERNAL_API_TOKEN must be at least 32 characters");
    }
    if (jwtTtlMinutes <= 0) {
      throw new IllegalStateException("JWT_TTL_MINUTES must be positive");
    }
  }

  public boolean secureCookies() {
    return frontendOrigin.startsWith("https://");
  }
}
