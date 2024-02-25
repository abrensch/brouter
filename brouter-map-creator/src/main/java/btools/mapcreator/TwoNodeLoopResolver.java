/**
 * Filter to eliminate some transfer nodes (Douglas-Peucker algorithm)
 *
 * @author ab
 */
package btools.mapcreator;

import btools.mapaccess.OsmLink;
import btools.mapaccess.OsmNode;
import btools.mapaccess.TurnRestriction;
import btools.util.CheapRuler;
import btools.util.CompactLongSet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TwoNodeLoopResolver {


  private List<OsmNode> nodes;
  private CompactLongSet positionSet = new CompactLongSet();

  public TwoNodeLoopResolver(List<OsmNode> nodes) {
    this.nodes = nodes;
    for( OsmNode n : nodes ) {
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
      OsmNode n = nodes.get(i);
      OsmLink duplicate = checkNode(n);
      if (duplicate == null) {
        i++;
      } else {
        splitDuplicateLink(n, duplicate);
      }
    }
System.out.println( "resolving done for tile, drops=" + drops + " splits=" + splits );
  }

  // check a node for duplicate targets
  private OsmLink checkNode(OsmNode n) {
    for(OsmLink l = n.firstLink; l != null; l = l.getNext(n)) {
      OsmNode t = l.getTarget(n);
      if ( t.hasBits(OsmNode.BORDER_BIT) && n.hasBits(OsmNode.BORDER_BIT) ) {
        continue; // do not split border-links
      }
      for(OsmLink ll = l.getNext(n); ll!=null; ll = ll.getNext(n)) {
        if (ll.getTarget(n) == t) {
          return ll;
        }
      }
    }
    return null;
  }
private long drops;
private long splits;

  private void splitDuplicateLink(OsmNode n, OsmLink link) {

    OsmNode t = link.getTarget(n);
    boolean reverse = link.isReverse(n);
    byte[] wayDescription = link.wayDescription;
    boolean justDrop = false;

    // re-find the other one and compare tagging and direction
    for(OsmLink l = n.firstLink; l != null; l = l.getNext(n)) {
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

    OsmNode splitNode = null;

    // search around the center for a free position
    int distance = Math.max( Math.abs(t.iLon - n.iLon), Math.abs(t.iLat - n.iLat));
    int pos = distance/2;
    int step = 1;
    while ( pos > 0 && pos < distance ) {
      double deltaLon = n.iLon - t.iLon;
      double deltaLat = n.iLat - t.iLat;
      int iLon = t.iLon + (int)((deltaLon*pos)/distance);
      int iLat = t.iLat + (int)((deltaLat*pos)/distance);
      OsmNode testNode = new OsmNode(iLon,iLat);
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

  private void checkTrPairing( OsmNode n1, OsmNode n2, OsmNode n3 ) {
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
          c.toLat = n3.iLon;
          c.toLat = n3.iLat;
        }
        n1.addTurnRestriction(c);
        System.out.println( "****** WARNING: TR affected by split (copied) " + n1 + " --> " + n2 );
      }
    }
  }
}
