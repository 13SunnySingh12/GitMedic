package dev.gitmedic.common;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** JDBC parameter helpers: the Postgres driver binds OffsetDateTime, not Instant. */
public final class Db {

  private Db() {}

  public static OffsetDateTime ts(Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }
}
