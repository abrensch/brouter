package btools.mapaccess;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import btools.util.TagValueValidator;
import org.junit.Test;

/**
 * Basic graph navigation on OsmNode / OsmLink / TurnRestriction,
 * built in memory (no segment files required).
 */
public class GraphNavigationTest {

  private static final byte[] WAY = new byte[] { 0x4a }; // a very simple valid way description

  private static OsmNode node(int lon, int lat) {
    return new OsmNode(180000000 + lon, 90000000 + lat);
  }

  @Test
  public void navigateRing() {
    OsmNode start = createRing();
    assertEquals(10, navigateRing(start, false).size() );
    assertEquals(10, navigateRing(start, true).size() );
  }

  @Test
  public void serializeDeserializeRing() throws IOException
  {
    OsmNode start = createRing();
    List<OsmNode> nodes = navigateRing( start, false );

    // attach additional node outside tile boundary
    OsmNode extern = node( 0, -100 );
    start.createLink( WAY, extern );

    OsmNode n0 = node( 0, 0 );
    OsmTile tile = new OsmTile( n0.iLon, n0.iLon );
    byte[] bigBuffer = new byte[10000];
    int len = tile.encodeTile( nodes, bigBuffer, null );

    byte[] buffer = new byte[len];
    System.arraycopy(bigBuffer, 0, buffer, 0, len);

    // now we have the ring in a buffer

    // we put a copy of the extern node in a hollowNodes map
    // to have a handle to the decoded ring
    OsmNodesMap hollowNodes = new OsmNodesMap();
    OsmNode hollowExtern = new OsmNode( extern.iLon, extern.iLat );
    hollowNodes.put(hollowExtern);
    hollowExtern.setBits( OsmNode.HOLLOW_BIT );

    // decode the ring
    len = tile.decodeTile(buffer, new DataBuffers(), null, null, hollowNodes);;
    assertEquals( len, buffer.length ); // all data decoded?

    // extern node should still be hollow and have a link to the ring start
    assertTrue( hollowExtern.isHollow() );
    OsmNode decodedStart = hollowExtern.firstLink.getTarget( hollowExtern );

    // to get a navigable ring again, we must unkink the external node
    decodedStart.unlinkLink( hollowExtern.firstLink );

    // result should be a navigable ring again
    assertEquals(10, navigateRing(decodedStart, false).size() );
    assertEquals(10, navigateRing(decodedStart, true).size() );
  }

  public OsmNode createRing ( ) {
    // create a ring
    OsmNode last = null;
    OsmNode first = null;
    for ( int i = 0; i < 10; i++ )
    {
      OsmNode n = node( 0, i*10 + 5 );
      if ( last != null )
      {
        last.createLink( WAY, n );
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

    private List<OsmNode> navigateRing ( OsmNode start,boolean reverse)
    {
      List<OsmNode> result = new ArrayList<>();
      OsmNode n = start;
      while ( result.size() < 100 )
      {
        result.add( n );
        n = searchLink( n, reverse ).getTarget( n );
        if ( n == start )
        {
          return result;
        }
      }
      throw new IllegalStateException( "ring not closed" );
    }

    private OsmLink searchLink ( OsmNode n,boolean reverse)
    {
      OsmLink result = null;
      OsmLink l = n.firstLink;
      while ( l != null )
      {
        if ( l.isReverse( n ) == reverse )
        {
          if ( result != null )
          {
            throw new IllegalStateException( "multiple links in same direction" );
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
