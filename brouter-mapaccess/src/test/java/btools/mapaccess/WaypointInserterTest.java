package btools.mapaccess;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
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

  private static final byte[] WAY = new byte[0]; // just any description

  private static GraphNode node(int lon, int lat) {
    return new GraphNode(180000000 + lon, 90000000 + lat);
  }

  private NodesCache nc;

  /**
   * Creates a simple graph (4 nodes in a row), writes it into a segment file
   * and opens a NodesCache on it.
   */
  @Before
  public void setUp() throws IOException
  {
    GraphNode n1 = node( 1000, 1000 );
    GraphNode n2 = node( 2000, 1000 );
    GraphNode n3 = node( 3000, 1000 );
    GraphNode n4 = node( 3000, 2000 );

    n1.createLink( WAY, n2 );
    n2.createLink( WAY, n3 );
    n3.createLink( WAY, n4 );

    // write the nodes into a file
    File segmentDir = tmpDir.newFolder("segments" );
    new GraphFile(new File(segmentDir, "E0_N0.gd5"), 180000000, 90000000).createWithNodes(
      List.of(n1, n2, n3, n4) );
    nc = new NodesCache( segmentDir, "gd5", null, null );
  }

  @Test
  public void insertAcrossSegments() throws IOException
  {
    assertRoute( "1500,1010 -> 3030,1500", "1500,1000 2000,1000 3000,1000 3000,1500" );
  }

  @Test
  public void insertWithinSegment() throws IOException
  {
    assertRoute( "1500,1010 -> 1600,990", "1500,1000 1600,1000" );
  }

  @Test
  public void insertWithinSnapPoints() throws IOException
  {
    assertRoute( "900,900 -> 3100,970", "1000,1000 2000,1000 3000,1000" );
  }

  /**
   * @param in  "lon,lat -> lon,lat"
   * @param out expected route nodes as "lon,lat lon,lat ..."
   */
  private void assertRoute( String in, String out ) throws IOException
  {
    int[] c = nums( in );
    List<GraphNode> route = getRoute( nc, c[0], c[1], c[2], c[3] );
    int[] e = nums( out );
    assertEquals( e.length / 2, route.size() );
    for ( int i = 0; i < route.size(); i++ )
    {
      assertEquals( node( e[2 * i], e[2 * i + 1] ).getIdFromPos(), route.get( i ).getIdFromPos() );
    }
  }

  private static int[] nums( String s )
  {
    return Arrays.stream( s.split( "[^0-9]+" ) ).filter( x -> !x.isEmpty() )
      .mapToInt( Integer::parseInt ).toArray();
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
