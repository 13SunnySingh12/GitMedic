package dev.gitmedic.auth;

import dev.gitmedic.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Decrypts a user's GitHub token only at the moment it is used; never cached or returned. */
@Component
public class UserTokens {

  private final UserRepository users;
  private final TokenCipher cipher;

  UserTokens(UserRepository users, TokenCipher cipher) {
    this.users = users;
    this.cipher = cipher;
  }

  public String githubToken(long userId) {
    return users
        .findEncryptedToken(userId)
        .map(cipher::decrypt)
        .orElseThrow(
            () ->
                new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in required."));
  }
}
