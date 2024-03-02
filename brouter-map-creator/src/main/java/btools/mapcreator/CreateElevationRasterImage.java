package btools.mapcreator;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;

import javax.imageio.ImageIO;


public class CreateElevationRasterImage {

  final static boolean DEBUG = false;

  int[] data;
  ElevationRaster lastSrtmRaster;
  Map<String, ElevationRaster> srtmmap;
  int lastSrtmLonIdx;
  int lastSrtmLatIdx;
  String srtmdir;
  boolean missingData;
  Map<Short, Color> colorMap;

  private void createImage(double lon, double lat, String dir, String imageName, int maxX, int maxY, int downscale, String format) throws Exception {
    srtmdir = dir;
    if (format.equals("hgt")) {
      createImageFromHgt(lon, lat, dir, imageName, maxX, maxY);
      return;
    }
    srtmmap = new HashMap<>();
    lastSrtmLonIdx = -1;
    lastSrtmLatIdx = -1;
    lastSrtmRaster = null;
    NodeData n = new NodeData(1, lon, lat);
    ElevationRaster srtm = srtmForNode(n.ilon, n.ilat);
    System.out.println("srtm " + srtm.toString());
    //System.out.println("srtm elev " + srtm.getElevation(n.ilon, n.ilat));
    double[] pos = getElevationPos(srtm, n.ilon, n.ilat);
    System.out.println("srtm pos " + pos[0] + " " + pos[1]); // Arrays.toString(pos));
    short[] raster = srtm.eval_array;
    int rasterX = srtm.ncols;
    int rasterY = srtm.nrows;

    int tileSize = 1000 / downscale;
    int sizeX = (maxX);
    int sizeY = (maxY);
    int[] imgraster = new int[sizeX * sizeY];
    System.out.println("srtm target " + sizeX + " " + sizeY + " (" + rasterX + " " + rasterY + ")");
    for (int y = 0; y < sizeY; y++) {
      for (int x = 0; x < sizeX; x++) {
        //short e = getElevationXY(srtm, /*pos[0] + */(sizeY - y) * downscale, /*pos[1] +*/ (x * downscale));
        short e = get(srtm, sizeY - y, x);

        if (e == Short.MIN_VALUE) {
          imgraster[sizeY * y + x] = 0xffff;
        } else {
          //imgraster[sizeY * y + x] = getColorForHeight((short)(e/4)); //(int)(e/4.);
          imgraster[sizeY * y + x] = getColorForHeight(e);
        }
      }
    }
    if (DEBUG) {
      String out = "short ";
      for (int i = 0; i < 100; i++) {
        out += " " + get(srtm, sizeY - 0, i);
      }
      System.out.println(out);
    }

    BufferedImage argbImage = new BufferedImage(sizeX, sizeY, BufferedImage.TYPE_INT_ARGB);
    data = ((DataBufferInt) argbImage.getRaster().getDataBuffer()).getData();

    for (int y = 0; y < sizeY; y++) {
      for (int x = 0; x < sizeX; x++) {
        int v0 = imgraster[sizeX * y + x];

        int rgb;
        if (v0 != 0xffff)
          rgb = 0xff000000 | v0; //(v0 << 8);
        else
          rgb = 0xff000000;
        data[y * sizeX + x] = rgb;
      }
    }

    ImageIO.write(argbImage, "png", new FileOutputStream(imageName));
  }

  private void createImageFromHgt(double lon, double lat, String dir, String imageName, int maxX, int maxY) throws Exception {
    HgtReader rdr = new HgtReader(dir);
    short[] data = rdr.getElevationDataFromHgt(lat, lon);
    int size = (data != null ? data.length : 0);
    int rowlen = (int) Math.sqrt(size);
    System.out.println("hgt size " + rowlen);
    int sizeX = (maxX);
    int sizeY = (maxY);
    int[] imgraster = new int[sizeX * sizeY];

    for (int y = 0; y < sizeY; y++) {
      for (int x = 0; x < sizeX; x++) {
        short e = data[(rowlen * y) + x];
        if (e == HgtReader.HGT_VOID) {
          imgraster[sizeY * y + x] = 0xffff;
        } else if (e == 0) {
          imgraster[sizeY * y + x] = 0xffff;
        } else {
          imgraster[sizeY * y + x] = getColorForHeight((short) (e)); //(int)(e/4.);
        }
      }
    }
    if (DEBUG) {
      String out = "short ";
      for (int i = 0; i < 100; i++) {
        out += " " + data[i];
      }
      System.out.println(out);
    }
    BufferedImage argbImage = new BufferedImage(sizeX, sizeY, BufferedImage.TYPE_INT_ARGB);
    int[] idata = ((DataBufferInt) argbImage.getRaster().getDataBuffer()).getData();

    for (int y = 0; y < sizeY; y++) {
      for (int x = 0; x < sizeX; x++) {
        int v0 = imgraster[sizeX * y + x];

        int rgb;
        if (v0 != 0xffff)
          rgb = 0xff000000 | v0; //(v0 << 8);
        else
          rgb = 0xff000000;
        idata[y * sizeX + x] = rgb;
      }
    }

    ImageIO.write(argbImage, "png", new FileOutputStream(imageName));

  }

  public double[] getElevationPos(ElevationRaster srtm, int ilon, int ilat) {
    double lon = ilon / 1000000. - 180.;
    double lat = ilat / 1000000. - 90.;

    double dcol = (lon - srtm.xllcorner) / srtm.cellsize - 0.5;
    double drow = (lat - srtm.yllcorner) / srtm.cellsize - 0.5;
    int row = (int) drow;
    int col = (int) dcol;
    if (col < 0) col = 0;
    if (row < 0) row = 0;

    return new double[]{drow, dcol};
  }

  private short get(ElevationRaster srtm, int r, int c) {
    short e = srtm.eval_array[(srtm.nrows - 1 - r) * srtm.ncols + c];
    if (e == Short.MIN_VALUE) missingData = true;
    return e;
  }

  public short getElevationXY(ElevationRaster srtm, double drow, double dcol) {
    int row = (int) drow;
    int col = (int) dcol;
    if (col < 0) col = 0;
    if (col >= srtm.ncols - 1) col = srtm.ncols - 2;
    if (row < 0) row = 0;
    if (row >= srtm.nrows - 1) row = srtm.nrows - 2;
    double wrow = drow - row;
    double wcol = dcol - col;
    missingData = false;

    double eval = (1. - wrow) * (1. - wcol) * get(srtm, row, col)
      + (wrow) * (1. - wcol) * get(srtm, row + 1, col)
      + (1. - wrow) * (wcol) * get(srtm, row, col + 1)
      + (wrow) * (wcol) * get(srtm, row + 1, col + 1);

    return missingData ? Short.MIN_VALUE : (short) (eval * 4);
  }


  int getColorForHeight(short h) {
    if (colorMap == null) {
      colorMap = new TreeMap<>();
      colorMap.put((short) 0, new Color(102, 153, 153));
      colorMap.put((short) 1, new Color(0, 102, 0));
      colorMap.put((short) 500, new Color(251, 255, 128));
      colorMap.put((short) 1200, new Color(224, 108, 31));
      colorMap.put((short) 2500, new Color(200, 55, 55));
      colorMap.put((short) 4000, new Color(215, 244, 244));
      colorMap.put((short) 8000, new Color(255, 244, 244));
    }
    Color lastColor = null;
    short lastKey = 0;
    for (Entry<Short, Color> entry : colorMap.entrySet()) {
      short key = entry.getKey();
      Color value = entry.getValue();
      if (key == h) return value.getRGB();
      if (lastColor != null && lastKey < h && key > h) {
        double between = (double) (h - lastKey) / (key - lastKey);
        return mixColors(value, lastColor, between);
      }
      lastColor = value;
      lastKey = key;
    }
    return 0;
  }

  public int mixColors(Color color1, Color color2, double percent) {
    double inverse_percent = 1.0 - percent;
    int redPart = (int) (color1.getRed() * percent + color2.getRed() * inverse_percent);
    int greenPart = (int) (color1.getGreen() * percent + color2.getGreen() * inverse_percent);
    int bluePart = (int) (color1.getBlue() * percent + color2.getBlue() * inverse_percent);
    return new Color(redPart, greenPart, bluePart).getRGB();
  }

  private ElevationRaster srtmForNode(int ilon, int ilat) throws Exception {
    int srtmLonIdx = (ilon + 5000000) / 5000000;
    int srtmLatIdx = (654999999 - ilat) / 5000000 - 100; // ugly negative rounding...

    if (srtmLonIdx == lastSrtmLonIdx && srtmLatIdx == lastSrtmLatIdx) {
      return lastSrtmRaster;
    }
    lastSrtmLonIdx = srtmLonIdx;
    lastSrtmLatIdx = srtmLatIdx;

    String slonidx = "0" + srtmLonIdx;
    String slatidx = "0" + srtmLatIdx;
    String filename = "srtm_" + slonidx.substring(slonidx.length() - 2) + "_" + slatidx.substring(slatidx.length() - 2);

    lastSrtmRaster = srtmmap.get(filename);
    if (lastSrtmRaster == null && !srtmmap.containsKey(filename)) {
      File f = new File(new File(srtmdir), filename + ".bef");
      if (f.exists()) {
        System.out.println("*** reading: " + f);
        try {
          InputStream isc = new BufferedInputStream(new FileInputStream(f));
          lastSrtmRaster = new ElevationRasterCoder().decodeRaster(isc);
          isc.close();
        } catch (Exception e) {
          System.out.println("**** ERROR reading " + f + " ****");
        }
        srtmmap.put(filename, lastSrtmRaster);
        return lastSrtmRaster;
      }

      srtmmap.put(filename, lastSrtmRaster);
    }
    return lastSrtmRaster;
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 6) {
      System.out.println("usage: java CreateLidarImage <lon> <lat> <srtm-folder> <imageFileName> <maxX> <maxY> <downscale> [type]");
      System.out.println("\nwhere: type = [bef|hgt] downscale = [1|2|4|..]");
      return;
    }
    String format = args.length == 8 ? args[7] : "bef";
    new CreateElevationRasterImage().createImage(Double.parseDouble(args[0]), Double.parseDouble(args[1]), args[2], args[3],
      Integer.parseInt(args[4]), Integer.parseInt(args[5]), Integer.parseInt(args[6]), format);
  }
}
