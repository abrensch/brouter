package btools.router;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Behavioural contract for round-trip routing, exercised across edge radii
 * (small/medium/large relative to the Dreieich fixture) where loops may or may
 * not be feasible:
 * <ul>
 *   <li><b>Success implies a real loop</b> — the engine must never report success
 *       (null error) while returning a degenerate stub. If a loop cannot be formed
 *       it must fail with a clear error and no track.</li>
 *   <li><b>Determinism</b> — identical inputs produce an identical track.</li>
 * </ul>
 */
@RunWith(Parameterized.class)
public class RoundTripContractTest {

  // Lower bounds the engine guarantees for any reported loop. Tighter than the engine's
  // coarse "is it a loop at all" guard (400 m closure) because a successful loop on real
  // road data closes within metres; this asserts the quality, not just the guard.
  private static final int MIN_LOOP_NODES = 6;
  private static final int MIN_LOOP_METERS = 200;
  private static final int CLOSURE_MAX_M = 150;

  @Parameterized.Parameter(0)
  public String profile;
  @Parameterized.Parameter(1)
  public int direction;
  @Parameterized.Parameter(2)
  public int radius;

  @Parameterized.Parameters(name = "{0}_dir{1}_r{2}")
  public static Collection<Object[]> data() {
    List<Object[]> params = new ArrayList<>();
    for (String profile : new String[]{"trekking", "fastbike", "gravel", "mtb"}) {
      for (int dir : new int[]{0, 90, 180, 270}) {
        // span feasible to over-constrained radii on the small fixture
        for (int radius : new int[]{500, 1500, 5000}) {
          params.add(new Object[]{profile, dir, radius});
        }
      }
    }
    return params;
  }

  /** Either a valid loop, or a clean failure — never a degenerate "success". */
  @Test
  public void successImpliesValidLoop() {
    RoutingEngine re = RoundTripFixture.engine(profile, direction, radius);
    String err = re.getErrorMessage();
    OsmTrack track = re.getFoundTrack();

    if (err != null) {
      // Acceptable: a loop is genuinely infeasible for this direction/radius on the
      // fixture. The contract only requires that failure be explicit and trackless.
      Assert.assertNull(label() + ": error set but a track was still returned", track);
      return;
    }

    Assert.assertNotNull(label() + ": success reported but no track", track);
    Assert.assertTrue(label() + ": success reported with degenerate loop ("
        + track.nodes.size() + " nodes, " + track.distance + "m)",
      track.nodes.size() >= MIN_LOOP_NODES && track.distance >= MIN_LOOP_METERS);

    int closing = track.nodes.get(0).calcDistance(track.nodes.get(track.nodes.size() - 1));
    Assert.assertTrue(label() + ": loop does not close, gap " + closing + "m",
      closing <= CLOSURE_MAX_M);
  }

  /** Identical inputs must yield an identical track (reproducible routing). */
  @Test
  public void deterministic() {
    RoutingEngine re1 = RoundTripFixture.engine(profile, direction, radius);
    Assume.assumeTrue("no loop for this case", re1.getErrorMessage() == null);
    OsmTrack t1 = re1.getFoundTrack();

    OsmTrack t2 = RoundTripFixture.engine(profile, direction, radius).getFoundTrack();
    Assert.assertNotNull(label() + ": second run produced no track", t2);
    Assert.assertEquals(label() + ": node count differs between runs",
      t1.nodes.size(), t2.nodes.size());
    for (int i = 0; i < t1.nodes.size(); i++) {
      OsmPathElement a = t1.nodes.get(i), b = t2.nodes.get(i);
      Assert.assertTrue(label() + ": node " + i + " differs between runs",
        a.getILon() == b.getILon() && a.getILat() == b.getILat());
    }
  }

  private String label() {
    return profile + "_dir" + direction + "_r" + radius;
  }
}
