package btools.mapcreator;

import btools.util.ReducedMedianFilter;

/**
 * Container for a elevation raster + it's meta-data
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

  public boolean usingWeights = false;

  private boolean missingData = false;

  public short getElevation(int ilon, int ilat) {
    double lon = ilon / 1000000. - 180.;
    double lat = ilat / 1000000. - 90.;

    if (usingWeights) {
      return getElevationFromShiftWeights(lon, lat);
    }

    // no weights calculated, use 2d linear interpolation
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

// System.out.println( "wrow=" + wrow + " wcol=" + wcol + " row=" + row + " col=" + col );
    double eval = (1. - wrow) * (1. - wcol) * get(row, col)
      + (wrow) * (1. - wcol) * get(row + 1, col)
      + (1. - wrow) * (wcol) * get(row, col + 1)
      + (wrow) * (wcol) * get(row + 1, col + 1);
// System.out.println( "eval=" + eval );
    return missingData ? Short.MIN_VALUE : (short) (eval * 4);
  }

  private short get(int r, int c) {
    short e = eval_array[(nrows - 1 - r) * ncols + c];
    if (e == Short.MIN_VALUE) missingData = true;
    return e;
  }

  private short getElevationFromShiftWeights(double lon, double lat) {
    // calc lat-idx and -weight
    double alat = lat < 0. ? -lat : lat;
    alat /= 5.;
    int latIdx = (int) alat;
    double wlat = alat - latIdx;

    double dcol = (lon - xllcorner) / cellsize;
    double drow = (lat - yllcorner) / cellsize;
    int row = (int) drow;
    int col = (int) dcol;

    double dgx = (dcol - col) * gridSteps;
    double dgy = (drow - row) * gridSteps;

//      System.out.println( "wrow=" + wrow + " wcol=" + wcol + " row=" + row + " col=" + col );

    int gx = (int) (dgx);
    int gy = (int) (dgy);

    double wx = dgx - gx;
    double wy = dgy - gy;

    double w00 = (1. - wx) * (1. - wy);
    double w01 = (1. - wx) * (wy);
    double w10 = (wx) * (1. - wy);
    double w11 = (wx) * (wy);

    Weights[][] w0 = getWeights(latIdx);
    Weights[][] w1 = getWeights(latIdx + 1);

    missingData = false;

    double m0 = w00 * getElevation(w0[gx][gy], row, col)
      + w01 * getElevation(w0[gx][gy + 1], row, col)
      + w10 * getElevation(w0[gx + 1][gy], row, col)
      + w11 * getElevation(w0[gx + 1][gy + 1], row, col);
    double m1 = w00 * getElevation(w1[gx][gy], row, col)
      + w01 * getElevation(w1[gx][gy + 1], row, col)
      + w10 * getElevation(w1[gx + 1][gy], row, col)
      + w11 * getElevation(w1[gx + 1][gy + 1], row, col);

    if (missingData) return Short.MIN_VALUE;
    double m = (1. - wlat) * m0 + wlat * m1;
    return (short) (m * 2);
  }

  private ReducedMedianFilter rmf = new ReducedMedianFilter(256);

  private double getElevation(Weights w, int row, int col) {
    if (missingData) {
      return 0.;
    }
    int nx = w.nx;
    int ny = w.ny;
    int mx = nx / 2; // mean pixels
    int my = ny / 2;

    // System.out.println( "nx="+ nx + " ny=" + ny );

    rmf.reset();

    for (int ix = 0; ix < nx; ix++) {
      for (int iy = 0; iy < ny; iy++) {
        short val = get(row + iy - my, col + ix - mx);
        rmf.addSample(w.getWeight(ix, iy), val);
      }
    }
    return missingData ? 0. : rmf.calcEdgeReducedMedian(filterCenterFraction);
  }


  private static class Weights {
    int nx;
    int ny;
    double[] weights;
    long total = 0;

    Weights(int nx, int ny) {
      this.nx = nx;
      this.ny = ny;
      weights = new double[nx * ny];
    }

    void inc(int ix, int iy) {
      weights[iy * nx + ix] += 1.;
      total++;
    }

    void normalize(boolean verbose) {
      for (int iy = 0; iy < ny; iy++) {
        StringBuilder sb = verbose ? new StringBuilder() : null;
        for (int ix = 0; ix < nx; ix++) {
          weights[iy * nx + ix] /= total;
          if (sb != null) {
            int iweight = (int) (1000 * weights[iy * nx + ix] + 0.5);
            String sval = "     " + iweight;
            sb.append(sval.substring(sval.length() - 4));
          }
        }
        if (sb != null) {
          System.out.println(sb);
          System.out.println();
        }
      }
    }

    double getWeight(int ix, int iy) {
      return weights[iy * nx + ix];
    }
  }

  private static int gridSteps = 10;
  private static Weights[][][] allShiftWeights = new Weights[17][][];

  private static double filterCenterFraction = 0.2;
  private static double filterDiscRadius = 4.999; // in pixels

  static {
    String sRadius = System.getProperty("filterDiscRadius");
    if (sRadius != null && sRadius.length() > 0) {
      filterDiscRadius = Integer.parseInt(sRadius);
      System.out.println("using filterDiscRadius = " + filterDiscRadius);
    }
    String sFraction = System.getProperty("filterCenterFraction");
    if (sFraction != null && sFraction.length() > 0) {
      filterCenterFraction = Integer.parseInt(sFraction) / 100.;
      System.out.println("using filterCenterFraction = " + filterCenterFraction);
    }
  }


  // calculate interpolation weights from the overlap of a probe disc of given radius at given latitude
  // ( latIndex = 0 -> 0 deg, latIndex = 16 -> 80 degree)

  private static Weights[][] getWeights(int latIndex) {
    int idx = latIndex < 16 ? latIndex : 16;

    Weights[][] res = allShiftWeights[idx];
    if (res == null) {
      res = calcWeights(idx);
      allShiftWeights[idx] = res;
    }
    return res;
  }

  private static Weights[][] calcWeights(int latIndex) {
    double coslat = Math.cos(latIndex * 5. / 57.3);

    // radius in pixel units
    double ry = filterDiscRadius;
    double rx = ry / coslat;

    // gridsize is 2*radius + 1 cell
    int nx = ((int) rx) * 2 + 3;
    int ny = ((int) ry) * 2 + 3;

    System.out.println("nx=" + nx + " ny=" + ny);

    int mx = nx / 2; // mean pixels
    int my = ny / 2;

    // create a matrix for the relative intergrid-position

    Weights[][] shiftWeights = new Weights[gridSteps + 1][];

    // loop the intergrid-position
    for (int gx = 0; gx <= gridSteps; gx++) {
      shiftWeights[gx] = new Weights[gridSteps + 1];
      double x0 = mx + ((double) gx) / gridSteps;

      for (int gy = 0; gy <= gridSteps; gy++) {
        double y0 = my + ((double) gy) / gridSteps;

        // create the weight-matrix
        Weights weights = new Weights(nx, ny);
        shiftWeights[gx][gy] = weights;

        double sampleStep = 0.001;

        for (double x = -1. + sampleStep / 2.; x < 1.; x += sampleStep) {
          double mx2 = 1. - x * x;

          int x_idx = (int) (x0 + x * rx);

          for (double y = -1. + sampleStep / 2.; y < 1.; y += sampleStep) {
            if (y * y > mx2) {
              continue;
            }
            // we are in the ellipse, see what pixel we are on
            int y_idx = (int) (y0 + y * ry);
            weights.inc(x_idx, y_idx);
          }
        }
        weights.normalize(true);
      }
    }
    return shiftWeights;
  }

  @Override
  public String toString() {
    return ncols + "," + nrows + "," + halfcol + "," + xllcorner + "," + yllcorner + "," + cellsize + "," + noDataValue + "," + usingWeights;
  }
}
