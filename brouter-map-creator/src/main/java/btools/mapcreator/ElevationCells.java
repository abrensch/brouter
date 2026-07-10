package btools.mapcreator;

import java.util.ArrayList;
import java.util.List;

/**
 * The 5x5-degree elevation cell conventions shared by every .bef producer.
 * <p>
 * ConvertMapterhornTile promises output byte-compatible with the hgt path in
 * {@link ElevationRasterTileConverter}; keeping the grid formula, corner offsets and
 * name math in one place is what makes that promise hold. If these constants or
 * formulas change, both converters and PosUnifier change together.
 */
public final class ElevationCells {

  /** Samples per degree at 3 arc seconds (90 m). */
  public static final int SRTM3_ROW_LENGTH = 1200;

  /** Samples per degree at 1 arc second (30 m). */
  public static final int SRTM1_ROW_LENGTH = 3600;

  /** The raster no-data marker, also understood by ElevationRasterCoder. */
  public static final short NODATA = Short.MIN_VALUE;

  private ElevationCells() {
  }

  /**
   * Samples per axis of a 5-degree cell: the +1 shares one sample row/column with the
   * neighbouring cell, exactly like SRTM's 6001-pixel tiles.
   */
  public static int cellSamples(int rowLength) {
    return 5 * rowLength + 1;
  }

  /**
   * Configure a raster's grid geometry for the cell with the given south-west corner.
   * The corner sits half a cell outside the degree grid so that grid nodes land on
   * sample centres ({@code ElevationRaster.getElevation} subtracts the half cell again).
   * Does not allocate {@code eval_array}.
   */
  public static void configureCellRaster(ElevationRaster raster, int lonStart, int latStart,
                                         int rowLength) {
    int n = cellSamples(rowLength);
    double cellsize = 1.0 / rowLength;
    raster.nrows = n;
    raster.ncols = n;
    raster.halfcol = false;
    raster.noDataValue = NODATA;
    raster.cellsize = cellsize;
    raster.xllcorner = lonStart - 0.5 * cellsize;
    raster.yllcorner = latStart - 0.5 * cellsize;
  }

  /**
   * South-west corners of every 5-degree cell in the world grid, northernmost first,
   * matching the iteration order of the existing converters.
   */
  public static List<int[]> worldCellCorners() {
    List<int[]> corners = new ArrayList<>(72 * 36);
    for (int lon = -180; lon < 180; lon += 5) {
      for (int lat = 85; lat >= -90; lat -= 5) {
        corners.add(new int[]{lon, lat});
      }
    }
    return corners;
  }

  /**
   * Legacy cell name: {@code srtm_38_03.bef} — lon index 1..72 from 180W, lat index
   * from 60N southwards (negative above 60N).
   */
  public static String legacyCellName(int lonStart, int latStart) {
    int lonIdx = ((lonStart + 180) / 5) + 1;
    int latIdx = (60 - latStart) / 5;
    return String.format(java.util.Locale.US, "srtm_%02d_%02d.bef", lonIdx, latIdx);
  }

  /**
   * Rd5-style cell name: {@code srtm_E10_N45.bef}, matching the rd5 segment scheme.
   */
  public static String rd5CellName(int lonStart, int latStart) {
    return String.format("srtm_%s_%s.bef", lonStart < 0 ? "W" + (-lonStart) : "E" + lonStart,
      latStart < 0 ? "S" + (-latStart) : "N" + latStart);
  }

  /**
   * Inverse of {@link #legacyCellName} / {@link #rd5CellName}: parse a cell name (with
   * or without the .bef suffix) to its south-west corner. Accepts the legacy index
   * scheme ({@code srtm_38_03}) and the rd5-style scheme ({@code srtm_E10_N45}).
   *
   * @return {lonStart, latStart}
   * @throws IllegalArgumentException on malformed names or out-of-range cells
   */
  public static int[] cornerFromCellName(String name) {
    String s = name.endsWith(".bef") ? name.substring(0, name.length() - 4) : name;
    // the prefix is case-insensitive: the previous positional parser never checked it
    if (s.length() < 5 || !s.regionMatches(true, 0, "srtm_", 0, 5)) {
      throw new IllegalArgumentException("cell name must start with 'srtm_': " + name);
    }
    String[] parts = s.substring(5).split("_");
    if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
      throw new IllegalArgumentException("cell name must be srtm_<lon>_<lat>: " + name);
    }
    int lonStart;
    int latStart;
    try {
      if (Character.isDigit(parts[0].charAt(0)) || parts[0].charAt(0) == '-') {
        // legacy scheme: srtm_<lonIdx>_<latIdx>, lonIdx 1..72 from 180W, latIdx from 60N
        int lonIdx = Integer.parseInt(parts[0]);
        int latIdx = Integer.parseInt(parts[1]);
        lonStart = (lonIdx - 1) * 5 - 180;
        latStart = 60 - latIdx * 5;
      } else {
        // rd5 scheme: srtm_<E|W><lon>_<N|S><lat>
        lonStart = parseSigned(parts[0], 'E', 'W', name);
        latStart = parseSigned(parts[1], 'N', 'S', name);
      }
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("cannot parse cell name: " + name, e);
    }
    requireCellCorner(lonStart, latStart, name);
    return new int[]{lonStart, latStart};
  }

  private static int parseSigned(String part, char pos, char neg, String name) {
    char c = part.charAt(0);
    int v = Integer.parseInt(part.substring(1));
    if (c == pos) {
      return v;
    }
    if (c == neg) {
      return -v;
    }
    throw new IllegalArgumentException("expected " + pos + " or " + neg + " in: " + name);
  }

  /**
   * Validate that a corner names a real cell of the world grid.
   */
  public static void requireCellCorner(int lonStart, int latStart, String source) {
    if (lonStart % 5 != 0 || latStart % 5 != 0
      || lonStart < -180 || lonStart > 175 || latStart < -90 || latStart > 85) {
      throw new IllegalArgumentException("not a 5-degree cell corner: lon=" + lonStart
        + " lat=" + latStart + " (from " + source + ")");
    }
  }
}
