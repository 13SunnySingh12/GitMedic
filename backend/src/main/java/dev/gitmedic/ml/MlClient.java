package dev.gitmedic.ml;

import dev.gitmedic.config.AppProperties;
import dev.gitmedic.config.RequestIdFilter;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Spring → FastAPI (§7.3/§7.4): X-Internal-Token, forwarded X-Request-Id, 5 s timeout (120 s for
 * train/index), one retry on connect errors, and a 30 s circuit-break after the service fails.
 */
@Component
public class MlClient {

  private static final Logger log = LoggerFactory.getLogger(MlClient.class);
  private static final Duration OPEN_FOR = Duration.ofSeconds(30);

  /** FastAPI is down, timed out or answered 5xx: callers degrade (NFR-008). */
  public static class MlUnavailableException extends RuntimeException {
    MlUnavailableException(String message) {
      super(message);
    }
  }

  /** FastAPI answered 4xx with a stable code (e.g. INSUFFICIENT_TRAINING_DATA). */
  public static class MlRejectedException extends RuntimeException {
    private final int status;
    private final String code;

    MlRejectedException(int status, String code, String message) {
      super(message);
      this.status = status;
      this.code = code;
    }

    public int status() {
      return status;
    }

    public String code() {
      return code;
    }
  }

  private final RestClient quick;
  private final RestClient slow;
  private final ObjectMapper mapper;
  private final Clock clock;
  private volatile Instant unavailableUntil = Instant.MIN;

  public MlClient(
      RestClient.Builder builder, AppProperties props, ObjectMapper mapper, Clock clock) {
    this.quick = build(builder, props, Duration.ofSeconds(5));
    this.slow = build(builder, props, Duration.ofSeconds(120));
    this.mapper = mapper;
    this.clock = clock;
  }

  private static RestClient build(RestClient.Builder builder, AppProperties props, Duration read) {
    // Loopback service: plain HTTP/1.1 (the JDK client's h2c upgrade attempt is wasted here).
    HttpClient http =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(read);
    return builder
        .clone()
        .baseUrl(props.mlBaseUrl())
        .requestFactory(factory)
        .defaultHeader("X-Internal-Token", props.internalApiToken())
        .build();
  }

  public boolean available() {
    return clock.instant().isAfter(unavailableUntil);
  }

  public JsonNode post(String path, Object body) {
    return call(quick, "POST", path, body);
  }

  public JsonNode postSlow(String path, Object body) {
    return call(slow, "POST", path, body);
  }

  public JsonNode get(String path) {
    return call(quick, "GET", path, null);
  }

  public void delete(String path) {
    call(quick, "DELETE", path, null);
  }

  private JsonNode call(RestClient client, String method, String path, Object body) {
    if (!available()) {
      throw new MlUnavailableException("ML service marked unavailable");
    }
    for (int attempt = 0; ; attempt++) {
      try {
        JsonNode result = exchange(client, method, path, body);
        unavailableUntil = Instant.MIN;
        return result;
      } catch (ResourceAccessException e) {
        // One retry, and only for connection failures; a timed-out request is not repeated.
        boolean connectFailure = e.getCause() instanceof java.net.ConnectException;
        if (attempt == 0 && connectFailure) {
          continue;
        }
        return fail("ML service unreachable: " + e.getClass().getSimpleName());
      }
    }
  }

  private JsonNode exchange(RestClient client, String method, String path, Object body) {
    RestClient.RequestBodySpec spec =
        client
            .method(org.springframework.http.HttpMethod.valueOf(method))
            .uri(path)
            .header(RequestIdFilter.HEADER, String.valueOf(MDC.get(RequestIdFilter.MDC_KEY)));
    if (body != null) {
      spec.contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(body);
    }
    return spec.exchange(
        (req, res) -> {
          int status = res.getStatusCode().value();
          byte[] bytes = res.getBody().readAllBytes();
          JsonNode json = bytes.length == 0 ? mapper.createObjectNode() : mapper.readTree(bytes);
          if (status >= 500) {
            return fail("ML service answered " + status);
          }
          if (status >= 400) {
            throw new MlRejectedException(
                status, json.path("code").asString("ML_ERROR"), json.path("message").asString(""));
          }
          return json;
        });
  }

  private JsonNode fail(String reason) {
    unavailableUntil = clock.instant().plus(OPEN_FOR);
    log.warn("{}; degrading for {}s", reason, OPEN_FOR.toSeconds());
    throw new MlUnavailableException(reason);
  }
}
