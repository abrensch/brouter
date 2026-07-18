package btools.router.roundtrip;

import org.junit.Assert;
import org.junit.Test;

import btools.router.roundtrip.RoundTripEffortPolicy.LengthClass;
import btools.router.roundtrip.RoundTripEffortPolicy.Preset;
import btools.router.roundtrip.RoundTripEffortPolicy.ProfileClass;

/** Unit tests for the effort presets and AUTO's context resolution rules. */
public class RoundTripEffortPolicyTest {

  @Test
  public void presetsCarryTheDocumentedKnobs() {
    Assert.assertEquals(2, RoundTripEffortPolicy.BOUNDED_PRESET.topKNormal);
    Assert.assertEquals(3, RoundTripEffortPolicy.BOUNDED_PRESET.topKLate);
    Assert.assertEquals(8_000, RoundTripEffortPolicy.BOUNDED_PRESET.tierBudgetMs);
    Assert.assertTrue(RoundTripEffortPolicy.BOUNDED_PRESET.skipRetryLayers);
    Assert.assertFalse(RoundTripEffortPolicy.BOUNDED_PRESET.runGreedyAlways);

    Assert.assertEquals(3, RoundTripEffortPolicy.STANDARD_PRESET.topKNormal);
    Assert.assertEquals(5, RoundTripEffortPolicy.STANDARD_PRESET.topKLate);
    Assert.assertEquals(0, RoundTripEffortPolicy.STANDARD_PRESET.tierBudgetMs);
    Assert.assertFalse(RoundTripEffortPolicy.STANDARD_PRESET.skipRetryLayers);

    Assert.assertEquals(4, RoundTripEffortPolicy.MAX_PRESET.topKNormal);
    Assert.assertEquals(6, RoundTripEffortPolicy.MAX_PRESET.topKLate);
    Assert.assertEquals(2.0, RoundTripEffortPolicy.MAX_PRESET.planBudgetScale, 1e-9);
    Assert.assertTrue(RoundTripEffortPolicy.MAX_PRESET.runGreedyAlways);
    Assert.assertFalse(RoundTripEffortPolicy.MAX_PRESET.skipRetryLayers);
  }

  @Test
  public void shortRequestBudgetResolvesBounded() {
    RoundTripEffortPolicy p = RoundTripEffortPolicy.resolveAuto(
      ProfileClass.BIKE, LengthClass.STANDARD, 64, 10_000);
    Assert.assertEquals(Preset.BOUNDED, p.preset);
    Assert.assertTrue(p.rationale, p.rationale.contains("constrained resources"));
  }

  @Test
  public void smallMemoryclassResolvesBounded() {
    RoundTripEffortPolicy p = RoundTripEffortPolicy.resolveAuto(
      ProfileClass.FOOT, LengthClass.SMALL, 32, 0);
    Assert.assertEquals(Preset.BOUNDED, p.preset);
    Assert.assertTrue(p.rationale, p.rationale.contains("memory-constrained"));
  }

  @Test
  public void unconstrainedResourcesResolveStandard() {
    // Unbounded budget (CLI) and server-class memory keep today's effort.
    RoundTripEffortPolicy p = RoundTripEffortPolicy.resolveAuto(
      ProfileClass.BIKE, LengthClass.STANDARD, 64, 0);
    Assert.assertEquals(Preset.STANDARD, p.preset);
    // A generous explicit budget too.
    p = RoundTripEffortPolicy.resolveAuto(ProfileClass.BIKE, LengthClass.LONG, 256, 60_000);
    Assert.assertEquals(Preset.STANDARD, p.preset);
    // Context lands in the rationale so future rules build on recorded evidence.
    Assert.assertTrue(p.rationale, p.rationale.contains("profile=BIKE"));
    Assert.assertTrue(p.rationale, p.rationale.contains("length=LONG"));
  }

  @Test
  public void lengthClassification() {
    Assert.assertEquals(LengthClass.SMALL, RoundTripEffortPolicy.classifyLength(15_000));
    Assert.assertEquals(LengthClass.STANDARD, RoundTripEffortPolicy.classifyLength(50_000));
    Assert.assertEquals(LengthClass.LONG, RoundTripEffortPolicy.classifyLength(150_000));
    Assert.assertEquals(LengthClass.XL, RoundTripEffortPolicy.classifyLength(250_000));
  }

  @Test
  public void profileClassification() {
    Assert.assertEquals(ProfileClass.FOOT, RoundTripEffortPolicy.classifyProfile(true, false, false));
    Assert.assertEquals(ProfileClass.BIKE, RoundTripEffortPolicy.classifyProfile(false, true, false));
    Assert.assertEquals(ProfileClass.MOTOR, RoundTripEffortPolicy.classifyProfile(false, false, true));
    // Motorized wins hybrids: its provisional-quality advisory must not be lost.
    Assert.assertEquals(ProfileClass.MOTOR, RoundTripEffortPolicy.classifyProfile(false, true, true));
    Assert.assertEquals(ProfileClass.UNKNOWN, RoundTripEffortPolicy.classifyProfile(false, false, false));
  }
}
