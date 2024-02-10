package btools.mapaccess;

import btools.statcoding.BitInputStream;
import btools.statcoding.BitInterleavedConverter;
import btools.statcoding.BitOutputStream;
import btools.statcoding.codecs.AdaptiveDiffDecoder;
import btools.statcoding.codecs.AdaptiveDiffEncoder;
import btools.statcoding.codecs.Number2PassDecoder;
import btools.statcoding.codecs.Number2PassEncoder;
import btools.statcoding.huffman.HuffmanDecoder;
import btools.statcoding.huffman.HuffmanEncoder;
import btools.util.SimpleByteArrayOutputStream;
import btools.util.TagValueValidator;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.*;

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

  private long[] shrinkIDs;
  private OsmNode[] nodes;
  private OsmNodesMap hollowNodes;

  public int decodeTile(byte[] buffer, DataBuffers dataBuffers, TagValueValidator wayValidator, WaypointMatcher waypointMatcher, OsmNodesMap hollowNodes) throws IOException {

    BitInputStream bis = new BitInputStream( new ByteArrayInputStream( buffer ) );
    TagValueDecoder wayTagDecoder = new TagValueDecoder(dataBuffers,wayValidator);
    TagValueDecoder nodeTagDecoder = new TagValueDecoder(dataBuffers,null);
    HuffmanDecoder<Integer> indexDeltaDecoder1 = new HuffmanDecoder<>() {
      protected Integer decodeObjectFromStream() throws IOException {
        return (int)bis.decodeBits(3); // 0=no more link 1=big delta 2..30 = delta+16
      }
    };
    HuffmanDecoder<Integer> indexDeltaDecoder2 = new HuffmanDecoder<>() {
      protected Integer decodeObjectFromStream() throws IOException {
        return (int)bis.decodeBits(3); // 0=no more link 1=big delta 2..30 = delta+16
      }
    };
    HuffmanDecoder<Integer> linkCountDecoder  = new HuffmanDecoder<>() {
      protected Integer decodeObjectFromStream() throws IOException {
        return (int)bis.decodeUnsignedVarBits(2); // 0=no more link 1=big delta 2..30 = delta+16
      }
    };
    Number2PassDecoder extDiffDecoder = new Number2PassDecoder();
    Number2PassDecoder largeDiffDecoder = new Number2PassDecoder();
    AdaptiveDiffDecoder nodeEleDiff = new AdaptiveDiffDecoder(bis);
    wayTagDecoder.init( bis, 10 );
    nodeTagDecoder.init( bis, 10 );
    indexDeltaDecoder1.init( bis, 7 );
    indexDeltaDecoder2.init( bis, 7 );
    linkCountDecoder.init( bis, 5 );
    largeDiffDecoder.init(bis);
    extDiffDecoder.init(bis);
    shrinkIDs = bis.decodeUniqueSortedArray();
    int size = shrinkIDs.length;
    nodes = new OsmNode[size];
    this.hollowNodes = hollowNodes;

    int[] linksReceived = new int[size];
    OsmNode dummy = new OsmNode(0,0);

    for (int n = 0; n < size; n++) { // loop over nodes

      int links = linkCountDecoder.decodeObject();
      boolean isTransferNode = links == 0;
      int linksToRead = (isTransferNode ? 2 : links ) - linksReceived[n];
      for (int linkIndex=0; linkIndex < linksToRead;  linkIndex++) { // loop over links
        int deltaCode = isTransferNode ? indexDeltaDecoder1.decodeObject() : indexDeltaDecoder2.decodeObject();
        int nodeIdxDelta = deltaCode == 7 ? (int) largeDiffDecoder.decode() + 7: deltaCode;
        int nodeIdx = n + nodeIdxDelta;
        boolean isExternal = deltaCode == 0;
        int lonDiff = isExternal ? (int) extDiffDecoder.decode() : 0;
        int latDiff = isExternal ? (int) extDiffDecoder.decode() : 0;

        // transfer nodes eventually know their tagging already
        byte[] wayDescription = null;
        boolean isReverse = false;
        if (isTransferNode && linksReceived[n] > 0) {
          if (nodes[n] != null) {
            OsmLink template = nodes[n].firstLink;
            wayDescription = template.wayDescription;
            isReverse = !template.isReverse(nodes[n]);
          }
        } else {
          isReverse = bis.decodeBit();
          TagValueWrapper w = wayTagDecoder.decodeObject();
          wayDescription = w == null ? null : w.data;
        }
        linksReceived[n]++;
        if (!isExternal) {
          linksReceived[nodeIdx]++;
        }
        // do the actual weaving only if the way is routable
        if (wayDescription != null) {
          OsmNode sourceNode = getNode(n);
          OsmLink link = null;
          OsmNode targetNode = getNode(nodeIdx);
          if (isExternal) {
            int linkLon = sourceNode.iLon + lonDiff;
            int linkLat = sourceNode.iLat + latDiff;
            link = sourceNode.linkForTarget(linkLon, linkLat);
            if (link != null) {
              link.wayDescription = wayDescription; // TODO: 2-node-loops?
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
              targetNode.createLink(wayDescription, sourceNode);
            } else {
              sourceNode.createLink(wayDescription, targetNode);
            }
          }
          if (waypointMatcher != null) {
            waypointMatcher.match(sourceNode, targetNode);
          }
          sourceNode.visitID = 1;
        }
      }  // ... loop over links

      OsmNode node = nodes[n] != null ? nodes[n] : dummy;
      node.sElev = (short) nodeEleDiff.decode();

      if ( isTransferNode ) {
        continue; // node feature processing only for network nodes
      }

      for(;;) {
        long feature = bis.decodeUnsignedVarBits();
        if ( feature == 0L ) {
          break; // no more node features
        }
        if ( feature == 1L ) {
          TagValueWrapper nodeTags = nodeTagDecoder.decodeObject();
          node.nodeDescription = nodeTags == null ? null : nodeTags.data; // TODO: unified?
        } else if ( feature == 2L ){
          // decode TR
          TurnRestriction tr = new TurnRestriction();
          tr.isPositive = bis.decodeBit();
          tr.exceptions = (short) bis.decodeUnsignedVarBits(10);
          tr.fromLon = (int) (node.iLon + bis.decodeSignedVarBits(10));
          tr.fromLat = (int) (node.iLat + bis.decodeSignedVarBits(10));
          tr.toLon = (int) (node.iLon + bis.decodeSignedVarBits(10));
          tr.toLat = (int) (node.iLat + bis.decodeSignedVarBits(10));
          node.addTurnRestriction(tr);
        } else {
          throw new RuntimeException( "unknown node feature: " + feature );
        }
      }
    }


    hollowNodes.cleanupAndCount(nodes);
    return buffer.length - bis.available();
  }

  private OsmNode getNode( int idx ) {
    if ( nodes[idx] == null ) {
      long s32 = BitInterleavedConverter.interleaved2Shift32(shrinkIDs[idx]);
      int iLon = lonBase + BitInterleavedConverter.xFromShift32(s32);
      int iLat = latBase + BitInterleavedConverter.yFromShift32(s32);
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

  public int encodeTile(List<OsmNode> nodes, byte[] buffer, Map<String,long[]> bitStatistics) throws IOException {
    SortedMap<Long,OsmNode> sortedNodes = sortNodes(nodes);

    HashMap<Long, Integer> idMap = new HashMap<>();
    int size = sortedNodes.size();
    OsmNode[] nodeArray = new OsmNode[size];
    long[] shrinkIDs = new long[size];
    int nodeIdx = 0;
    for (Map.Entry<Long,OsmNode> e : sortedNodes.entrySet() ) { // loop over nodes
      OsmNode node = e.getValue();
      shrinkIDs[nodeIdx] = e.getKey();
      nodeArray[nodeIdx] = node;
      idMap.put(node.getIdFromPos(), nodeIdx++);
    }
    TagValueEncoder wayTagEncoder = new TagValueEncoder();
    TagValueEncoder nodeTagEncoder = new TagValueEncoder();
    HuffmanEncoder<Integer> indexDeltaEncoder1  = new HuffmanEncoder<>() {
      protected void encodeObjectToStream(Integer i) throws IOException {
        bos.encodeBits(3,i); // 0=extern, 1..31 = delta, 32=big Delta
      }
    };
    HuffmanEncoder<Integer> indexDeltaEncoder2  = new HuffmanEncoder<>() {
      protected void encodeObjectToStream(Integer i) throws IOException {
        bos.encodeBits(3,i); // 0=extern, 1..31 = delta, 32=big Delta
      }
    };
    HuffmanEncoder<Integer> linkCountEncoder  = new HuffmanEncoder<>() {
      protected void encodeObjectToStream(Integer i) throws IOException {
        bos.encodeUnsignedVarBits(i,2);
      }
    };
    Number2PassEncoder extDiffEncoder = new Number2PassEncoder();
    Number2PassEncoder largeDiffEncoder = new Number2PassEncoder();

    for (int pass = 1;; pass++) { // 2 passes: huffman-stats, final write

      int[] linksReceived = new int[size];

      BitOutputStream bos = new BitOutputStream( new SimpleByteArrayOutputStream( buffer ) );
      if ( pass == 2 ) {
        bos.registerBitStatistics(bitStatistics);
      }
      wayTagEncoder.init(bos);
      bos.assignBits( "dictionary way");
      nodeTagEncoder.init(bos);
      bos.assignBits( "dictionary node");
      indexDeltaEncoder1.init(bos);
      indexDeltaEncoder2.init(bos);
      bos.assignBits( "dictionary delta");
      linkCountEncoder.init(bos);
      largeDiffEncoder.init(bos);
      extDiffEncoder.init(bos);
      bos.assignBits( "dictionary linkcount");
      AdaptiveDiffEncoder nodeEleDiff = new AdaptiveDiffEncoder(bos);

      bos.encodeUniqueSortedArray( shrinkIDs );
      bos.assignBits( "lon/lat coords");

      for (int nodeIndex = 0; nodeIndex<size; nodeIndex++ ) { // loop over nodes
        OsmNode node = nodeArray[nodeIndex];

        // check classify as transfer node
        boolean isTransferNode = false;
        if (node.nodeDescription == null && node.firstRestriction == null && node.linkCount() == 2) {
            OsmLink l1 = node.getFirstLink();
            OsmLink l2 = l1.getNext(node);
            if (Arrays.equals(l1.wayDescription, l2.wayDescription)) {
              if (l1.isReverse(node) != l2.isReverse(node)) {
                Integer idx1 = idMap.get(l1.getTarget(node).getIdFromPos());
                Integer idx2 = idMap.get(l2.getTarget(node).getIdFromPos());
                if (idx1 != null && idx2 != null) {
                  isTransferNode = true;
                }
              }
            }
        }
        linkCountEncoder.encodeObject( isTransferNode ? 0  : node.linkCount() );
        bos.assignBits( "link count");
        for (OsmLink link = node.firstLink; link != null; link = link.getNext(node)) { // loop over links

          OsmNode target = link.getTarget(node);
          boolean isReverse = link.isReverse(node);
          Integer idx = idMap.get(target.getIdFromPos());
          boolean isInternal = idx != null;
          if (isInternal && idx < nodeIndex) {
            continue; // do not encode internal backward links
          }
          HuffmanEncoder<Integer> indexDeltaEncoder = isTransferNode ? indexDeltaEncoder1 : indexDeltaEncoder2;
          if (isInternal) {
            int delta = idx - nodeIndex;
            if (delta <= 0) throw new RuntimeException("ups: self ref?");
            if ( delta< 7 ) {
              indexDeltaEncoder.encodeObject( delta );
              bos.assignBits( "delta small");
            } else {
              indexDeltaEncoder.encodeObject( 7 );
              bos.assignBits( "delta large marker");
              largeDiffEncoder.encode(delta-7 );
              bos.assignBits( "delta large");
            }
            linksReceived[idx]++;
          } else {
            indexDeltaEncoder.encodeObject( 0 );
            bos.assignBits( "delta extern marker ");
            extDiffEncoder.encode(target.iLon - node.iLon );
            extDiffEncoder.encode(target.iLat - node.iLat );
            bos.assignBits( "delta extern");
          }
          if ( !isTransferNode || linksReceived[nodeIndex] == 0 ) {
            bos.encodeBit(isReverse);
            wayTagEncoder.encodeTagValues(link.wayDescription);
            bos.assignBits( "tags way");
          }
          linksReceived[nodeIndex]++;
        }

        // write elevation and node tags
        int sElev = node.sElev;
        nodeEleDiff.encode(sElev);
        bos.assignBits( "elevation");

         if ( !isTransferNode ) { // node features for network nodes only
          if ( node.nodeDescription != null ) {
            bos.encodeUnsignedVarBits(1L );  // node tags follow
            nodeTagEncoder.encodeTagValues(node.nodeDescription);
            bos.assignBits("tags node");
          }

          // write turn restrictions
          TurnRestriction tr = node.firstRestriction;
          while (tr != null) {
            bos.encodeUnsignedVarBits(2L );  // TR follows
            bos.encodeBit(tr.isPositive);
            bos.encodeUnsignedVarBits(tr.exceptions, 10);
            bos.encodeSignedVarBits(tr.fromLon - node.iLon, 10);
            bos.encodeSignedVarBits(tr.fromLat - node.iLat, 10);
            bos.encodeSignedVarBits(tr.toLon - node.iLon, 10);
            bos.encodeSignedVarBits(tr.toLat - node.iLat, 10);
            tr = tr.next;
            bos.assignBits("turn restrictions");
          }
          bos.encodeUnsignedVarBits(0L );  // node features finished
          bos.assignBits("feature stop");
        }
      }
      if (pass == 2) {
        bos.close();
        return (int)(bos.getBitPosition() >> 3);
      }
    }
  }

  private SortedMap<Long,OsmNode> sortNodes(List<OsmNode> nodes ) {
    // sort via treemap
    TreeMap<Long, OsmNode> sortedList = new TreeMap<>();
    for (OsmNode n : nodes) {
      long il = BitInterleavedConverter.xy2Interleaved(n.iLon-lonBase,n.iLat-latBase);
      sortedList.put(il, n);
    }
    return sortedList;
  }
}
