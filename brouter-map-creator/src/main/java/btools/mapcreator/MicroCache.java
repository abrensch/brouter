package btools.mapcreator;

import btools.codec.IntegerFifo3Pass;
import btools.codec.NoisyDiffCoder;
import btools.codec.StatCoderContext;
import btools.codec.TagValueCoder;
import btools.mapaccess.TurnRestriction;
import btools.util.ByteDataWriter;

import java.util.ArrayList;
import java.util.HashMap;

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

  public void discardNode() {
    aboffset = startPos(size);
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

  /**
   * (stasticially) encode the micro-cache into the format used in the datafiles
   *
   * @param buffer byte array to encode into (considered big enough)
   * @return the size of the encoded data
   */
  public int encodeMicroCache(byte[] buffer) {
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
    NoisyDiffCoder transEleDiff = new NoisyDiffCoder();

    for (int pass = 1; ; pass++) { // 3 passes: counters, stat-collection, encoding
      boolean dostats = pass == 3;
      boolean dodebug = debug && pass == 3;

      StatCoderContext bc = new StatCoderContext(buffer);

      linkCounts.init();
      restrictionBits.init();

      wayTagCoder.encodeDictionary(bc);
      if (dostats) bc.assignBits("wayTagDictionary");
      nodeTagCoder.encodeDictionary(bc);
      if (dostats) bc.assignBits("nodeTagDictionary");
      nodeIdxDiff.encodeDictionary(bc);
      nodeEleDiff.encodeDictionary(bc);
      extLonDiff.encodeDictionary(bc);
      extLatDiff.encodeDictionary(bc);
      transEleDiff.encodeDictionary(bc);
      if (dostats) bc.assignBits("noisebits");
      bc.encodeNoisyNumber(size, 5);
      if (dostats) bc.assignBits("nodecount");
      bc.encodeSortedArray(faid, 0, size, 0x20000000, 0);
      if (dostats) bc.assignBits("node-positions");
      if (dodebug) System.out.println("*** encoding cache of size=" + size);
      int lastSelev = 0;

      for (int n = 0; n < size; n++) { // loop over nodes
        aboffset = startPos(n);
        aboffsetEnd = fapos[n];
        if (dodebug)
          System.out.println("*** encoding node " + n + " from " + aboffset + " to " + aboffsetEnd);

        long id64 = expandId(faid[n]);
        int ilon = (int) (id64 >> 32);
        int ilat = (int) (id64 & 0xffffffff);

        if (aboffset == aboffsetEnd) {
          bc.encodeVarBits(13); // empty node escape (delta files only)
          continue;
        }

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

        if (dostats) bc.assignBits("extradata");

        int sElev = readShort();
        nodeEleDiff.encodeSignedValue(sElev - lastSelev);
        if (dostats) bc.assignBits("nodeele");
        lastSelev = sElev;
        nodeTagCoder.encodeTagValueSet(readVarBytes());
        if (dostats) bc.assignBits("nodeTagIdx");
        int nlinks = linkCounts.getNext();
        if (dodebug) System.out.println("*** nlinks=" + nlinks);
        bc.encodeNoisyNumber(nlinks, 1);
        if (dostats) bc.assignBits("link-counts");

        nlinks = 0;
        while (hasMoreData()) { // loop over links
          // read link data
          int startPointer = aboffset;
          int endPointer = getEndPointer();

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
            if (dodebug)
              System.out.println("*** NOT encoding link reverse=" + isReverse + " internal=" + isInternal);
            continue; // do not encode internal reverse links
          }
          if (dodebug)
            System.out.println("*** encoding link reverse=" + isReverse + " internal=" + isInternal);
          nlinks++;

          if (isInternal) {
            int nodeIdx = idx.intValue();
            if (dodebug) System.out.println("*** target nodeIdx=" + nodeIdx);
            if (nodeIdx == n) throw new RuntimeException("ups: self ref?");
            nodeIdxDiff.encodeSignedValue(nodeIdx - n);
            if (dostats) bc.assignBits("nodeIdx");
          } else {
            nodeIdxDiff.encodeSignedValue(0);
            bc.encodeBit(isReverse);
            extLonDiff.encodeSignedValue(ilonlink - ilon);
            extLatDiff.encodeSignedValue(ilatlink - ilat);
            if (dostats) bc.assignBits("externalNode");
          }
          wayTagCoder.encodeTagValueSet(description);
          if (dostats) bc.assignBits("wayDescIdx");
        }
        linkCounts.add(nlinks);
      }
      if (pass == 3) {
        return bc.closeAndGetEncodedLength();
      }
    }
  }


  public void writeNodeData(OsmNodeP node) {
    boolean valid = writeNodeData2(node);;
    if (valid) {
      finishNode(node.getIdFromPos());
    } else {
      discardNode();
    }
  }

  public boolean writeNodeData2(OsmNodeP node) {
    boolean hasLinks = false;

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
    ArrayList<OsmNodeP> internalReverse = new ArrayList<>();

    for (OsmLinkP link = node.getFirstLink(); link != null; link = link.getNext(node)) {
      OsmNodeP target = link.getTarget(node);;
      hasLinks = true;

      // internal reverse links later
      boolean isReverse = link.isReverse(node);
      if (isReverse) {
        if (isInternal(target.iLon, target.iLat)) {
          internalReverse.add(target);
          continue;
        }
      }

      byte[] description = link.descriptionBitmap;

      // write link data
      int sizeoffset = writeSizePlaceHolder();
      writeVarLengthSigned(target.iLon - node.iLon);
      writeVarLengthSigned(target.iLat - node.iLat);
      writeModeAndDesc(isReverse, description);
      injectSize(sizeoffset);
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
      OsmNodeP target = internalReverse.remove(nextIdx);
      int sizeoffset = writeSizePlaceHolder();
      writeVarLengthSigned(target.iLon - node.iLon);
      writeVarLengthSigned(target.iLat - node.iLat);
      writeModeAndDesc(true, null);
      injectSize(sizeoffset);
    }
    return hasLinks;
  }
}
