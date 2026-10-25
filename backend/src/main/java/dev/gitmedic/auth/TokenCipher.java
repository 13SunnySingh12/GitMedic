package dev.gitmedic.auth;

import dev.gitmedic.config.AppProperties;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** SEC-02: AES-256-GCM, fresh 96-bit IV per encryption; stored as IV ‖ ciphertext ‖ tag. */
@Component
public class TokenCipher {

  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final SecretKey key;
  private final SecureRandom random = new SecureRandom();

  public TokenCipher(AppProperties props) {
    this.key = new SecretKeySpec(decodeKey(props.tokenEncryptionKey()), "AES");
  }

  static byte[] decodeKey(String base64) {
    byte[] raw;
    try {
      raw = Base64.getDecoder().decode(base64.trim());
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("TOKEN_ENCRYPTION_KEY must be base64");
    }
    if (raw.length != 32) {
      throw new IllegalStateException("TOKEN_ENCRYPTION_KEY must decode to exactly 32 bytes");
    }
    return raw;
  }

  public byte[] encrypt(String plaintext) {
    byte[] iv = new byte[IV_BYTES];
    random.nextBytes(iv);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      return ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Token encryption failed", e);
    }
  }

  public String decrypt(byte[] stored) {
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, stored, 0, IV_BYTES));
      return new String(
          cipher.doFinal(stored, IV_BYTES, stored.length - IV_BYTES), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Token decryption failed", e);
    }
  }
}
