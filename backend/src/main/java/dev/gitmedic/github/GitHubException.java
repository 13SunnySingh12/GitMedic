package dev.gitmedic.github;

import java.time.Instant;

/** A GitHub failure GitMedic reacts to differently (§2.7, §7.4). */
public class GitHubException extends RuntimeException {

  public enum Kind {
    NOT_FOUND,
    TOKEN_INVALID,
    RATE_LIMITED,
    UNAVAILABLE
  }

  private final Kind kind;
  private final Instant resetAt;

  public GitHubException(Kind kind, String message) {
    this(kind, message, null);
  }

  public GitHubException(Kind kind, String message, Instant resetAt) {
    super(message);
    this.kind = kind;
    this.resetAt = resetAt;
  }

  public Kind kind() {
    return kind;
  }

  /** When a RATE_LIMITED request may be retried. */
  public Instant resetAt() {
    return resetAt;
  }
}
