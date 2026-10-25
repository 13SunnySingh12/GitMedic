package dev.gitmedic.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** BR-06 hand-computed cases. */
class BusFactorTest {

  @Test
  void qaMl07_sixtyTwentyFiveFifteenIsOne() {
    assertThat(AnalyticsService.busFactor(List.of(60L, 25L, 15L))).isEqualTo(1);
  }

  @Test
  void evenSplitNeedsHalfTheContributors() {
    assertThat(AnalyticsService.busFactor(List.of(25L, 25L, 25L, 25L))).isEqualTo(2);
    assertThat(AnalyticsService.busFactor(List.of(10L, 10L, 10L, 10L, 10L))).isEqualTo(3);
  }

  @Test
  void exactlyHalfCounts() {
    assertThat(AnalyticsService.busFactor(List.of(50L, 30L, 20L))).isEqualTo(1);
    assertThat(AnalyticsService.busFactor(List.of(49L, 30L, 21L))).isEqualTo(2);
  }

  @Test
  void noCommitsHasNoBusFactor() {
    assertThat(AnalyticsService.busFactor(List.of())).isNull();
  }
}
