/**
 * cache for a single square
 *
 * @author ab
 */
package btools.mapaccess;

import java.io.*;
import java.nio.channels.Channels;
import java.util.BitSet;
import java.util.List;

import btools.codec.DataBuffers;
import btools.codec.StatCoderContext;
import btools.codec.TagValueValidator;
import btools.codec.WaypointMatcher;
import btools.statcoding.BitInputStream;
import btools.statcoding.BitOutputStream;
import btools.statcoding.codecs.AdaptiveDiffDecoder;
import btools.statcoding.codecs.AdaptiveDiffEncoder;
import btools.util.ByteDataReader;
import btools.util.Crc32;
import btools.util.LazyArrayOfLists;

public final class OsmFile {
  private File file;
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

  /**
   * Opens the file for reading, parses the header and the tile index
   */
  public void openForReading() throws IOException {
    is = new RandomAccessFile(file, "r");
    int nCaches = divisor * divisor;
    tileIndex = new long[nCaches+1];
    long indexStart = is.readLong();
    is.seek(indexStart);
    try ( BitInputStream bis = new BitInputStream( new BufferedInputStream( Channels.newInputStream(is.getChannel() ) ) ) ) {
      AdaptiveDiffDecoder diffDecoder = new AdaptiveDiffDecoder(bis);
      long p0 = 0L;
      for (int i = 0; i <= nCaches; i++) {
        p0 = tileIndex[i] = p0 + diffDecoder.decode();
      }
    }
    is = new RandomAccessFile(file, "r");
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


    long[] posIdx = new long[nTiles + 1];

    // ... and write them to a file
    try (BitOutputStream bos = new BitOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
      bos.writeLong(0L); // placeholder for index-start
      posIdx[0] = bos.getBitPosition() >> 3;

      byte[] abBuf1 = new byte[10 * 1024 * 1024];

      for (int si = 0; si < nTiles; si++) {
        int size = subs.getSize(si);
        if (size > 0) {
          List<OsmNode> subList = subs.getList(si);
          OsmNode n0 = subList.get(0);
          int lonBase = n0.iLon - n0.iLon % cellSize;
          int latBase = n0.iLat - n0.iLat % cellSize;

          int len = new OsmTile(lonBase, latBase).encodeTile(subList, abBuf1);
          bos.startCRC();
          bos.write(abBuf1, 0, len);
          bos.writeLong(bos.finishCRC());
        }
        posIdx[si + 1] = bos.getBitPosition() >> 3;
      }
      bos.startCRC();
      AdaptiveDiffEncoder diffEncoder = new AdaptiveDiffEncoder(bos);
      long p0 = 0L;
      for (long tilePos : posIdx) {
        diffEncoder.encode(tilePos - p0);
        p0 = tilePos;
      }
      bos.writeLong(bos.finishCRC());
    }
    // re-open random-access to write the index position
    RandomAccessFile ra = new RandomAccessFile(file, "rw");
    ra.writeLong(posIdx[posIdx.length - 1]);
    ra.close();

    System.out.println("**** codec stats: *******\n" + StatCoderContext.getBitReport());
  }



  private int getDataInputForTileIdx(int tileIdx, byte[] ioBuffer) throws IOException {
    long startPos = tileIndex[tileIdx];
    long endPos = tileIndex[tileIdx+1];
    int size = (int)(endPos - startPos);
    if (size > 0) {
      is.seek(startPos);
      if (size <= ioBuffer.length) {
        is.readFully(ioBuffer, 0, size);
      }
    }
    return size;
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
    byte[] ab = dataBuffers.iobuffer;
    int asize = getDataInputForTileIdx(tileIdx, ab);

    if (asize == 0) {
      return;
    }
    if (asize > ab.length) {
      ab = new byte[asize];
      asize = getDataInputForTileIdx(tileIdx, ab);
    }

    StatCoderContext bc = new StatCoderContext(ab);

    try {
      if (reallyDecode) {
        if (hollowNodes == null) {
          throw new IllegalArgumentException("expected hollowNodes non-null");
        }
        new OsmTile(lonBase,latBase).decodeTile(bc, dataBuffers, wayValidator, waypointMatcher, hollowNodes);
      }
    } finally {
      // crc check only if the buffer has not been fully read
      int readBytes = (bc.getReadingBitPosition() + 7) >> 3;
      if (readBytes != asize - 8) {
        int crcData = Crc32.crc(ab, 0, asize - 4);
        int crcFooter = new ByteDataReader(ab, asize - 4).readInt();
        if (crcData != crcFooter) {
          // TODO: repair crc test throw new IOException("checksum error");
        }
      }
    }
  }

  void clean() {
    tileDecoded.clear();
  }

  public void close() throws IOException {
    is.close();
  }

  public static String checkFileIntegrity(File f) throws IOException {
    OsmFile osmf = null;
    try {
      DataBuffers dataBuffers = new DataBuffers();
      int nTiles = 160*160;
      osmf = new OsmFile(f, 0, 0);
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
