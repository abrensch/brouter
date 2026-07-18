package btools.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import btools.mapaccess.MatchedWaypoint;
import btools.router.roundtrip.RoundTripRuntimeHints;

/**
 * Contracts of the engine seam the round-trip subsystem runs on:
 * {@link RoundTripRuntimeHints} publication (the single write into the
 * engine's search-loop fields), {@code findTrackTimed}'s save/restore of the
 * engine timing fields (including the exception path), and doRoundTrip's
 * track-XOR-error publication.
 */
public class RoundTripSeamContractTest {

  private static RoutingEngine dummyEngine() {
    List<OsmNodeNamed> wps = new ArrayList<>();
    OsmNodeNamed start = new OsmNodeNamed();
    start.ilon = 188720000;
    start.ilat = 140000000;
    start.name = "from";
    wps.add(start);
    RoutingContext rc = new RoutingContext();
    rc.localFunction = RoundTripFixture.profileFile("trekking").getAbsolutePath();
    return new RoutingEngine(null, null, RoundTripFixture.segmentDir(), wps, rc,
      RoutingEngine.BROUTER_ENGINEMODE_ROUNDTRIP);
  }

  // ---- RoundTripRuntimeHints publication ----------------------------------

  @Test
  public void runtimeHintsPublicationUnpacksEveryField() {
    RoutingEngine re = dummyEngine();
    OsmTrack leg = new OsmTrack();
    OsmTrack[] legs = {leg, null};
    RoundTripRuntimeHints hints = new RoundTripRuntimeHints(4800.0, 123_456L, true, legs);
    re.roundTripOps().setRoundTripRuntimeHints(hints);

    assertEquals("searchRadius reaches the matching hooks", 4800.0, re.roundTripSearchRadius, 0.0);
    assertEquals("requestDeadline reaches the search loops", 123_456L, re.roundTripRequestDeadline);
    assertTrue("explicit-via flag reaches the engine", re.explicitViaRoundTrip);
    assertNotNull(re.greedyLegTracks);
    assertSame("leg tracks stay shared (guides, not copies)", leg, re.greedyLegTracks[0]);
  }

  @Test
  public void runtimeHintsSnapshotIsImmuneToCallerArrayReuse() {
    OsmTrack leg = new OsmTrack();
    OsmTrack[] legs = {leg};
    RoundTripRuntimeHints hints = new RoundTripRuntimeHints(0, 0, false, legs);
    legs[0] = null; // caller reuses its array after publishing
    assertNotSame("hints must have cloned the array", legs, hints.greedyLegTracks());
    assertSame("snapshot keeps the published leg", leg, hints.greedyLegTracks()[0]);
    // The accessor also copies on the way OUT: mutating a returned array must
    // not reach the snapshot (the class calls itself immutable).
    hints.greedyLegTracks()[0] = null;
    assertSame("snapshot survives mutation of a returned array", leg, hints.greedyLegTracks()[0]);
  }

  @Test
  public void runtimeHintsClearPublicationResetsTheEngine() {
    RoutingEngine re = dummyEngine();
    re.roundTripOps().setRoundTripRuntimeHints(
      new RoundTripRuntimeHints(4800.0, 123_456L, true, new OsmTrack[]{new OsmTrack()}));
    re.roundTripOps().setRoundTripRuntimeHints(
      new RoundTripRuntimeHints(0, 0, false, null));
    assertEquals(0.0, re.roundTripSearchRadius, 0.0);
    assertEquals(0L, re.roundTripRequestDeadline);
    assertFalse(re.explicitViaRoundTrip);
    assertNull(re.greedyLegTracks);
  }

  // ---- findTrackTimed save/restore -----------------------------------------

  @Test
  public void findTrackTimedRestoresTimingFieldsOnTheExceptionPath() {
    RoutingEngine re = dummyEngine();
    re.startTime = 777L;
    re.maxRunningTime = 42_000L;
    re.airDistanceCostFactor = 3.5;

    // Degenerate waypoints (no crosspoint, no cache) make the inner findTrack
    // throw — the seam's contract is that the engine timing fields are
    // restored in the finally regardless.
    MatchedWaypoint a = new MatchedWaypoint();
    MatchedWaypoint b = new MatchedWaypoint();
    boolean threw = false;
    try {
      re.roundTripOps().findTrackTimed("seam-contract", a, b, null, 1_000L);
    } catch (RuntimeException expected) {
      threw = true; // any failure shape is fine — the restore below is the contract
    }
    assertTrue("fixture must exercise the EXCEPTION path — a normal return only"
      + " proves the happy-path restore", threw);

    assertEquals("startTime restored", 777L, re.startTime);
    assertEquals("maxRunningTime restored", 42_000L, re.maxRunningTime);
    assertEquals("airDistanceCostFactor restored", 3.5, re.airDistanceCostFactor, 0.0);
  }

  // ---- doRoundTrip publication: track XOR error ----------------------------

  @Test
  public void successfulRoundTripPublishesTrackWithoutError() {
    RoutingEngine re = RoundTripFixture.engine("trekking", 90, 1000, rc -> { });
    RoundTripFixture.assertNoEngineErrorOrSkip(re, "seam-contract loop");
    assertNotNull("success must publish a track", re.getFoundTrack());
    assertNull("success must not carry an error", re.getErrorMessage());
  }

  @Test
  public void failedRoundTripPublishesErrorWithoutTrack() {
    // Start far outside the fixture tile: routing cannot begin, the request
    // must surface a clean error and a null track — never both, never neither
    // (the finally-publication contract; a silent null/null pair is the
    // false-green shape the fixture assertions now reject).
    List<OsmNodeNamed> wps = new ArrayList<>();
    OsmNodeNamed start = new OsmNodeNamed();
    start.name = "from";
    start.ilon = 100000000; // 80W — no fixture data
    start.ilat = 140000000;
    wps.add(start);
    RoutingContext rc = new RoutingContext();
    rc.localFunction = RoundTripFixture.profileFile("trekking").getAbsolutePath();
    rc.roundTripDistance = 1000;
    RoutingEngine re = new RoutingEngine(null, null, RoundTripFixture.segmentDir(), wps, rc,
      RoutingEngine.BROUTER_ENGINEMODE_ROUNDTRIP);
    re.quite = true;
    re.doRun(0);

    assertNull("failure must not publish a track", re.getFoundTrack());
    assertNotNull("failure must carry an error message", re.getErrorMessage());
  }
}
