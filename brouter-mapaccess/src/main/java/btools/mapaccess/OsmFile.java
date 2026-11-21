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

  public OsmFile(File file, int iLonBase, int iLatBase) {
    this.file = file;
    this.iLonBase = iLonBase;
    this.iLatBase = iLatBase;
  }

  public static String getBaseName( int lon, int lat ) {
    String slon = lon < 0 ? "W" + (-lon) : "E" + lon;
    String slat = lat < 0 ? "S" + (-lat) : "N" + lat;
    return slon + "_" + slat;
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
   * creates a new datafile  with the given list of nodes. The nodes must be within the bounds of this OsmFile (iLonBase, iLatBase).
   * The nodes are sorted into tiles and each tile is encoded separately. The tile index is written at the end of the file,
   * and the position of the index is written at the beginning of the file.
   * @param nodes the list of nodes to write
   * @throws IOException if an I/O error occurs
   */
  public void createWithNodes(List<OsmNode> nodes) throws IOException {
    int nTiles = divisor * divisor;

    // sort the nodes in sub-lists for the tiles...
    LazyArrayOfLists<OsmNode> subs = new LazyArrayOfLists<>(nTiles);
    for (OsmNode n : nodes) {
      int subLonIdx = (n.iLon - iLonBase) / cellSize;
      int subLatIdx = (n.iLat - iLatBase) / cellSize;
      if ( subLonIdx < 0 || subLonIdx >= divisor || subLatIdx < 0 || subLatIdx >= divisor ) {
        throw new IllegalArgumentException("node " + n + " is out of bounds for this OsmFile");
      }
      int si = subLatIdx * divisor + subLonIdx;
      subs.getList(si).add(n);
    }
    subs.trimAll();


    tileIndex= new long[nTiles + 1];
    int indexSize;

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
          bos.startCRC();
          new OsmTile(lonBase, latBase).encodeTile(subList, bos);
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

    // re-open random-access to write the index position
    try ( RandomAccessFile ra = new RandomAccessFile(file, "rw") ) {
      ra.writeLong( tileIndex[nTiles] );
      ra.writeInt( indexSize );
    }
  }

  private byte[] getDataFromFile(long startPos, int size, String crcMessage ) throws IOException {
    byte[] ab = new byte[size];
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

  public void checkDecodeTile(OsmNode template, TagValueValidator wayValidator, WaypointMatcher waypointMatcher, Map<Long, OsmNode> hollowNodes)
    throws Exception {
    int lonIdx = (template.iLon-iLonBase) / cellSize;
    int latIdx = (template.iLat-iLatBase) / cellSize;
    int subIdx = latIdx * divisor + lonIdx;
    if ( !tileDecoded.get(subIdx) ) {
      int lonBase = lonIdx * cellSize + iLonBase;
      int latBase = latIdx * cellSize + iLatBase;

      decodeTileForIndex(subIdx, lonBase, latBase, wayValidator, waypointMatcher, hollowNodes);
      tileDecoded.set(subIdx);
    }
  }

  private void decodeTileForIndex(int tileIdx, int lonBase, int latBase, TagValueValidator wayValidator,
                                 WaypointMatcher waypointMatcher, Map<Long, OsmNode> hollowNodes) throws IOException {
    long startPos = tileIndex[tileIdx];
    long endPos = tileIndex[tileIdx+1];
    long sizeLong = endPos-startPos;
    if (sizeLong < 0L || sizeLong > Integer.MAX_VALUE) {
      throw new IllegalArgumentException( "invalid tile size: " + sizeLong );
    }
    int size = (int)sizeLong;
    if (size == 0) {
      return;
    }
    byte[] ab = getDataFromFile(startPos, size, null);
    int decodedBytes = 0;
    try {
      decodedBytes = new OsmTile(lonBase,latBase).decodeTile(ab, wayValidator, waypointMatcher, hollowNodes);
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
}
