package btools.mapaccess;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Basic graph navigation on GraphNode / GraphLink / TurnRestriction,
 * The graph is build in memory and persisted to a temp file.
 */
public class GraphNavigationTest {

  // temporary directory, deleted automatically after each test
  @Rule
  public TemporaryFolder tmpDir = new TemporaryFolder();

  private static final byte[] WAY = new byte[] { 1 }; // just any description
  private static final byte[] WAY2 = new byte[] { 2 }; // another one

  private static GraphNode node(int lon, int lat) {
    return new GraphNode(180000000 + lon, 90000000 + lat);
  }

  @Test
  public void navigateRing() {
    int nodeCount = 100;
    GraphNode start = createRing(nodeCount);
    assertEquals(nodeCount, navigateRing(start, false, null).size() );
    assertEquals(nodeCount, navigateRing(start, true, null ).size() );
  }

  @Test
  public void serializeDeserializeRing() throws IOException
  {
    int nodeCount = 100;
    GraphNode start = createRing(nodeCount);

    List<GraphNode> nodes = navigateRing( start, false, null );

    // write the nodes into a file
    File segmentDir = tmpDir.newFolder("segments");
    new GraphFile(new File(segmentDir, "E0_N0.rd5"), 180000000, 90000000).createWithNodes(nodes);

    // now recover the ring from the file and navigate again
    NodesCache nc = new NodesCache(segmentDir, "rd5", null, null);

    // .. match a point near the start node
    MatchedWaypoint mwp = new MatchedWaypoint();
    mwp.waypoint = new GraphNode(start.iLon, start.iLat + 500 );
    mwp.name = "some point near the start";
    List<MatchedWaypoint> matchedWaypoints = new ArrayList<>();
    matchedWaypoints.add(mwp);
    nc.matchWaypointsToNodes( matchedWaypoints, 1000., new NodePairSet(1000 ) );
    assertNotNull( "start node not matched", mwp.node1 );

    // the matcher only delivers position templates, not graph nodes.
    // before turning that into a (hollow) graph node, clean the nodes cache
    nc.clean();
    GraphNode decodedStart = nc.getGraphNode( mwp.node1 );

    // result should be a navigable ring again (supported by the nodes cache)
    assertEquals(nodeCount, navigateRing(decodedStart, false, nc).size() );
    assertEquals(nodeCount, navigateRing(decodedStart, true, nc).size() );
  }


  private GraphNode createRing ( int nodeCount ) {
    // create a ring around a tile-crossing, so that we can test the hollow node handling
    GraphNode last = null;
    GraphNode first = null;
    double angleStep = 2.0 * Math.PI / nodeCount;
    int cellsize = 1000000 / 32;
    for ( int i = 0; i < nodeCount; i++ )
    {
      double angle = i * angleStep;
      GraphNode n = node( (int)(cellsize + 1000*Math.cos(angle)), (int)(cellsize + 1000*Math.sin(angle)) );
      if ( last != null )
      {
        last.createLink( i % 5 == 0 ? WAY2 : WAY, n );
      }
      if ( first == null )
      {
        first = n;
      }
      last = n;
    }
    last.createLink( WAY, first );
    return first;
  }

    private List<GraphNode> navigateRing ( GraphNode start,boolean reverse, NodesCache nc )
    {
      List<GraphNode> result = new ArrayList<>();
      GraphNode n = start;
      while ( result.size() < 100 )
      {
        if ( n.isHollow() )
        {
          if ( nc != null )
          {
            nc.obtainNonHollowNode( n );
          }
          if ( n.isHollow() )
          {
            throw new IllegalStateException( "hollow node in ring: " + n );
          }
        }
        result.add( n );
        n = searchLink( n, reverse ).getTarget( n );
        if ( n == start )
        {
          return result;
        }
      }
      throw new IllegalStateException( "ring not closed" );
    }

    private GraphLink searchLink ( GraphNode n,boolean reverse)
    {
      GraphLink result = null;
      GraphLink l = n.firstLink;
      while ( l != null )
      {
        if ( l.isReverse( n ) == reverse )
        {
          if ( result != null )
          {
            throw new IllegalStateException( "multiple links for direction: " + reverse );
          }
          result = l;
        }
        l = l.getNext( n );
      }
      if ( result == null )
      {
        throw new IllegalStateException( "no link found for direction: " + reverse );
      }
      return result;
    }
  }
