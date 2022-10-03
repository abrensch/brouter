package btools.mapcreator;

/**
 * This is a wrapper for a 5*5 degree srtm file in ascii/zip-format
 * <p>
 * - filter out unused nodes according to the way file
 * - enhance with SRTM elevation data
 * - split further in smaller (5*5 degree) tiles
 *
 * @author ab
 */

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.StringTokenizer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class SrtmData {
  private SrtmRaster raster;

  public SrtmData(File file) throws Exception {
    raster = new SrtmRaster();

    ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(file)));
    try {
      for (; ; ) {
        ZipEntry ze = zis.getNextEntry();
        if (ze.getName().endsWith(".asc")) {
          readFromStream(zis);
          return;
        }
      }
    } finally {
      zis.close();
    }
  }

  public SrtmRaster getRaster() {
    return raster;
  }

  private String secondToken(String s) {
    StringTokenizer tk = new StringTokenizer(s, " ");
    tk.nextToken();
    return tk.nextToken();
  }

  public void readFromStream(InputStream is) throws Exception {
    BufferedReader br = new BufferedReader(new InputStreamReader(is));
    int linenr = 0;
    for (; ; ) {
      linenr++;
      if (linenr <= 6) {
        String line = br.readLine();
        if (linenr == 1)
          raster.ncols = Integer.parseInt(secondToken(line));
        else if (linenr == 2)
          raster.nrows = Integer.parseInt(secondToken(line));
        else if (linenr == 3)
          raster.xllcorner = Double.parseDouble(secondToken(line));
        else if (linenr == 4)
          raster.yllcorner = Double.parseDouble(secondToken(line));
        else if (linenr == 5)
          raster.cellsize = Double.parseDouble(secondToken(line));
        else if (linenr == 6) {
          // nodata ignored here ( < -250 assumed nodata... )
          // raster.noDataValue = Short.parseShort( secondToken( line ) );
          raster.eval_array = new short[raster.ncols * raster.nrows];
        }
      } else {
        int row = 0;
        int col = 0;
        int n = 0;
        boolean negative = false;
        for (; ; ) {
          int c = br.read();
          if (c < 0)
            break;
          if (c == ' ') {
            if (negative)
              n = -n;
            short val = n < -250 ? Short.MIN_VALUE : (short) (n);

            raster.eval_array[row * raster.ncols + col] = val;
            if (++col == raster.ncols) {
              col = 0;
              ++row;
            }
            n = 0;
            negative = false;
          } else if (c >= '0' && c <= '9') {
            n = 10 * n + (c - '0');
          } else if (c == '-') {
            negative = true;
          }
        }
        break;
      }
    }
    br.close();
  }

  public static void main(String[] args) throws Exception {
    String fromDir = args[0];
    String toDir = args[1];

    File[] files = new File(fromDir).listFiles();
    for (File f : files) {
      if (!f.getName().endsWith(".zip")) {
        continue;
      }
      System.out.println("*** reading: " + f);
      long t0 = System.currentTimeMillis();
      SrtmRaster raster = new SrtmData(f).getRaster();
      long t1 = System.currentTimeMillis();
      String name = f.getName();

      long zipTime = t1 - t0;

      File fbef = new File(new File(toDir), name.substring(0, name.length() - 3) + "bef");
      System.out.println("recoding: " + f + " to " + fbef);
      OutputStream osbef = new BufferedOutputStream(new FileOutputStream(fbef));
      new RasterCoder().encodeRaster(raster, osbef);
      osbef.close();

      System.out.println("*** re-reading: " + fbef);

      long t2 = System.currentTimeMillis();
      InputStream isc = new BufferedInputStream(new FileInputStream(fbef));
      SrtmRaster raster2 = new RasterCoder().decodeRaster(isc);
      isc.close();
      long t3 = System.currentTimeMillis();

      long befTime = t3 - t2;

      System.out.println("*** zip-time: " + zipTime + "*** bef-time: " + befTime);

      String s1 = raster.toString();
      String s2 = raster2.toString();

      if (!s1.equals(s2)) {
        throw new IllegalArgumentException("missmatch: " + s1 + "<--->" + s2);
      }

      int cols = raster.ncols;
      int rows = raster.nrows;
      for (int c = 0; c < cols; c++) {
        for (int r = 0; r < rows; r++) {
          int idx = r * cols + c;

          if (raster.eval_array[idx] != raster2.eval_array[idx]) {
            throw new IllegalArgumentException("missmatch: at " + c + "," + r + ": " + raster.eval_array[idx] + "<--->" + raster2.eval_array[idx]);
          }
        }
      }
    }
  }
}
