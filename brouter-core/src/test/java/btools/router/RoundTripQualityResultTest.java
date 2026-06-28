package btools.router;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Builder-contract tests for {@link RoundTripQualityResult}. The
 * two-tier rejection model relies on every reject site stating its tier
 * explicitly: a forgotten tier must fail loudly at build time rather than
 * silently defaulting to a STRUCTURAL hard-reject (which would defeat the
 * lenient-by-default behaviour). These tests pin that guard.
 */
public class RoundTripQualityResultTest {

  @Test
  public void rejectSetsTierAndReason() {
    RoundTripQualityResult r = RoundTripQualityResult.builder()
      .shape(RouteShape.INVALID_RETRACE)
      .reject(RoundTripQualityResult.RejectionTier.QUALITY, "off target")
      .build();
    assertFalse("reject() marks the result not accepted", r.isAccepted());
    assertEquals(RoundTripQualityResult.RejectionTier.QUALITY, r.getRejectionTier());
    assertEquals("off target", r.getRejectionReason());
  }

  @Test
  public void rejectionTierSetterAlsoSatisfiesGuard() {
    // The standalone rejectionTier() setter must also flag the tier as set, so
    // the legacy accepted(false)+rejectionTier()+rejectionReason() form builds.
    RoundTripQualityResult r = RoundTripQualityResult.builder()
      .accepted(false).shape(RouteShape.INVALID_RETRACE)
      .rejectionTier(RoundTripQualityResult.RejectionTier.STRUCTURAL)
      .rejectionReason("no track")
      .build();
    assertEquals(RoundTripQualityResult.RejectionTier.STRUCTURAL, r.getRejectionTier());
  }

  @Test
  public void rejectedResultWithoutTierThrows() {
    // The whole point of fix #10: forgetting the tier on a reject site is a
    // build-time failure, not a silent STRUCTURAL default.
    try {
      RoundTripQualityResult.builder()
        .accepted(false).shape(RouteShape.INVALID_RETRACE)
        .rejectionReason("some reason")
        .build();
      fail("expected IllegalStateException for a rejected result without a tier");
    } catch (IllegalStateException expected) {
      assertTrue("message should mention rejectionTier",
        expected.getMessage().contains("rejectionTier"));
    }
  }

  @Test
  public void rejectedResultWithoutReasonThrows() {
    try {
      RoundTripQualityResult.builder()
        .shape(RouteShape.INVALID_RETRACE)
        .rejectionTier(RoundTripQualityResult.RejectionTier.QUALITY)
        .build();
      fail("expected IllegalStateException for a rejected result without a reason");
    } catch (IllegalStateException expected) {
      assertTrue("message should mention rejectionReason",
        expected.getMessage().contains("rejectionReason"));
    }
  }

  @Test
  public void acceptedResultWithReasonThrows() {
    try {
      RoundTripQualityResult.builder()
        .accepted(true).shape(RouteShape.STRICT_LOOP)
        .rejectionReason("accepted routes must not carry a reason")
        .build();
      fail("expected IllegalStateException for an accepted result with a reason");
    } catch (IllegalStateException expected) {
      // ok
    }
  }

  @Test
  public void acceptedResultBuildsWithoutTier() {
    // Accepted results never read the tier, so they must build without one.
    RoundTripQualityResult r = RoundTripQualityResult.builder()
      .accepted(true).shape(RouteShape.STRICT_LOOP)
      .build();
    assertTrue(r.isAccepted());
  }
}
