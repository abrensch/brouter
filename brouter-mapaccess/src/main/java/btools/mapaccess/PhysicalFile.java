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
  RandomAccessFile ra = null;
  long[] fileIndex = new long[25];
  int[] fileHeaderCrcs;

  public long creationTime;

  String fileName;

  public int divisor = 80;
  public byte elevationType = 3;

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

  public static int checkVersionIntegrity(File f) {
    int version = -1;
    RandomAccessFile raf = null;
    try {
      byte[] iobuffer = new byte[200];
      raf = new RandomAccessFile(f, "r");
      raf.readFully(iobuffer, 0, 200);
      ByteDataReader dis = new ByteDataReader(iobuffer);
      long lv = dis.readLong();
      version = (int) (lv >> 48);
    } catch (IOException e) {
    } finally {
      try {
        if (raf != null) raf.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
    return version;
  }

  /**
   * Checks the integrity of the file using the build-in checksums
   *
   * @return the error message if file corrupt, else null
   */
  public static String checkFileIntegrity(File f) throws IOException {
    PhysicalFile pf = null;
    try {
      DataBuffers dataBuffers = new DataBuffers();
      pf = new PhysicalFile(f, dataBuffers, -1, -1);
      int div = pf.divisor;
      for (int lonDegree = 0; lonDegree < 5; lonDegree++) { // doesn't really matter..
        for (int latDegree = 0; latDegree < 5; latDegree++) { // ..where on earth we are
          OsmFile osmf = new OsmFile(pf, lonDegree, latDegree, dataBuffers);
          if (osmf.hasData())
            for (int lonIdx = 0; lonIdx < div; lonIdx++)
              for (int latIdx = 0; latIdx < div; latIdx++)
                osmf.createMicroCache(lonDegree * div + lonIdx, latDegree * div + latIdx, dataBuffers, null, null, MicroCache.debug, null);
        }
      }
    } finally {
      if (pf != null)
        try {
          pf.ra.close();
        } catch (Exception ee) {
        }
    }
    return null;
  }

  public PhysicalFile(File f, DataBuffers dataBuffers, int lookupVersion, int lookupMinorVersion) throws IOException {
    fileName = f.getName();
    byte[] iobuffer = dataBuffers.iobuffer;
    ra = new RandomAccessFile(f, "r");
    ra.readFully(iobuffer, 0, 200);
    int fileIndexCrc = Crc32.crc(iobuffer, 0, 200);
    ByteDataReader dis = new ByteDataReader(iobuffer);
    for (int i = 0; i < 25; i++) {
      long lv = dis.readLong();
      short readVersion = (short) (lv >> 48);
      if (i == 0 && lookupVersion != -1 && readVersion != lookupVersion) {
        throw new IOException("lookup version mismatch (old rd5?) lookups.dat="
          + lookupVersion + " " + f.getName() + "=" + readVersion);
      }
      fileIndex[i] = lv & 0xffffffffffffL;
    }

    // read some extra info from the end of the file, if present
    long len = ra.length();

    long pos = fileIndex[24];
    int extraLen = 8 + 26 * 4;

    if (len == pos) return; // old format o.k.

    if ((len-pos) > extraLen) {
      extraLen++;
    }

    if (len < pos + extraLen) { // > is o.k. for future extensions!
      throw new IOException("file of size " + len + " too short, should be " + (pos + extraLen));
    }

    ra.seek(pos);
    ra.readFully(iobuffer, 0, extraLen);
    dis = new ByteDataReader(iobuffer);
    creationTime = dis.readLong();

    int crcData = dis.readInt();
    if (crcData == fileIndexCrc) {
      divisor = 80; // old format
    } else if ((crcData ^ 2) == fileIndexCrc) {
      divisor = 32; // new format
    } else {
      throw new IOException("top index checksum error");
    }
    fileHeaderCrcs = new int[25];
    for (int i = 0; i < 25; i++) {
      fileHeaderCrcs[i] = dis.readInt();
    }
    try {
      elevationType = dis.readByte();
    } catch (Exception e) {}
  }
}
