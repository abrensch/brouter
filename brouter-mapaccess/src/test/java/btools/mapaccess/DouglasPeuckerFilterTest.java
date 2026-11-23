package btools.mapaccess;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;


/**
 * Tests for the Douglas-Peucker elimination of transfer nodes,
 * using small in-memory graphs. Coordinates are in micro-degrees
 * (1 unit is about 0.11m), the threshold is 0.4m.
 */
public class DouglasPeuckerFilterTest {

  private static final byte[] WAY = new byte[] { 1 };
  private static final byte[] WAY2 = new byte[] { 2 };

  private static GraphNode node(int lon, int lat) {
    return new GraphNode(180000000 + lon, 90000000 + lat);
  }

  /**
   * create a forward-linked chain, the first and last node are
   * network nodes (only one link), all others are transfer nodes
   */
  private static List<GraphNode> chain(byte[][] ways, int[][] lonLat) {
    List<GraphNode> nodes = new ArrayList<>();
    for (int[] p : lonLat) {
      nodes.add(node(p[0], p[1]));
    }
    for (int i = 0; i < nodes.size() - 1; i++) {
      nodes.get(i).createLink(ways[i], nodes.get(i + 1));
    }
    return nodes;
  }

  private static byte[][] sameWay(int linkCount) {
    byte[][] ways = new byte[linkCount][];
    java.util.Arrays.fill(ways, WAY);
    return ways;
  }

  /** follow the forward links from the start and count the nodes */
  private static int forwardLength(GraphNode start) {
    int count = 1;
    GraphNode n = start;
    while (true) {
      GraphNode next = null;
      for (GraphLink l = n.firstLink; l != null; l = l.getNext(n)) {
        if (!l.isReverse(n)) {
          next = l.getTarget(n);
        }
      }
      if (next == null) {
        return count;
      }
      n = next;
      count++;
    }
  }

  @Test
  public void collinearNodesAreDropped() {
    List<GraphNode> nodes = chain(sameWay(4), new int[][] {
        { 0, 0 }, { 1000, 0 }, { 2000, 0 }, { 3000, 0 }, { 4000, 0 } });

    DouglasPeuckerFilter.filter(nodes);

    GraphNode first = nodes.get(0);
    GraphNode last = nodes.get(4);
    assertEquals(2, forwardLength(first));
    assertSame(last, first.firstLink.getTarget(first));
  }

  @Test
  public void smallDeviationIsDropped() {
    // 1 unit is about 0.11m, below the threshold
    List<GraphNode> nodes = chain(sameWay(2), new int[][] {
        { 0, 0 }, { 1000, 1 }, { 2000, 0 } });

    DouglasPeuckerFilter.filter(nodes);

    assertEquals(2, forwardLength(nodes.get(0)));
  }

  @Test
  public void significantDeviationSurvives() {
    // 100 units are about 11m, far above the threshold
    List<GraphNode> nodes = chain(sameWay(2), new int[][] {
        { 0, 0 }, { 1000, 100 }, { 2000, 0 } });

    DouglasPeuckerFilter.filter(nodes);

    assertEquals(3, forwardLength(nodes.get(0)));
    assertEquals(2, nodes.get(1).linkCount());
  }

  @Test
  public void onlyTheCornerSurvives() {
    List<GraphNode> nodes = chain(sameWay(4), new int[][] {
        { 0, 0 }, { 500, 0 }, { 1000, 0 }, { 1000, 500 }, { 1000, 1000 } });

    DouglasPeuckerFilter.filter(nodes);

    assertEquals(3, forwardLength(nodes.get(0)));
    assertSame(nodes.get(2), nodes.get(0).firstLink.getTarget(nodes.get(0)));
  }

  @Test
  public void differentWayDescriptionsAreNoTransferNodes() {
    byte[][] ways = { WAY, WAY2 };
    List<GraphNode> nodes = chain(ways, new int[][] {
        { 0, 0 }, { 1000, 0 }, { 2000, 0 } });

    DouglasPeuckerFilter.filter(nodes);

    assertEquals(3, forwardLength(nodes.get(0)));
  }

  @Test
  public void nodesWithTagsAreKept() {
    List<GraphNode> nodes = chain(sameWay(2), new int[][] {
        { 0, 0 }, { 1000, 0 }, { 2000, 0 } });
    nodes.get(1).nodeDescription = new byte[] { 7 };

    DouglasPeuckerFilter.filter(nodes);

    assertEquals(3, forwardLength(nodes.get(0)));
  }
}

