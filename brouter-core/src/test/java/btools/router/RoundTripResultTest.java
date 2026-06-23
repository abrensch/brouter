package btools.router;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link RoundTripResult}'s Phase 2.0 / 2.1 telemetry block.
 * The iso/radial counters are checked elsewhere
 * ({@code RoutingEngineAutoCompetitionTest}); this pins the asymmetry-bearing
 * and axis-retry fields, whose sentinel defaults (NaN / -1 / 0.0 / false) are
 * load-bearing — downstream consumers branch on "not applied" by testing the
 * sentinel, so a wrong default would silently read as a real measurement.
 */
public class RoundTripResultTest {

  @Test
  public void phase2TelemetryHasSentinelDefaults() {
    RoundTripResult r = new RoundTripResult();

    // Phase 2.0 — isochrone-asymmetry bearing.
    assertFalse(r.isIsoAsymmetryBearingApplied());
    assertTrue("bearing default is NaN", Double.isNaN(r.getIsoAsymmetryBearingDegrees()));
    assertTrue("indirectness default is NaN",
      Double.isNaN(r.getIsoAsymmetryBestBucketIndirectness()));
    assertEquals(-1, r.getIsoAsymmetryBestBucketHits());
    assertEquals(-1, r.getIsoAsymmetryBestBucketAirDistMeters());

    // Phase 2.1 — frontier-axis retry.
    assertFalse(r.isPhase21AxisRetryTriggered());
    assertFalse(r.isPhase21AxisRetrySucceeded());
    assertTrue("axis bearing default is NaN", Double.isNaN(r.getPhase21AxisBearingDegrees()));
    assertEquals("axis strength default is 0.0", 0.0, r.getPhase21AxisStrength(), 0.0);
    assertTrue("retry direction default is NaN",
      Double.isNaN(r.getPhase21RetryDirectionDegrees()));
  }

  @Test
  public void phase2TelemetrySettersRoundTrip() {
    RoundTripResult r = new RoundTripResult();

    r.setIsoAsymmetryBearingApplied(true);
    r.setIsoAsymmetryBearingDegrees(123.5);
    r.setIsoAsymmetryBestBucketIndirectness(1.8);
    r.setIsoAsymmetryBestBucketHits(7);
    r.setIsoAsymmetryBestBucketAirDistMeters(4200);
    r.setPhase21AxisRetryTriggered(true);
    r.setPhase21AxisRetrySucceeded(true);
    r.setPhase21AxisBearingDegrees(95.0);
    r.setPhase21AxisStrength(3.2);
    r.setPhase21RetryDirectionDegrees(80.0);

    assertTrue(r.isIsoAsymmetryBearingApplied());
    assertEquals(123.5, r.getIsoAsymmetryBearingDegrees(), 1e-9);
    assertEquals(1.8, r.getIsoAsymmetryBestBucketIndirectness(), 1e-9);
    assertEquals(7, r.getIsoAsymmetryBestBucketHits());
    assertEquals(4200, r.getIsoAsymmetryBestBucketAirDistMeters());
    assertTrue(r.isPhase21AxisRetryTriggered());
    assertTrue(r.isPhase21AxisRetrySucceeded());
    assertEquals(95.0, r.getPhase21AxisBearingDegrees(), 1e-9);
    assertEquals(3.2, r.getPhase21AxisStrength(), 1e-9);
    assertEquals(80.0, r.getPhase21RetryDirectionDegrees(), 1e-9);
  }
}
