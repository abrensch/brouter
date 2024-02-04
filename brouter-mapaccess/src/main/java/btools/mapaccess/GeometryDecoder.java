/**
 * Container for link between two Osm nodes
 *
 * @author ab
 */
package btools.mapaccess;

import btools.util.ByteDataReader;


public final class GeometryDecoder {
  private ByteDataReader r = new ByteDataReader(null);
  private OsmTransferNode[] cachedNodes;
  private int nCachedNodes = 128;

  // result-cache
  private OsmTransferNode firstTransferNode;
  private boolean lastReverse;
  private byte[] lastGeometry;

  public GeometryDecoder() {
    // create some caches
    cachedNodes = new OsmTransferNode[nCachedNodes];
    for (int i = 0; i < nCachedNodes; i++) {
      cachedNodes[i] = new OsmTransferNode();
    }
  }

  public OsmTransferNode decodeGeometry(byte[] geometry, OsmNode sourceNode, OsmNode targetNode, boolean reverseLink) {
    if ((lastGeometry == geometry) && (lastReverse == reverseLink)) {
      return firstTransferNode;
    }

    firstTransferNode = null;
    OsmTransferNode lastTransferNode = null;
    OsmNode startnode = reverseLink ? targetNode : sourceNode;
    r.reset(geometry);
    int olon = startnode.ilon;
    int olat = startnode.ilat;
    int oselev = startnode.selev;
    int idx = 0;
    while (r.hasMoreData()) {
      OsmTransferNode trans = idx < nCachedNodes ? cachedNodes[idx++] : new OsmTransferNode();
      trans.ilon = olon + r.readVarLengthSigned();
      trans.ilat = olat + r.readVarLengthSigned();
      trans.selev = (short) (oselev + r.readVarLengthSigned());
      olon = trans.ilon;
      olat = trans.ilat;
      oselev = trans.selev;
      if (reverseLink) { // reverse chaining
        trans.next = firstTransferNode;
        firstTransferNode = trans;
      } else {
        trans.next = null;
        if (lastTransferNode == null) {
          firstTransferNode = trans;
        } else {
          lastTransferNode.next = trans;
        }
        lastTransferNode = trans;
      }
    }

    lastReverse = reverseLink;
    lastGeometry = geometry;

    return firstTransferNode;
  }
}
