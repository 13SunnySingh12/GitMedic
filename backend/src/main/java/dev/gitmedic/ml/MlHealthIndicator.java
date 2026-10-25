package dev.gitmedic.ml;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * §5.10: `/actuator/health` reports the ML service as component "ml". When it is unreachable the
 * component is DEGRADED, which the configured status order keeps from making the app DOWN.
 */
@Component("ml")
class MlHealthIndicator implements HealthIndicator {

  static final Status DEGRADED = new Status("DEGRADED");

  private final MlClient ml;

  MlHealthIndicator(MlClient ml) {
    this.ml = ml;
  }

  @Override
  public Health health() {
    try {
      JsonNode h = ml.get("/health");
      return "ok".equals(h.path("status").asString())
          ? Health.up().build()
          : Health.status(DEGRADED).build();
    } catch (RuntimeException e) {
      return Health.status(DEGRADED).build();
    }
  }
}
