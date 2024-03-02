// License: GPL. For details, see LICENSE file.
package btools.mapcreator;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * adapted from https://github.com/JOSM/josm-plugins/blob/master/ElevationProfile/src/org/openstreetmap/josm/plugins/elevation/HgtReader.java
 * <p>
 * Class HgtReader reads data from SRTM HGT files. Currently this class is restricted to a resolution of 3 arc seconds.
 * <p>
 * SRTM data files are available at the <a href="http://dds.cr.usgs.gov/srtm/version2_1/SRTM3">NASA SRTM site</a>
 *
 * @author Oliver Wieland &lt;oliver.wieland@online.de&gt;
 */
public class HgtReader {
  final static boolean DEBUG = false;

  private static final int SECONDS_PER_MINUTE = 60;

  public static final String HGT_EXT = ".hgt";
  public static final String ZIP_EXT = ".zip";

  // alter these values for different SRTM resolutions
  public static final int HGT3_RES = 3; // resolution in arc seconds
  public static final int HGT3_ROW_LENGTH = 1201; // number of elevation values per line
  public static final int HGT_VOID = -32768; // magic number which indicates 'void data' in HGT file
  public static final int HGT1_RES = 1;  // <<- The new SRTM is 1-ARCSEC
  public static final int HGT1_ROW_LENGTH = 3601; //-- New file resolution is 3601x3601
  /**
   * The 'no elevation' data magic.
   */
  public static double NO_ELEVATION = Double.NaN;

  private static String srtmFolder = "";

  private static final Map<String, ShortBuffer> cache = new HashMap<>();

  public HgtReader(String folder) {
    srtmFolder = folder;
  }

  public static double getElevationFromHgt(double lat, double lon) {
    try {
      String file = getHgtFileName(lat, lon);
      if (DEBUG) System.out.println("HGT buffer " + file + " for " + lat + " " + lon);

      // given area in cache?
      if (!cache.containsKey(file)) {

        // fill initial cache value. If no file is found, then
        // we use it as a marker to indicate 'file has been searched
        // but is not there'
        cache.put(file, null);
        // Try all resource directories
        //for (String location : Main.pref.getAllPossiblePreferenceDirs())
        {
          String fullPath = new File(srtmFolder, file + HGT_EXT).getPath();
          File f = new File(fullPath);
          if (f.exists()) {
            // found something: read HGT file...
            ShortBuffer data = readHgtFile(fullPath);
            // ... and store result in cache
            cache.put(file, data);
            //break;
          } else {
            fullPath = new File(srtmFolder, file + ZIP_EXT).getPath();
            f = new File(fullPath);
            if (f.exists()) {
              ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(f)));
              try {
                for (; ; ) {
                  ZipEntry ze = zis.getNextEntry();
                  if (ze == null) break;
                  if (ze.getName().toLowerCase().endsWith(HGT_EXT)) {
                    // System.out.println("read zip " + ze.getName());
                    ShortBuffer data = readHgtStream(zis);
                    // ... and store result in cache
                    cache.put(file, data);
                    break;
                  }
                  zis.closeEntry();
                }
              } finally {
                zis.close();
              }
            }
          }
          System.out.println("*** reading: " + f.getName() + "  " + cache.get(file));
        }
      }

      // read elevation value
      return readElevation(lat, lon);
    } catch (FileNotFoundException e) {
      System.err.println("HGT Get elevation " + lat + ", " + lon + " failed: => " + e.getMessage());
      // no problem... file not there
      return NO_ELEVATION;
    } catch (Exception ioe) {
      // oops...
      ioe.printStackTrace(System.err);
      // fallback
      return NO_ELEVATION;
    }
  }

  public static short[] getElevationDataFromHgt(double lat, double lon) {
    try {
      if (lon < 0) lon += 1;
      if (lat < 0) lat += 1;
      String file = getHgtFileName(lat, lon);
      if (DEBUG) System.out.println("HGT buffer " + file + " for " + lat + " " + lon);

      ShortBuffer data = null;

      // Try all resource directories
      //for (String location : Main.pref.getAllPossiblePreferenceDirs())

      String fullPath = new File(srtmFolder, file + HGT_EXT).getPath();
      File f = new File(fullPath);
      if (f.exists()) {
        // found something: read HGT file...
        data = readHgtFile(fullPath);
      } else {
        fullPath = new File(srtmFolder, file + ZIP_EXT).getPath();
        f = new File(fullPath);
        if (f.exists()) {
          ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(f)));
          try {
            for (; ; ) {
              ZipEntry ze = zis.getNextEntry();
              if (ze == null) break;
              if (ze.getName().toLowerCase().endsWith(HGT_EXT)) {
                // System.out.println("read zip " + ze.getName());
                data = readHgtStream(zis);
                break;
              }
              zis.closeEntry();
            }
          } finally {
            zis.close();
          }
        }
      }


      System.out.println("*** reading: " + f.getName() + "  " + (data != null ? data.limit() : -1));
      if (data != null) {
        short[] array = new short[data.limit()];
        data.get(array);
        return array;
      }
      return null;
    } catch (FileNotFoundException e) {
      System.err.println("HGT Get elevation " + lat + ", " + lon + " failed: => " + e.getMessage());
      // no problem... file not there
      return null;
    } catch (Exception ioe) {
      // oops...
      ioe.printStackTrace(System.err);
      // fallback
      return null;
    }
  }

  @SuppressWarnings("resource")
  private static ShortBuffer readHgtFile(String file) throws Exception {
    if (file == null) throw new Exception("no hgt file " + file);

    FileChannel fc = null;
    ShortBuffer sb = null;
    try {
      // Eclipse complains here about resource leak on 'fc' - even with 'finally' clause???
      fc = new FileInputStream(file).getChannel();
      // choose the right endianness

      ByteBuffer bb = ByteBuffer.allocateDirect((int) fc.size());
      while (bb.remaining() > 0) fc.read(bb);

      bb.flip();
      //sb = bb.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
      sb = bb.order(ByteOrder.BIG_ENDIAN).asShortBuffer();
    } finally {
      if (fc != null) fc.close();
    }

    return sb;
  }

  // @SuppressWarnings("resource")
  private static ShortBuffer readHgtStream(InputStream zis) throws Exception {
    if (zis == null) throw new Exception("no hgt stream ");

    ShortBuffer sb = null;
    try {
      // choose the right endianness

      byte[] bytes = zis.readAllBytes();
      ByteBuffer bb = ByteBuffer.allocate(bytes.length);
      bb.put(bytes, 0, bytes.length);
      //while (bb.remaining() > 0) zis.read(bb, 0, size);

      //ByteBuffer bb = ByteBuffer.allocate(zis.available());
      //Channels.newChannel(zis).read(bb);
      bb.flip();
      //sb = bb.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
      sb = bb.order(ByteOrder.BIG_ENDIAN).asShortBuffer();
    } finally {

    }

    return sb;
  }

  /**
   * Reads the elevation value for the given coordinate.
   * <p>
   * See also <a href="http://gis.stackexchange.com/questions/43743/how-to-extract-elevation-from-hgt-file">stackexchange.com</a>
   *
   * @param lat, lon the coordinate to get the elevation data for
   * @return the elevation value or <code>Double.NaN</code>, if no value is present
   */
  public static double readElevation(double lat, double lon) {
    String tag = getHgtFileName(lat, lon);

    ShortBuffer sb = cache.get(tag);

    if (sb == null) {
      return NO_ELEVATION;
    }

    if (DEBUG) System.out.println("HGT buffer size " + sb.capacity() + " limit " + sb.limit());
    try {
      int rowLength = HGT3_ROW_LENGTH;
      int resolution = HGT3_RES;
      if (sb.capacity() > (HGT3_ROW_LENGTH * HGT3_ROW_LENGTH)) {
        rowLength = HGT1_ROW_LENGTH;
        resolution = HGT1_RES;
      }
      // see http://gis.stackexchange.com/questions/43743/how-to-extract-elevation-from-hgt-file
      double fLat = frac(lat) * SECONDS_PER_MINUTE;
      double fLon = frac(lon) * SECONDS_PER_MINUTE;

      // compute offset within HGT file
      int row = (int) Math.round((fLat) * SECONDS_PER_MINUTE / resolution);
      int col = (int) Math.round((fLon) * SECONDS_PER_MINUTE / resolution);
      if (lon < 0) col = rowLength - col - 1;
      if (lat > 0) row = rowLength - row - 1;


      //row = rowLength - row;
      int cell = (rowLength * (row)) + col;
      //int cell = ((rowLength * (latitude)) + longitude);

      if (DEBUG)
        System.out.println("Read HGT elevation data from row/col/cell " + row + "," + col + ", " + cell + ", " + sb.limit());

      // valid position in buffer?
      if (cell < sb.limit()) {
        short ele = sb.get(cell);
        // check for data voids
        if (ele == HGT_VOID) {
          return NO_ELEVATION;
        } else {
          return ele;
        }
      } else {
        return NO_ELEVATION;
      }
    } catch (Exception e) {
      System.err.println("error at " + lon + " " + lat + " ");
      e.printStackTrace();
    }
    return NO_ELEVATION;
  }

  /**
   * Gets the associated HGT file name for the given way point. Usually the
   * format is <tt>[N|S]nn[W|E]mmm.hgt</tt> where <i>nn</i> is the integral latitude
   * without decimals and <i>mmm</i> is the longitude.
   *
   * @param llat,llon the coordinate to get the filename for
   * @return the file name of the HGT file
   */
  public static String getHgtFileName(double llat, double llon) {
    int lat = (int) llat;
    int lon = (int) llon;

    String latPref = "N";
    if (lat < 0) {
      latPref = "S";
      lat = -lat + 1;
    }
    String lonPref = "E";
    if (lon < 0) {
      lonPref = "W";
      lon = -lon + 1;
    }

    return String.format("%s%02d%s%03d", latPref, lat, lonPref, lon);
  }

  public static double frac(double d) {
    long iPart;
    double fPart;

    // Get user input
    iPart = (long) d;
    fPart = d - iPart;
    return Math.abs(fPart);
  }

  public static void clear() {
    if (cache != null) {
      cache.clear();
    }
  }

  public static void main(String[] args) throws Exception {
    System.out.println("*** HGT position values and enhance elevation");
    if (args.length == 3) {
      HgtReader elevReader = new HgtReader(args[0]);
      double lon = Double.parseDouble(args[1]);
      double lat = Double.parseDouble(args[2]);
      // check hgt direct
      double elev = elevReader.getElevationFromHgt(lat, lon);
      System.out.println("-----> elv for hgt " + lat + ", " + lon + " = " + elev);

    }
  }
}
