package btools.router;

import java.util.ArrayList;

import org.junit.Before;
import org.junit.Test;

import btools.mapaccess.MatchedWaypoint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Pure-function tests for {@link RoundTripQualityGate}. These tests construct
 * synthetic {@link OsmTrack} objects and verify the gate accepts good routes
 * and rejects every hard-fail case named in the production-safety spec. */
public class RoundTripQualityGateTest {

  /**
   * Mark the profiles these gate tests use, since classification now comes from
   * the cost-model probe (see {@link PavedProfileProbeTest}) rather than the
   * profile name. The hostile-surface checks read this via
   * {@link RoundTripQualityGate#isPavedProfile}.
   */
  @Before
  public void seedPavedClassification() {
    RoundTripQualityGate.putPavedClassificationForTest("fastbike", true);
    RoundTripQualityGate.putPavedClassificationForTest("gravel", false);
    RoundTripQualityGate.putPavedClassificationForTest("mtb", false);
  }

  // ---- Classification is memoised, name-independent --------------------------

  @Test
  public void pavedClassificationIsMemoisedAndNameIndependent() {
    RoundTripQualityGate.putPavedClassificationForTest("seed-paved", true);
    RoundTripQualityGate.putPavedClassificationForTest("seed-unpaved", false);
    assertTrue(RoundTripQualityGate.isPavedProfile("seed-paved"));
    assertFalse(RoundTripQualityGate.isPavedProfile("seed-unpaved"));

    // No name-based guessing any more: an unclassified profile is treated as
    // not-paved even when its name contains "fastbike" / "road" tokens. Real
    // classification comes from the cost-model probe (PavedProfileProbeTest).
    assertFalse(RoundTripQualityGate.isPavedProfile("unclassified-fastbike-road"));
    assertFalse(RoundTripQualityGate.isPavedProfile(null));
  }

  // ---- Happy path ---------------------------------------------------------

  @Test
  public void acceptsCleanCloseLoop() {
    OsmTrack good = squareLoop(/*sideMeters*/ 5000);
    // 4 edges × ~5km = ~20km loop — pass desired = actual so ratio = 1.0
    assertNull(RoundTripQualityGate.validate(good, good.distance, "fastbike"));
  }

  @Test
  public void acceptsAtExactRatioBoundary() {
    OsmTrack track = squareLoop(2500);
    // Reference the actual computed distance so the test is robust to
    // exact distance-formula differences. Test that ratio at MIN and MAX
    // boundaries is accepted.
    double dist = track.distance;
    double desiredAtMin = dist / RoundTripQualityGate.MIN_DISTANCE_RATIO;
    double desiredAtMax = dist / RoundTripQualityGate.MAX_DISTANCE_RATIO;
    assertNull("at MIN_DISTANCE_RATIO should pass",
      RoundTripQualityGate.validate(track, desiredAtMin, "fastbike"));
    assertNull("at MAX_DISTANCE_RATIO should pass",
      RoundTripQualityGate.validate(track, desiredAtMax, "fastbike"));
  }

  // ---- Hard-fail cases (one per spec criterion) ---------------------------

  @Test
  public void rejectsNullTrack() {
    assertNotNull(RoundTripQualityGate.validate(null, 20000, "fastbike"));
  }

  @Test
  public void rejectsTooFewNodes() {
    OsmTrack t = new OsmTrack();
    t.nodes = new ArrayList<>();
    t.nodes.add(makeNode(0, 0));
    t.nodes.add(makeNode(1000, 0));
    t.distance = 1000;
    String reason = RoundTripQualityGate.validate(t, 20000, "fastbike");
    assertNotNull(reason);
    assertTrue("expected too-few-nodes, got: " + reason, reason.contains("too few nodes"));
  }

  @Test
  public void rejectsClosureGapTooLarge() {
    OsmTrack t = squareLoop(5000);
    // Move the last node 500m away from the start
    OsmPathElement last = t.nodes.get(t.nodes.size() - 1);
    t.nodes.set(t.nodes.size() - 1, makeNodeRaw(last.getILon() + 5000, last.getILat()));
    String reason = RoundTripQualityGate.validate(t, 20000, "fastbike");
    assertNotNull(reason);
    assertTrue("expected closure rejection, got: " + reason, reason.contains("closure"));
  }

  @Test
  public void rejectsRatioBelowMin() {
    OsmTrack t = squareLoop(2000);  // 8km
    // 8km / 20km = 0.4 → below MIN_DISTANCE_RATIO 0.5
    String reason = RoundTripQualityGate.validate(t, 20000, "fastbike");
    assertNotNull(reason);
    assertTrue("expected ratio rejection, got: " + reason, reason.contains("ratio"));
  }

  @Test
  public void rejectsRatioAboveMax() {
    OsmTrack t = squareLoop(5000); // 20km
    // 20km / 10km = 2.0 → above MAX_DISTANCE_RATIO 1.8
    String reason = RoundTripQualityGate.validate(t, 10000, "fastbike");
    assertNotNull(reason);
    assertTrue("expected ratio rejection, got: " + reason, reason.contains("ratio"));
  }

  @Test
  public void rejectsExcessiveReuse() {
    // 4 edges out (1km each) + 4 edges back (re-traced) + 1 extra short edge
    // → 4/9 ≈ 44%? no, we need > 50%. Use 3 out + 3 back = 6 edges, 3 reused
    // = exactly 50% by edge count, but by distance-weighted reuse the first
    // visit isn't reuse so it's 3/6 = 50% — exactly at the boundary.
    // Push past with one extra reused edge.
    OsmTrack heavy = new OsmTrack();
    heavy.nodes = new ArrayList<>();
    heavy.nodes.add(makeNode(0, 0));
    heavy.nodes.add(makeNode(1000, 0));
    heavy.nodes.add(makeNode(2000, 0));
    heavy.nodes.add(makeNode(3000, 0));
    heavy.nodes.add(makeNode(2000, 0)); // reuse #1
    heavy.nodes.add(makeNode(1000, 0)); // reuse #2
    heavy.nodes.add(makeNode(2000, 0)); // reuse #3 (re-revisit of 1000-2000)
    heavy.nodes.add(makeNode(1000, 0)); // reuse #4 (re-revisit of 2000-1000)
    heavy.nodes.add(makeNode(0, 0));    // reuse #5 (0-1000)
    int hd = 0;
    for (int i = 1; i < heavy.nodes.size(); i++) {
      hd += heavy.nodes.get(i - 1).calcDistance(heavy.nodes.get(i));
      MessageData m = msgCostfactor(1.5f, "highway=residential surface=asphalt");
      heavy.nodes.get(i).message = m;
    }
    heavy.distance = hd;
    String reason = RoundTripQualityGate.validate(heavy, hd, "fastbike");
    assertNotNull(reason);
    // The new semantic classifier rejects this synthetic zigzag (an edge
    // visited 3+ times within a contiguous reuse stretch) as accidental
    // backtracking, not just "high reuse percentage". Either rejection
    // wording is acceptable — the contract is that the route is rejected.
    assertTrue("expected reuse/retrace rejection, got: " + reason,
      reason.contains("reuse") || reason.contains("retrace") || reason.contains("backtrack")
        || reason.contains("out-and-back"));
  }

  @Test
  public void rejectionTierSeparatesStructuralFromQuality() {
    RoundTripQualityResult.RejectionTier Q = RoundTripQualityResult.RejectionTier.QUALITY;
    RoundTripQualityResult.RejectionTier S = RoundTripQualityResult.RejectionTier.STRUCTURAL;

    // QUALITY — rideable but suboptimal (engine warns by default):
    // distance ratio below band (8km loop vs 20km target → 0.4)
    assertEquals("ratio rejection is QUALITY", Q,
      RoundTripQualityGate.evaluate(squareLoop(2000), 20000, "fastbike", false).getRejectionTier());
    // profile-hostile surface (whole loop is highway=path on a paved profile)
    assertEquals("hostile-surface rejection is QUALITY", Q,
      RoundTripQualityGate.evaluate(squareLoopWithMessage(5000, msgWayTags("highway=path")),
        20000, "fastbike", false).getRejectionTier());

    // STRUCTURAL — broken / not a rideable loop (engine always hard-rejects):
    // too few nodes
    OsmTrack tiny = new OsmTrack();
    tiny.nodes = new ArrayList<>();
    tiny.nodes.add(makeNode(0, 0));
    tiny.nodes.add(makeNode(1000, 0));
    tiny.distance = 1000;
    assertEquals("too-few-nodes is STRUCTURAL", S,
      RoundTripQualityGate.evaluate(tiny, 20000, "fastbike", false).getRejectionTier());
    // closure gap (last node 5km from start)
    OsmTrack open = squareLoop(5000);
    OsmPathElement last = open.nodes.get(open.nodes.size() - 1);
    open.nodes.set(open.nodes.size() - 1, makeNodeRaw(last.getILon() + 5000, last.getILat()));
    assertEquals("closure-gap is STRUCTURAL", S,
      RoundTripQualityGate.evaluate(open, 20000, "fastbike", false).getRejectionTier());
    // beeline (DIRECT-marked waypoint)
    OsmTrack bl = squareLoop(5000);
    bl.matchedWaypoints = new ArrayList<>();
    MatchedWaypoint direct = new MatchedWaypoint();
    direct.wpttype = MatchedWaypoint.WAYPOINT_TYPE_DIRECT;
    bl.matchedWaypoints.add(direct);
    assertEquals("beeline is STRUCTURAL", S,
      RoundTripQualityGate.evaluate(bl, 20000, "fastbike", false).getRejectionTier());
  }

  @Test
  public void rejectsBeelineMarkedWaypoint() {
    OsmTrack t = squareLoop(5000);
    t.matchedWaypoints = new ArrayList<>();
    MatchedWaypoint mwp = new MatchedWaypoint();
    mwp.wpttype = MatchedWaypoint.WAYPOINT_TYPE_DIRECT;
    t.matchedWaypoints.add(mwp);
    String reason = RoundTripQualityGate.validate(t, 20000, "fastbike");
    assertNotNull(reason);
    assertTrue("expected beeline rejection, got: " + reason, reason.contains("beeline"));
  }

  @Test
  public void rejectsDirectSegmentMarkerInTrackGeometry() {
    OsmTrack t = squareLoopWithMessage(5000,
      msgWayTags("highway=residential surface=asphalt direct_segment=true"));
    String reason = RoundTripQualityGate.validate(t, t.distance, "fastbike");
    assertNotNull(reason);
    assertTrue("expected direct_segment beeline rejection, got: " + reason,
      reason.contains("beeline") || reason.contains("direct_segment"));
  }

  @Test
  public void rejectsFerrySegmentByDefault() {
    OsmTrack t = squareLoopWithMessage(5000,
      msgWayTags("highway=service surface=asphalt route=ferry"));
    RoundTripQualityResult result = RoundTripQualityGate.evaluate(
      t, t.distance, "fastbike", false, false);
    assertFalse("ferry must reject by default", result.isAccepted());
    assertTrue("expected ferry rejection, got: " + result.getRejectionReason(),
      result.getRejectionReason().contains("ferry"));
  }

  @Test
  public void acceptsFerrySegmentWhenExplicitlyAllowed() {
    OsmTrack t = squareLoopWithMessage(5000,
      msgWayTags("highway=service surface=asphalt route=ferry"));
    RoundTripQualityResult result = RoundTripQualityGate.evaluate(
      t, t.distance, "fastbike", false, false, true);
    assertTrue("explicit ferry opt-in should leave clean geometry acceptable: "
      + result.getRejectionReason(), result.isAccepted());
  }

  @Test
  public void pavedProfileRejectsPathHeavyRoute() {
    OsmTrack t = squareLoopWithMessage(5000, msgWayTags("highway=path"));
    String reason = RoundTripQualityGate.validate(t, 20000, "fastbike");
    assertNotNull(reason);
    assertTrue("expected hostile-segment rejection, got: " + reason,
      reason.contains("profile-hostile") || reason.contains("path/track/unpaved"));
  }

  @Test
  public void pavedProfileRejectsHighCostFactorSpike() {
    // 100% of edges have costfactor=10 (e.g. forced onto a grade-5 track)
    OsmTrack t = squareLoopWithMessage(5000, msgCostfactor(10.0f, "highway=residential"));
    String reason = RoundTripQualityGate.validate(t, 20000, "fastbike");
    assertNotNull(reason);
    assertTrue("expected hostile rejection on cost-spike, got: " + reason,
      reason.contains("profile-hostile"));
  }

  // ---- Phase 2 v2 — worst-contiguous helper -------------------------------

  @Test
  public void worstContiguousHelperReturnsZeroForCleanTrack() {
    OsmTrack t = squareLoopWithMessage(5000, msgCostfactor(1.5f, "highway=residential"));
    assertEquals(0, RoundTripQualityGate.worstContiguousHostileMetersPaved(t));
  }

  @Test
  public void worstContiguousHelperFindsLongestStretch() {
    // Same metric the gate uses internally — verify the helper sees the
    // full hostile run and not just a single edge. Use the existing
    // trackWithContiguousHostile(total, contiguous, scattered) helper.
    OsmTrack t = trackWithContiguousHostile(20000, 800, 0);
    int worst = RoundTripQualityGate.worstContiguousHostileMetersPaved(t);
    // CheapRuler scaling means each 100m chunk → ~120m actual; 800 nominal
    // meters across 8 chunks → ~900-1000m measured. Accept a wide band.
    assertTrue("expected 700-1100m worst-contiguous, got " + worst,
      worst >= 700 && worst <= 1100);
  }

  @Test
  public void worstContiguousHelperHandlesEmptyTrack() {
    OsmTrack t = new OsmTrack();
    t.nodes = new ArrayList<>();
    assertEquals(0, RoundTripQualityGate.worstContiguousHostileMetersPaved(t));
  }

  @Test
  public void tagMatchIsTokenBoundedNotSubstring() {
    // bicycle=no is a restriction; oneway:bicycle=no is the OPPOSITE (cyclists
    // exempted from a oneway) and must NOT match as a substring.
    assertTrue(RoundTripQualityGate.hasTag("highway=residential bicycle=no", "bicycle=no"));
    assertTrue(RoundTripQualityGate.hasTag("bicycle=no", "bicycle=no"));
    assertTrue(RoundTripQualityGate.hasTag("surface=asphalt bicycle=no foo=bar", "bicycle=no"));
    assertFalse(RoundTripQualityGate.hasTag(
      "highway=track surface=asphalt oneway:bicycle=no", "bicycle=no"));
    assertFalse(RoundTripQualityGate.hasTag("access=designated", "access=no"));
    assertFalse(RoundTripQualityGate.hasTag(null, "bicycle=no"));
  }

  @Test
  public void missingMetadataFractionCountsOnlyUnverifiedEdges() {
    OsmTrack t = squareLoop(5000);
    t.nodes.get(2).message = null;
    t.nodes.get(4).message = msgNoMetadata();
    assertEquals(0.5, RoundTripQualityGate.missingMetadataFraction(t), 0.05);
  }

  @Test
  public void pavedProfileRejectsRouteWithMissingMetadata() {
    // No per-edge messages — engine can't prove the edges are paved
    OsmTrack t = squareLoopNoMessage(5000);
    String reason = RoundTripQualityGate.validate(t, 20000, "fastbike");
    assertNotNull(reason);
    assertTrue("expected missing-metadata rejection, got: " + reason,
      reason.contains("missing/unknown metadata"));
  }

  @Test
  public void pavedProfileAcceptsCleanResidentialRoute() {
    // costfactor=1.5 (residential-ish), tags are clearly paved
    OsmTrack t = squareLoopWithMessage(5000, msgCostfactor(1.5f, "highway=residential"));
    String reason = RoundTripQualityGate.validate(t, 20000, "fastbike");
    assertNull("clean paved route should pass: " + reason, reason);
  }

  @Test
  public void nonPavedProfileSkipsHostilityCheck() {
    // Same path-heavy route that fastbike rejects → gravel/MTB accept it
    OsmTrack t = squareLoopWithMessage(5000, msgWayTags("highway=path"));
    assertNull(RoundTripQualityGate.validate(t, 20000, "gravel"));
    assertNull(RoundTripQualityGate.validate(t, 20000, "mtb"));
  }

  @Test
  public void pavedProfileToleratesMinorHostileSegments() {
    // 95% residential, 5% path → below MAX_HOSTILE_FRACTION (10%)
    OsmTrack t = mixedSurfaceLoop(/*sideMeters*/ 5000, /*hostileFractionPct*/ 5);
    String reason = RoundTripQualityGate.validate(t, 20000, "fastbike");
    assertNull("5% path content should pass: " + reason, reason);
  }

  // ---- Phase 1.1 — asphalt-surface exemption ----------------------------

  @Test
  public void pathWithAsphaltSurfaceIsNotHostile() {
    // Common OSM mis-tagging: rural cycleway tagged highway=path but
    // surface=asphalt. Paved infrastructure — must pass.
    MessageData m = new MessageData();
    m.wayKeyValues = "highway=path surface=asphalt";
    m.costfactor = 1.5f;
    assertFalse("path + asphalt is paved cycleway, not hostile",
      RoundTripQualityGate.isHostileForPavedProfile(m));
  }

  @Test
  public void pathWithPavedSurfaceIsNotHostile() {
    // surface=paved is the generic version of surface=asphalt.
    MessageData m = new MessageData();
    m.wayKeyValues = "highway=footway surface=paved";
    m.costfactor = 1.5f;
    assertFalse("footway + paved is paved cycleway, not hostile",
      RoundTripQualityGate.isHostileForPavedProfile(m));
  }

  @Test
  public void pathWithoutSurfaceTagStaysHostile() {
    // No surface= tag — engine can't prove it's paved; keep the
    // conservative behaviour (treat as hostile).
    MessageData m = new MessageData();
    m.wayKeyValues = "highway=path";
    m.costfactor = 1.5f;
    assertTrue("path without surface tag stays hostile",
      RoundTripQualityGate.isHostileForPavedProfile(m));
  }

  @Test
  public void pathWithGravelSurfaceStaysHostile() {
    // surface=gravel is NOT a hard surface — must remain hostile even
    // though highway=path is in the overridable list.
    MessageData m = new MessageData();
    m.wayKeyValues = "highway=path surface=gravel";
    m.costfactor = 1.5f;
    assertTrue("path + gravel is unpaved, hostile",
      RoundTripQualityGate.isHostileForPavedProfile(m));
  }

  // ---- pebblestone / cobblestone — rideable constructed stone paving ------

  @Test
  public void pathWithPebblestoneSurfaceIsNotHostile() {
    // surface=pebblestone is rough constructed stone paving (cobbles): slow
    // but rideable on a road bike, and bona fide paved infrastructure. The
    // fastbike cost function treats it as "unpaved" (costfactor ~15), so this
    // exercises the exemption that must fire BEFORE the costfactor>4 check.
    MessageData m = new MessageData();
    m.wayKeyValues = "highway=path surface=pebblestone";
    m.costfactor = 15.0f;
    assertFalse("path + pebblestone is rideable paved infrastructure, not hostile",
      RoundTripQualityGate.isHostileForPavedProfile(m));
  }

  @Test
  public void footwayWithCobblestoneSurfaceIsNotHostile() {
    MessageData m = new MessageData();
    m.wayKeyValues = "highway=footway surface=cobblestone";
    m.costfactor = 15.0f;
    assertFalse("footway + cobblestone is rideable paved infrastructure, not hostile",
      RoundTripQualityGate.isHostileForPavedProfile(m));
  }

  @Test
  public void pebblestoneTrackWithoutPoorTracktypeIsNotHostile() {
    // A plain highway=track surface=pebblestone (no grade2-5) is rideable.
    MessageData m = new MessageData();
    m.wayKeyValues = "highway=track surface=pebblestone";
    m.costfactor = 15.0f;
    assertFalse("pebblestone track without a poor tracktype is rideable, not hostile",
      RoundTripQualityGate.isHostileForPavedProfile(m));
  }

  @Test
  public void pebblestoneGrade2TrackStaysHostile() {
    // tracktype=grade2 deliberately overrides the surface tag (the gate
    // distrusts grade2-5 as genuinely rough riding regardless of surface),
    // so a grade2 pebblestone forest track stays hostile.
    MessageData m = new MessageData();
    m.wayKeyValues = "highway=track surface=pebblestone tracktype=grade2";
    m.costfactor = 10.0f;
    assertTrue("grade2 pebblestone track stays hostile (grade2 distrust)",
      RoundTripQualityGate.isHostileForPavedProfile(m));
  }

  @Test
  public void pebblestonePathWithBicycleNoStaysHostile() {
    // An explicit bicycle restriction is a legal denial that the surface
    // exemption must not override.
    MessageData m = new MessageData();
    m.wayKeyValues = "highway=path surface=pebblestone bicycle=no";
    m.costfactor = 15.0f;
    assertTrue("pebblestone path with bicycle=no stays hostile",
      RoundTripQualityGate.isHostileForPavedProfile(m));
  }

  @Test
  public void grade3HardSurfacePathStaysHostileHighCost() {
    // Symmetric with the track case: a poor tracktype (grade2-5) on a soft
    // highway vetoes the hard-surface rideability exemption even when the cost
    // function flags the surface as unpaved (high costfactor branch).
    MessageData m = new MessageData();
    m.wayKeyValues = "highway=path surface=asphalt tracktype=grade3";
    m.costfactor = 15.0f;
    assertTrue("grade3 path stays hostile despite hard surface (poor-tracktype veto)",
      RoundTripQualityGate.isHostileForPavedProfile(m));
  }

  @Test
  public void grade2HardSurfacePathStaysHostileLowCost() {
    // The low-cost branch: tracktype=grade2 is not itself a hostile fragment, so
    // before the poor-tracktype guard this slipped through the override loop as
    // "not hostile". It must stay hostile, symmetric with the grade2 track case.
    MessageData m = new MessageData();
    m.wayKeyValues = "highway=path surface=asphalt tracktype=grade2";
    m.costfactor = 1.5f;
    assertTrue("grade2 path stays hostile even on the low-cost branch (poor-tracktype veto)",
      RoundTripQualityGate.isHostileForPavedProfile(m));
  }

  @Test
  public void plainTrackWithAsphaltSurfaceIsNotHostile() {
    // B.2 evidence: cyclists ride asphalt-surfaced tracks routinely.
    // 2+ Freiburg routes share the Kandel "Saubergweg" tagged exactly
    // this way (highway=track surface=asphalt, no tracktype), and
    // adjacent ways are paved service roads (highway=service
    // surface=asphalt). Asphalt is asphalt; tracktype absence is just
    // OSM under-tagging.
    MessageData m = new MessageData();
    m.wayKeyValues = "highway=track surface=asphalt";
    m.costfactor = 1.5f;
    assertFalse("plain track + asphalt is paved infrastructure, not hostile",
      RoundTripQualityGate.isHostileForPavedProfile(m));
  }

  @Test
  public void pavedGrade1BicycleTrackIsNotHostile() {
    MessageData m = new MessageData();
    m.wayKeyValues = "highway=track surface=asphalt tracktype=grade1 bicycle=yes";
    m.costfactor = 1.5f;
    assertFalse("paved grade1 bicycle track is road-bike suitable",
      RoundTripQualityGate.isHostileForPavedProfile(m));
  }

  @Test
  public void pavedGrade1CycleRouteTrackIsNotHostileEvenWithHighCost() {
    MessageData m = new MessageData();
    m.wayKeyValues = "highway=track surface=asphalt tracktype=grade1 route_bicycle_lcn=yes";
    m.costfactor = 8.0f;
    assertFalse("official paved grade1 cycle-route track should override cost spike",
      RoundTripQualityGate.isHostileForPavedProfile(m));
  }

  @Test
  public void pavedGrade2BicycleTrackStaysHostileForRoadBike() {
    MessageData m = new MessageData();
    m.wayKeyValues = "highway=track surface=asphalt tracktype=grade2 bicycle=yes";
    m.costfactor = 1.5f;
    assertTrue("grade2 track is not the narrow road-bike exemption",
      RoundTripQualityGate.isHostileForPavedProfile(m));
  }

  // ---- Phase 2 v5 — paved grade1 track without explicit bicycle tag ------

  @Test
  public void pavedGrade1TrackWithoutBicycleTagIsNotHostile() {
    // GPX-replay evidence (52 Basel routes, 8 false positives) shows
    // cyclists routinely ride paved grade1 tracks that OSM doesn't
    // bother tagging with bicycle=*. The Seewen road (47.5125, 7.6524)
    // appears in 4 different road-bike GPXs.
    MessageData m = new MessageData();
    m.wayKeyValues = "highway=track surface=asphalt tracktype=grade1";
    m.costfactor = 1.5f;
    assertFalse("paved grade1 track without explicit restriction must be acceptable: "
      + m.wayKeyValues, RoundTripQualityGate.isHostileForPavedProfile(m));
  }

  @Test
  public void pavedGrade1TrackWithFootPermissiveIsNotHostile() {
    // The Seewen-area pattern from the Basel corpus: foot=permissive
    // but no bicycle=*. Default-permissive tagging convention.
    MessageData m = new MessageData();
    m.wayKeyValues = "highway=track surface=asphalt tracktype=grade1 foot=permissive";
    m.costfactor = 1.5f;
    assertFalse("paved grade1 + foot=permissive should be acceptable",
      RoundTripQualityGate.isHostileForPavedProfile(m));
  }

  @Test
  public void pavedGrade1TrackWithBicycleNoStaysHostile() {
    // Explicit bicycle=no restriction must still trip the gate even on
    // a paved grade1 track — the relaxation is not unconditional.
    MessageData m = new MessageData();
    m.wayKeyValues = "highway=track surface=asphalt tracktype=grade1 bicycle=no";
    m.costfactor = 1.5f;
    assertTrue("explicit bicycle=no restriction must override relaxation",
      RoundTripQualityGate.isHostileForPavedProfile(m));
  }

  @Test
  public void pavedGrade1TrackWithAccessPrivateStaysHostile() {
    MessageData m = new MessageData();
    m.wayKeyValues = "highway=track surface=asphalt tracktype=grade1 access=private";
    m.costfactor = 1.5f;
    assertTrue("access=private must override relaxation",
      RoundTripQualityGate.isHostileForPavedProfile(m));
  }

  // ---- Phase 2 v5 (B.1 cascade) — grade1 + cycle network → paved -----------

  @Test
  public void grade1TrackOnCycleNetworkWithoutSurfaceIsNotHostile() {
    // B.1 evidence: Baar grade1+lcn track appears in 2 different
    // Freiburg routes. OSM grade1 = "Solid hard surface, typically
    // tarmac/asphalt/concrete" and the cycle-network tag is curated
    // evidence the way is rideable.
    MessageData m = new MessageData();
    m.wayKeyValues = "highway=track tracktype=grade1 route_bicycle_lcn=yes";
    m.costfactor = 1.5f;
    assertFalse("grade1 + local cycle network is paved cycle infrastructure",
      RoundTripQualityGate.isHostileForPavedProfile(m));
  }

  @Test
  public void grade1TrackOnRegionalCycleNetworkIsNotHostile() {
    MessageData m = new MessageData();
    m.wayKeyValues = "highway=track tracktype=grade1 route_bicycle_rcn=yes";
    m.costfactor = 1.5f;
    assertFalse("grade1 + regional cycle network is paved cycle infrastructure",
      RoundTripQualityGate.isHostileForPavedProfile(m));
  }

  @Test
  public void grade1TrackWithoutCycleNetworkOrSurfaceStaysHostile() {
    // Without ANY positive evidence (no surface, no cycle network) and
    // only grade1, we keep the conservative default. Grade1 alone is a
    // hint, not enough; the predicate needs at least one corroborating
    // signal.
    MessageData m = new MessageData();
    m.wayKeyValues = "highway=track tracktype=grade1";
    m.costfactor = 1.5f;
    assertTrue("grade1 alone, no surface, no network → keep conservative reject",
      RoundTripQualityGate.isHostileForPavedProfile(m));
  }

  @Test
  public void grade2CycleNetworkTrackStaysHostile() {
    // B.1 is grade1-only. Grade2 (compacted/gravel cycle routes) is
    // not road-bike-suitable even with a cycle-network tag — the
    // Freiburg "Rinken" road appears in 4 routes tagged
    // grade2+compacted+lcn and is correctly rejected.
    MessageData m = new MessageData();
    m.wayKeyValues = "highway=track surface=compacted tracktype=grade2 route_bicycle_lcn=yes";
    m.costfactor = 1.5f;
    assertTrue("grade2 + cycle network is gravel-bike-suitable, not road-bike",
      RoundTripQualityGate.isHostileForPavedProfile(m));
  }

  // ---- Phase 1.2 — cobblestone/pebblestone are paved (rough, rideable) --

  @Test
  public void cobblestoneIsNotHostile() {
    // Rough but rideable — German pedestrian zones, Belgian cycleways.
    MessageData m = new MessageData();
    m.wayKeyValues = "highway=residential surface=cobblestone";
    m.costfactor = 1.5f;
    assertFalse("cobblestone is rough-but-rideable paved",
      RoundTripQualityGate.isHostileForPavedProfile(m));
  }

  @Test
  public void pebblestoneIsNotHostile() {
    MessageData m = new MessageData();
    m.wayKeyValues = "highway=residential surface=pebblestone";
    m.costfactor = 1.5f;
    assertFalse("pebblestone is rough-but-rideable paved",
      RoundTripQualityGate.isHostileForPavedProfile(m));
  }

  // ---- Phase 1.3 — contiguous-hostile sub-cap ---------------------------

  @Test
  public void shortContiguousHostileRunBelowCapAccepted() {
    // ~6% total hostile (1200m of 20km), longest contiguous run only 400m
    // → accept (under both total-fraction and contiguous caps).
    OsmTrack t = trackWithContiguousHostile(20000, 400, 800);
    String reason = RoundTripQualityGate.validate(t, 20000, "fastbike");
    assertNull("short contiguous hostile under sub-cap: " + reason, reason);
  }

  @Test
  public void longContiguousHostileRunAboveCapRejected() {
    // ~9% total hostile, longest contiguous run 1800m → reject with
    // "contiguous" prefix (over the 1500m sub-cap) even though total
    // fraction is below the 10% cap.
    OsmTrack t = trackWithContiguousHostile(20000, 1800, 0);
    String reason = RoundTripQualityGate.validate(t, 20000, "fastbike");
    assertNotNull("long contiguous hostile must reject", reason);
    assertTrue("rejection prefix is 'contiguous': " + reason,
      reason.startsWith("contiguous "));
  }

  @Test
  public void worstHostileStretchReportsCoordinatesAndTags() {
    OsmTrack t = trackWithContiguousHostile(20000, 1800, 0);

    RoundTripQualityGate.HostileStretch stretch =
      RoundTripQualityGate.worstHostileStretchPaved(t);

    assertTrue(stretch.isPresent());
    assertTrue("expected hostile stretch near requested length, got " + stretch.meters,
      stretch.meters >= 1700);
    assertEquals(0, stretch.startIndex);
    assertTrue(stretch.endIndex > stretch.startIndex);
    assertEquals(t.nodes.get(stretch.startIndex).getILon(), stretch.startIlon);
    assertEquals(t.nodes.get(stretch.startIndex).getILat(), stretch.startIlat);
    assertEquals(t.nodes.get(stretch.endIndex).getILon(), stretch.endIlon);
    assertEquals(t.nodes.get(stretch.endIndex).getILat(), stretch.endIlat);
    assertTrue(stretch.startTags.contains("highway=path"));
    assertTrue(stretch.endTags.contains("surface=ground"));
    assertTrue(stretch.describe().contains("highway=path"));
  }

  @Test
  public void totalFractionRejectionPathRemainsActive() {
    // ~15% total hostile but contiguous bursts under 1500m. The
    // contiguous sub-cap doesn't fire (≤ 1500m), so the existing total-
    // fraction rejection takes over. Helper chunks are ~120m (not 100m)
    // due to CheapRuler scaling at 50°N, so 1000m of declared contiguous
    // turns into ~1200m actual — comfortably under the 1500m sub-cap.
    OsmTrack t = trackWithContiguousHostile(40000, 1000, 5000);
    String reason = RoundTripQualityGate.validate(t, 40000, "fastbike");
    assertNotNull(reason);
    assertTrue("expected total-fraction prefix: " + reason,
      reason.contains("of distance on profile-hostile ways"));
  }

  // ---- Combined questionable-surface ceiling (hostile + suspect) ----------

  @Test
  public void rejectsCombinedHostilePlusSuspectAboveCombinedCap() {
    // ~9% confirmed-hostile + ~9% unverifiable = ~18% non-confirmed-paved.
    // Each individual bucket is below the 10% ceiling, but the combined
    // questionable fraction exceeds MAX_QUESTIONABLE_FRACTION (15%) and must
    // be rejected. Edges are scattered so the contiguous sub-cap never fires.
    OsmTrack t = loopWithScatteredKinds(40000, 36, 36, msgNoMetadata());
    String reason = RoundTripQualityGate.validate(t, t.distance, "fastbike");
    assertNotNull("9%+9% questionable surface must be rejected by the combined cap", reason);
    assertTrue("expected combined questionable-surface message, got: " + reason,
      reason.contains("hostile or unverifiable") || reason.contains("questionable"));
  }

  @Test
  public void acceptsModerateHostilePlusSuspectBelowCombinedCap() {
    // ~6% + ~6% = ~12% < 15% combined cap, each well under 10% — still accepted,
    // so the new ceiling does not over-tighten ordinary data-sparse loops.
    OsmTrack t = loopWithScatteredKinds(40000, 24, 24, msgNoMetadata());
    String reason = RoundTripQualityGate.validate(t, t.distance, "fastbike");
    assertNull("12% combined questionable surface should still pass: " + reason, reason);
  }

  @Test
  public void nullTagHighCostEdgesCountAsHostileNotSuspect() {
    // ~12% of distance on edges with no tags but a router cost above the hostile
    // threshold (4.0). These are confirmed-expensive by the router, so the gate
    // must count them as hostile ("profile-hostile ways") rather than merely
    // unverifiable ("missing/unknown metadata"). Scattered: contiguous cap N/A.
    OsmTrack t = loopWithScatteredKinds(40000, 0, 48, msgSinglePass(5.0f));
    String reason = RoundTripQualityGate.validate(t, t.distance, "fastbike");
    assertNotNull("12% confirmed-expensive surface must be rejected", reason);
    assertTrue("expected hostile (not metadata) rejection, got: " + reason,
      reason.contains("profile-hostile ways"));
  }

  @Test
  public void nullTagLowCostEdgesStaySuspect() {
    // No tags AND low router cost = genuinely unverifiable, must stay suspect.
    // ~12% suspect alone trips the suspect ceiling with the metadata message.
    OsmTrack t = loopWithScatteredKinds(40000, 0, 48, msgSinglePass(1.2f));
    String reason = RoundTripQualityGate.validate(t, t.distance, "fastbike");
    assertNotNull("12% unverifiable surface must be rejected", reason);
    assertTrue("expected missing-metadata rejection, got: " + reason,
      reason.contains("missing/unknown metadata"));
  }

  // ---- helpers ------------------------------------------------------------

  /** Square loop with 4 edges of {@code side} meters each — total ~4×side.
   * Edges carry clean residential metadata so the paved-profile gate accepts
   * the route unless the test deliberately overrides it. */
  private static OsmTrack squareLoop(int side) {
    OsmTrack t = squareLoopNoMessage(side);
    MessageData clean = msgCostfactor(1.5f, "highway=residential surface=asphalt");
    for (int i = 1; i < t.nodes.size(); i++) {
      t.nodes.get(i).message = clone(clean);
    }
    return t;
  }

  /** Same geometry but without per-edge messages — used by the missing-metadata
   * test to verify the gate rejects routes whose paved-ness can't be proven. */
  private static OsmTrack squareLoopNoMessage(int side) {
    OsmTrack t = new OsmTrack();
    t.nodes = new ArrayList<>();
    t.nodes.add(makeNode(0, 0));
    t.nodes.add(makeNode(side, 0));
    t.nodes.add(makeNode(side, side));
    t.nodes.add(makeNode(0, side));
    t.nodes.add(makeNode(0, 0)); // close
    int dist = 0;
    for (int i = 1; i < t.nodes.size(); i++) {
      dist += t.nodes.get(i - 1).calcDistance(t.nodes.get(i));
    }
    t.distance = dist;
    return t;
  }

  /** Square loop where every edge carries the supplied {@link MessageData}. */
  private static OsmTrack squareLoopWithMessage(int side, MessageData m) {
    OsmTrack t = squareLoop(side);
    for (int i = 1; i < t.nodes.size(); i++) {
      t.nodes.get(i).message = clone(m);
    }
    return t;
  }

  /**
   * Build a synthetic clean loop (square geometry, every edge unique) of
   * approximately {@code totalMeters} total length, then tag a contiguous
   * run of {@code contiguousHostileMeters} along one side and scatter
   * {@code scatteredHostileMeters} of additional hostile bursts on the
   * opposite side (broken up by non-hostile edges to avoid extending the
   * contiguous run). The geometry is loop-shaped to avoid tripping the
   * reuse classifier as OUT_AND_BACK.
   */
  private static OsmTrack trackWithContiguousHostile(int totalMeters,
                                                     int contiguousHostileMeters,
                                                     int scatteredHostileMeters) {
    // 4-sided square loop with edges subdivided into 100m chunks. Each side
    // is totalMeters / 4 long; we choose chunk count per side to give
    // fine-grained tagging control while keeping geometry clearly loop-like.
    int side = totalMeters / 4;
    int chunkLen = 100;
    int chunksPerSide = side / chunkLen;
    if (chunksPerSide < 1) chunksPerSide = 1;

    OsmTrack t = new OsmTrack();
    t.nodes = new ArrayList<>();
    // Four sides of the square, with intermediate chunk nodes:
    //   (0,0) → (side,0) → (side,side) → (0,side) → (0,0)
    addSquareSide(t, 0, 0, +chunkLen, 0, chunksPerSide); // east side
    addSquareSide(t, side, 0, 0, +chunkLen, chunksPerSide); // north side
    addSquareSide(t, side, side, -chunkLen, 0, chunksPerSide); // west side
    addSquareSide(t, 0, side, 0, -chunkLen, chunksPerSide); // south side
    t.nodes.add(makeNode(0, 0)); // close the loop

    int contigEdges = Math.min(contiguousHostileMeters / chunkLen, chunksPerSide);
    int scatteredEdges = Math.min(scatteredHostileMeters / chunkLen, chunksPerSide);
    // Place the contiguous hostile run at the START of the east side.
    // Place the scattered hostile bursts on the west side, every-other
    // chunk, so each is separated by a non-hostile edge.
    int dist = 0;
    int totalEdges = t.nodes.size() - 1;
    int westSideStart = 2 * chunksPerSide + 1;
    int westSideEnd = 3 * chunksPerSide;
    for (int i = 1; i < t.nodes.size(); i++) {
      dist += t.nodes.get(i - 1).calcDistance(t.nodes.get(i));
      boolean isContigHostile = i <= contigEdges;
      int westIdx = i - westSideStart;
      boolean isScatteredHostile = westIdx >= 0 && westIdx < chunksPerSide
        && westIdx % 2 == 0 && (westIdx / 2) < scatteredEdges;
      MessageData m = (isContigHostile || isScatteredHostile)
        ? msgWayTags("highway=path surface=ground")
        : msgCostfactor(1.0f, "highway=residential surface=asphalt");
      t.nodes.get(i).message = m;
    }
    t.distance = dist;
    return t;
  }

  /** Push {@code n} segments of (dx, dy) starting from (startX, startY)
   * into the track. The starting node is added only if the track is empty
   * (other sides chain off the previous one's endpoint). */
  private static void addSquareSide(OsmTrack t, int startX, int startY,
                                    int dx, int dy, int n) {
    if (t.nodes.isEmpty()) t.nodes.add(makeNode(startX, startY));
    for (int i = 1; i <= n; i++) {
      t.nodes.add(makeNode(startX + dx * i, startY + dy * i));
    }
  }

  /** Square loop where the first {@code hostileFractionPct}% of edges are
   * path/track and the rest are residential. */
  private static OsmTrack mixedSurfaceLoop(int side, int hostileFractionPct) {
    OsmTrack t = squareLoop(side);
    // 4 edges; flip k of them to "hostile" based on percentage
    int hostileEdges = (4 * hostileFractionPct) / 100;
    for (int i = 1; i < t.nodes.size(); i++) {
      MessageData m = (i <= hostileEdges)
        ? msgWayTags("highway=path")
        : msgCostfactor(1.5f, "highway=residential");
      t.nodes.get(i).message = m;
    }
    return t;
  }

  /**
   * Chunked clean square loop with {@code hostileEdges} edges tagged
   * profile-hostile (highway=path surface=ground) and {@code suspectEdges} edges
   * carrying {@code suspectMsg}, scattered (every 3rd edge) so no contiguous
   * hostile run approaches the 1500m sub-cap. All other edges are clean
   * residential. Equal-length chunks, so edge counts map directly to distance
   * fractions. Used to exercise the combined and per-bucket surface ceilings.
   */
  private static OsmTrack loopWithScatteredKinds(int totalMeters, int hostileEdges,
                                                 int suspectEdges, MessageData suspectMsg) {
    OsmTrack t = trackWithContiguousHostile(totalMeters, 0, 0); // all-clean chunked loop
    int h = 0;
    int s = 0;
    for (int i = 1; i < t.nodes.size() && (h < hostileEdges || s < suspectEdges); i++) {
      int mod = i % 3;
      if (mod == 1 && h < hostileEdges) {
        t.nodes.get(i).message = msgWayTags("highway=path surface=ground");
        h++;
      } else if (mod == 2 && s < suspectEdges) {
        t.nodes.get(i).message = clone(suspectMsg);
        s++;
      }
    }
    return t;
  }

  private static OsmPathElement makeNode(int x, int y) {
    // ~1 degree ≈ 111km at equator; use small offsets so distances are roughly
    // x and y in meters. CheapRuler is what does the actual scaling.
    return makeNodeRaw(180000000 + x * 14, 50000000 + y * 9);
  }

  private static OsmPathElement makeNodeRaw(int ilon, int ilat) {
    return OsmPathElement.create(ilon, ilat, (short) 0, null);
  }

  private static MessageData msgWayTags(String tags) {
    MessageData m = new MessageData();
    m.wayKeyValues = tags;
    m.costfactor = 1.5f;
    return m;
  }

  private static MessageData msgCostfactor(float cf, String tags) {
    MessageData m = new MessageData();
    m.wayKeyValues = tags;
    m.costfactor = cf;
    return m;
  }

  /** Missing-metadata placeholder: wayKeyValues=null treated as suspect. */
  private static MessageData msgNoMetadata() {
    MessageData m = new MessageData();
    m.wayKeyValues = null;
    m.costfactor = 0;
    return m;
  }

  private static MessageData clone(MessageData src) {
    MessageData m = new MessageData();
    m.wayKeyValues = src.wayKeyValues;
    m.costfactor = src.costfactor;
    return m;
  }

  // --- worstContiguousCostlyMetersForScorer (scorer-side divergence fix) ---

  /** Single-pass-style MessageData: costfactor set, wayKeyValues null
   *  (simulating a single-pass routed sub-track before retrackForDetail). */
  private static MessageData msgSinglePass(float costfactor) {
    MessageData m = new MessageData();
    m.wayKeyValues = null;
    m.costfactor = costfactor;
    return m;
  }

  /** Actual CheapRuler distance between adjacent makeNode(*100, 0) nodes,
   *  measured once so tests don't have to assume metric scaling. The
   *  helper's "~1m per unit" comment is an approximation; at the encoded
   *  latitude (ilat=50000000 → -40°) each unit is ~1.19m. */
  private static final double ONE_HUNDRED_UNITS_METERS =
    makeNode(0, 0).calcDistance(makeNode(100, 0));

  @Test
  public void worstContiguousCostlyMeters_emptyTrack_returnsZero() {
    OsmTrack t = new OsmTrack();
    t.nodes = new ArrayList<>();
    assertEquals(0,
      RoundTripQualityGate.worstContiguousMetersAboveCostfactor(t, 3.0));
  }

  @Test
  public void worstContiguousCostlyMeters_allLowCost_returnsZero() {
    OsmTrack t = new OsmTrack();
    t.nodes = new ArrayList<>();
    t.nodes.add(makeNode(0, 0));
    for (int i = 1; i <= 10; i++) {
      OsmPathElement n = makeNode(i * 100, 0);
      n.message = msgSinglePass(1.5f);
      t.nodes.add(n);
    }
    assertEquals(0,
      RoundTripQualityGate.worstContiguousMetersAboveCostfactor(t, 3.0));
  }

  @Test
  public void worstContiguousCostlyMeters_singlePassTrack_detectsCostStretch() {
    // The smoking-gun test: a single-pass track (wayKeyValues all null)
    // has 5 contiguous high-costfactor edges. The legacy
    // worstContiguousHostileMetersPaved would return 0 because the null
    // wayKeyValues check breaks the run before costfactor is even
    // considered. The scorer-side method must detect the stretch.
    OsmTrack t = new OsmTrack();
    t.nodes = new ArrayList<>();
    t.nodes.add(makeNode(0, 0));
    for (int i = 1; i <= 10; i++) {
      OsmPathElement n = makeNode(i * 100, 0);
      n.message = msgSinglePass((i >= 3 && i <= 7) ? 5.0f : 1.0f);
      t.nodes.add(n);
    }
    int worst = RoundTripQualityGate.worstContiguousMetersAboveCostfactor(t, 3.0);
    double expected = 5 * ONE_HUNDRED_UNITS_METERS;
    assertEquals("5 contiguous high-cost edges", expected, worst, 5.0);

    // Confirm the legacy gate-side check returns 0 on this same single-pass
    // input (the bug being fixed).
    assertEquals("legacy check returns 0 when wayKeyValues is null",
      0, RoundTripQualityGate.worstContiguousHostileMetersPaved(t));
  }

  @Test
  public void worstContiguousCostlyMeters_brokenByLowCostEdge() {
    // Two separate high-cost runs of 3 and 5 edges; longest is reported.
    OsmTrack t = new OsmTrack();
    t.nodes = new ArrayList<>();
    t.nodes.add(makeNode(0, 0));
    int[] pattern = {5, 5, 5, 1, 5, 5, 5, 5, 5, 1};
    for (int i = 0; i < pattern.length; i++) {
      OsmPathElement n = makeNode((i + 1) * 100, 0);
      n.message = msgSinglePass((float) pattern[i]);
      t.nodes.add(n);
    }
    int worst = RoundTripQualityGate.worstContiguousMetersAboveCostfactor(t, 3.0);
    double expected = 5 * ONE_HUNDRED_UNITS_METERS;
    assertEquals("longest run is 5 contiguous high-cost edges", expected, worst, 5.0);
  }

  @Test
  public void worstContiguousCostlyMeters_nullMessageDataBreaksRun() {
    // Edges with null MessageData (no edge data at all) must break the run,
    // since we cannot prove they are costly. This matches the legacy
    // worstHostileStretchPaved behavior for that specific case.
    OsmTrack t = new OsmTrack();
    t.nodes = new ArrayList<>();
    t.nodes.add(makeNode(0, 0));
    for (int i = 1; i <= 7; i++) {
      OsmPathElement n = makeNode(i * 100, 0);
      if (i == 4) {
        n.message = null;
      } else {
        n.message = msgSinglePass(5.0f);
      }
      t.nodes.add(n);
    }
    int worst = RoundTripQualityGate.worstContiguousMetersAboveCostfactor(t, 3.0);
    double expected = 3 * ONE_HUNDRED_UNITS_METERS;
    assertEquals("3 high-cost then null then 3 high-cost = 3-edge run best",
      expected, worst, 5.0);
  }

  @Test
  public void worstContiguousCostlyMeters_thresholdGovernsDetection() {
    // A track of all edges at costfactor=3.5. With threshold=3.0, they all
    // count. With threshold=4.0, none count.
    OsmTrack t = new OsmTrack();
    t.nodes = new ArrayList<>();
    t.nodes.add(makeNode(0, 0));
    for (int i = 1; i <= 5; i++) {
      OsmPathElement n = makeNode(i * 100, 0);
      n.message = msgSinglePass(3.5f);
      t.nodes.add(n);
    }
    double expected = 5 * ONE_HUNDRED_UNITS_METERS;
    assertEquals("threshold 3.0 catches costfactor 3.5", expected,
      RoundTripQualityGate.worstContiguousMetersAboveCostfactor(t, 3.0), 5.0);
    assertEquals("threshold 4.0 misses costfactor 3.5",
      0, RoundTripQualityGate.worstContiguousMetersAboveCostfactor(t, 4.0));
  }

  @Test
  public void worstContiguousCostlyMetersForScorer_usesDefaultThreshold() {
    // Public wrapper should match the package-private overload at the
    // configured threshold.
    OsmTrack t = new OsmTrack();
    t.nodes = new ArrayList<>();
    t.nodes.add(makeNode(0, 0));
    for (int i = 1; i <= 5; i++) {
      OsmPathElement n = makeNode(i * 100, 0);
      n.message = msgSinglePass(3.5f);
      t.nodes.add(n);
    }
    assertEquals(
      RoundTripQualityGate.worstContiguousMetersAboveCostfactor(
        t, RoundTripQualityGate.SCORER_HOSTILE_COSTFACTOR_THRESHOLD),
      RoundTripQualityGate.worstContiguousCostlyMetersForScorer(t));
  }

  // ======================================================================
  // Hairpin-turn chaos check (countHairpinTurns / MAX_HAIRPIN_TURNS)
  //
  // The whole hairpin branch of checkShapeChaos had zero coverage. These
  // tests pin its three thresholds (count cap, 130° reversal angle, 25m
  // jitter floor) with real-geometry tracks, plus the gate boundary at
  // MAX_HAIRPIN_TURNS. Geometry is verified against the public counter so a
  // generator mistake fails loudly rather than silently miscounting.
  // ======================================================================

  @Test
  public void countHairpinTurns_shortTrackReturnsZero() {
    // < 3 nodes: there is no interior vertex to turn at.
    OsmTrack t = new OsmTrack();
    t.nodes = new ArrayList<>();
    t.nodes.add(makeNode(0, 0));
    t.nodes.add(makeNode(1000, 0));
    assertEquals(0, RoundTripQualityGate.countHairpinTurns(t));
  }

  @Test
  public void countHairpinTurns_gentleTurnBelowThresholdNotCounted() {
    // A 120° turn is below the 130° reversal threshold → not a hairpin.
    assertEquals(0, RoundTripQualityGate.countHairpinTurns(hairpinTriple(120, 1000)));
  }

  @Test
  public void countHairpinTurns_sharpReversalAboveThresholdCounted() {
    // A 140° turn is above the 130° threshold → a hairpin.
    assertEquals(1, RoundTripQualityGate.countHairpinTurns(hairpinTriple(140, 1000)));
  }

  @Test
  public void countHairpinTurns_skipsSubMinSegmentJitter() {
    // A near-180° reversal whose legs are below MIN_HAIRPIN_SEGMENT_METERS
    // (25m) is digitization jitter, not a real U-turn, and must be ignored.
    // The same reversal with real-length legs IS counted — proving the skip
    // is the only difference.
    assertEquals("sub-25m reversal is jitter, not a hairpin",
      0, RoundTripQualityGate.countHairpinTurns(hairpinTriple(175, 10)));
    assertEquals("same reversal with real-length legs is a hairpin",
      1, RoundTripQualityGate.countHairpinTurns(hairpinTriple(175, 1000)));
  }

  @Test
  public void countHairpinTurns_countsEachReversalInSerpentine() {
    // An open serpentine with T teeth has exactly T-1 interior reversals.
    assertEquals(5, RoundTripQualityGate.countHairpinTurns(openSerpentine(6)));
    assertEquals(20, RoundTripQualityGate.countHairpinTurns(openSerpentine(21)));
  }

  @Test
  public void gate_hairpinsAtMaxAreNotChaosRejected() {
    // Exactly MAX_HAIRPIN_TURNS (20): '>' not '>=', so it must NOT be a
    // hairpin rejection. (The loop is otherwise clean and should pass.)
    OsmTrack t = closedSerpentine(21); // 21 teeth → 20 reversals
    assertEquals("fixture sanity", 20, RoundTripQualityGate.countHairpinTurns(t));
    String reason = RoundTripQualityGate.validate(t, t.distance, "gravel");
    assertFalse("20 hairpins (== MAX) must not be a hairpin rejection: " + reason,
      reason != null && reason.contains("hairpin"));
  }

  @Test
  public void gate_hairpinsAboveMaxRejectedAsChaotic() {
    // One more reversal (21 > MAX 20) trips the chaos gate.
    OsmTrack t = closedSerpentine(22); // 22 teeth → 21 reversals
    assertEquals("fixture sanity", 21, RoundTripQualityGate.countHairpinTurns(t));
    String reason = RoundTripQualityGate.validate(t, t.distance, "gravel");
    assertNotNull(reason);
    assertTrue("21 hairpins (> MAX) must reject as chaotic: " + reason,
      reason.contains("hairpin"));
  }

  // ======================================================================
  // Self-intersection chaos boundary (MAX_SELF_INTERSECTIONS = 5)
  // ======================================================================

  @Test
  public void countSelfIntersections_exactCountAtAndAboveCap() {
    // K independent bow-tie crossings → exactly K self-intersections.
    assertEquals(5, RoundTripQualityGate.countSelfIntersections(openBowties(5)));
    assertEquals(6, RoundTripQualityGate.countSelfIntersections(openBowties(6)));
  }

  @Test
  public void gate_aboveMaxSelfIntersectionsRejectedAsChaotic() {
    // 6 > MAX_SELF_INTERSECTIONS (5): the self-intersection branch of the
    // chaos gate fires first (before hairpins / hostility), so this rejects
    // with the self-intersections reason regardless of profile.
    OsmTrack t = closedBowties(6);
    assertEquals("fixture sanity", 6, RoundTripQualityGate.countSelfIntersections(t));
    String reason = RoundTripQualityGate.validate(t, t.distance, "gravel");
    assertNotNull(reason);
    assertTrue("6 self-intersections (> MAX 5) must reject: " + reason,
      reason.contains("self-intersections"));
  }

  @Test
  public void gate_crossingExplosionIsStructural_moderateChaosStaysQuality() {
    // Load-robustness tiering: > 2x MAX_SELF_INTERSECTIONS is the exhausted
    // planner's weave residue (Nice 100km gravel shipped 42-57 crossings
    // under CPU contention) - STRUCTURAL, never lenient-shipped. Moderate
    // chaos (cap < x <= 2x cap) stays QUALITY (lenient ships with Warning).
    OsmTrack moderate = closedBowties(6);
    RoundTripQualityResult qm = RoundTripQualityGate.evaluate(
      moderate, moderate.distance, "gravel", false, false, false);
    assertFalse(qm.isAccepted());
    assertEquals(RoundTripQualityResult.RejectionTier.QUALITY, qm.getRejectionTier());

    OsmTrack explosion = closedBowties(11); // 11 > 2 x 5
    RoundTripQualityResult qe = RoundTripQualityGate.evaluate(
      explosion, explosion.distance, "gravel", false, false, false);
    assertFalse(qe.isAccepted());
    assertEquals(RoundTripQualityResult.RejectionTier.STRUCTURAL, qe.getRejectionTier());
  }

  // ======================================================================
  // Node-shared crossings (the CCW scan's blind spot)
  // ======================================================================

  /** Track passing twice through node P=(0,0); the second pass enters from
   *  {@code in2} and leaves to {@code out2} (coordinates in makeNode units).
   *  Lead-in/lead-out keep P outside the CROSSING_START_END_EXEMPT_M home
   *  zone (the expected leave-and-return weave near route start/end is
   *  deliberately not counted), so these tests exercise pure transversality. */
  private static OsmTrack twoPassesThroughNode(int in2x, int in2y, int out2x, int out2y) {
    OsmTrack t = new OsmTrack();
    t.nodes = new ArrayList<>();
    t.nodes.add(makeNode(-1200, 0));
    t.nodes.add(makeNode(-700, 0));
    t.nodes.add(makeNode(-200, 0));
    t.nodes.add(makeNode(0, 0));      // P, pass 1: west → east
    t.nodes.add(makeNode(200, 0));
    t.nodes.add(makeNode(in2x, in2y));
    t.nodes.add(makeNode(0, 0));      // P again
    t.nodes.add(makeNode(out2x, out2y));
    // Lead-out extends radially along the exit direction so it cannot cross
    // the lead-in regardless of which exit the test variant chooses.
    t.nodes.add(makeNode(2 * out2x, 2 * out2y));
    t.nodes.add(makeNode(3 * out2x, 3 * out2y));
    t.nodes.add(makeNode(4 * out2x, 4 * out2y));
    return t;
  }

  @Test
  public void countsTransverseCrossingAtSharedJunctionNode() {
    // Pass 2 enters from the NE and exits to the S: the four directions
    // interleave — a genuine X through the junction. The CCW scan alone
    // counts 0 here (shared endpoint); the cyclist sees a knot.
    OsmTrack t = twoPassesThroughNode(200, 200, 0, -200);
    assertEquals("transverse pass through a shared node is a crossing",
      1, RoundTripQualityGate.countSelfIntersections(t));
  }

  @Test
  public void touchAndTurnAtSharedNodeIsNotACrossing() {
    // Pass 2 enters from the NE and exits to the NW: both directions on the
    // same side of pass 1 — a teardrop pinch (near-revisit territory), not a
    // crossing.
    OsmTrack t = twoPassesThroughNode(200, 200, -200, 200);
    assertEquals("tangential touch is not a crossing",
      0, RoundTripQualityGate.countSelfIntersections(t));
  }

  @Test
  public void sameEdgeRetraceIsNotACrossing() {
    // Out-and-back over the same edge: shared neighbor → reuse, not crossing.
    OsmTrack t = new OsmTrack();
    t.nodes = new ArrayList<>();
    t.nodes.add(makeNode(-200, 0));
    t.nodes.add(makeNode(0, 0));
    t.nodes.add(makeNode(200, 0));
    t.nodes.add(makeNode(0, 0));
    t.nodes.add(makeNode(-200, 0));
    assertEquals("same-edge retrace is reuse, not a crossing",
      0, RoundTripQualityGate.countSelfIntersections(t));
  }

  // ======================================================================
  // Shared-corridor crossings (DARK — countCorridorCrossings, not yet in
  // countSelfIntersections; see the section comment in the gate)
  // ======================================================================

  /**
   * Track riding a horizontal shared run twice (default 200m: 0→100→200, under
   * {@link RoundTripQualityGate#MAX_CORRIDOR_CROSS_M}). Pass 1 enters from
   * {@code in1} and exits to {@code out1}; pass 2 enters from {@code in2},
   * rides the run in the SAME direction, and exits to {@code out2}. Lead-in/out
   * keep the run outside the home zone (mirrors {@link #twoPassesThroughNode});
   * the connector between the passes loops far north so it cannot cross
   * anything itself.
   */
  private static OsmTrack twoPassesThroughCorridor(int in1x, int in1y, int out1x, int out1y,
                                                   int in2x, int in2y, int out2x, int out2y) {
    return twoPassesThroughCorridor(in1x, in1y, out1x, out1y, in2x, in2y, out2x, out2y, 200);
  }

  /** As above, with an explicit shared-run length {@code runEndX} (m) so the
   *  MAX_CORRIDOR_CROSS_M length bound can be exercised. Run is 0 → runEndX/2 →
   *  runEndX; attachments hang off the two ends. */
  private static OsmTrack twoPassesThroughCorridor(int in1x, int in1y, int out1x, int out1y,
                                                   int in2x, int in2y, int out2x, int out2y,
                                                   int runEndX) {
    int mid = runEndX / 2;
    OsmTrack t = new OsmTrack();
    t.nodes = new ArrayList<>();
    t.nodes.add(makeNode(in1x * 4, in1y * 4));
    t.nodes.add(makeNode(in1x * 3, in1y * 3));
    t.nodes.add(makeNode(in1x * 2, in1y * 2));
    t.nodes.add(makeNode(in1x, in1y));
    t.nodes.add(makeNode(0, 0));            // run start, pass 1
    t.nodes.add(makeNode(mid, 0));
    t.nodes.add(makeNode(runEndX, 0));      // run end, pass 1
    t.nodes.add(makeNode(runEndX + out1x, out1y));
    // connector: far north, well clear of the run and all attachments
    t.nodes.add(makeNode(runEndX + out1x, 5000));
    t.nodes.add(makeNode(in2x, 5000));
    t.nodes.add(makeNode(in2x, in2y));
    t.nodes.add(makeNode(0, 0));            // run start, pass 2 (same direction)
    t.nodes.add(makeNode(mid, 0));
    t.nodes.add(makeNode(runEndX, 0));      // run end, pass 2
    t.nodes.add(makeNode(runEndX + out2x, out2y));
    t.nodes.add(makeNode(runEndX + out2x * 2, out2y * 2));
    t.nodes.add(makeNode(runEndX + out2x * 3, out2y * 3));
    t.nodes.add(makeNode(runEndX + out2x * 4, out2y * 4));
    return t;
  }

  @Test
  public void corridorCrossing_sideSwapThroughSharedRunCounts() {
    // Pass 1: in from SW, out to E. Pass 2: in from NW, out to S — pass 2
    // enters north of pass 1's path and leaves south of it: one crossing
    // smeared along the shared run (the Rond-Point de la Contamine shape).
    OsmTrack t = twoPassesThroughCorridor(-200, -200, 200, 0, -200, 200, 0, -300);
    assertEquals("side swap through a shared run is a crossing",
      1, RoundTripQualityGate.countCorridorCrossings(t.nodes));
    // The corridor count is now wired into countSelfIntersections, so the gate
    // sees this knot too (the segment/per-node scans alone are blind to it
    // because every run node shares an incident edge).
    assertEquals("countSelfIntersections now includes the corridor crossing",
      1, RoundTripQualityGate.countSelfIntersections(t));
  }

  @Test
  public void corridorCrossing_sameSideExitDoesNotCount() {
    // Pass 2 enters from the NW and exits to the N: both attachments on the
    // same side of pass 1's path — riding the same stretch twice without
    // crossing it (same-direction reuse, not a figure-eight).
    OsmTrack t = twoPassesThroughCorridor(-200, -200, 200, 0, -200, 200, 0, 300);
    assertEquals("same-side exit is reuse, not a crossing",
      0, RoundTripQualityGate.countCorridorCrossings(t.nodes));
  }

  @Test
  public void corridorCrossing_oppositeDirectionRetraceNeverCounts() {
    // Out along the run, back along the run in the OPPOSITE direction (a
    // two-way road retrace, the Route de Clermont shape): never a crossing
    // here, even though the external attachments interleave by parity —
    // that defect class belongs to reuse/CorridorOverlapIndex.
    OsmTrack t = new OsmTrack();
    t.nodes = new ArrayList<>();
    t.nodes.add(makeNode(-800, -200));
    t.nodes.add(makeNode(-400, -100));
    t.nodes.add(makeNode(-200, -200));      // in from S
    t.nodes.add(makeNode(0, 0));            // run start, pass 1
    t.nodes.add(makeNode(300, 0));
    t.nodes.add(makeNode(600, 0));          // run end, pass 1
    t.nodes.add(makeNode(800, 200));        // lobe to the N
    t.nodes.add(makeNode(700, 400));
    t.nodes.add(makeNode(600, 0));          // run end, pass 2 (reversed)
    t.nodes.add(makeNode(300, 0));
    t.nodes.add(makeNode(0, 0));            // run start, pass 2
    t.nodes.add(makeNode(-200, -300));      // out to S — interleaved by parity
    t.nodes.add(makeNode(-400, -500));
    t.nodes.add(makeNode(-600, -700));
    assertEquals("opposite-direction retrace is never a corridor crossing",
      0, RoundTripQualityGate.countCorridorCrossings(t.nodes));
  }

  @Test
  public void corridorCrossing_sharedApproachEdgeExtendsRunIntoReuseLength() {
    // Pass 2 shares pass 1's approach NODE, so the grouped run EXTENDS back over
    // that shared approach (the verdict is taken at the extended ends, not via
    // the shared-edge guard). With a realistic home-zone-clearing lead-in, that
    // extension makes the total shared overlap exceed MAX_CORRIDOR_CROSS_M — so
    // it reads as long road reuse, not a knot, and is not counted. (A clean
    // sub-bound side-swap that DOES count is corridorCrossing_sideSwap...; the
    // bound itself is corridorCrossing_longSharedRun....)
    OsmTrack t = twoPassesThroughCorridor(-200, -200, 200, 0, -200, -200, 0, -300);
    assertEquals("shared approach + run is a long overlap → reuse, not counted",
      0, RoundTripQualityGate.countCorridorCrossings(t.nodes));
  }

  @Test
  public void corridorCrossing_singleNodeRevisitIsNotACorridor() {
    // A single shared node (no shared edge) stays the per-node detector's
    // domain: countCorridorCrossings must not double-count it.
    OsmTrack t = twoPassesThroughNode(200, 200, 0, -200);
    assertEquals("single-node revisit is not a corridor",
      0, RoundTripQualityGate.countCorridorCrossings(t.nodes));
    assertEquals("per-node detector still owns the single-node X",
      1, RoundTripQualityGate.countSelfIntersections(t));
  }

  @Test
  public void corridorCrossing_longSharedRunIsReuseNotCrossing() {
    // Same side-swap geometry as the counting test, but the shared run is long
    // (>MAX_CORRIDOR_CROSS_M). Per the labeling pass, a multi-hundred-metre
    // same-direction overlap reads as road reuse (priced by reuse%), not a
    // knot — so it must NOT count, even though it transversally crosses.
    OsmTrack shortRun = twoPassesThroughCorridor(-200, -200, 200, 0, -200, 200, 0, -300, 200);
    assertEquals("200m side-swap is within the bound — counts",
      1, RoundTripQualityGate.countCorridorCrossings(shortRun.nodes));
    OsmTrack longRun = twoPassesThroughCorridor(-200, -200, 200, 0, -200, 200, 0, -300, 600);
    assertEquals("600m side-swap exceeds MAX_CORRIDOR_CROSS_M — reuse, not counted",
      0, RoundTripQualityGate.countCorridorCrossings(longRun.nodes));
  }

  // ======================================================================
  // Closure-gap boundary (MAX_CLOSURE_METERS = 400)
  // ======================================================================

  @Test
  public void gate_closureJustUnderCapAccepted() {
    OsmTrack t = squareLoopWithClosureGap(2500, 350);
    int closure = t.nodes.get(0).calcDistance(t.nodes.get(t.nodes.size() - 1));
    assertTrue("fixture closure should be < 400m, got " + closure, closure < 400);
    assertNull("closure under cap must pass",
      RoundTripQualityGate.validate(t, t.distance, "fastbike"));
  }

  @Test
  public void gate_closureJustOverCapRejected() {
    OsmTrack t = squareLoopWithClosureGap(2500, 450);
    int closure = t.nodes.get(0).calcDistance(t.nodes.get(t.nodes.size() - 1));
    assertTrue("fixture closure should be > 400m, got " + closure, closure > 400);
    String reason = RoundTripQualityGate.validate(t, t.distance, "fastbike");
    assertNotNull(reason);
    assertTrue("closure over cap must reject: " + reason, reason.contains("closure"));
  }

  // ======================================================================
  // Node-count boundary (MIN_NODES = 4)
  // ======================================================================

  @Test
  public void gate_threeNodesRejectedAsTooFew() {
    OsmTrack t = new OsmTrack();
    t.nodes = new ArrayList<>();
    t.nodes.add(makeNode(0, 0));
    t.nodes.add(makeNode(1500, 0));
    t.nodes.add(makeNode(0, 0));
    recomputeDistance(t);
    String reason = RoundTripQualityGate.validate(t, t.distance, "fastbike");
    assertNotNull(reason);
    assertTrue("3 nodes (< MIN_NODES 4) is too few: " + reason,
      reason.contains("too few nodes"));
  }

  @Test
  public void gate_fourNodeLoopPassesNodeCountCheck() {
    // n == MIN_NODES (4): a clean triangle loop must clear the count gate.
    OsmTrack t = triangleLoop();
    assertEquals("fixture sanity", 4, t.nodes.size());
    String reason = RoundTripQualityGate.validate(t, t.distance, "fastbike");
    assertFalse("4-node loop must pass the node-count check: " + reason,
      reason != null && reason.contains("too few nodes"));
  }

  // ======================================================================
  // Distance-ratio just-outside boundaries (MIN=0.5, MAX=1.8)
  // (acceptsAtExactRatioBoundary already pins the at-boundary accept side;
  //  these pin the strict '<' / '>' reject side.)
  // ======================================================================

  @Test
  public void gate_ratioJustBelowMinRejected() {
    OsmTrack t = squareLoop(2500);
    String reason = RoundTripQualityGate.validate(t, t.distance / 0.49, "fastbike");
    assertNotNull(reason);
    assertTrue("ratio 0.49 (< MIN 0.5) must reject: " + reason, reason.contains("ratio"));
  }

  @Test
  public void gate_ratioJustAboveMaxRejected() {
    OsmTrack t = squareLoop(2500);
    String reason = RoundTripQualityGate.validate(t, t.distance / 1.81, "fastbike");
    assertNotNull(reason);
    assertTrue("ratio 1.81 (> MAX 1.8) must reject: " + reason, reason.contains("ratio"));
  }

  @Test
  public void gate_ratioJustInsideBandAccepted() {
    OsmTrack t = squareLoop(2500);
    assertNull("ratio 0.51 (> MIN) must pass",
      RoundTripQualityGate.validate(t, t.distance / 0.51, "fastbike"));
    assertNull("ratio 1.79 (< MAX) must pass",
      RoundTripQualityGate.validate(t, t.distance / 1.79, "fastbike"));
  }

  // ======================================================================
  // Contiguous-hostile boundary (MAX_CONTIGUOUS_HOSTILE_METERS = 1500)
  //
  // Chunk lengths scale ~1.19× at the encoded latitude, so we assert against
  // the *measured* worst stretch rather than the nominal request, then check
  // the gate decision is consistent with that measurement straddling the cap.
  // ======================================================================

  @Test
  public void gate_contiguousHostileStraddlesCap() {
    OsmTrack under = trackWithContiguousHostile(40000, 1100, 0);
    OsmTrack over = trackWithContiguousHostile(40000, 1400, 0);
    int wu = RoundTripQualityGate.worstContiguousHostileMetersPaved(under);
    int wo = RoundTripQualityGate.worstContiguousHostileMetersPaved(over);
    assertTrue("under-fixture must measure <= cap, got " + wu,
      wu <= RoundTripQualityGate.MAX_CONTIGUOUS_HOSTILE_METERS);
    assertTrue("over-fixture must measure > cap, got " + wo,
      wo > RoundTripQualityGate.MAX_CONTIGUOUS_HOSTILE_METERS);

    String ru = RoundTripQualityGate.validate(under, under.distance, "fastbike");
    assertTrue("measured-under-cap must not be a contiguous rejection: " + ru,
      ru == null || !ru.startsWith("contiguous"));
    String ro = RoundTripQualityGate.validate(over, over.distance, "fastbike");
    assertNotNull(ro);
    assertTrue("measured-over-cap must reject as contiguous: " + ro,
      ro.startsWith("contiguous"));
  }

  // ---- boundary-test geometry helpers -------------------------------------

  /** Meters per {@code makeNode} x-unit and y-unit at the encoded latitude,
   *  measured once so metric fixtures don't have to assume the scaling. */
  private static final double X_UNIT_M =
    makeNode(0, 0).calcDistance(makeNode(1000, 0)) / 1000.0;
  private static final double Y_UNIT_M =
    makeNode(0, 0).calcDistance(makeNode(0, 1000)) / 1000.0;

  /** A node placed at an approximate metric offset (meters) from the origin. */
  private static OsmPathElement metricNode(double mx, double my) {
    return makeNode((int) Math.round(mx / X_UNIT_M), (int) Math.round(my / Y_UNIT_M));
  }

  private static void recomputeDistance(OsmTrack t) {
    int d = 0;
    for (int i = 1; i < t.nodes.size(); i++) {
      d += t.nodes.get(i - 1).calcDistance(t.nodes.get(i));
    }
    t.distance = d;
  }

  /**
   * Three nodes forming a single turn of {@code turnDeg} between two legs of
   * {@code legMeters}. The turn the gate measures equals {@code turnDeg}: the
   * second leg's metric direction is rotated {@code turnDeg} off the first
   * leg's heading, and bearings rotate identically with metric directions.
   */
  private static OsmTrack hairpinTriple(double turnDeg, double legMeters) {
    double r = Math.toRadians(turnDeg);
    OsmTrack t = new OsmTrack();
    t.nodes = new ArrayList<>();
    t.nodes.add(metricNode(0, 0));
    t.nodes.add(metricNode(legMeters, 0));
    t.nodes.add(metricNode(legMeters + legMeters * Math.cos(r), legMeters * Math.sin(r)));
    recomputeDistance(t);
    return t;
  }

  /**
   * Open switchback serpentine: {@code teeth+1} points alternating between
   * x=0 and x=W while climbing in y. Each interior vertex is a ~173° reversal
   * (well above the 130° threshold); each segment is hundreds of meters (well
   * above the 25m floor). Adjacent segments occupy disjoint y-bands, so the
   * comb has zero self-intersections. Result: exactly {@code teeth-1} hairpins.
   */
  private static OsmTrack openSerpentine(int teeth) {
    final int W = 800, d = 60;
    OsmTrack t = new OsmTrack();
    t.nodes = new ArrayList<>();
    for (int k = 0; k <= teeth; k++) {
      int x = (k % 2 == 1) ? W : 0;
      t.nodes.add(makeNode(x, k * d));
    }
    recomputeDistance(t);
    return t;
  }

  /**
   * The serpentine closed into a loop by running around the outside (the side
   * the last tooth exits toward, so the top vertex stays a straight ~0° pass,
   * not an extra hairpin) and back to the origin. Closure ≈ 0, zero
   * self-intersections, hairpins = {@code teeth-1}. Edges carry clean
   * residential metadata so the loop is paved-profile-clean.
   */
  private static OsmTrack closedSerpentine(int teeth) {
    final int W = 800, d = 60, M = 1200;
    OsmTrack t = openSerpentine(teeth);
    int topY = teeth * d;
    boolean exitsRight = (teeth % 2 == 1); // last tooth ends at x=W heading east
    int sideX = exitsRight ? (W + M) : (-M);
    t.nodes.add(makeNode(sideX, topY));
    t.nodes.add(makeNode(sideX, 0));
    t.nodes.add(makeNode(0, 0)); // close
    for (int i = 1; i < t.nodes.size(); i++) {
      t.nodes.get(i).message = msgCostfactor(1.5f, "highway=residential surface=asphalt");
    }
    recomputeDistance(t);
    return t;
  }

  /**
   * {@code crossings} independent bow-tie X's stacked in disjoint y-bands.
   * Each band's diagonals cross exactly once; the vertical inter-band
   * connectors (at x=0) add none. Open track → exactly {@code crossings}
   * self-intersections.
   */
  private static OsmTrack openBowties(int crossings) {
    final int W = 500, H = 200, B = 400; // B > H keeps connectors clear of the X
    OsmTrack t = new OsmTrack();
    t.nodes = new ArrayList<>();
    for (int k = 0; k < crossings; k++) {
      int yb = k * B;
      t.nodes.add(makeNode(0, yb));        // P0
      t.nodes.add(makeNode(W, yb + H));    // P1
      t.nodes.add(makeNode(W, yb));        // P2
      t.nodes.add(makeNode(0, yb + H));    // P3 (→ next P0 is the connector)
    }
    recomputeDistance(t);
    return t;
  }

  /** The bow-tie stack closed back to the origin around the left side
   *  (x &lt; 0, clear of the X bodies). Closure ≈ 0, crossings preserved. */
  private static OsmTrack closedBowties(int crossings) {
    final int H = 200, B = 400, M = 900;
    OsmTrack t = openBowties(crossings);
    int lastY = (crossings - 1) * B + H;
    t.nodes.add(makeNode(-M, lastY));
    t.nodes.add(makeNode(-M, 0));
    t.nodes.add(makeNode(0, 0)); // close
    recomputeDistance(t);
    return t;
  }

  /** Clean square loop whose closing node is moved {@code gapMeters} east of
   *  the start, leaving a measurable closure gap. Distance is recomputed so
   *  the ratio check still passes when desired == actual. */
  private static OsmTrack squareLoopWithClosureGap(int side, double gapMeters) {
    OsmTrack t = squareLoop(side);
    OsmPathElement close = metricNode(gapMeters, 0);
    close.message = msgCostfactor(1.5f, "highway=residential surface=asphalt");
    t.nodes.set(t.nodes.size() - 1, close);
    recomputeDistance(t);
    return t;
  }

  /** Near-equilateral triangle loop (4 nodes incl. close), clean residential
   *  surface. Corner turns are ~120° (below the hairpin threshold). */
  private static OsmTrack triangleLoop() {
    OsmTrack t = new OsmTrack();
    t.nodes = new ArrayList<>();
    t.nodes.add(makeNode(0, 0));
    t.nodes.add(metricNode(3000, 0));
    t.nodes.add(metricNode(1500, 2600));
    t.nodes.add(makeNode(0, 0));
    for (int i = 1; i < t.nodes.size(); i++) {
      t.nodes.get(i).message = msgCostfactor(1.5f, "highway=residential surface=asphalt");
    }
    recomputeDistance(t);
    return t;
  }
}
