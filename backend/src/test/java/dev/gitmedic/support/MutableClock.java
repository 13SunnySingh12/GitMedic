package dev.gitmedic.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** System time plus an adjustable offset, so tests can jump past rate-limit resets. */
public class MutableClock extends Clock {

  private volatile Duration offset = Duration.ZERO;

  public void advance(Duration d) {
    offset = offset.plus(d);
  }

  public void reset() {
    offset = Duration.ZERO;
  }

  @Override
  public Instant instant() {
    return Instant.now().plus(offset);
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return this;
  }
}
