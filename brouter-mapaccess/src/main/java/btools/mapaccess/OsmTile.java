package btools.mapaccess;

import btools.codec.*;
import btools.statcoding.BitInputStream;
import btools.statcoding.BitOutputStream;
import btools.statcoding.codecs.AdaptiveDiffDecoder;
import btools.statcoding.codecs.AdaptiveDiffEncoder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * OsmTile handles the encoding and decoding of the nodes in a tile
 * (typically a 1/32*1/32 degree square)
 * to and from the serialized file format.
 */
public final class OsmTile {

  private final int lonBase;
  private final int latBase;

  public OsmTile(int lonBase, int latBase) {
    this.lonBase = lonBase;
    this.latBase = latBase;
  }

  public int decodeTile(byte[] buffer, DataBuffers dataBuffers, TagValueValidator wayValidator, WaypointMatcher waypointMatcher, OsmNodesMap hollowNodes) throws IOException {

    BitInputStream bis = new BitInputStream( new ByteArrayInputStream( buffer ) );
    TagValueDecoder wayTagDecoder = new TagValueDecoder(dataBuffers,wayValidator);
    TagValueDecoder nodeTagDecoder = new TagValueDecoder(dataBuffers,null);
    AdaptiveDiffDecoder nodeIdxDiff = new AdaptiveDiffDecoder(bis);
    AdaptiveDiffDecoder nodeEleDiff = new AdaptiveDiffDecoder(bis);
    AdaptiveDiffDecoder extLonDiff = new AdaptiveDiffDecoder(bis);
    AdaptiveDiffDecoder extLatDiff = new AdaptiveDiffDecoder(bis);
    wayTagDecoder.init( bis, 10 );
    nodeTagDecoder.init( bis, 10 );
    long[] shringIDs = bis.decodeUniqueSortedArray();
    int size = shringIDs.length;

    OsmNode[] nodes = new OsmNode[size];
    for (int n = 0; n < size; n++) {
      long id = expandId((int)shringIDs[n]);
      int iLon = (int) (id >> 32);
      int iLat = (int) (id & 0xffffffff);
      OsmNode node = hollowNodes.get(iLon, iLat);
      if (node == null) {
        node = new OsmNode(iLon, iLat);
      } else {
        node.visitID = 1;
        hollowNodes.remove(node);
      }
      nodes[n] = node;
    }

    int selev = 0;
    for (int n = 0; n < size; n++) { // loop over nodes
      OsmNode node = nodes[n];

      selev += nodeEleDiff.decode();
      node.sElev = (short) selev;
      TagValueWrapper nodeTags = nodeTagDecoder.decodeObject();
      node.nodeDescription = nodeTags == null ? null : nodeTags.data; // TODO: unified?

      // decode node items
      for (; ; ) {
        long itemId = bis.decodeUnsignedVarBits();
        if (itemId == 0L) break;
        if (itemId == 2L) { // turn-restriction
          TurnRestriction tr = new TurnRestriction();
          tr.isPositive = bis.decodeBit();
          tr.exceptions = (short) bis.decodeBounded(1023);
          tr.fromLon = (int)(node.iLon + bis.decodeSignedVarBits(10));
          tr.fromLat = (int)(node.iLat + bis.decodeSignedVarBits(10));
          tr.toLon = (int)(node.iLon + bis.decodeSignedVarBits(10));
          tr.toLat = (int)(node.iLat + bis.decodeSignedVarBits(10));
          node.addTurnRestriction(tr);
        } else if (itemId == 1) { // link

          int nodeIdx = (int)(n + nodeIdxDiff.decode());
          int linkLon;
          int linkLat;
          boolean isReverse = false;
          if (nodeIdx != n) { // internal (forward-) link
            linkLon = nodes[nodeIdx].iLon;
            linkLat = nodes[nodeIdx].iLat;
          } else {
            isReverse = bis.decodeBit();
            linkLon = (int)(node.iLon + extLonDiff.decode());
            linkLat = (int)(node.iLat + extLatDiff.decode());
          }
          TagValueWrapper wayTags = wayTagDecoder.decodeObject();
          if (!isReverse) { // wp-matching for forward links only
            WaypointMatcher matcher = wayTags == null || wayTags.accessType < 2 ? null : waypointMatcher;
            if (matcher != null) {
              matcher.match(node.iLon, node.iLat, linkLon, linkLat);
            }
          }
          if (wayTags != null) {
            if (nodeIdx != n) { // internal (forward-) link
              node.createLink(wayTags.data, nodes[nodeIdx]);
            } else { // weave external link

              OsmLink link = node.linkForTarget(linkLon, linkLat);
              if (link != null) {
                link.wayDescription = wayTags.data; // TODO: 2-node-loops?
              } else {
                // .. not found, check the hollow nodes
                OsmNode tn = hollowNodes.get(linkLon, linkLat); // target node
                if (tn == null) { // node not yet known, create a new hollow proxy
                  tn = new OsmNode(linkLon, linkLat);
                  tn.setHollow();
                  hollowNodes.put(tn);
                }
                if (isReverse) {
                  tn.createLink(wayTags.data, node);
                } else {
                  node.createLink(wayTags.data, tn);
                }
              }
              node.visitID = 1;
            }
          }
        } else {
          throw new RuntimeException( "unknown item-id: " + itemId );
        }
      }  // ... loop over node items
    } // ... loop over nodes

    hollowNodes.cleanupAndCount(nodes);
    return buffer.length - bis.available();
  }

  private static final long[] id32_00 = new long[1024];
  private static final long[] id32_10 = new long[1024];
  private static final long[] id32_20 = new long[1024];

  static {
    for (int i = 0; i < 1024; i++) {
      id32_00[i] = _expandId(i);
      id32_10[i] = _expandId(i << 10);
      id32_20[i] = _expandId(i << 20);
    }
  }

  private static long _expandId(int id32) {
    int dlon = 0;
    int dlat = 0;

    for (int bm = 1; bm < 0x8000; bm <<= 1) {
      if ((id32 & 1) != 0) dlon |= bm;
      if ((id32 & 2) != 0) dlat |= bm;
      id32 >>= 2;
    }
    return ((long) dlon) << 32 | dlat;
  }

  public long expandId(int id32) {
    return (((long) lonBase) << 32 | latBase) + id32_00[id32 & 1023] + id32_10[(id32 >> 10) & 1023] + id32_20[(id32 >> 20) & 1023];
  }

  /**
   * shrink a 64-bit (lon|lat) global-id into a a 32-bit micro-cache-internal id
   */
  public int shrinkId(int iLon, int iLat) {
    int dLon = iLon - lonBase;
    int dLat = iLat - latBase;

    int id32 = 0;

    for (int bm = 0x4000; bm > 0; bm >>= 1) {
      id32 <<= 2;
      if ((dLon & bm) != 0) id32 |= 1;
      if ((dLat & bm) != 0) id32 |= 2;
    }
    return id32;
  }

  public int encodeTile(List<OsmNode> nodes, byte[] buffer) throws IOException {
    SortedMap<Integer,OsmNode> sortedNodes = sortNodes(nodes);

    HashMap<Long, Integer> idMap = new HashMap<>();
    int nodeIndex = 0;
    for (OsmNode node : sortedNodes.values() ) { // loop over nodes
      idMap.put(node.getIdFromPos(), nodeIndex++);
    }
    long[] shrinkIDs = new long[sortedNodes.size()];
    nodeIndex = 0;
    for( int shrinkID : sortedNodes.keySet() ) {
      shrinkIDs[nodeIndex++] = shrinkID;
    }
    TagValueEncoder wayTagEncoder = new TagValueEncoder();
    TagValueEncoder nodeTagEncoder = new TagValueEncoder();

    for (int pass = 1;; pass++) { // 2 passes: huffman-stats, final write
      BitOutputStream bos = new BitOutputStream( new SimpleByteArrayOutputStream( buffer ) );
      wayTagEncoder.init(bos);
      nodeTagEncoder.init(bos);
      AdaptiveDiffEncoder nodeIdxDiff = new AdaptiveDiffEncoder(bos);
      AdaptiveDiffEncoder nodeEleDiff = new AdaptiveDiffEncoder(bos);
      AdaptiveDiffEncoder extLonDiff = new AdaptiveDiffEncoder(bos);
      AdaptiveDiffEncoder extLatDiff = new AdaptiveDiffEncoder(bos);

      bos.encodeUniqueSortedArray( shrinkIDs );
      int lastSelev = 0;

      nodeIndex = 0;
      for (OsmNode node : sortedNodes.values() ) { // loop over nodes

        // write turn restrictions
        TurnRestriction tr = node.firstRestriction;
        while (tr != null) {
          bos.encodeUnsignedVarBits(2L); // 2 = turn restriction
          bos.encodeBit(tr.isPositive);
          bos.encodeSignedVarBits(tr.fromLon - node.iLon, 10);
          bos.encodeSignedVarBits(tr.fromLat - node.iLat, 10);
          bos.encodeSignedVarBits(tr.toLon - node.iLon, 10);
          bos.encodeSignedVarBits(tr.toLat - node.iLat, 10);
          tr = tr.next;
        }

        int sElev = node.sElev;
        nodeEleDiff.encode(sElev - lastSelev);
        lastSelev = sElev;
        nodeTagEncoder.encodeTagValues(node.nodeDescription);

        for( OsmLink link = node.firstLink; link != null; link = link.getNext(node)) { // loop over links

          OsmNode target = link.getTarget(node);
          boolean isReverse = link.isReverse(node);

          Integer idx = idMap.get(target.getIdFromPos());
          boolean isInternal = idx != null;

          if (isReverse && isInternal) {
            continue; // do not encode internal reverse links
          }
          bos.encodeUnsignedVarBits(1L); // 1 = link
          if (isInternal) {
            if (idx == nodeIndex) throw new RuntimeException("ups: self ref?");
            nodeIdxDiff.encode(idx - nodeIndex);
          } else {
            nodeIdxDiff.encode(0);
            bos.encodeBit(isReverse);
            extLonDiff.encode(target.iLon - node.iLon);
            extLatDiff.encode(target.iLat - node.iLat);
          }
          wayTagEncoder.encodeTagValues(link.wayDescription);
        }
        bos.encodeUnsignedVarBits(0); // end of node data
        nodeIndex++;
      }
      if (pass == 2) {
        bos.close();
        return (int)(bos.getBitPosition() >> 3);
      }
    }
  }

  private SortedMap<Integer,OsmNode> sortNodes(List<OsmNode> nodes ) {
    // sort via treemap
    TreeMap<Integer, OsmNode> sortedList = new TreeMap<>();
    for (OsmNode n : nodes) {
      sortedList.put(shrinkId(n.iLon,n.iLat), n);
    }
    return sortedList;
  }
}
