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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TwoNodeLoopResolver {

  /*
   * check each node for duplicate targets and eventually split a link
   */
  public static void resolveTwoNodeLoops(List<OsmNode> nodes) {

    // loop over network nodes and follow forward links
    int initialNodeCount = nodes.size();
    int i = 0;
    while( i<initialNodeCount ) {
      OsmNode n = nodes.get(i);
      OsmLink duplicate = checkNode(n);
      if (duplicate == null) {
        i++;
      } else {
        splitDuplicateLink(n, duplicate, nodes);
      }
    }
  }

  // check a node for duplicate targets
  private static OsmLink checkNode(OsmNode n) {
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

  private static void splitDuplicateLink(OsmNode n, OsmLink link, List<OsmNode> nodes) {

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
      return;
    }

    System.out.println( "*** INFO: splitting duplicate link " + n + " --> " + t );

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
      if  ( !positionOccupied(iLon,iLat,nodes) ) {
        splitNode = new OsmNode(iLon,iLat);
        splitNode.sElev = n.sElev;
        break;
      }
      pos += step;
      step = (step < 0 ? 1 : -1 ) - step;
    }

    if ( splitNode == null ) {
      System.out.println( "*** ERROR: cannot create splitNode for duplicate link: " + n + " --> " + t );
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
    checkTrPairing( n, t);
    checkTrPairing( t, n);
  }

  private static void checkTrPairing( OsmNode n1, OsmNode n2 ) {
    for(TurnRestriction tr = n1.firstRestriction; tr != null; tr = tr.next ) {
      if ( ( tr.fromLon == n2.iLon && tr.fromLat == n2.iLat )
        || ( tr.toLon == n2.iLon && tr.toLat == n2.iLat ) ) {
        System.out.println( "****** WARNING: TR affected by split ! ******" );
      }
    }
  }

  private static boolean positionOccupied( int iLon, int iLat, List<OsmNode> nodes ) {
    for( OsmNode nn : nodes ) {
      if ( nn.iLat == iLat && nn.iLon == iLon ) {
        return true;
      }
    }
    return false;
  }
}
