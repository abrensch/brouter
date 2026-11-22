/**
 * GraphFile represents a large square of a Graph, typically a 5*5 degree area.
 * The class is responsible for converting the data between:
 * - the file format (which is a compact, statistically encoded, serialized representation)
 * - the runtime-representation (a weaved graph of GraphNode's)
 */
package btools.mapaccess;
import java.io.*;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import btools.util.TagValueValidator;
import btools.statcoding.BitInputStream;
import btools.statcoding.BitOutputStream;
import btools.statcoding.Crc64;
import btools.statcoding.PrefixedBitInputStream;
import btools.statcoding.PrefixedBitOutputStream;
import btools.statcoding.codecs.AdaptiveDiffDecoder;
import btools.statcoding.codecs.AdaptiveDiffEncoder;
/**
 * File layout:
 * <ol>
 * <li>the header: magic word, then a versioned block (PrefixedBitOutputStream) with geometry
 * (iLonBase, iLatBase, divisor, cellSize), map-snapshot-time, lookup major/minor version and
 * the position/size of the tile index. Fields may be appended in later minor versions.
 * The pointer fields are fixed-width, so the header can be rewritten in place once the index position is known.</li>
 * <li>the tiles, each followed by a CRC</li>
 * <li>the tile index, followed by a CRC</li>
 * </ol>
 */
public final class GraphFile {
  private static final int MAGIC = 0x52443502; // "RD5" + format generation
  private static final long HEADER_MAJOR_VERSION = 1;
  private static final long HEADER_MINOR_VERSION = 1;
  private final File file;
  private RandomAccessFile is;
  private long[] tileIndex;
  private final BitSet tileDecoded = new BitSet(160*160); // (just the initial size)
  private final int iLonBase;
  private final int iLatBase;
  private int divisor = 160;
  private int cellSize = 5000000 / divisor;
  // header placeholders, not yet used
  private long mapSnapshotTime;
  private int lookupMajorVersion;
  private int lookupMinorVersion;
  public long getMapSnapshotTime() {
    return mapSnapshotTime;
  }
  public void setMapSnapshotTime(long mapSnapshotTime) {
    this.mapSnapshotTime = mapSnapshotTime;
  }
  public int getLookupMajorVersion() {
    return lookupMajorVersion;
  }
  public int getLookupMinorVersion() {
    return lookupMinorVersion;
  }
  public void setLookupVersion(int major, int minor) {
    this.lookupMajorVersion = major;
    this.lookupMinorVersion = minor;
  }
  private void writeHeader(BitOutputStream bos) throws IOException {
    try (PrefixedBitOutputStream pos = new PrefixedBitOutputStream(bos, HEADER_MAJOR_VERSION, HEADER_MINOR_VERSION)) {
      pos.writeInt(iLonBase);
      pos.writeInt(iLatBase);
      pos.writeInt(divisor);
      pos.writeInt(cellSize);
      pos.writeLong(mapSnapshotTime);
      pos.writeInt(lookupMajorVersion);
      pos.writeInt(lookupMinorVersion);
    }
  }
  /**
   * Reads the header from the given stream.
   */
  private void readHeader(BitInputStream bis) throws IOException {
    // throws if the major version is newer than we know
    PrefixedBitInputStream pis = new PrefixedBitInputStream(bis, HEADER_MAJOR_VERSION);
    int lonBase = pis.readInt();
    int latBase = pis.readInt();
    int div = pis.readInt();
    int cell = pis.readInt();
    mapSnapshotTime = pis.readLong();
    lookupMajorVersion = pis.readInt();
    lookupMinorVersion = pis.readInt();
    if (lonBase != iLonBase || latBase != iLatBase) {
      throw new IOException("geometry mismatch in " + file + ": " + lonBase + "/" + latBase
        + " expected " + iLonBase + "/" + iLatBase);
    }
    if (div <= 0 || div > 4096 || cell <= 0) {
      throw new IOException("invalid tile geometry in " + file + ": divisor=" + div + " cellSize=" + cell);
    }
    divisor = div;
    cellSize = cell;
  }  public GraphFile(File file, int iLonBase, int iLatBase) {
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
    long tileIndexStart;
    int tileIndexSize;
    try (BitInputStream bis = new BitInputStream(new BufferedInputStream(new FileInputStream(file)))) {
      if (bis.readInt() != MAGIC) {
        throw new IllegalArgumentException("this is not a BRouter data file");
      }
      tileIndexStart = bis.readLong();
      tileIndexSize = bis.readInt();
      readHeader(bis);
    }
    is = new RandomAccessFile(file, "r");
    int nCaches = divisor * divisor;
    tileIndex = new long[nCaches + 1];
    byte[] ab = getDataFromFile(tileIndexStart, tileIndexSize, "index-data");
    try (BitInputStream bis = new BitInputStream(ab)) {
      AdaptiveDiffDecoder diffDecoder = new AdaptiveDiffDecoder(bis);
      long p0 = 0L;
      for (int i = 0; i <= nCaches; i++) {
        p0 = tileIndex[i] = p0 + diffDecoder.decode();
      }
    }
  }

  /**
   * creates a new datafile  with the given list of nodes. The nodes must be within the bounds of this GraphFile (iLonBase, iLatBase).
   * The nodes are sorted into tiles and each tile is encoded separately. The header is written first, with a placeholder
   * for the tile index position, which is filled in (header rewritten in place) at the end of the file creation.
   * @param nodes the list of nodes to write
   * @throws IOException if an I/O error occurs
   */
  public void createWithNodes(List<GraphNode> nodes) throws IOException {
    int nTiles = divisor * divisor;
    // sort the nodes in sub-lists for the tiles...
    List<ArrayList<GraphNode>> subs = new ArrayList<>(nTiles);
    for (int i = 0; i < nTiles; i++) {
      subs.add(null);
    }
    for (GraphNode n : nodes) {
      int subLonIdx = (n.iLon - iLonBase) / cellSize;
      int subLatIdx = (n.iLat - iLatBase) / cellSize;
      if ( subLonIdx < 0 || subLonIdx >= divisor || subLatIdx < 0 || subLatIdx >= divisor ) {
        throw new IllegalArgumentException("node " + n + " is out of bounds for this GraphFile");
      }
      int si = subLatIdx * divisor + subLonIdx;
      if ( subs.get(si) == null ) {
        subs.set(si, new ArrayList<>());
      }
      subs.get(si).add(n);
    }
    for( ArrayList<GraphNode> sub : subs) {
      if (sub != null) {
        sub.trimToSize();
      }
    }
    tileIndex= new long[nTiles + 1];
    long tileIndexStart;
    int tileIndexSize;
    // ... and write them to a file
    try (BitOutputStream bos = new BitOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
      bos.writeInt(MAGIC);
      bos.writeLong(0L); // dummy, replaced later with the real tile index position
      bos.writeInt(0);
      bos.startCRC();
      writeHeader(bos);
      bos.writeLong(bos.finishCRC());
      tileIndex[0] = bos.getBitPosition() >> 3;
      for (int si = 0; si < nTiles; si++) {
        List<GraphNode> subList = subs.get(si);
        if (subList != null && !subList.isEmpty()) {
          GraphNode n0 = subList.get(0);
          int lonBase = n0.iLon - n0.iLon % cellSize;
          int latBase = n0.iLat - n0.iLat % cellSize;
          bos.startCRC();
          new GraphTile(lonBase, latBase).encodeTile(subList, bos);
          bos.writeLong(bos.finishCRC());
        }
        tileIndex[si + 1] = bos.getBitPosition() >> 3;
      }
      tileIndexStart = bos.getBitPosition() >> 3;
      bos.startCRC();
      AdaptiveDiffEncoder diffEncoder = new AdaptiveDiffEncoder(bos);
      long p0 = 0L;
      for (long tilePos : tileIndex) {
        diffEncoder.encode(tilePos - p0);
        p0 = tilePos;
      }
      bos.writeLong(bos.finishCRC());
      tileIndexSize = (int)((bos.getBitPosition() >> 3) - tileIndexStart);
    }
    // rewrite the header in place, now with the real tile index position
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (BitOutputStream hos = new BitOutputStream(baos)) {
      writeHeader(hos);
    }
    // reopen random access to the tile index location
    try (RandomAccessFile ra = new RandomAccessFile(file, "rw")) {
      ra.writeInt(MAGIC);
      ra.writeLong(tileIndexStart);
      ra.writeInt(tileIndexSize);
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

  public void checkDecodeTile(GraphNode template, TagValueValidator wayValidator, WaypointMatcher waypointMatcher, Map<Long, GraphNode> hollowNodes)
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
                                 WaypointMatcher waypointMatcher, Map<Long, GraphNode> hollowNodes) throws IOException {
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
      decodedBytes = new GraphTile(lonBase,latBase).decodeTile(ab, wayValidator, waypointMatcher, hollowNodes);
    } finally {
      if (decodedBytes != size - 8) {
        checkCrc( ab, size, "tile-data" );
      }
    }
  }

  /**
   * Reset this GraphFile to the state after
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
