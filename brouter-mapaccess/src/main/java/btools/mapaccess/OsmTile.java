package btools.mapaccess;

import btools.statcoding.BitInputStream;
import btools.statcoding.BitOutputStream;
import btools.statcoding.codecs.AdaptiveDiffDecoder;
import btools.statcoding.codecs.AdaptiveDiffEncoder;
import btools.statcoding.huffman.HuffmanDecoder;
import btools.statcoding.huffman.HuffmanEncoder;
import btools.util.SimpleByteArrayOutputStream;
import btools.util.TagValueValidator;

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

  private long[] shringIDs;
  private OsmNode[] nodes;
  private OsmNodesMap hollowNodes;

  public int decodeTile(byte[] buffer, DataBuffers dataBuffers, TagValueValidator wayValidator, WaypointMatcher waypointMatcher, OsmNodesMap hollowNodes) throws IOException {

    BitInputStream bis = new BitInputStream( new ByteArrayInputStream( buffer ) );
    TagValueDecoder wayTagDecoder = new TagValueDecoder(dataBuffers,wayValidator);
    TagValueDecoder nodeTagDecoder = new TagValueDecoder(dataBuffers,null);
    HuffmanDecoder<Integer> indexDeltaDecoder  = new HuffmanDecoder<>() {
      protected Integer decodeObjectFromStream() throws IOException {
        return (int)bis.decodeBits(5); // 0=no more link 1=big delta 2..30 = delta+16
      }
    };


    AdaptiveDiffDecoder nodeEleDiff = new AdaptiveDiffDecoder(bis);
    AdaptiveDiffDecoder extLonDiff = new AdaptiveDiffDecoder(bis);
    AdaptiveDiffDecoder extLatDiff = new AdaptiveDiffDecoder(bis);
    wayTagDecoder.init( bis, 10 );
    nodeTagDecoder.init( bis, 10 );
    indexDeltaDecoder.init( bis, 7 );
    shringIDs = bis.decodeUniqueSortedArray();
    int size = shringIDs.length;
    nodes = new OsmNode[size];
    this.hollowNodes = hollowNodes;

    for (int n = 0; n < size; n++) { // loop over nodes
      for (;;) { // loop over links
        int deltaCode = indexDeltaDecoder.decodeObject();
        if ( deltaCode == 0 ) {
          break;
        }
        int nodeIdxDelta = deltaCode == 1 ? (int)bis.decodeSignedVarBits(6) : deltaCode-16;
        int nodeIdx = n + nodeIdxDelta;
        boolean isExternal = nodeIdx == n;
        boolean isReverse = isExternal ? bis.decodeBit() : false;
        int lonDiff = isExternal ? (int)extLonDiff.decode() : 0;
        int latDiff = isExternal ? (int)extLatDiff.decode() : 0;
        TagValueWrapper wayTags = wayTagDecoder.decodeObject();
        if (wayTags != null) {
          OsmNode sourceNode = getNode(n);
          OsmLink link = null;
          OsmNode targetNode = getNode(nodeIdx);
          if (isExternal) {
            int linkLon = sourceNode.iLon + lonDiff;
            int linkLat = sourceNode.iLat + latDiff;
            link = sourceNode.linkForTarget(linkLon, linkLat);
            if (link != null) {
              link.wayDescription = wayTags.data; // TODO: 2-node-loops?
              targetNode = link.getTarget(sourceNode);
            } else {
              // .. not found, check the hollow nodes
              targetNode = hollowNodes.get(linkLon, linkLat);
              if (targetNode == null) { // node not yet known, create a new hollow proxy
                targetNode = new OsmNode(linkLon, linkLat);
                targetNode.setHollow();
                hollowNodes.put(targetNode);
              }
            }
          }
          if (link == null) {
            if (isReverse) {
              targetNode.createLink(wayTags.data, sourceNode);
            } else {
              sourceNode.createLink(wayTags.data, targetNode);
            }
          }
          if (waypointMatcher != null && !isReverse && wayTags.accessType > 1) {
            waypointMatcher.match(sourceNode, targetNode);
          }
          sourceNode.visitID = 1;
        }
      }  // ... loop over links
    } // ... loop over nodes

    int selev = 0;
    OsmNode dummy = new OsmNode(0,0);
    for (int n = 0; n < size; n++) { // loop over nodes

      OsmNode node = nodes[n] != null ? nodes[n] : dummy;
      selev += nodeEleDiff.decode();
      node.sElev = (short) selev;
      TagValueWrapper nodeTags = nodeTagDecoder.decodeObject();
      node.nodeDescription = nodeTags == null ? null : nodeTags.data; // TODO: unified?

      // decode TRs
      while (bis.decodeBit()) {
        TurnRestriction tr = new TurnRestriction();
        tr.isPositive = bis.decodeBit();
        tr.exceptions = (short) bis.decodeBounded(1023);
        tr.fromLon = (int) (node.iLon + bis.decodeSignedVarBits(10));
        tr.fromLat = (int) (node.iLat + bis.decodeSignedVarBits(10));
        tr.toLon = (int) (node.iLon + bis.decodeSignedVarBits(10));
        tr.toLat = (int) (node.iLat + bis.decodeSignedVarBits(10));
        node.addTurnRestriction(tr);
      }
    }


    hollowNodes.cleanupAndCount(nodes);
    return buffer.length - bis.available();
  }

  private OsmNode getNode( int idx ) {
    if ( nodes[idx] == null ) {
      long id = expandId((int) shringIDs[idx]);
      int iLon = (int) (id >> 32);
      int iLat = (int) (id & 0xffffffff);
      OsmNode node = hollowNodes.get(iLon, iLat);
      if (node == null) {
        node = new OsmNode(iLon, iLat);
      } else {
        node.visitID = 1;
        hollowNodes.remove(node);
      }
      nodes[idx] = node;
    }
    return nodes[idx];
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
    HuffmanEncoder<Integer> indexDeltaEncoder  = new HuffmanEncoder<>() {
      protected void encodeObjectToStream(Integer i) throws IOException {
        bos.encodeBits(5,i); // 0=no more link 1=big delta 2..30 = delta+16
      }
    };

    for (int pass = 1;; pass++) { // 2 passes: huffman-stats, final write
      BitOutputStream bos = new BitOutputStream( new SimpleByteArrayOutputStream( buffer ) );
      wayTagEncoder.init(bos);
      nodeTagEncoder.init(bos);
      indexDeltaEncoder.init(bos);
      AdaptiveDiffEncoder nodeEleDiff = new AdaptiveDiffEncoder(bos);
      AdaptiveDiffEncoder extLonDiff = new AdaptiveDiffEncoder(bos);
      AdaptiveDiffEncoder extLatDiff = new AdaptiveDiffEncoder(bos);


      bos.encodeUniqueSortedArray( shrinkIDs );

      nodeIndex = 0;
      for (OsmNode node : sortedNodes.values() ) { // loop over nodes
        for (OsmLink link = node.firstLink; link != null; link = link.getNext(node)) { // loop over links
          OsmNode target = link.getTarget(node);
          boolean isReverse = link.isReverse(node);
          Integer idx = idMap.get(target.getIdFromPos());
          boolean isInternal = idx != null;
          if (isReverse && isInternal) {
            continue; // do not encode internal reverse links
          }
          if (isInternal) {
            int delta = idx - nodeIndex;
            if (delta == 0) throw new RuntimeException("ups: self ref?");
            if ( Math.abs( delta ) <= 14 ) {
              indexDeltaEncoder.encodeObject( delta+16 );
            } else {
              indexDeltaEncoder.encodeObject( 1 );
              bos.encodeSignedVarBits(delta,6);
            }
          } else {
            indexDeltaEncoder.encodeObject( 16 );
            bos.encodeBit(isReverse);
            extLonDiff.encode(target.iLon - node.iLon);
            extLatDiff.encode(target.iLat - node.iLat);
          }
System.out.println( "way tags... ") ;
          wayTagEncoder.encodeTagValues(link.wayDescription);
        }
        indexDeltaEncoder.encodeObject( 0 );
        nodeIndex++;
      }

      int lastSelev = 0;
      nodeIndex = 0;
      for (OsmNode node : sortedNodes.values() ) { // loop over nodes again

        // write elevation and node tags
        int sElev = node.sElev;
        nodeEleDiff.encode(sElev - lastSelev);
        lastSelev = sElev;
        System.out.println( "node tags... ") ;
        nodeTagEncoder.encodeTagValues(node.nodeDescription);

        // write turn restrictions
        TurnRestriction tr = node.firstRestriction;
        while (tr != null) {
          bos.encodeBit(true);  // TR follows
          bos.encodeBit(tr.isPositive);
          bos.encodeSignedVarBits(tr.fromLon - node.iLon, 10);
          bos.encodeSignedVarBits(tr.fromLat - node.iLat, 10);
          bos.encodeSignedVarBits(tr.toLon - node.iLon, 10);
          bos.encodeSignedVarBits(tr.toLat - node.iLat, 10);
          tr = tr.next;
        }
        bos.encodeBit(false); // end of TRs


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
