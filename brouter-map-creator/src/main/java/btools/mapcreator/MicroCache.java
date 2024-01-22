package btools.mapcreator;

import btools.codec.IntegerFifo3Pass;
import btools.codec.NoisyDiffCoder;
import btools.codec.StatCoderContext;
import btools.codec.TagValueCoder;
import btools.mapaccess.OsmLink;
import btools.mapaccess.OsmNode;
import btools.mapaccess.TurnRestriction;
import btools.util.ByteDataWriter;

import java.util.*;

/**
 * MicroCache2 is the new format that uses statistical encoding and
 * is able to do access filtering and waypoint matching during encoding
 */
public final class MicroCache extends ByteDataWriter {
  private final int[] faid;
  private final int[] fapos;

  private int size;

  private final int lonBase;
  private final int latBase;
  private final int cellSize;

  public static boolean debug = false;

  public MicroCache(int size, byte[] databuffer, int lonIdx, int latIdx, int cellSize ) {
    super(databuffer); // sets ab=databuffer, aboffset=0

    faid = new int[size];
    fapos = new int[size];
    this.size = 0;
    this.cellSize = cellSize;
    lonBase = lonIdx * cellSize;
    latBase = latIdx * cellSize;
  }

  public void finishNode(long id) {
    fapos[size] = aboffset;
    faid[size] = shrinkId(id);
    size++;
  }

  public int getSize() {
    return size;
  }

  private int startPos(int n) {
    return n > 0 ? fapos[n - 1] & 0x7fffffff : 0;
  }

  /**
   * expand a 32-bit micro-cache-internal id into a 64-bit (lon|lat) global-id
   *
   * @see #shrinkId
   */
  public long expandId(int id32) {
    int dlon = 0;
    int dlat = 0;

    for (int bm = 1; bm < 0x8000; bm <<= 1) {
      if ((id32 & 1) != 0) dlon |= bm;
      if ((id32 & 2) != 0) dlat |= bm;
      id32 >>= 2;
    }

    int lon32 = lonBase + dlon;
    int lat32 = latBase + dlat;

    return ((long) lon32) << 32 | lat32;
  }

  /**
   * shrink a 64-bit (lon|lat) global-id into a a 32-bit micro-cache-internal id
   *
   * @see #expandId
   */
  public int shrinkId(long id64) {
    int lon32 = (int) (id64 >> 32);
    int lat32 = (int) (id64 & 0xffffffff);
    int dlon = lon32 - lonBase;
    int dlat = lat32 - latBase;


    if ( dlon >= cellSize || dlat >= cellSize || dlon < 0 || dlat < 0 ) throw new RuntimeException( "*** out of ranage: dlon=" + dlon + " dlat=" + dlat );
    int id32 = 0;

    for (int bm = 0x4000; bm > 0; bm >>= 1) {
      id32 <<= 2;
      if ((dlon & bm) != 0) id32 |= 1;
      if ((dlat & bm) != 0) id32 |= 2;
    }
    return id32;
  }

  /**
   * @return true if the given lon/lat position is internal for that micro-cache
   */
  public boolean isInternal(int ilon, int ilat) {
    return ilon >= lonBase && ilon < lonBase + cellSize
      && ilat >= latBase && ilat < latBase + cellSize;
  }

  public int encodeMicroCache(List<OsmNode> nodes, byte[] buffer) {
    SortedMap<Integer,OsmNode> sortedNodes = sortNodes(nodes);

    for( OsmNode n: sortedNodes.values() ) {
      writeNodeData(n);
    }
    return encodeMicroCache(buffer);
  }

  public int encodeMicroCache2(List<OsmNode> nodes, byte[] buffer) {
    SortedMap<Integer,OsmNode> sortedNodes = sortNodes(nodes);

    HashMap<Long, Integer> idMap = new HashMap<>();
    int nodeIndex = 0;
    for (OsmNode node : sortedNodes.values() ) { // loop over nodes
      idMap.put(node.getIdFromPos(), nodeIndex++);
    }

    IntegerFifo3Pass restrictionBits = new IntegerFifo3Pass(16);

    TagValueCoder wayTagCoder = new TagValueCoder();
    TagValueCoder nodeTagCoder = new TagValueCoder();
    NoisyDiffCoder nodeIdxDiff = new NoisyDiffCoder();
    NoisyDiffCoder nodeEleDiff = new NoisyDiffCoder();
    NoisyDiffCoder extLonDiff = new NoisyDiffCoder();
    NoisyDiffCoder extLatDiff = new NoisyDiffCoder();

    for (int pass = 1; ; pass++) { // 3 passes: counters, stat-collection, encoding
      StatCoderContext bc = new StatCoderContext(buffer);

      restrictionBits.init();

      wayTagCoder.encodeDictionary(bc);
      nodeTagCoder.encodeDictionary(bc);
      nodeIdxDiff.encodeDictionary(bc);
      nodeEleDiff.encodeDictionary(bc);
      extLonDiff.encodeDictionary(bc);
      extLatDiff.encodeDictionary(bc);
      bc.encodeNoisyNumber(size, 5);
      bc.encodeSortedArray(faid, 0, size, 0x20000000, 0);
      int lastSelev = 0;

      for (OsmNode node : sortedNodes.values() ) { // loop over nodes
        aboffset = startPos(n);
        aboffsetEnd = fapos[n];

        int ilon = node.iLon;
        int ilat = node.iLat;

        // write turn restrictions
        TurnRestriction tr = node.firstRestriction;
        while (tr != null) {
          short exceptions = tr.exceptions; // except bikes, psv, ...
          if (exceptions != 0) {
            bc.encodeVarBits(2); // 2 = tr exceptions
            bc.encodeNoisyNumber(10, 5); // bit-count
            bc.encodeBounded(1023, exceptions & 1023);
          }
          bc.encodeVarBits(1); // 1 = turn restriction
          bc.encodeNoisyNumber(restrictionBits.getNext(), 5); // bit-count using look-ahead fifo
          long b0 = bc.getWritingBitPosition();
          bc.encodeBit(tr.isPositive); // isPositive
          bc.encodeNoisyDiff(tr.fromLon - ilon, 10); // fromLon
          bc.encodeNoisyDiff(tr.fromLat - ilat, 10); // fromLat
          bc.encodeNoisyDiff(tr.toLon - ilon, 10); // toLon
          bc.encodeNoisyDiff(tr.toLat - ilat, 10); // toLat
          restrictionBits.add((int) (bc.getWritingBitPosition() - b0));
          tr = tr.next;
        }
        bc.encodeVarBits(0); // end of extra data

        int sElev = node.sElev;
        nodeEleDiff.encodeSignedValue(sElev - lastSelev);
        lastSelev = sElev;
        nodeTagCoder.encodeTagValueSet(readVarBytes());
        int nlinks = node.linkCount();
        bc.encodeNoisyNumber(nlinks, 1);

        OsmLink link = node.firstLink;
        while (link != null) { // loop over links
          // read link data
          int ilonlink = ilon + readVarLengthSigned();
          int ilatlink = ilat + readVarLengthSigned();

          int sizecode = readVarLengthUnsigned();
          boolean isReverse = (sizecode & 1) != 0;
          int descSize = sizecode >> 1;
          byte[] description = null;
          if (descSize > 0) {
            description = new byte[descSize];
            readFully(description);
          }

          long link64 = ((long) ilonlink) << 32 | ilatlink;
          Integer idx = idMap.get(link64);
          boolean isInternal = idx != null;

          if (isReverse && isInternal) {
            continue; // do not encode internal reverse links
          }

          if (isInternal) {
            int nodeIdx = idx.intValue();
            if (nodeIdx == n) throw new RuntimeException("ups: self ref?");
            nodeIdxDiff.encodeSignedValue(nodeIdx - n);
          } else {
            nodeIdxDiff.encodeSignedValue(0);
            bc.encodeBit(isReverse);
            extLonDiff.encodeSignedValue(ilonlink - ilon);
            extLatDiff.encodeSignedValue(ilatlink - ilat);
          }
          wayTagCoder.encodeTagValueSet(description);
        }
        linkCounts.add(nlinks);
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
      long longId = n.getIdFromPos();
      int shrinkId = shrinkId(longId);
      if (expandId(shrinkId) != longId) {
        throw new IllegalArgumentException("inconstistent shrinking: " + longId + "<->" + expandId(shrinkId) );
      }
      sortedList.put(shrinkId, n);
    }
    return sortedList;
  }



  /**
   * (stasticially) encode the micro-cache into the format used in the datafiles
   *
   * @param buffer byte array to encode into (considered big enough)
   * @return the size of the encoded data
   */
  private int encodeMicroCache(byte[] buffer) {
    HashMap<Long, Integer> idMap = new HashMap<>();
    for (int n = 0; n < size; n++) { // loop over nodes
      idMap.put(expandId(faid[n]), n);
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
      bc.encodeNoisyNumber(size, 5);
      bc.encodeSortedArray(faid, 0, size, 0x20000000, 0);
      int lastSelev = 0;

      for (int n = 0; n < size; n++) { // loop over nodes
        aboffset = startPos(n);
        aboffsetEnd = fapos[n];

        long id64 = expandId(faid[n]);
        int ilon = (int) (id64 >> 32);
        int ilat = (int) (id64 & 0xffffffff);

        // write turn restrictions
        while (readBoolean()) {
          short exceptions = readShort(); // except bikes, psv, ...
          if (exceptions != 0) {
            bc.encodeVarBits(2); // 2 = tr exceptions
            bc.encodeNoisyNumber(10, 5); // bit-count
            bc.encodeBounded(1023, exceptions & 1023);
          }
          bc.encodeVarBits(1); // 1 = turn restriction
          bc.encodeNoisyNumber(restrictionBits.getNext(), 5); // bit-count using look-ahead fifo
          long b0 = bc.getWritingBitPosition();
          bc.encodeBit(readBoolean()); // isPositive
          bc.encodeNoisyDiff(readInt() - ilon, 10); // fromLon
          bc.encodeNoisyDiff(readInt() - ilat, 10); // fromLat
          bc.encodeNoisyDiff(readInt() - ilon, 10); // toLon
          bc.encodeNoisyDiff(readInt() - ilat, 10); // toLat
          restrictionBits.add((int) (bc.getWritingBitPosition() - b0));
        }
        bc.encodeVarBits(0); // end of extra data

        int sElev = readShort();
        nodeEleDiff.encodeSignedValue(sElev - lastSelev);
        lastSelev = sElev;
        nodeTagCoder.encodeTagValueSet(readVarBytes());
        int nlinks = linkCounts.getNext();
        bc.encodeNoisyNumber(nlinks, 1);

        nlinks = 0;
        while (hasMoreData()) { // loop over links
          // read link data
          int ilonlink = ilon + readVarLengthSigned();
          int ilatlink = ilat + readVarLengthSigned();

          int sizecode = readVarLengthUnsigned();
          boolean isReverse = (sizecode & 1) != 0;
          int descSize = sizecode >> 1;
          byte[] description = null;
          if (descSize > 0) {
            description = new byte[descSize];
            readFully(description);
          }

          long link64 = ((long) ilonlink) << 32 | ilatlink;
          Integer idx = idMap.get(link64);
          boolean isInternal = idx != null;

          if (isReverse && isInternal) {
            continue; // do not encode internal reverse links
          }
          nlinks++;

          if (isInternal) {
            int nodeIdx = idx.intValue();
            if (nodeIdx == n) throw new RuntimeException("ups: self ref?");
            nodeIdxDiff.encodeSignedValue(nodeIdx - n);
          } else {
            nodeIdxDiff.encodeSignedValue(0);
            bc.encodeBit(isReverse);
            extLonDiff.encodeSignedValue(ilonlink - ilon);
            extLatDiff.encodeSignedValue(ilatlink - ilat);
          }
          wayTagCoder.encodeTagValueSet(description);
        }
        linkCounts.add(nlinks);
      }
      if (pass == 3) {
        return bc.closeAndGetEncodedLength();
      }
    }
  }

  private void writeNodeData(OsmNode node) {

    // write turn restrictions
    TurnRestriction r = node.firstRestriction;
    while (r != null) {
      if (r.validate() && r.fromLon != 0 && r.toLon != 0) {
        writeBoolean(true); // restriction follows
        writeShort(r.exceptions);
        writeBoolean(r.isPositive);
        writeInt(r.fromLon);
        writeInt(r.fromLat);
        writeInt(r.toLon);
        writeInt(r.toLat);
      }
      r = r.next;
    }
    writeBoolean(false); // end restritions

    writeShort(node.getSElev());
    writeVarBytes(node.nodeDescription);

    // buffer internal reverse links
    ArrayList<OsmNode> internalReverse = new ArrayList<>();

    for (OsmLink link = node.getFirstLink(); link != null; link = link.getNext(node)) {
      OsmNode target = link.getTarget(node);;

      // internal reverse links later
      boolean isReverse = link.isReverse(node);
      if (isReverse) {
        if (isInternal(target.iLon, target.iLat)) {
          internalReverse.add(target);
          continue;
        }
      }

      byte[] description = link.wayDescription;

      // write link data
      writeVarLengthSigned(target.iLon - node.iLon);
      writeVarLengthSigned(target.iLat - node.iLat);
      writeModeAndDesc(isReverse, description);
    }

    while (internalReverse.size() > 0) {
      int nextIdx = 0;
      if (internalReverse.size() > 1) {
        int max32 = Integer.MIN_VALUE;
        for (int i = 0; i < internalReverse.size(); i++) {
          int id32 = shrinkId(internalReverse.get(i).getIdFromPos());
          if (id32 > max32) {
            max32 = id32;
            nextIdx = i;
          }
        }
      }
      OsmNode target = internalReverse.remove(nextIdx);
      writeVarLengthSigned(target.iLon - node.iLon);
      writeVarLengthSigned(target.iLat - node.iLat);
      writeModeAndDesc(true, null);
    }
    finishNode(node.getIdFromPos());
  }
}
