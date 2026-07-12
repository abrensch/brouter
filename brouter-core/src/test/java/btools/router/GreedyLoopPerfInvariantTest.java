package btools.router;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Fast, segment-free parity tests pinning the exact invariants the
 * result-preserving loop-generator optimizations rely on
 * (docs/features/greedy-loop-perf-optimizations.md). These run in the standard
 * build and give millisecond feedback; the end-to-end proof is
 * {@link LoopGoldenSignatureTest}.
 */
public class GreedyLoopPerfInvariantTest {

  private static OsmPathElement node(int x, int y) {
    // Same scaling convention as RoundTripQualityGateTest: small integer
    // offsets near a real lon/lat so CheapRuler distances are well-defined.
    return OsmPathElement.create(180000000 + x * 14, 50000000 + y * 9, (short) 0, null);
  }

  // --- SAFE-1: skipping buildMap() must not change the self-intersection count ---

  /**
   * countSelfIntersections reads only track.nodes, so its result must be
   * identical whether or not buildMap() was called — that is precisely what
   * lets the tentative merge skip the map build.
   */
  @Test
  public void buildMapDoesNotChangeSelfIntersectionCount() {
    for (OsmTrack t : new OsmTrack[]{cleanSquare(), figureEight()}) {
      int withoutMap = RoundTripQualityGate.countSelfIntersections(t);
      t.buildMap();
      int withMap = RoundTripQualityGate.countSelfIntersections(t);
      assertEquals("buildMap must not affect crossing count", withoutMap, withMap);
    }
    // Sanity: the fixtures actually exercise both branches.
    assertEquals(0, RoundTripQualityGate.countSelfIntersections(cleanSquare()));
    assertTrue(RoundTripQualityGate.countSelfIntersections(figureEight()) > 0);
  }

  /**
   * buildMap() must mutate only nodesMap — never the node list, distance,
   * ascend or cost that feed routing decisions and the final result.
   */
  @Test
  public void buildMapLeavesDecisionFieldsUntouched() {
    OsmTrack t = figureEight();
    t.distance = 12345;
    t.ascend = 67;
    t.cost = 89012;
    List<OsmPathElement> before = new ArrayList<>(t.nodes);

    t.buildMap();

    assertEquals(12345, t.distance);
    assertEquals(67, t.ascend);
    assertEquals(89012, t.cost);
    assertEquals(before.size(), t.nodes.size());
    for (int i = 0; i < before.size(); i++) {
      assertSame("node identity/order must be preserved", before.get(i), t.nodes.get(i));
    }
  }

  // --- SAFE-4: shared-prefix append must reproduce appendTrack's node dedupe ---

  /**
   * appendNodesDeduped is the node-level core of appendTrack: it must skip the
   * first source node iff it coincides with the current tail, and otherwise
   * append every node in order. This is what makes the per-attempt shared
   * prefix produce a node sequence identical to the former per-candidate
   * full re-merge.
   */
  @Test
  public void appendNodesDedupedMatchesAppendTrackDedupe() {
    OsmPathElement a = node(0, 0);
    OsmPathElement b = node(10, 0);
    OsmPathElement x = node(20, 0);

    // First source node duplicates the tail -> skipped.
    List<OsmPathElement> t1 = new ArrayList<>(List.of(a, b, x));
    OsmPathElement xDup = node(20, 0); // same coords, different instance
    OsmPathElement c = node(30, 0);
    GreedyRoundTripPlanner.appendNodesDeduped(t1, new ArrayList<>(List.of(xDup, c)));
    assertEquals(List.of(a, b, x, c), t1);

    // First source node differs -> all appended.
    List<OsmPathElement> t2 = new ArrayList<>(List.of(a, b, x));
    OsmPathElement y = node(25, 5);
    GreedyRoundTripPlanner.appendNodesDeduped(t2, new ArrayList<>(List.of(y, c)));
    assertEquals(List.of(a, b, x, y, c), t2);

    // Empty target -> append verbatim (no tail to dedupe against).
    List<OsmPathElement> t3 = new ArrayList<>();
    GreedyRoundTripPlanner.appendNodesDeduped(t3, new ArrayList<>(List.of(x, c)));
    assertEquals(List.of(x, c), t3);
  }

  // --- SAFE-3: VisitedEdgeStore must behave exactly like the two HashMaps ---

  /**
   * Differential test: replay the planner's exact visit/remove operation
   * grammar against both the reference {@code HashMap<Long,Integer>} +
   * {@code HashMap<Long,Double>} pair and the primitive store, asserting full
   * parity (count, presence, firstPos bits, isEmpty, size) after every single
   * operation. The key universe deliberately includes 0L, -1L, MIN/MAX (which
   * forbid sentinel keys) and a tiny dense range that forces collisions,
   * probing, backward-shift deletion and table growth; firstPos values include
   * 0.0 (the 1-metre-first-edge case that forbids a sentinel double).
   */
  @Test
  public void visitedEdgeStoreMatchesReferenceMaps() {
    long[] universe = {
      0L, -1L, 1L, Long.MIN_VALUE, Long.MAX_VALUE,
      0x9E3779B97F4A7C15L, -0x9E3779B97F4A7C15L,
      10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25,
      1L << 40, (1L << 40) + 16, (1L << 40) + 32, // collide on low bits before mixing
    };
    Map<Long, Integer> refCounts = new HashMap<>();
    Map<Long, Double> refFirst = new HashMap<>();
    VisitedEdgeStore store = new VisitedEdgeStore();

    Random rnd = new Random(20260530L);
    boolean sawNonEmpty = false;
    boolean sawZeroFirstPos = false;
    for (int op = 0; op < 50000; op++) {
      long key = universe[rnd.nextInt(universe.length)];
      if (rnd.nextBoolean()) {
        // visit: mirrors addVisitedEdges' per-edge body
        double pos = rnd.nextInt(5) == 0 ? 0.0 : rnd.nextDouble() * 100000.0;
        Integer prev = refCounts.get(key);
        if (prev == null || prev == 0) refFirst.put(key, pos);
        refCounts.merge(key, 1, Integer::sum);

        if (store.count(key) == 0) store.setFirstPos(key, pos);
        store.increment(key);
      } else {
        // remove: mirrors removeVisitedEdges' per-edge body
        Integer c = refCounts.get(key);
        if (c != null) {
          if (c <= 1) {
            refCounts.remove(key);
            refFirst.remove(key);
          } else {
            refCounts.put(key, c - 1);
          }
        }
        int sc = store.count(key);
        if (sc != 0) {
          if (sc <= 1) store.remove(key);
          else store.decrement(key);
        }
      }

      // Full parity check against every key in the universe.
      assertEquals("size", refCounts.size(), store.size());
      assertEquals("isEmpty", refCounts.isEmpty(), store.isEmpty());
      sawNonEmpty |= !store.isEmpty();
      for (long k : universe) {
        Integer rc = refCounts.get(k);
        int expected = (rc == null) ? 0 : rc;
        assertEquals("count[" + k + "] op " + op, expected, store.count(k));
        assertEquals("contains[" + k + "] op " + op,
          refCounts.containsKey(k), store.containsKey(k));
        if (store.containsKey(k)) {
          Double rf = refFirst.get(k);
          assertNotNull("firstPos must be present when key present (op " + op + ")", rf);
          assertEquals("firstPos bits[" + k + "] op " + op,
            Double.doubleToRawLongBits(rf), Double.doubleToRawLongBits(store.firstPos(k)));
          if (store.firstPos(k) == 0.0) sawZeroFirstPos = true;
        }
      }
    }
    assertTrue("test should exercise non-empty states", sawNonEmpty);
    assertTrue("test should exercise a firstPos==0.0 present key", sawZeroFirstPos);
  }

  // --- SAFE-5: precomputed segment-distance buffer must reproduce the scan ---

  /**
   * The worst-contiguous-costfactor scan must return the identical value whether
   * it recomputes calcDistance inline or reads a precomputed buffer. The buffer
   * is built with the same formula segmentDistances uses, so this also pins the
   * index alignment (segLens[i-1] = distance(nodes[i-1], nodes[i])) — including
   * a null-message segment, which the scan visits (it computes the distance
   * before the null check) but treats as a run-breaker.
   */
  @Test
  public void precomputedSegmentBufferMatchesInlineHostilityScan() {
    OsmTrack t = new OsmTrack();
    t.nodes = new ArrayList<>();
    t.nodes.add(node(0, 0));
    int[] costPattern = {5, 5, 1, 5, 5, 5, -1, 5, 5}; // -1 marks a null-message segment
    for (int i = 0; i < costPattern.length; i++) {
      OsmPathElement n = node((i + 1) * 100, 0);
      if (costPattern[i] == -1) {
        n.message = null;
      } else {
        MessageData m = new MessageData();
        m.wayKeyValues = null;
        m.costfactor = costPattern[i];
        n.message = m;
      }
      t.nodes.add(n);
    }

    int[] segLens = new int[t.nodes.size() - 1];
    for (int i = 1; i < t.nodes.size(); i++) {
      segLens[i - 1] = t.nodes.get(i - 1).calcDistance(t.nodes.get(i));
    }

    for (double threshold : new double[]{1.0, 3.0, 4.0, 5.0}) {
      int inline = RoundTripQualityGate.worstContiguousMetersAboveCostfactor(t, threshold);
      int buffered = RoundTripQualityGate.worstContiguousMetersAboveCostfactor(t, threshold, segLens);
      assertEquals("threshold " + threshold, inline, buffered);
    }
  }

  /** Axis-aligned square loop — no self-crossing. */
  private static OsmTrack cleanSquare() {
    OsmTrack t = new OsmTrack();
    t.nodes.add(node(0, 0));
    t.nodes.add(node(1000, 0));
    t.nodes.add(node(1000, 1000));
    t.nodes.add(node(0, 1000));
    t.nodes.add(node(0, 0));
    return t;
  }

  /** Figure-eight — the two lobes cross in the middle (>= 1 self-intersection). */
  private static OsmTrack figureEight() {
    OsmTrack t = new OsmTrack();
    t.nodes.add(node(0, 0));
    t.nodes.add(node(1000, 1000));
    t.nodes.add(node(1000, 0));
    t.nodes.add(node(0, 1000));
    t.nodes.add(node(0, 0));
    return t;
  }
}
