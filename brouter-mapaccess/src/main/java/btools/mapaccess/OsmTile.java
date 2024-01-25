package btools.mapaccess;

import btools.codec.*;

import java.util.HashMap;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * OsmTile handles the encoding and decoding of the nodes in a tile (1/32*1/32 degree)
 * to and from the serialized file format.
 */
public final class OsmTile {

  private final int lonBase;
  private final int latBase;

  public OsmTile(int lonBase, int latBase) {
    this.lonBase = lonBase;
    this.latBase = latBase;
  }

  public void decodeTile(StatCoderContext bc, DataBuffers dataBuffers, TagValueValidator wayValidator, WaypointMatcher waypointMatcher, OsmNodesMap hollowNodes) {

    TagValueCoder wayTagCoder = new TagValueCoder(bc, dataBuffers, wayValidator);
    TagValueCoder nodeTagCoder = new TagValueCoder(bc, dataBuffers, null);
    NoisyDiffCoder nodeIdxDiff = new NoisyDiffCoder(bc);
    NoisyDiffCoder nodeEleDiff = new NoisyDiffCoder(bc);
    NoisyDiffCoder extLonDiff = new NoisyDiffCoder(bc);
    NoisyDiffCoder extLatDiff = new NoisyDiffCoder(bc);

    int size = bc.decodeNoisyNumber(5);

    int[] shrinkedIds = size > dataBuffers.ibuf2.length ? new int[size] : dataBuffers.ibuf2;

    bc.decodeSortedArray(shrinkedIds, 0, size, 29, 0);

    OsmNode[] nodes = new OsmNode[size];
    for (int n = 0; n < size; n++) {
      long id = expandId(shrinkedIds[n]);
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

      // future escapes (turn restrictions?)
      for (; ; ) {
        int featureId = bc.decodeVarBits();
        if (featureId == 0) break;
        int bitsize = bc.decodeNoisyNumber(5);

        if (featureId == 1) { // turn-restriction
          TurnRestriction tr = new TurnRestriction();
          tr.isPositive = bc.decodeBit();
          tr.exceptions = (short) bc.decodeBounded(1023);;
          tr.fromLon = node.iLon + bc.decodeNoisyDiff(10);
          tr.fromLat = node.iLat + bc.decodeNoisyDiff(10);
          tr.toLon = node.iLon + bc.decodeNoisyDiff(10);
          tr.toLat = node.iLat + bc.decodeNoisyDiff(10);
          node.addTurnRestriction(tr);
        } else {
          for (int i = 0; i < bitsize; i++) bc.decodeBit(); // unknown feature, just skip
        }
      }

      selev += nodeEleDiff.decodeSignedValue();
      node.sElev = (short) selev;
      TagValueWrapper nodeTags = nodeTagCoder.decodeTagValueSet();
      node.nodeDescription = nodeTags == null ? null : nodeTags.data; // TODO: unified?

      int links = bc.decodeNoisyNumber(1);
      for (int li = 0; li < links; li++) {
        int nodeIdx = n + nodeIdxDiff.decodeSignedValue();

        int linkLon;
        int linkLat;

        boolean isReverse = false;
        if (nodeIdx != n) { // internal (forward-) link
          linkLon = nodes[nodeIdx].iLon;
          linkLat = nodes[nodeIdx].iLat;
        } else {
          isReverse = bc.decodeBit();
          linkLon = node.iLon + extLonDiff.decodeSignedValue();
          linkLat = node.iLat + extLatDiff.decodeSignedValue();
        }

        TagValueWrapper wayTags = wayTagCoder.decodeTagValueSet();

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

            OsmLink link = node.linkForTarget(linkLon,linkLat );
            if ( link != null ) {
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
      } // ... loop over links
    } // ... loop over nodes

    hollowNodes.cleanupAndCount(nodes);
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

  public int encodeTile(List<OsmNode> nodes, byte[] buffer) {
    SortedMap<Integer,OsmNode> sortedNodes = sortNodes(nodes);

    HashMap<Long, Integer> idMap = new HashMap<>();
    int nodeIndex = 0;
    for (OsmNode node : sortedNodes.values() ) { // loop over nodes
      idMap.put(node.getIdFromPos(), nodeIndex++);
    }
    int[] shrinkedIds = new int[sortedNodes.size()];
    nodeIndex = 0;
    for( int shrinkedId : sortedNodes.keySet() ) {
      shrinkedIds[nodeIndex++] = shrinkedId;
    }
    IntegerFifo3Pass linkCounts = new IntegerFifo3Pass(256);
    IntegerFifo3Pass restrictionBits = new IntegerFifo3Pass(16);

    TagValueCoder wayTagCoder = new TagValueCoder();
    TagValueCoder nodeTagCoder = new TagValueCoder();
    NoisyDiffCoder nodeIdxDiff = new NoisyDiffCoder();
    NoisyDiffCoder nodeEleDiff = new NoisyDiffCoder();
    NoisyDiffCoder extLonDiff = new NoisyDiffCoder();
    NoisyDiffCoder extLatDiff = new NoisyDiffCoder();

    for (int pass = 1; ; pass++) { // 3 passes: counters, stat-collection, encoding
      StatCoderContext bc = new StatCoderContext(buffer);

      linkCounts.init();
      restrictionBits.init();

      wayTagCoder.encodeDictionary(bc);
      nodeTagCoder.encodeDictionary(bc);
      nodeIdxDiff.encodeDictionary(bc);
      nodeEleDiff.encodeDictionary(bc);
      extLonDiff.encodeDictionary(bc);
      extLatDiff.encodeDictionary(bc);
      bc.encodeNoisyNumber(shrinkedIds.length, 5);
      bc.encodeSortedArray(shrinkedIds, 0, shrinkedIds.length, 0x20000000, 0);
      int lastSelev = 0;

      nodeIndex = 0;
      for (OsmNode node : sortedNodes.values() ) { // loop over nodes

        int ilon = node.iLon;
        int ilat = node.iLat;

        // write turn restrictions
        TurnRestriction tr = node.firstRestriction;
        while (tr != null) {
          bc.encodeVarBits(1); // 1 = turn restriction
          bc.encodeNoisyNumber(restrictionBits.getNext(), 5); // bit-count using look-ahead fifo
          long b0 = bc.getWritingBitPosition();
          bc.encodeBit(tr.isPositive);
          bc.encodeBounded(1023, tr.exceptions & 1023);
          bc.encodeNoisyDiff(tr.fromLon - ilon, 10);
          bc.encodeNoisyDiff(tr.fromLat - ilat, 10);
          bc.encodeNoisyDiff(tr.toLon - ilon, 10);
          bc.encodeNoisyDiff(tr.toLat - ilat, 10);
          restrictionBits.add((int) (bc.getWritingBitPosition() - b0));
          tr = tr.next;
        }
        bc.encodeVarBits(0); // end of extra data

        int sElev = node.sElev;
        nodeEleDiff.encodeSignedValue(sElev - lastSelev);
        lastSelev = sElev;
        nodeTagCoder.encodeTagValueSet(node.nodeDescription);
        int nlinks = linkCounts.getNext();
        bc.encodeNoisyNumber(nlinks, 1);

        nlinks = 0;
        for( OsmLink link = node.firstLink; link != null; link = link.getNext(node)) { // loop over links

          OsmNode target = link.getTarget(node);
          int ilonlink = target.iLon;
          int ilatlink = target.iLat;
          boolean isReverse = link.isReverse(node);
          byte[] description = link.wayDescription;

          Integer idx = idMap.get(target.getIdFromPos());
          boolean isInternal = idx != null;

          if (isReverse && isInternal) {
            continue; // do not encode internal reverse links
          }
          nlinks++;
          if (isInternal) {
            if (idx == nodeIndex) throw new RuntimeException("ups: self ref?");
            nodeIdxDiff.encodeSignedValue(idx - nodeIndex);
          } else {
            nodeIdxDiff.encodeSignedValue(0);
            bc.encodeBit(isReverse);
            extLonDiff.encodeSignedValue(ilonlink - ilon);
            extLatDiff.encodeSignedValue(ilatlink - ilat);
          }
          wayTagCoder.encodeTagValueSet(description);
        }
        linkCounts.add(nlinks);
        nodeIndex++;
      }
      if (pass == 3) {
        return bc.closeAndGetEncodedLength();
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
