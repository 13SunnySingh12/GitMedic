package dev.gitmedic.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import dev.gitmedic.config.AppProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/** SEC-03: HS256 session JWT ({@code sub} = user id) carried in the HttpOnly GM_SESSION cookie. */
@Component
public class JwtService {

  public static final String COOKIE = "GM_SESSION";

  private final NimbusJwtEncoder encoder;
  private final JwtDecoder decoder;
  private final Duration ttl;
  private final boolean secure;

  public JwtService(AppProperties props) {
    SecretKey key = new SecretKeySpec(decodeSecret(props.jwtSecret()), "HmacSHA256");
    this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
    NimbusJwtDecoder d =
        NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    d.setJwtValidator(new JwtTimestampValidator(Duration.ZERO));
    this.decoder = d;
    this.ttl = Duration.ofMinutes(props.jwtTtlMinutes());
    this.secure = props.secureCookies();
  }

  static byte[] decodeSecret(String base64) {
    byte[] raw;
    try {
      raw = Base64.getDecoder().decode(base64.trim());
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("JWT_SECRET must be base64");
    }
    if (raw.length < 32) {
      throw new IllegalStateException("JWT_SECRET must decode to at least 32 bytes (256 bits)");
    }
    return raw;
  }

  public String issue(long userId) {
    Instant now = Instant.now();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .subject(Long.toString(userId))
            .issuedAt(now)
            .expiresAt(now.plus(ttl))
            .build();
    JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
    return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
  }

  /** Returns the user id for a valid, unexpired token. */
  public Optional<Long> verify(String token) {
    try {
      return Optional.of(Long.parseLong(decoder.decode(token).getSubject()));
    } catch (JwtException | NumberFormatException e) {
      return Optional.empty();
    }
  }

  public ResponseCookie sessionCookie(String token) {
    return base(token).maxAge(ttl).build();
  }

  public ResponseCookie clearedCookie() {
    return base("").maxAge(0).build();
  }

  private ResponseCookie.ResponseCookieBuilder base(String value) {
    return ResponseCookie.from(COOKIE, value)
        .httpOnly(true)
        .secure(secure)
        .sameSite("Lax")
        .path("/");
  }
}
