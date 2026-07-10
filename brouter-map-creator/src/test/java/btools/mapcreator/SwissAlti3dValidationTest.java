package btools.mapcreator;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * Ground-truth validation: the converter runs over REAL Mapterhorn terrain tiles
 * (bundled as fixtures) and its elevations are compared against swissALTI3D, the Swiss
 * national LiDAR reference (accuracy +/-0.5 m), along two professionally measured Swiss
 * Cycling Talent-ID test climbs. Fully offline and deterministic.
 * <p>
 * The decisive metric is CUMULATIVE ELEVATION GAIN along the climb - the quantity
 * routing sums into total-ascent. Reference figures established 2026-07-10: gain error
 * -0.35 m (Beggingen) / +0.05 m (Wilderswil) over 1.76 km, point RMSE ~1.2 m, worst
 * single point 3.1 m (a 30 m cell mixing road and embankment).
 */
public class SwissAlti3dValidationTest {

  private static final int ZOOM = 12;
  private static final int TILE_SIZE = 512;
  private static final int ROW_LENGTH = ElevationCells.SRTM1_ROW_LENGTH; // 1 arcsec

  // the cell holding both climbs: lon 5..10, lat 45..50
  private static final int LON_START = 5;
  private static final int LAT_START = 45;

  private static final double MAX_GAIN_ERROR_M = 1.0;
  private static final double MAX_POINT_RMSE_M = 1.5;
  private static final double MAX_POINT_ERROR_M = 3.5;
  private static final double MAX_BIAS_M = 1.0;

  @Test
  public void beggingenClimbMatchesSwissAlti3d() throws IOException {
    validateClimb("beggingen", new int[][]{{2145, 1427}, {2145, 1428}},
      new double[]{8.545, 47.750, 8.566, 47.762});
  }

  @Test
  public void wilderswilClimbMatchesSwissAlti3d() throws IOException {
    validateClimb("wilderswil", new int[][]{{2137, 1446}},
      new double[]{7.850, 46.650, 7.863, 46.662});
  }

  private void validateClimb(String site, int[][] tiles, double[] bbox) throws IOException {
    PmTilesTestArchive builder = new PmTilesTestArchive()
      .zoomRange(ZOOM, ZOOM)
      .tileType(PmTilesArchive.TILETYPE_WEBP);
    for (int[] t : tiles) {
      builder.put(ZOOM, t[0], t[1], resource("/mapterhorn/12_" + t[0] + "_" + t[1] + ".webp"));
    }

    ElevationRaster raster;
    try (PmTilesArchive archive = PmTilesArchive.open(builder.asByteSource());
         ConvertMapterhornTile converter =
           new ConvertMapterhornTile(archive, ZOOM, null, TILE_SIZE)) {
      raster = converter.buildRaster(LON_START, LAT_START, ROW_LENGTH, bbox);
    }
    assertNotNull("conversion produced no raster", raster);

    List<double[]> reference = readReference("/mapterhorn/swissalti3d_" + site + ".csv");
    double gainSwiss = 0;
    double gainMapterhorn = 0;
    double prevSwiss = Double.NaN;
    double prevMapterhorn = Double.NaN;
    double sumDiff = 0;
    double sumSq = 0;
    double maxAbs = 0;
    for (double[] p : reference) {
      int ilon = (int) Math.round((p[0] + 180.0) * 1e6);
      int ilat = (int) Math.round((p[1] + 90.0) * 1e6);
      short quarterMetres = raster.getElevation(ilon, ilat);
      assertNotEquals("no elevation at " + p[1] + "," + p[0],
        (long) Short.MIN_VALUE, (long) quarterMetres);
      double mapterhorn = quarterMetres / 4.0;
      double swiss = p[2];

      double diff = mapterhorn - swiss;
      sumDiff += diff;
      sumSq += diff * diff;
      maxAbs = Math.max(maxAbs, Math.abs(diff));
      if (!Double.isNaN(prevSwiss)) {
        gainSwiss += Math.max(0, swiss - prevSwiss);
        gainMapterhorn += Math.max(0, mapterhorn - prevMapterhorn);
      }
      prevSwiss = swiss;
      prevMapterhorn = mapterhorn;
    }
    int n = reference.size();
    double bias = sumDiff / n;
    double rmse = Math.sqrt(sumSq / n);
    double gainError = gainMapterhorn - gainSwiss;

    assertTrue(site + ": climb gain off the LiDAR reference by " + gainError
        + " m (swiss " + gainSwiss + ", mapterhorn " + gainMapterhorn + ")",
      Math.abs(gainError) <= MAX_GAIN_ERROR_M);
    assertTrue(site + ": point RMSE " + rmse + " m", rmse <= MAX_POINT_RMSE_M);
    assertTrue(site + ": systematic bias " + bias + " m", Math.abs(bias) <= MAX_BIAS_M);
    assertTrue(site + ": worst point off by " + maxAbs + " m", maxAbs <= MAX_POINT_ERROR_M);
  }

  private static byte[] resource(String path) throws IOException {
    try (InputStream is = SwissAlti3dValidationTest.class.getResourceAsStream(path)) {
      assertNotNull("missing test resource " + path, is);
      return is.readAllBytes();
    }
  }

  /**
   * @return points as {lon, lat, swissAlti3dHeight}, in along-the-climb order
   */
  private static List<double[]> readReference(String path) throws IOException {
    List<double[]> points = new ArrayList<>();
    try (InputStream is = SwissAlti3dValidationTest.class.getResourceAsStream(path)) {
      assertNotNull("missing test resource " + path, is);
      for (String line : new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
        if (line.isEmpty()) {
          continue;
        }
        String[] p = line.split(",");
        points.add(new double[]{Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2])});
      }
    }
    return points;
  }
}
