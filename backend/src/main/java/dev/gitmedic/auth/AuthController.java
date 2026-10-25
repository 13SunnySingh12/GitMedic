package dev.gitmedic.auth;

import dev.gitmedic.common.ApiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class AuthController {

  private final UserRepository users;
  private final JwtService jwt;

  AuthController(UserRepository users, JwtService jwt) {
    this.users = users;
    this.jwt = jwt;
  }

  /** API-03. A valid JWT for a deleted user is treated as no session. */
  @GetMapping("/me")
  UserRepository.User me(@AuthenticationPrincipal Long userId) {
    return users
        .findById(userId)
        .orElseThrow(
            () ->
                new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in required."));
  }

  /** API-04. */
  @PostMapping("/auth/logout")
  ResponseEntity<Void> logout() {
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, jwt.clearedCookie().toString())
        .build();
  }
}
