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
import btools.codec.MicroCache;
import btools.util.ByteDataReader;
import btools.util.Crc32;

final public class PhysicalFile {
  RandomAccessFile ra;

  String fileName;

  public int divisor = 160;

  public static void main(String[] args) {
    MicroCache.debug = true;

    try {
      checkFileIntegrity(new File(args[0]));
    } catch (IOException e) {
      System.err.println("************************************");
      e.printStackTrace();
      System.err.println("************************************");
    }
  }

  /**
   * Checks the integrity of the file using the build-in checksums
   *
   * @return the error message if file corrupt, else null
   */
  public static String checkFileIntegrity(File f) throws IOException {
    OsmFile osmf = null;
    try {
      DataBuffers dataBuffers = new DataBuffers();
      int nTiles = 160*160;
      osmf = new OsmFile(f, 0, 0, dataBuffers);
      for (int tileIdx = 0; tileIdx < nTiles; tileIdx++) {
        osmf.decodeMicroTileForIndex(tileIdx, 0L, dataBuffers, null, null, false, null);
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

  public PhysicalFile(File f) throws IOException {

  }
}
