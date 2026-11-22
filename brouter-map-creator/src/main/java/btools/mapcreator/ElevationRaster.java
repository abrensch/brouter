package btools.mapcreator;
/**
 * Container for a elevation-raster + it's meta-data
 *
 * @author ab
 */
public class ElevationRaster {
  public int ncols;
  public int nrows;
  public boolean halfcol;
  public double xllcorner;
  public double yllcorner;
  public double cellsize;
  public short[] eval_array;
  public short noDataValue;
  private boolean missingData = false;
  public short getElevation(int ilon, int ilat) {
    double lon = ilon / 1000000. - 180.;
    double lat = ilat / 1000000. - 90.;
    // 2d linear interpolation
    double dcol = (lon - xllcorner) / cellsize - 0.5;
    double drow = (lat - yllcorner) / cellsize - 0.5;
    int row = (int) drow;
    int col = (int) dcol;
    if (col < 0) col = 0;
    if (col >= ncols - 1) col = ncols - 2;
    if (row < 0) row = 0;
    if (row >= nrows - 1) row = nrows - 2;
    double wrow = drow - row;
    double wcol = dcol - col;
    missingData = false;
    double eval = (1. - wrow) * (1. - wcol) * get(row, col)
      + (wrow) * (1. - wcol) * get(row + 1, col)
      + (1. - wrow) * (wcol) * get(row, col + 1)
      + (wrow) * (wcol) * get(row + 1, col + 1);
    return missingData ? Short.MIN_VALUE : (short) (eval * 4);
  }
  private short get(int r, int c) {
    short e = eval_array[(nrows - 1 - r) * ncols + c];
    if (e == Short.MIN_VALUE) missingData = true;
    return e;
  }
  @Override
  public String toString() {
    return ncols + "," + nrows + "," + halfcol + "," + xllcorner + "," + yllcorner + "," + cellsize + "," + noDataValue;
  }
}