package btools.mapcreator;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ConvertLidarTile {
  private static int NROWS;
  private static int NCOLS;

  public static final short NODATA2 = -32767; // hgt-formats nodata
  public static final short NODATA = Short.MIN_VALUE;

  private static final String HGT_FILE_EXT = ".hgt";
  private static final int HGT_BORDER_OVERLAP = 1;
  private static final int HGT_3ASEC_ROWS = 1201; // 3 arc second resolution (90m)
  private static final int HGT_3ASEC_FILE_SIZE = HGT_3ASEC_ROWS * HGT_3ASEC_ROWS * Short.BYTES;
  private static final int HGT_1ASEC_ROWS = 3601; // 1 arc second resolution (30m)

  static short[] imagePixels;

  private static void readHgtZip(String filename, int rowOffset, int colOffset) throws Exception {
    ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(filename)));
    try {
      for (; ; ) {
        ZipEntry ze = zis.getNextEntry();
        if (ze == null) break;
        if (ze.getName().toLowerCase().endsWith(HGT_FILE_EXT)) {
          readHgtFromStream(zis, rowOffset, colOffset, HGT_3ASEC_ROWS);
          return;
        }
      }
    } finally {
      zis.close();
    }
  }

  private static void readHgtFromStream(InputStream is, int rowOffset, int colOffset, int rowLength)
    throws Exception {
    DataInputStream dis = new DataInputStream(new BufferedInputStream(is));
    for (int ir = 0; ir < rowLength; ir++) {
      int row = rowOffset + ir;

      for (int ic = 0; ic < rowLength; ic++) {
        int col = colOffset + ic;

        int i1 = dis.read(); // msb first!
        int i0 = dis.read();

        if (i0 == -1 || i1 == -1)
          throw new RuntimeException("unexpected end of file reading hgt entry!");

        short val = (short) ((i1 << 8) | i0);

        if (val == NODATA2) {
          val = NODATA;
        }

        setPixel(row, col, val);
      }
    }
  }

  private static void setPixel(int row, int col, short val) {
    if (row >= 0 && row < NROWS && col >= 0 && col < NCOLS) {
      imagePixels[row * NCOLS + col] = val;
    }
  }

  private static short getPixel(int row, int col) {
    if (row >= 0 && row < NROWS && col >= 0 && col < NCOLS) {
      return imagePixels[row * NCOLS + col];
    }
    return NODATA;
  }


  public static void doConvert(String inputDir, int lonDegreeStart, int latDegreeStart, String outputFile) throws Exception {
    int extraBorder = 0;

    NROWS = 5 * 1200 + 1 + 2 * extraBorder;
    NCOLS = 5 * 1200 + 1 + 2 * extraBorder;

    imagePixels = new short[NROWS * NCOLS]; // 650 MB !

    // prefill as NODATA
    for (int row = 0; row < NROWS; row++) {
      for (int col = 0; col < NCOLS; col++) {
        imagePixels[row * NCOLS + col] = NODATA;
      }
    }

    for (int latIdx = -1; latIdx <= 5; latIdx++) {
      int latDegree = latDegreeStart + latIdx;
      int rowOffset = extraBorder + (4 - latIdx) * 1200;

      for (int lonIdx = -1; lonIdx <= 5; lonIdx++) {
        int lonDegree = lonDegreeStart + lonIdx;
        int colOffset = extraBorder + lonIdx * 1200;

        String filename = inputDir + "/" + formatLat(latDegree) + formatLon(lonDegree) + ".zip";
        File f = new File(filename);
        if (f.exists() && f.length() > 0) {
          System.out.println("exist: " + filename);
          readHgtZip(filename, rowOffset, colOffset);
        } else {
          System.out.println("none : " + filename);
        }
      }
    }

    boolean halfCol5 = false; // no halfcol tiles in lidar data (?)


    SrtmRaster raster = new SrtmRaster();
    raster.nrows = NROWS;
    raster.ncols = NCOLS;
    raster.halfcol = halfCol5;
    raster.noDataValue = NODATA;
    raster.cellsize = 1 / 1200.;
    raster.xllcorner = lonDegreeStart - (0.5 + extraBorder) * raster.cellsize;
    raster.yllcorner = latDegreeStart - (0.5 + extraBorder) * raster.cellsize;
    raster.eval_array = imagePixels;

    // encode the raster
    OutputStream os = new BufferedOutputStream(new FileOutputStream(outputFile));
    new RasterCoder().encodeRaster(raster, os);
    os.close();

    // decode the raster
    InputStream is = new BufferedInputStream(new FileInputStream(outputFile));
    SrtmRaster raster2 = new RasterCoder().decodeRaster(is);
    is.close();

    short[] pix2 = raster2.eval_array;
    if (pix2.length != imagePixels.length)
      throw new RuntimeException("length mismatch!");

    // compare decoding result
    for (int row = 0; row < NROWS; row++) {
      int colstep = halfCol5 ? 2 : 1;
      for (int col = 0; col < NCOLS; col += colstep) {
        int idx = row * NCOLS + col;
        short p2 = pix2[idx];
        if (p2 != imagePixels[idx]) {
          throw new RuntimeException("content mismatch: p2=" + p2 + " p1=" + imagePixels[idx]);
        }
      }
    }
  }

  private static String formatLon(int lon) {
    if (lon >= 180)
      lon -= 180; // TODO: w180 oder E180 ?

    String s = "E";
    if (lon < 0) {
      lon = -lon;
      s = "W";
    }
    String n = "000" + lon;
    return s + n.substring(n.length() - 3);
  }

  private static String formatLat(int lat) {
    String s = "N";
    if (lat < 0) {
      lat = -lat;
      s = "S";
    }
    String n = "00" + lat;
    return s + n.substring(n.length() - 2);
  }

  public static void main(String[] args) throws Exception {
    String filename90 = args[0];
    String filename30 = filename90.substring(0, filename90.length() - 3) + "bef";

    int srtmLonIdx = Integer.parseInt(filename90.substring(5, 7).toLowerCase());
    int srtmLatIdx = Integer.parseInt(filename90.substring(8, 10).toLowerCase());

    int ilon_base = (srtmLonIdx - 1) * 5 - 180;
    int ilat_base = 150 - srtmLatIdx * 5 - 90;

    doConvert(args[1], ilon_base, ilat_base, filename30);
  }

  public SrtmRaster getRaster(File f, double lon, double lat) throws Exception {
    long fileSize;
    InputStream inputStream;

    if (f.getName().toLowerCase().endsWith(".zip")) {
      ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(f)));
      for (; ; ) {
        ZipEntry ze = zis.getNextEntry();
        if (ze == null) {
          throw new FileNotFoundException(f.getName() + " doesn't contain a " + HGT_FILE_EXT + " file.");
        }
        if (ze.getName().toLowerCase().endsWith(HGT_FILE_EXT)) {
          fileSize = ze.getSize();
          inputStream = zis;
          break;
        }
      }
    } else {
      fileSize = f.length();
      inputStream = new FileInputStream(f);
    }

    int rowLength;
    if (fileSize > HGT_3ASEC_FILE_SIZE) {
      rowLength = HGT_1ASEC_ROWS;
    } else {
      rowLength = HGT_3ASEC_ROWS;
    }

    // stay at 1 deg * 1 deg raster
    NROWS = rowLength;
    NCOLS = rowLength;

    imagePixels = new short[NROWS * NCOLS];

    // prefill as NODATA
    Arrays.fill(imagePixels, NODATA);
    readHgtFromStream(inputStream, 0, 0, rowLength);
    inputStream.close();

    SrtmRaster raster = new SrtmRaster();
    raster.nrows = NROWS;
    raster.ncols = NCOLS;
    raster.halfcol = false; // assume full resolution
    raster.noDataValue = NODATA;
    raster.cellsize = 1. / (double) (rowLength - HGT_BORDER_OVERLAP);
    raster.xllcorner = (int) (lon < 0 ? lon - 1 : lon); //onDegreeStart - raster.cellsize;
    raster.yllcorner = (int) (lat < 0 ? lat - 1 : lat); //latDegreeStart - raster.cellsize;
    raster.eval_array = imagePixels;

    return raster;
  }

}
