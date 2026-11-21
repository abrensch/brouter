/**
 * Filter to eliminate some transfer nodes (Douglas-Peucker algorithm)
 *
 * @author ab
 */
package btools.mapcreator;

import btools.mapaccess.GraphLink;
import btools.mapaccess.GraphNode;
import btools.mapaccess.TurnRestriction;
import btools.util.CheapRuler;
import btools.util.CompactLongSet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TwoNodeLoopResolver {


  private List<GraphNode> nodes;
  private CompactLongSet positionSet = new CompactLongSet();

  public TwoNodeLoopResolver(List<GraphNode> nodes) {
    this.nodes = nodes;
    for( GraphNode n : nodes ) {
      positionSet.fastAdd( n.getIdFromPos() );
    }
  }

  /*
   * check each node for duplicate targets and eventually split a link
   */
  public void resolve() {

    // loop over network nodes and follow forward links
    int initialNodeCount = nodes.size();
    int i = 0;
    while( i<initialNodeCount ) {
      GraphNode n = nodes.get(i);
      GraphLink duplicate = checkNode(n);
      if (duplicate == null) {
        i++;
      } else {
        splitDuplicateLink(n, duplicate);
      }
    }
System.out.println( "resolving done for tile, drops=" + drops + " splits=" + splits );
  }

  // check a node for duplicate targets
  private GraphLink checkNode(GraphNode n) {
    for(GraphLink l = n.firstLink; l != null; l = l.getNext(n)) {
      GraphNode t = l.getTarget(n);
      if ( t.hasBits(GraphNode.BORDER_BIT) && n.hasBits(GraphNode.BORDER_BIT) ) {
        continue; // do not split border-links
      }
      for(GraphLink ll = l.getNext(n); ll!=null; ll = ll.getNext(n)) {
        if (ll.getTarget(n) == t) {
          return ll;
        }
      }
    }
    return null;
  }
private long drops;
private long splits;

  private void splitDuplicateLink(GraphNode n, GraphLink link) {

    GraphNode t = link.getTarget(n);
    boolean reverse = link.isReverse(n);
    byte[] wayDescription = link.wayDescription;
    boolean justDrop = false;

    // re-find the other one and compare tagging and direction
    for(GraphLink l = n.firstLink; l != null; l = l.getNext(n)) {
      if ( l.getTarget(n) == t ) {
        if ( l == link ) {
          throw new RuntimeException( "ups? no duplicate?" );
        }
        // compare direction and tagging
        if ( Arrays.equals( l.wayDescription, wayDescription ) && reverse == l.isReverse(n) ) {
          justDrop = true;
        }
        break;
      }

    }

    // remove the link
    n.unlinkLink( link );
    if (!link.isLinkUnused()) {
      t.unlinkLink(link);
    }

    if ( justDrop ) {
      drops++;
      return;
    }

    splits++;

    GraphNode splitNode = null;

    // search around the center for a free position
    int distance = Math.max( Math.abs(t.iLon - n.iLon), Math.abs(t.iLat - n.iLat));
    int pos = distance/2;
    int step = 1;
    while ( pos > 0 && pos < distance ) {
      double deltaLon = n.iLon - t.iLon;
      double deltaLat = n.iLat - t.iLat;
      int iLon = t.iLon + (int)((deltaLon*pos)/distance);
      int iLat = t.iLat + (int)((deltaLat*pos)/distance);
      GraphNode testNode = new GraphNode(iLon,iLat);
      if ( !positionSet.add( testNode.getIdFromPos() ) ) {
        splitNode = testNode;
        splitNode.sElev = n.sElev;
        break;
      }
      pos += step;
      step = (step < 0 ? 1 : -1 ) - step;
    }

    if ( splitNode == null ) {
      System.out.println( "*** ERROR: cannot create splitNode for duplicate link: " + n + " --> " + t + " distance=" + distance );
      return;
    }

    nodes.add( splitNode );
    if (reverse) {
      t.createLink(wayDescription, splitNode);
      splitNode.createLink(wayDescription, n);
    } else {
      n.createLink(wayDescription, splitNode);
      splitNode.createLink(wayDescription, t);
    }

    // check if we tampered with a TR (just report for now)
    checkTrPairing( n, t, splitNode );
    checkTrPairing( t, n, splitNode );
  }

  private void checkTrPairing( GraphNode n1, GraphNode n2, GraphNode n3 ) {
    for(TurnRestriction tr = n1.firstRestriction; tr != null; tr = tr.next ) {
      boolean fromMatch = tr.fromLon == n2.iLon && tr.fromLat == n2.iLat;
      boolean toMatch = tr.toLon == n2.iLon && tr.toLat == n2.iLat;
      if ( fromMatch || toMatch ) {
        TurnRestriction c = tr.createCopy();
        if ( fromMatch ) {
          c.fromLon = n3.iLon;
          c.fromLat = n3.iLat;
        }
        if ( toMatch ) {
          c.toLon = n3.iLon;
          c.toLat = n3.iLat;
        }
        n1.addTurnRestriction(c);
        System.out.println( "****** WARNING: TR affected by split (copied) " + n1 + " --> " + n2 );
      }
    }
  }
}
