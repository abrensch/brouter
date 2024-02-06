/**
 * OsmFile represents a large square of OSM data, typically a 5*5 degree area.
 * The class is responsible for converting the data between:
 * - the file format (which is a compact, statistically encoded, serialized representation)
 * - the runtime-representation (a weaved graph of OsmNode's)
 */
package btools.mapaccess;

import java.io.*;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import btools.util.TagValueValidator;
import btools.statcoding.BitInputStream;
import btools.statcoding.BitOutputStream;
import btools.statcoding.Crc64;
import btools.statcoding.codecs.AdaptiveDiffDecoder;
import btools.statcoding.codecs.AdaptiveDiffEncoder;
import btools.util.LazyArrayOfLists;

public final class OsmFile {
  private final File file;
  private RandomAccessFile is;

  private long[] tileIndex;
  private final BitSet tileDecoded = new BitSet(160*160); // (just the initial size)
  private final int iLonBase;
  private final int iLatBase;
  private final int divisor = 160;
  private final int cellSize = 5000000 / divisor;
  private final byte[] ioBuffer;

  public OsmFile(File file, int iLonBase, int iLatBase, byte[] ioBuffer) {
    this.file = file;
    this.iLonBase = iLonBase;
    this.iLatBase = iLatBase;
    this.ioBuffer = ioBuffer;
  }

  /**
   * Opens the file for reading, parses the header and the tile index
   */
  public void openForReading() throws IOException {
    is = new RandomAccessFile(file, "r");
    int nCaches = divisor * divisor;
    tileIndex = new long[nCaches+1];
    long indexStart = is.readLong();
    int indexSize = is.readInt();
    byte[] ab = getDataFromFile(indexStart, indexSize, "index-data" );
    try ( BitInputStream bis = new BitInputStream( new ByteArrayInputStream( ab ) ) ) {
      AdaptiveDiffDecoder diffDecoder = new AdaptiveDiffDecoder(bis);
      long p0 = 0L;
      for (int i = 0; i <= nCaches; i++) {
        p0 = tileIndex[i] = p0 + diffDecoder.decode();
      }
    }
  }

  /**
   * Opens the file for reading, parses the header and the tile index
   */
  public void createWithNodes(List<OsmNode> nodes) throws IOException {
    int nTiles = divisor * divisor;

    // sort the nodes in sub-lists for the tiles...
    LazyArrayOfLists<OsmNode> subs = new LazyArrayOfLists<>(nTiles);
    for (OsmNode n : nodes) {
      int subLonIdx = (n.iLon - iLonBase) / cellSize;
      int subLatIdx = (n.iLat - iLatBase) / cellSize;
      int si = subLatIdx * divisor + subLonIdx;
      subs.getList(si).add(n);
    }
    subs.trimAll();


    tileIndex= new long[nTiles + 1];
    int indexSize;
    Map<String,long[]> bitStatistics = new TreeMap<>();

    // ... and write them to a file
    try (BitOutputStream bos = new BitOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
      bos.writeLong(0L); // placeholder for index-start
      bos.writeInt(0); // placeholder for index-size

      tileIndex[0] = bos.getBitPosition() >> 3;

      for (int si = 0; si < nTiles; si++) {
        int size = subs.getSize(si);
        if (size > 0) {
          List<OsmNode> subList = subs.getList(si);
          OsmNode n0 = subList.get(0);
          int lonBase = n0.iLon - n0.iLon % cellSize;
          int latBase = n0.iLat - n0.iLat % cellSize;
          int len = new OsmTile(lonBase, latBase).encodeTile(subList, ioBuffer, bitStatistics);
          bos.startCRC();
          bos.write(ioBuffer, 0, len);
          bos.writeLong(bos.finishCRC());
        }
        tileIndex[si + 1] = bos.getBitPosition() >> 3;
      }
      bos.startCRC();
      AdaptiveDiffEncoder diffEncoder = new AdaptiveDiffEncoder(bos);
      long p0 = 0L;
      for (long tilePos : tileIndex) {
        diffEncoder.encode(tilePos - p0);
        p0 = tilePos;
      }
      bos.writeLong(bos.finishCRC());
      indexSize = (int)((bos.getBitPosition() >> 3) - tileIndex[nTiles]);
    }

    // report the bit statistics
    System.out.println("**** bit stats report ****");
    for (String name : bitStatistics.keySet()) {
      long[] stats = bitStatistics.get(name);
      System.out.println(name + " count=" + stats[1] + " bits=" + stats[0] );
    }
    System.out.println("***************************");

    // re-open random-access to write the index position
    RandomAccessFile ra = new RandomAccessFile(file, "rw");
    ra.writeLong(tileIndex[nTiles]);
    ra.writeInt(indexSize);
    ra.close();
  }

  private byte[] getDataFromFile(long startPos, int size, String crcMessage ) throws IOException {
    byte[] ab = ioBuffer == null || size > ioBuffer.length ? new byte[size] : ioBuffer;
    is.seek(startPos);
    is.readFully(ab, 0, size);
    checkCrc( ab, size, crcMessage );
    return ab;
  }

  private void checkCrc( byte[] ab, int size, String crcMessage ) throws IOException {
    if ( crcMessage != null ) {
      long crcData = Crc64.crc(ab, 0, size - 8);
      long crcFooter = new DataInputStream(new ByteArrayInputStream(ab, size - 8, 8)).readLong();
      if (crcData != crcFooter) {
        throw new IOException( "crc-mismatch in " + crcMessage );
      }
    }
  }

  public void checkDecodeTile(int iLon, int iLat, DataBuffers dataBuffers, TagValueValidator wayValidator, WaypointMatcher waypointMatcher, OsmNodesMap hollowNodes)
    throws Exception {
    int lonIdx = (iLon-iLonBase) / cellSize;
    int latIdx = (iLat-iLatBase) / cellSize;
    int subIdx = latIdx * divisor + lonIdx;
    if ( !tileDecoded.get(subIdx) ) {
      int lonBase = lonIdx * cellSize + iLonBase;
      int latBase = latIdx * cellSize + iLatBase;

      decodeTileForIndex(subIdx, lonBase, latBase, dataBuffers, wayValidator, waypointMatcher, true, hollowNodes);
      tileDecoded.set(subIdx);
    }
  }

  private void decodeTileForIndex(int tileIdx, int lonBase, int latBase, DataBuffers dataBuffers, TagValueValidator wayValidator,
                                 WaypointMatcher waypointMatcher, boolean reallyDecode, OsmNodesMap hollowNodes) throws IOException {
    long startPos = tileIndex[tileIdx];
    long endPos = tileIndex[tileIdx+1];
    int size = (int)(endPos-startPos);
    if (size == 0) {
      return;
    }
    byte[] ab = getDataFromFile(startPos, size, null);
    int decodedBytes = 0;
    try {
      if (reallyDecode) {
        if (hollowNodes == null) {
          throw new IllegalArgumentException("expected hollowNodes non-null");
        }
        decodedBytes = new OsmTile(lonBase,latBase).decodeTile(ab, dataBuffers, wayValidator, waypointMatcher, hollowNodes);
      }
    } finally {
      if (decodedBytes != size - 8) {
        checkCrc( ab, size, "tile-data" );
      }
    }
  }

  /**
   * Reset this OsmFile to the state after
   * is was opened (index decoded, bot no tile yet)
   */
  void clean() {
    tileDecoded.clear();
  }

  /**
   * Close the underlying file handle
   */
  public void close() throws IOException {
    is.close();
  }

  public static String checkFileIntegrity(File f) throws IOException {
    OsmFile osmf = null;
    try {
      int nTiles = 160*160;
      DataBuffers dataBuffers = new DataBuffers();
      osmf = new OsmFile(f, 0, 0, dataBuffers.iobuffer );
      osmf.openForReading();
      for (int tileIdx = 0; tileIdx < nTiles; tileIdx++) {
        osmf.decodeTileForIndex(tileIdx, 0, 0, dataBuffers, null, null, false, null);
      }
    } finally {
      if (osmf != null)
        try {
          osmf.close();
        } catch (Exception ee) {
        }
    }
    return null;
  }

  public static void main(String[] args) {
    try {
      checkFileIntegrity(new File(args[0]));
    } catch (IOException e) {
      System.err.println("************************************");
      e.printStackTrace();
      System.err.println("************************************");
    }
  }
}
