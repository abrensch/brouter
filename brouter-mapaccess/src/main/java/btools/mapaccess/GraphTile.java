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
import btools.util.TagValueValidator;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.*;

/**
 * GraphTile handles the encoding and decoding of the nodes in a tile
 * (typically a 1/32*1/32 degree square)
 * to and from the serialized file format.
 */
public final class GraphTile {

  private final int lonBase;
  private final int latBase;

  public GraphTile(int lonBase, int latBase) {
    this.lonBase = lonBase;
    this.latBase = latBase;
  }

  private long[] shrinkIDs;
  private GraphNode[] nodes;
  private Map<Long, GraphNode> hollowNodes;

  /**
   * Decodes the nodes of this tile from the serialized format and weaves them
   * into the routing graph. That's the twin of {@link #encodeTile(List, BitOutputStream)}.
   * <p>
   * Nodes are created lazily: an {@link GraphNode} instance is only allocated
   * (or taken over from {@code hollowNodes}) when at least one routable link
   * (way description != null, as decided by {@code wayValidator}) touches it.
   * <p>
   * The codec uses advanced statistical coding techniques as provided by the
   * {@link btools.statcoding} package.
   * <p>
   * If a node is decoded that pre-exists as a hollow node in {@code hollowNodes},
   * it is removed from hollow noded and has it's hollow bit cleared.
   *
   * @param buffer          the serialized tile data
   * @param wayValidator    validator deciding which way tags are routable
   * @param waypointMatcher optional matcher called for each woven link, may be null
   * @param hollowNodes     global map of known hollow nodes, maintaining object identity across tiles
   * @return the number of bytes consumed from the buffer
   * @throws IOException      if shit happens
   */
  public int decodeTile(byte[] buffer, TagValueValidator wayValidator, WaypointMatcher waypointMatcher, Map<Long, GraphNode> hollowNodes) throws IOException {

    BitInputStream bis = new BitInputStream( new ByteArrayInputStream( buffer ) );
    TagValueDecoder wayTagDecoder = new TagValueDecoder(wayValidator);
    TagValueDecoder nodeTagDecoder = new TagValueDecoder(null);
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
    nodes = new GraphNode[size];
    this.hollowNodes = hollowNodes;

    int[] linksReceived = new int[size];
    GraphNode dummy = new GraphNode(0,0);

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
            GraphLink template = nodes[n].firstLink;
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
          GraphNode sourceNode = getNode(n);
          GraphLink link = null;
          GraphNode targetNode = getNode(nodeIdx);
          if (isExternal) {
            int linkLon = sourceNode.iLon + lonDiff;
            int linkLat = sourceNode.iLat + latDiff;
            link = sourceNode.anyLinkForTarget( linkLon, linkLat);
            if (link != null) {
              targetNode = link.getTarget(sourceNode);
              if ( targetNode.isHollow() ) {
                link = null;
              }

            } else {
              // .. not found, check the hollow nodes
              targetNode = hollowNodes.get(GraphNode.idFromPos(linkLon, linkLat));
              if (targetNode == null) { // node not yet known, create a new hollow proxy
                targetNode = new GraphNode(linkLon, linkLat);
                targetNode.setBits( GraphNode.HOLLOW_BIT );
                hollowNodes.put(GraphNode.idFromPos(linkLon, linkLat), targetNode);
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
        }
      }  // ... loop over links

      GraphNode node = nodes[n] != null ? nodes[n] : dummy;
      node.sElev = (short) nodeEleDiff.decode();

      if (isTransferNode) {
        node.setBits(GraphNode.TRANSFERNODE_BIT); // preserve the transfer-node info
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
    return buffer.length - bis.available();
  }

  private GraphNode getNode( int idx ) {
    if ( nodes[idx] == null ) {
      long s32 = BitInterleavedConverter.interleaved2Shift32(shrinkIDs[idx]);
      int iLon = lonBase + BitInterleavedConverter.xFromShift32(s32);
      int iLat = latBase + BitInterleavedConverter.yFromShift32(s32);
      GraphNode node = hollowNodes.remove(GraphNode.idFromPos(iLon, iLat));
      if (node == null) {
        node = new GraphNode(iLon, iLat);
      } else {
        node.clearBits( GraphNode.HOLLOW_BIT );
      }
      nodes[idx] = node;
    }
    return nodes[idx];
  }

  /**
   * Encodes the given nodes into the given stream (2 passes: the first one
   * only gathers statistics and writes to a null stream).
   * The stream is not closed or re-aligned.
   */
  public void encodeTile(List<GraphNode> nodes, BitOutputStream out) throws IOException {
    SortedMap<Long,GraphNode> sortedNodes = sortNodes(nodes);

    HashMap<Long, Integer> idMap = new HashMap<>();
    int size = sortedNodes.size();
    GraphNode[] nodeArray = new GraphNode[size];
    long[] shrinkIDs = new long[size];
    int nodeIdx = 0;
    for (Map.Entry<Long,GraphNode> e : sortedNodes.entrySet() ) { // loop over nodes
      GraphNode node = e.getValue();
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

      // pass 1 only collects statistics and writes to /dev/null
      BitOutputStream bos = pass == 2 ? out : new BitOutputStream( OutputStream.nullOutputStream() );
      wayTagEncoder.init(bos);
      nodeTagEncoder.init(bos);
      indexDeltaEncoder1.init(bos);
      indexDeltaEncoder2.init(bos);
      linkCountEncoder.init(bos);
      largeDiffEncoder.init(bos);
      extDiffEncoder.init(bos);
      AdaptiveDiffEncoder nodeEleDiff = new AdaptiveDiffEncoder(bos);

      bos.encodeUniqueSortedArray( shrinkIDs );

      for (int nodeIndex = 0; nodeIndex<size; nodeIndex++ ) { // loop over nodes
        GraphNode node = nodeArray[nodeIndex];

        // check classify as transfer node
        boolean isTransferNode = false;
        if (node.nodeDescription == null && node.firstRestriction == null && node.linkCount() == 2) {
            GraphLink l1 = node.firstLink;
            GraphLink l2 = l1.getNext(node);
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
        for (GraphLink link = node.firstLink; link != null; link = link.getNext(node)) { // loop over links

          GraphNode target = link.getTarget(node);
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
            } else {
              indexDeltaEncoder.encodeObject( 7 );
              largeDiffEncoder.encode(delta-7 );
            }
            linksReceived[idx]++;
          } else {
            indexDeltaEncoder.encodeObject( 0 );
            extDiffEncoder.encode(target.iLon - node.iLon );
            extDiffEncoder.encode(target.iLat - node.iLat );
          }
          if ( !isTransferNode || linksReceived[nodeIndex] == 0 ) {
            bos.encodeBit(isReverse);
            wayTagEncoder.encodeTagValues(link.wayDescription);
          }
          linksReceived[nodeIndex]++;
        }

        // write elevation and node tags
        int sElev = node.sElev;
        nodeEleDiff.encode(sElev);

         if ( !isTransferNode ) { // node features for network nodes only
          if ( node.nodeDescription != null ) {
            bos.encodeUnsignedVarBits(1L );  // node tags follow
            nodeTagEncoder.encodeTagValues(node.nodeDescription);
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
          }
          bos.encodeUnsignedVarBits(0L );  // node features finished
        }
      }
      if (pass == 2) {
        return; // caller owns the stream and is responsible for re-alignment/closing
      }
    }
  }

  private SortedMap<Long,GraphNode> sortNodes(List<GraphNode> nodes ) {
    // sort via treemap
    TreeMap<Long, GraphNode> sortedList = new TreeMap<>();
    for (GraphNode n : nodes) {
      long il = BitInterleavedConverter.xy2Interleaved(n.iLon-lonBase,n.iLat-latBase);
      if ( sortedList.put(il, n) != null ) {
        throw new IllegalStateException("duplicate node: " + n );
      }
    }
    return sortedList;
  }
}
