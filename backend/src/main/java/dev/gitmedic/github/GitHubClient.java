package dev.gitmedic.github;

import dev.gitmedic.config.AppProperties;
import dev.gitmedic.github.GitHubException.Kind;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * GitHub REST client (§5.9): per_page=100 + Link pagination, ETag revalidation for single
 * resources, rate-limit guard (pause below 100 remaining), 3 retries with backoff on 5xx/network.
 */
@Component
public class GitHubClient {

  private static final Logger log = LoggerFactory.getLogger(GitHubClient.class);
  private static final Pattern NEXT = Pattern.compile("<([^>]+)>;\\s*rel=\"next\"");
  static final int PAUSE_BELOW = 100;
  private static final int MAX_RETRIES = 3;
  private static final int ETAG_ENTRIES = 1000;

  private final RestClient rest;
  private final ObjectMapper mapper;
  private final String baseUrl;
  private final Clock clock;
  private final long retryBaseMillis;
  private final Map<String, RateState> rateByToken = new ConcurrentHashMap<>();
  // ponytail: in-memory LRU for single-resource ETags; lost on restart, which only costs requests.
  private final Map<String, Cached> etags =
      new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cached> e) {
          return size() > ETAG_ENTRIES;
        }
      };

  private record RateState(int remaining, Instant reset) {}

  private record Cached(String etag, JsonNode body) {}

  /** One fetched page: its JSON plus the absolute URL of the next page, if any. */
  public record Page(JsonNode body, URI next) {}

  public GitHubClient(
      RestClient.Builder builder,
      ObjectMapper mapper,
      AppProperties props,
      Clock clock,
      @Value("${gitmedic.github-retry-base-ms:1000}") long retryBaseMillis) {
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(Duration.ofSeconds(30));
    this.rest =
        builder
            .requestFactory(factory)
            .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
            .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
            .build();
    this.mapper = mapper;
    this.baseUrl = props.githubApiUrl();
    this.clock = clock;
    this.retryBaseMillis = retryBaseMillis;
  }

  /** Builds an API URI; path segments are encoded, so a repo full name is passed as owner/name. */
  public UriComponentsBuilder uri(String path) {
    return UriComponentsBuilder.fromUriString(baseUrl).path(path);
  }

  public URI repoUri(String fullName, String suffix, Map<String, ?> query) {
    String[] parts = fullName.split("/", 2);
    UriComponentsBuilder b = uri("/repos/{owner}/{repo}" + suffix);
    query.forEach(b::queryParam);
    return b.buildAndExpand(parts[0], parts[1]).encode().toUri();
  }

  /** GET a single resource, revalidated with If-None-Match when cached. */
  public JsonNode get(String token, URI uri) {
    String key = fingerprint(token) + " " + uri;
    Cached cached;
    synchronized (etags) {
      cached = etags.get(key);
    }
    Response r = send(token, uri, cached == null ? null : cached.etag());
    if (r.status() == 304 && cached != null) {
      return cached.body();
    }
    String etag = r.headers().getETag();
    if (etag != null && r.next() == null) {
      synchronized (etags) {
        etags.put(key, new Cached(etag, r.body()));
      }
    }
    return r.body();
  }

  public Page page(String token, URI uri) {
    Response r = send(token, uri, null);
    return new Page(r.body(), r.next());
  }

  /**
   * Follows Link: rel="next" until {@code max} items were collected or {@code stop} matches an item
   * (that item and the rest are dropped). per_page=100 is added to the first request.
   */
  public List<JsonNode> list(String token, URI first, int max, Predicate<JsonNode> stop) {
    List<JsonNode> out = new ArrayList<>();
    URI next =
        UriComponentsBuilder.fromUri(first).replaceQueryParam("per_page", 100).build(true).toUri();
    while (next != null && out.size() < max) {
      Page p = page(token, next);
      for (JsonNode item : p.body()) {
        if (stop.test(item)) {
          return out;
        }
        out.add(item);
        if (out.size() >= max) {
          return out;
        }
      }
      next = p.next();
    }
    return out;
  }

  private record Response(int status, HttpHeaders headers, JsonNode body, URI next) {}

  private Response send(String token, URI uri, String etag) {
    guardRateLimit(token);
    for (int attempt = 0; ; attempt++) {
      try {
        Response r = exchange(token, uri, etag);
        if (r.status() < 500) {
          return r;
        }
        if (attempt >= MAX_RETRIES) {
          throw new GitHubException(Kind.UNAVAILABLE, "GitHub returned " + r.status() + ".");
        }
      } catch (ResourceAccessException e) {
        if (attempt >= MAX_RETRIES) {
          throw new GitHubException(Kind.UNAVAILABLE, "GitHub is unreachable.");
        }
      }
      sleep(retryBaseMillis << attempt);
    }
  }

  private Response exchange(String token, URI uri, String etag) {
    return rest.get()
        .uri(uri)
        .headers(
            h -> {
              h.setBearerAuth(token);
              if (etag != null) {
                h.setIfNoneMatch(etag);
              }
            })
        .exchange(
            (req, res) -> {
              HttpHeaders headers = res.getHeaders();
              recordRateLimit(token, headers);
              HttpStatusCode status = res.getStatusCode();
              int code = status.value();
              if (code == 304 || code >= 500) {
                return new Response(code, headers, null, null);
              }
              if (code == 401) {
                throw new GitHubException(Kind.TOKEN_INVALID, "GitHub rejected the token.");
              }
              if (code == 404) {
                throw new GitHubException(Kind.NOT_FOUND, "GitHub resource not found.");
              }
              if (code == 403 || code == 429) {
                Instant reset = rateLimitReset(headers);
                if (reset != null) {
                  throw new GitHubException(Kind.RATE_LIMITED, "GitHub rate limit reached.", reset);
                }
                throw new GitHubException(Kind.NOT_FOUND, "GitHub denied access.");
              }
              if (code >= 400) {
                throw new GitHubException(Kind.UNAVAILABLE, "GitHub returned " + code + ".");
              }
              JsonNode body = readBody(res.getBody().readAllBytes());
              return new Response(code, headers, body, nextLink(headers));
            });
  }

  private JsonNode readBody(byte[] bytes) throws IOException {
    return bytes.length == 0 ? mapper.createObjectNode() : mapper.readTree(bytes);
  }

  /** §5.9: stop issuing requests while fewer than 100 remain, until GitHub's reset time. */
  private void guardRateLimit(String token) {
    RateState s = rateByToken.get(fingerprint(token));
    if (s != null && s.remaining() < PAUSE_BELOW && clock.instant().isBefore(s.reset())) {
      throw new GitHubException(
          Kind.RATE_LIMITED, "GitHub rate limit nearly exhausted.", s.reset());
    }
  }

  private void recordRateLimit(String token, HttpHeaders h) {
    String remaining = h.getFirst("X-RateLimit-Remaining");
    String reset = h.getFirst("X-RateLimit-Reset");
    if (remaining != null && reset != null) {
      try {
        rateByToken.put(
            fingerprint(token),
            new RateState(
                Integer.parseInt(remaining), Instant.ofEpochSecond(Long.parseLong(reset))));
      } catch (NumberFormatException e) {
        log.warn("Ignoring malformed GitHub rate-limit headers");
      }
    }
  }

  /** Primary limit (remaining 0 + reset) or secondary limit (Retry-After seconds). */
  private Instant rateLimitReset(HttpHeaders h) {
    String retryAfter = h.getFirst(HttpHeaders.RETRY_AFTER);
    if (retryAfter != null && retryAfter.matches("\\d+")) {
      return clock.instant().plusSeconds(Long.parseLong(retryAfter));
    }
    String reset = h.getFirst("X-RateLimit-Reset");
    if ("0".equals(h.getFirst("X-RateLimit-Remaining")) && reset != null && reset.matches("\\d+")) {
      return Instant.ofEpochSecond(Long.parseLong(reset));
    }
    return null;
  }

  /**
   * The next page from the Link header. Only same-origin links are followed: the request carries
   * the user's token, so a link to another host must never be requested (F-02).
   */
  private URI nextLink(HttpHeaders h) {
    String link = h.getFirst(HttpHeaders.LINK);
    if (link == null) {
      return null;
    }
    Matcher m = NEXT.matcher(link);
    if (!m.find()) {
      return null;
    }
    URI next = URI.create(m.group(1));
    URI base = URI.create(baseUrl);
    boolean sameOrigin =
        base.getScheme().equalsIgnoreCase(String.valueOf(next.getScheme()))
            && base.getHost().equalsIgnoreCase(String.valueOf(next.getHost()))
            && port(base) == port(next);
    if (!sameOrigin) {
      throw new GitHubException(
          Kind.UNAVAILABLE, "GitHub returned a pagination link to another host; not followed.");
    }
    return next;
  }

  private static int port(URI u) {
    return u.getPort() != -1 ? u.getPort() : "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
  }

  private static String fingerprint(String token) {
    try {
      byte[] d =
          MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(d, 0, 8);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new GitHubException(Kind.UNAVAILABLE, "Interrupted while retrying GitHub.");
    }
  }
}
