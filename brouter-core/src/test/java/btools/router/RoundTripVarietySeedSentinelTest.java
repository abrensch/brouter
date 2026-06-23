package btools.router;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * CI sentinel for the round-trip variety seed (ADR-0001) on the bundled
 * Dreieich fixture. Guards the two contracts a refactor could silently break:
 * <ol>
 *   <li><b>Determinism</b> — the same request + the same seed must reproduce
 *       the same route, node for node. The jitter hash is keyed on stable
 *       candidate coordinates; if someone reintroduces iteration-order or
 *       time-dependent input, this trips.</li>
 *   <li><b>Variety</b> — different seeds must be able to produce different
 *       loops. If the seed wiring is dropped (e.g. setVarietySeed no longer
 *       called, or the geometry knobs bypassed), every seed collapses onto
 *       the seed-0 route and this trips.</li>
 * </ol>
 * The full divergence/quality calibration lives in the opt-in
 * {@code VarietySeedCalibration*} integration suites; this is the cheap
 * always-on canary. Lenient quality mode: the comparison is geometric, so the
 * shipped best-effort loop is exactly what we want to compare.
 */
public class RoundTripVarietySeedSentinelTest {

  private static final int DIRECTION = 90;
  private static final int RADIUS = 1500;

  private static OsmTrack route(String profile, RoundTripAlgorithm algo, int seed) {
    RoutingEngine re = RoundTripFixture.engine(profile, DIRECTION, RADIUS, rc -> {
      rc.roundTripAlgorithm = algo;
      rc.roundTripStrictQuality = false;
      rc.setAlternativeIdx(seed);
    });
    Assert.assertNull("engine error (" + algo + ", seed " + seed + ")", re.getErrorMessage());
    OsmTrack t = re.getFoundTrack();
    Assert.assertNotNull("no track (" + algo + ", seed " + seed + ")", t);
    return t;
  }

  private static List<Long> nodeSequence(OsmTrack t) {
    List<Long> seq = new ArrayList<>(t.nodes.size());
    for (OsmPathElement n : t.nodes) {
      seq.add(((long) n.getILon() << 32) | (n.getILat() & 0xFFFFFFFFL));
    }
    return seq;
  }

  @Test
  public void sameSeedReproducesTheExactRoute() {
    for (int seed : new int[]{0, 3}) {
      List<Long> first = nodeSequence(route("trekking", RoundTripAlgorithm.WAYPOINT, seed));
      List<Long> second = nodeSequence(route("trekking", RoundTripAlgorithm.WAYPOINT, seed));
      Assert.assertEquals("seed " + seed + " must reproduce node-for-node", first, second);
    }
  }

  @Test
  public void waypointSeedsProduceDifferentLoops() {
    // The geometry knobs (phase/radius/targetPoints) are derived from the seed
    // alone, so on ANY map at least one of seeds 1-4 must displace the route.
    assertSomeSeedDiverges("trekking", RoundTripAlgorithm.WAYPOINT);
  }

  @Test
  public void greedySeedsProduceDifferentLoops() {
    // Score jitter flips near-tie candidate rankings. The fixture is a small
    // suburban tile with a dense-enough network that the calibration evidence
    // (median divergence 0.81-0.85 on real tiles) makes 4 inert seeds wildly
    // unlikely — unless the wiring broke, which is what this guards.
    assertSomeSeedDiverges("trekking", RoundTripAlgorithm.GREEDY);
  }

  private static void assertSomeSeedDiverges(String profile, RoundTripAlgorithm algo) {
    Set<Long> seed0 = new HashSet<>(nodeSequence(route(profile, algo, 0)));
    Assume.assumeTrue("fixture loop too small to compare", seed0.size() >= 10);
    for (int seed = 1; seed <= 4; seed++) {
      Set<Long> nodes = new HashSet<>(nodeSequence(route(profile, algo, seed)));
      if (!nodes.equals(seed0)) {
        return;
      }
    }
    Assert.fail(algo + ": seeds 1-4 all produced the identical node set as seed 0 — "
      + "the variety-seed wiring is broken (ADR-0001)");
  }
}
