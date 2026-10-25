package dev.gitmedic.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** SEC-06: X-Hub-Signature-256 = "sha256=" + hex(HMAC-SHA256(secret, raw body)). */
final class SignatureVerifier {

  private SignatureVerifier() {}

  static boolean valid(String secret, byte[] body, String header) {
    if (secret == null || secret.isEmpty() || header == null || !header.startsWith("sha256=")) {
      return false;
    }
    byte[] given;
    try {
      given = HexFormat.of().parseHex(header.substring("sha256=".length()));
    } catch (IllegalArgumentException e) {
      return false;
    }
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return MessageDigest.isEqual(mac.doFinal(body), given); // constant-time compare
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
