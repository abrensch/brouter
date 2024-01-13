package btools.codec;

import java.util.HashMap;

import btools.util.ByteDataReader;

/**
 * MicroCache2 is the new format that uses statistical encoding and
 * is able to do access filtering and waypoint matching during encoding
 */
public final class MicroCache2 extends MicroCache {
  private final int lonBase;
  private final int latBase;
  private final int cellSize;

  public MicroCache2(int size, byte[] databuffer, int lonIdx, int latIdx, int cellSize ) {
    super(databuffer); // sets ab=databuffer, aboffset=0

    faid = new int[size];
    fapos = new int[size];
    this.size = 0;
    this.cellSize = cellSize;
    lonBase = lonIdx * cellSize;
    latBase = latIdx * cellSize;
  }


  @Override
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

  @Override
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

  @Override
  public boolean isInternal(int ilon, int ilat) {
    return ilon >= lonBase && ilon < lonBase + cellSize
      && ilat >= latBase && ilat < latBase + cellSize;
  }

  @Override
  public int encodeMicroCache(byte[] buffer) {
    HashMap<Long, Integer> idMap = new HashMap<>();
    for (int n = 0; n < size; n++) { // loop over nodes
      idMap.put(expandId(faid[n]), n);
    }

    IntegerFifo3Pass linkCounts = new IntegerFifo3Pass(256);
    IntegerFifo3Pass transCounts = new IntegerFifo3Pass(256);
    IntegerFifo3Pass restrictionBits = new IntegerFifo3Pass(16);

    TagValueCoder wayTagCoder = new TagValueCoder();
    TagValueCoder nodeTagCoder = new TagValueCoder();
    NoisyDiffCoder nodeIdxDiff = new NoisyDiffCoder();
    NoisyDiffCoder nodeEleDiff = new NoisyDiffCoder();
    NoisyDiffCoder extLonDiff = new NoisyDiffCoder();
    NoisyDiffCoder extLatDiff = new NoisyDiffCoder();
    NoisyDiffCoder transEleDiff = new NoisyDiffCoder();

    int netdatasize = 0;

    for (int pass = 1; ; pass++) { // 3 passes: counters, stat-collection, encoding
      boolean dostats = pass == 3;
      boolean dodebug = debug && pass == 3;

      if (pass < 3) netdatasize = fapos[size - 1];

      StatCoderContext bc = new StatCoderContext(buffer);

      linkCounts.init();
      transCounts.init();
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
      bc.encodeNoisyNumber(netdatasize, 10); // net-size
      if (dostats) bc.assignBits("netdatasize");
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
            netdatasize -= aboffset - startPointer;
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

          if (!isReverse) {
            byte[] geometry = readDataUntil(endPointer);
            // write transition nodes
            int count = transCounts.getNext();
            if (dodebug) System.out.println("*** encoding geometry with count=" + count);
            bc.encodeVarBits(count++);
            if (dostats) bc.assignBits("transcount");
            int transcount = 0;
            if (geometry != null) {
              int dlon_remaining = ilonlink - ilon;
              int dlat_remaining = ilatlink - ilat;

              ByteDataReader r = new ByteDataReader(geometry);
              while (r.hasMoreData()) {
                transcount++;

                int dlon = r.readVarLengthSigned();
                int dlat = r.readVarLengthSigned();
                bc.encodePredictedValue(dlon, dlon_remaining / count);
                bc.encodePredictedValue(dlat, dlat_remaining / count);
                dlon_remaining -= dlon;
                dlat_remaining -= dlat;
                if (count > 1) count--;
                if (dostats) bc.assignBits("transpos");
                transEleDiff.encodeSignedValue(r.readVarLengthSigned());
                if (dostats) bc.assignBits("transele");
              }
            }
            transCounts.add(transcount);
          }
        }
        linkCounts.add(nlinks);
      }
      if (pass == 3) {
        return bc.closeAndGetEncodedLength();
      }
    }
  }
}
