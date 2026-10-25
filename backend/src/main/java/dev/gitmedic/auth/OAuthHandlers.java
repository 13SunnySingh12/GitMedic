package dev.gitmedic.auth;

import dev.gitmedic.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/** FR-001/002/003: upsert the user with an encrypted token, set GM_SESSION, land on Repo List. */
@Component
class OAuthHandlers implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

  private static final Logger log = LoggerFactory.getLogger(OAuthHandlers.class);

  private final OAuth2AuthorizedClientService clients;
  private final UserRepository users;
  private final TokenCipher cipher;
  private final JwtService jwt;
  private final AppProperties props;

  OAuthHandlers(
      OAuth2AuthorizedClientService clients,
      UserRepository users,
      TokenCipher cipher,
      JwtService jwt,
      AppProperties props) {
    this.clients = clients;
    this.users = users;
    this.cipher = cipher;
    this.jwt = jwt;
    this.props = props;
  }

  @Override
  public void onAuthenticationSuccess(
      HttpServletRequest req, HttpServletResponse res, Authentication auth) throws IOException {
    OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) auth;
    String registration = token.getAuthorizedClientRegistrationId();
    OAuth2AuthorizedClient client = clients.loadAuthorizedClient(registration, token.getName());
    Map<String, Object> a = token.getPrincipal().getAttributes();
    long userId =
        users.upsert(
            ((Number) a.get("id")).longValue(),
            (String) a.get("login"),
            (String) a.get("name"),
            (String) a.get("avatar_url"),
            cipher.encrypt(client.getAccessToken().getTokenValue()),
            String.join(",", client.getAccessToken().getScopes()));
    // The token now lives only encrypted in the DB: drop the framework's in-memory copy and
    // session.
    clients.removeAuthorizedClient(registration, token.getName());
    HttpSession session = req.getSession(false);
    if (session != null) {
      session.invalidate();
    }
    res.addHeader(HttpHeaders.SET_COOKIE, jwt.sessionCookie(jwt.issue(userId)).toString());
    res.sendRedirect(props.frontendOrigin() + "/repos");
  }

  @Override
  public void onAuthenticationFailure(
      HttpServletRequest req, HttpServletResponse res, AuthenticationException ex)
      throws IOException {
    String error = isNetworkFailure(ex) ? "github_unavailable" : "oauth_failed";
    log.warn("OAuth login failed ({}): {}", error, ex.getMessage());
    res.sendRedirect(props.frontendOrigin() + "/login?error=" + error);
  }

  private static boolean isNetworkFailure(Throwable t) {
    for (Throwable c = t; c != null; c = c.getCause()) {
      if (c instanceof IOException
          || c instanceof org.springframework.web.client.ResourceAccessException) {
        return true;
      }
    }
    return false;
  }
}
