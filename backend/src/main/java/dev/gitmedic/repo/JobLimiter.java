package dev.gitmedic.repo;

import dev.gitmedic.common.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * SEC-16: expensive endpoints (sync, train, index, report generate) allow one running job per user
 * and 10 starts per rolling hour. ponytail: in-memory, single instance; resets on restart.
 */
@Component
public class JobLimiter {

  static final int PER_HOUR = 10;

  private final Clock clock;
  private final Set<Long> running = new HashSet<>();
  private final Map<Long, Deque<Instant>> starts = new HashMap<>();

  public JobLimiter(Clock clock) {
    this.clock = clock;
  }

  /** Reserves the user's job slot; the caller must {@link #release} it when the job ends. */
  public synchronized void acquire(long userId) {
    if (running.contains(userId)) {
      throw new ApiException(
          HttpStatus.CONFLICT, "JOB_RUNNING", "Another long-running job is still in progress.");
    }
    Instant now = clock.instant();
    Deque<Instant> recent = starts.computeIfAbsent(userId, k -> new ArrayDeque<>());
    while (!recent.isEmpty() && recent.peekFirst().isBefore(now.minus(Duration.ofHours(1)))) {
      recent.pollFirst();
    }
    if (recent.size() >= PER_HOUR) {
      long retryAfter =
          Math.max(
              1, Duration.between(now, recent.peekFirst().plus(Duration.ofHours(1))).toSeconds());
      throw new ApiException(
          HttpStatus.TOO_MANY_REQUESTS,
          "TOO_MANY_JOBS",
          "At most " + PER_HOUR + " long-running jobs per hour.",
          Map.of("retryAfter", retryAfter));
    }
    recent.addLast(now);
    running.add(userId);
  }

  public synchronized void release(long userId) {
    running.remove(userId);
  }
}
