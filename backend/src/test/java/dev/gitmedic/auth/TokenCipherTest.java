package dev.gitmedic.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.gitmedic.config.AppProperties;
import org.junit.jupiter.api.Test;

class TokenCipherTest {

  private final TokenCipher cipher =
      new TokenCipher(
          new AppProperties(
              "http://localhost:5173",
              "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
              480,
              "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
              "",
              "http://127.0.0.1:8000",
              "test-internal-token-000000000000000",
              "UTC",
              "0 0 6 * * MON",
              "https://api.github.com",
              "https://github.com"));

  @Test
  void roundTripsWithFreshIvEachTime() {
    byte[] a = cipher.encrypt("gho_secret");
    byte[] b = cipher.encrypt("gho_secret");
    assertThat(a).isNotEqualTo(b);
    assertThat(a).hasSize(12 + "gho_secret".length() + 16); // IV ‖ ciphertext ‖ 128-bit tag
    assertThat(cipher.decrypt(a)).isEqualTo("gho_secret");
  }

  @Test
  void tamperedCiphertextFailsAuthentication() {
    byte[] a = cipher.encrypt("gho_secret");
    a[14] ^= 1;
    assertThatThrownBy(() -> cipher.decrypt(a)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void rejectsWrongKeyLength() {
    assertThatThrownBy(() -> TokenCipher.decodeKey("c2hvcnQ="))
        .hasMessageContaining("TOKEN_ENCRYPTION_KEY");
  }
}
