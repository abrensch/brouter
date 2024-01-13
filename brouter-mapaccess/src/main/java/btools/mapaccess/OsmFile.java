/**
 * cache for a single square
 *
 * @author ab
 */
package btools.mapaccess;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

import btools.codec.DataBuffers;
import btools.codec.StatCoderContext;
import btools.codec.TagValueValidator;
import btools.codec.WaypointMatcher;
import btools.util.ByteDataReader;
import btools.util.Crc32;

final class OsmFile {
  private RandomAccessFile is;

  private long fileOffset;

  private int[] posIdx;
  private boolean[] microTileDecoded;

  public int iLonBase;
  public int iLatBase;

  private int divisor = 160;
  private int cellSize;
  private int indexSize;

  public OsmFile(File f, int iLonBase, int iLatBase, DataBuffers dataBuffers) throws IOException {
    this.iLonBase = iLonBase;
    this.iLatBase = iLatBase;

    cellSize = 5000000 / divisor;

    is = new RandomAccessFile(f, "r");

    int nCaches = divisor * divisor;
    indexSize = nCaches * 4;
    fileOffset = indexSize;

    byte[] ioBuffer = dataBuffers.iobuffer;
    posIdx = new int[nCaches];
    microTileDecoded = new boolean[nCaches];
    is.seek(0L);
    is.readFully(ioBuffer, 0, indexSize);

    ByteDataReader dis = new ByteDataReader(ioBuffer);
    for (int i = 0; i < nCaches; i++) {
      posIdx[i] = dis.readInt();
    }
  }

  private int getPosIdx(int idx) {
    return idx == -1 ? 0 : posIdx[idx];
  }

  public int getDataInputForSubIdx(int subIdx, byte[] iobuffer) throws IOException {
    int startPos = getPosIdx(subIdx - 1);
    int endPos = getPosIdx(subIdx);
    int size = endPos - startPos;
    if (size > 0) {
      is.seek(fileOffset + startPos);
      if (size <= iobuffer.length) {
        is.readFully(iobuffer, 0, size);
      }
    }
    return size;
  }

  public void checkDecodeMicroTile(int iLon, int iLat, DataBuffers dataBuffers, TagValueValidator wayValidator, WaypointMatcher waypointMatcher, OsmNodesMap hollowNodes)
    throws Exception {
    int lonIdx = (iLon-iLonBase) / cellSize;
    int latIdx = (iLat-iLatBase) / cellSize;
    int subIdx = latIdx * divisor + lonIdx;
    if ( !microTileDecoded[subIdx] ) {
      long id64Base = ((long) (lonIdx * cellSize + iLonBase)) << 32 | (latIdx * cellSize + iLatBase);

      decodeMicroTileForIndex(subIdx, id64Base, dataBuffers, wayValidator, waypointMatcher, true, hollowNodes);
      microTileDecoded[subIdx] = true;
    }
  }

  public void decodeMicroTileForIndex(int subIdx, long id64Base, DataBuffers dataBuffers, TagValueValidator wayValidator,
                              WaypointMatcher waypointMatcher, boolean reallyDecode, OsmNodesMap hollowNodes) throws IOException {
    byte[] ab = dataBuffers.iobuffer;
    int asize = getDataInputForSubIdx(subIdx, ab);

    if (asize == 0) {
      return;
    }
    if (asize > ab.length) {
      ab = new byte[asize];
      asize = getDataInputForSubIdx(subIdx, ab);
    }

    StatCoderContext bc = new StatCoderContext(ab);

    try {
      if (reallyDecode) {
        if (hollowNodes == null) {
          throw new IllegalArgumentException("expected hollowNodes non-null");
        }
        new DirectWeaver(bc, dataBuffers, id64Base, wayValidator, waypointMatcher, hollowNodes);
      }
    } finally {
      // crc check only if the buffer has not been fully read
      int readBytes = (bc.getReadingBitPosition() + 7) >> 3;
      if (readBytes != asize - 4) {
        int crcData = Crc32.crc(ab, 0, asize - 4);
        int crcFooter = new ByteDataReader(ab, asize - 4).readInt();
        if (crcData != crcFooter) {
          throw new IOException("checkum error");
        }
      }
    }
  }

  void clean() {
    int nc = microTileDecoded == null ? 0 : microTileDecoded.length;
    for (int i = 0; i < nc; i++) {
      microTileDecoded[i] = false;
    }
  }

  public void close() throws IOException {
    is.close();
  }
}
