/**
 * cache for a single square
 *
 * @author ab
 */
package btools.mapaccess;

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

  public int lonDegree;
  public int latDegree;

  public String filename;

  private int divisor;
  private int cellSize;
  private int indexSize;

  public OsmFile(PhysicalFile rafile, int lonDegree, int latDegree, DataBuffers dataBuffers) throws IOException {
    this.lonDegree = lonDegree;
    this.latDegree = latDegree;
    int lonMod5 = lonDegree % 5;
    int latMod5 = latDegree % 5;
    int tileIndex = lonMod5 * 5 + latMod5;

    if (rafile != null) {
      divisor = rafile.divisor;

      cellSize = 1000000 / divisor;
      int nCaches = divisor * divisor;
      indexSize = nCaches * 4;

      byte[] ioBuffer = dataBuffers.iobuffer;
      filename = rafile.fileName;

      long[] index = rafile.fileIndex;
      fileOffset = tileIndex > 0 ? index[tileIndex - 1] : 200L;
      if (fileOffset == index[tileIndex])
        return; // empty

      is = rafile.ra;
      posIdx = new int[nCaches];
      microTileDecoded = new boolean[nCaches];
      is.seek(fileOffset);
      is.readFully(ioBuffer, 0, indexSize);

      if (rafile.fileHeaderCrcs != null) {
        int headerCrc = Crc32.crc(ioBuffer, 0, indexSize);
        if (rafile.fileHeaderCrcs[tileIndex] != headerCrc) {
          throw new IOException("sub index checksum error");
        }
      }

      ByteDataReader dis = new ByteDataReader(ioBuffer);
      for (int i = 0; i < nCaches; i++) {
        posIdx[i] = dis.readInt();
      }
    }
  }

  public boolean hasData() {
    return microTileDecoded != null;
  }



  private int getPosIdx(int idx) {
    return idx == -1 ? indexSize : posIdx[idx];
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
    int lonIdx = iLon / cellSize;
    int latIdx = iLat / cellSize;
    int subIdx = (latIdx - divisor * latDegree) * divisor + (lonIdx - divisor * lonDegree);
    if ( !microTileDecoded[subIdx] ) {
      long id64Base = ((long) (lonIdx * cellSize)) << 32 | (latIdx * cellSize);

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
}
