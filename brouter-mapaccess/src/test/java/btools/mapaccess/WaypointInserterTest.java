package btools.mapaccess;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Basic graph navigation on GraphNode / GraphLink / TurnRestriction,
 * The graph is build in memory and persisted to a temp file.
 */
public class WaypointInserterTest
{
  // temporary directory, deleted automatically after each test
  @Rule
  public TemporaryFolder tmpDir = new TemporaryFolder();

  private static final byte[] WAY = new byte[] { 1 }; // just any description

  private static GraphNode node(int lon, int lat) {
    return new GraphNode(180000000 + lon, 90000000 + lat);
  }

  /**
   * Creates a simple graph (4 nodes in a row), writes it into a segment file
   * and returns the segment directory.
   */
  private File createSimpleGraph() throws IOException
  {
    GraphNode n1 = node( 1000, 1000 );
    GraphNode n2 = node( 2000, 1000 );
    GraphNode n3 = node( 3000, 1000 );
    GraphNode n4 = node( 4000, 1000 );

    n1.createLink( WAY, n2 );
    n2.createLink( WAY, n3 );
    n3.createLink( WAY, n4 );

    // write the nodes into a file
    File segmentDir = tmpDir.newFolder("segments" );
    new GraphFile(new File(segmentDir, "E0_N0.gd5"), 180000000, 90000000).createWithNodes(
      List.of(n1, n2, n3, n4) );
    return segmentDir;
  }

  @Test
  public void testInsert() throws IOException
  {
    File segmentDir = createSimpleGraph();

    // now recover the graph from the file and navigate again
    NodesCache nc = new NodesCache(segmentDir, "gd5", null, null);

    List<GraphNode> route;

    // expected route: start crosspoint, n2, n3, end crosspoint
    route = getRoute( nc, 1500, 1010, 3500, 1020 );
    assertEquals( 4, route.size() );
    assertEquals( node( 1500, 1000 ).getIdFromPos(), route.get( 0 ).getIdFromPos() );
    assertEquals( node( 2000, 1000 ).getIdFromPos(), route.get( 1 ).getIdFromPos() );
    assertEquals( node( 3000, 1000 ).getIdFromPos(), route.get( 2 ).getIdFromPos() );
    assertEquals( node( 3500, 1000 ).getIdFromPos(), route.get( 3 ).getIdFromPos() );

    // same segment, expected route: start crosspoint, end crosspoint
    route = getRoute( nc, 1500, 1010, 1600, 990 );
    assertEquals( 2, route.size() );
    assertEquals( node( 1500, 1000 ).getIdFromPos(), route.get( 0 ).getIdFromPos() );
    assertEquals( node( 1600, 1000 ).getIdFromPos(), route.get( 1 ).getIdFromPos() );
  }

  private List<GraphNode> getRoute( NodesCache nc, int lon1, int lat1, int lon2, int lat2 ) throws IOException
  {
    // .. match a point near the start node
    MatchedWaypoint wpS = new MatchedWaypoint();
    wpS.waypoint = node( lon1, lat1 );
    wpS.name = "start";
    MatchedWaypoint wpE = new MatchedWaypoint();
    wpE.waypoint = node( lon2, lat2 );
    wpE.name = "end";

    new NodesCache(nc).matchWaypointsToNodes( List.of( wpS, wpE ), 1000., new NodePairSet(1000 ) );

    // expected route: start crosspoint, n2, n3, end crosspoint
    return new SimpleDijkstra( nc, wpS, wpE ).getRoute();
  }

}
