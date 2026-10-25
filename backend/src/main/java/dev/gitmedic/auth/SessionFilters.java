package dev.gitmedic.auth;

import dev.gitmedic.common.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/** Filters registered in {@link SecurityConfig}; not components, so they run only in that chain. */
final class SessionFilters {

  private SessionFilters() {}

  /** Authenticates a request from a valid GM_SESSION cookie; principal = user id (Long). */
  static final class JwtCookieFilter extends OncePerRequestFilter {
    private final JwtService jwt;

    JwtCookieFilter(JwtService jwt) {
      this.jwt = jwt;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest req, HttpServletResponse res, FilterChain chain)
        throws ServletException, IOException {
      Cookie[] cookies = req.getCookies();
      if (cookies != null) {
        for (Cookie c : cookies) {
          if (JwtService.COOKIE.equals(c.getName())) {
            jwt.verify(c.getValue())
                .ifPresent(
                    id ->
                        SecurityContextHolder.getContext()
                            .setAuthentication(
                                new UsernamePasswordAuthenticationToken(id, null, List.of())));
          }
        }
      }
      chain.doFilter(req, res);
    }
  }

  /**
   * SEC-05: state-changing API calls must carry {@code X-Requested-With: GitMedic}, which a
   * cross-site form cannot set. The webhook endpoint is exempt (HMAC-verified instead).
   */
  static final class CsrfHeaderFilter extends OncePerRequestFilter {
    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private final ObjectMapper mapper;

    CsrfHeaderFilter(ObjectMapper mapper) {
      this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest req, HttpServletResponse res, FilterChain chain)
        throws ServletException, IOException {
      String path = req.getRequestURI();
      boolean guarded =
          path.startsWith("/api/")
              && !path.startsWith("/api/v1/webhooks/")
              && !SAFE.contains(req.getMethod());
      if (guarded && !"GitMedic".equals(req.getHeader("X-Requested-With"))) {
        Problems.write(
            res,
            mapper,
            HttpStatus.FORBIDDEN,
            "CSRF_HEADER_MISSING",
            "Missing X-Requested-With: GitMedic header.");
        return;
      }
      chain.doFilter(req, res);
    }
  }

  /**
   * Caps API request bodies at 64 KB (the largest real request is a few hundred bytes) so a client
   * cannot make the server buffer arbitrarily large JSON. Webhooks enforce their own 1 MB.
   */
  static final class BodySizeFilter extends OncePerRequestFilter {
    static final long MAX_BODY = 64 * 1024;
    private final ObjectMapper mapper;

    BodySizeFilter(ObjectMapper mapper) {
      this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest req, HttpServletResponse res, FilterChain chain)
        throws ServletException, IOException {
      String path = req.getRequestURI();
      boolean api = path.startsWith("/api/") && !path.startsWith("/api/v1/webhooks/");
      long length = req.getContentLengthLong();
      boolean chunked = req.getHeader("Transfer-Encoding") != null;
      if (api && (length > MAX_BODY || (length < 0 && chunked))) {
        Problems.write(
            res,
            mapper,
            HttpStatus.CONTENT_TOO_LARGE,
            "PAYLOAD_TOO_LARGE",
            "Request bodies must declare a Content-Length of at most 64 KB.");
        return;
      }
      chain.doFilter(req, res);
    }
  }
}
