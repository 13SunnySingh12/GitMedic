package dev.gitmedic.config;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.sql.SQLException;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** §2.7: a paused Supabase project surfaces as a clear startup message, not a stack-trace wall. */
class DatabaseUnreachableFailureAnalyzer extends AbstractFailureAnalyzer<SQLException> {

  @Override
  protected FailureAnalysis analyze(Throwable root, SQLException cause) {
    boolean connectivity =
        "08001".equals(cause.getSQLState())
            || "08006".equals(cause.getSQLState())
            || hasCause(cause, ConnectException.class)
            || hasCause(cause, UnknownHostException.class);
    if (!connectivity) {
      return null;
    }
    return new FailureAnalysis(
        "Database unreachable — resume project in Supabase dashboard (" + cause.getMessage() + ")",
        "Resume the Supabase project (it pauses after 7 idle days), check DB_URL uses the session"
            + " pooler host, or start the local fallback with `docker compose up -d db`.",
        cause);
  }

  private static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
    for (Throwable c = t; c != null; c = c.getCause()) {
      if (type.isInstance(c)) {
        return true;
      }
    }
    return false;
  }
}
