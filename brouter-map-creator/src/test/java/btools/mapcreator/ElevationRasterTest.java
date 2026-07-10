package btools.mapcreator;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import org.junit.Test;

public class ElevationRasterTest {

  private static final int TEST_LON = 180_000_000;
  private static final int TEST_LAT = 90_000_000;

  @Test
  public void scaledElevationSaturatesWithoutUsingTheNoDataValue() {
    assertEquals(Short.MAX_VALUE, constantRaster(8849).getElevation(TEST_LON, TEST_LAT));
    assertEquals(Short.MIN_VALUE + 1,
      constantRaster(-9000).getElevation(TEST_LON, TEST_LAT));
    assertEquals(4000, constantRaster(1000).getElevation(TEST_LON, TEST_LAT));
  }

  @Test
  public void noDataStillReturnsTheReservedValue() {
    assertEquals(Short.MIN_VALUE,
      constantRaster(Short.MIN_VALUE).getElevation(TEST_LON, TEST_LAT));
  }

  private static ElevationRaster constantRaster(int elevation) {
    ElevationRaster raster = new ElevationRaster();
    raster.ncols = 2;
    raster.nrows = 2;
    raster.xllcorner = -0.5;
    raster.yllcorner = -0.5;
    raster.cellsize = 1.0;
    raster.eval_array = new short[4];
    Arrays.fill(raster.eval_array, (short) elevation);
    return raster;
  }
}
