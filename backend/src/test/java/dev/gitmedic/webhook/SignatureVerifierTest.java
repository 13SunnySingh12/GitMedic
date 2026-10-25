package dev.gitmedic.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SignatureVerifierTest {

  // GitHub's documented example: secret "It's a Secret to Everybody", payload "Hello, World!".
  private static final String SECRET = "It's a Secret to Everybody";
  private static final byte[] BODY = "Hello, World!".getBytes(StandardCharsets.UTF_8);
  private static final String SIG =
      "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17";

  @Test
  void acceptsGithubDocumentedExample() {
    assertThat(SignatureVerifier.valid(SECRET, BODY, SIG)).isTrue();
  }

  @Test
  void rejectsTamperingAndMalformedHeaders() {
    assertThat(SignatureVerifier.valid(SECRET, "Hello, World?".getBytes(), SIG)).isFalse();
    assertThat(SignatureVerifier.valid("other", BODY, SIG)).isFalse();
    assertThat(SignatureVerifier.valid(SECRET, BODY, SIG.replace("sha256=", "sha1="))).isFalse();
    assertThat(SignatureVerifier.valid(SECRET, BODY, "sha256=zz")).isFalse();
    assertThat(SignatureVerifier.valid(SECRET, BODY, null)).isFalse();
    assertThat(SignatureVerifier.valid("", BODY, SIG)).isFalse();
  }
}
