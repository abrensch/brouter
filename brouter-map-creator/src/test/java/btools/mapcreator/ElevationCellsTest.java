package btools.mapcreator;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.List;
import org.junit.Test;

public class ElevationCellsTest {

  /**
   * The name math must invert both filename generators for every cell of the world
   * grid, including the negative latitude indices north of 60N in the legacy scheme.
   */
  @Test
  public void cellNamesRoundTripForTheWholeWorldGrid() {
    List<int[]> corners = ElevationCells.worldCellCorners();
    assertEquals(72 * 35, corners.size());
    for (int[] corner : corners) {
      String oldName = ElevationRasterTileConverter.genFilenameOld(corner[0], corner[1]);
      assertArrayEquals(oldName, corner, ElevationCells.cornerFromCellName(oldName));
      String rd5Name = ElevationRasterTileConverter.genFilenameRd5(corner[0], corner[1]);
      assertArrayEquals(rd5Name, corner, ElevationCells.cornerFromCellName(rd5Name));
    }
  }

  @Test
  public void knownCellsParseToTheRightCorners() {
    // the Alps fixture cell: lon 5..10, lat 45..50
    assertArrayEquals(new int[]{5, 45}, ElevationCells.cornerFromCellName("srtm_38_03"));
    assertArrayEquals(new int[]{5, 45}, ElevationCells.cornerFromCellName("srtm_38_03.bef"));
    assertArrayEquals(new int[]{5, 45}, ElevationCells.cornerFromCellName("srtm_E5_N45"));
    // north of 60N the legacy scheme goes negative
    assertArrayEquals(new int[]{10, 65}, ElevationCells.cornerFromCellName("srtm_39_-1"));
    assertArrayEquals(new int[]{-10, -70}, ElevationCells.cornerFromCellName("srtm_W10_S70"));
  }

  @Test
  public void malformedOrOutOfRangeNamesFailLoudly() {
    String[] bad = {"srtm_99_99", "srtm_0_0", "srtm_38", "srtm_38_03_x", "srtm_E10", "dem_38_03", "srtm_X10_N45"};
    for (String name : bad) {
      try {
        ElevationCells.cornerFromCellName(name);
        fail("expected rejection of " + name);
      } catch (IllegalArgumentException expected) {
        // good: no silent nonsense corner
      }
    }
  }

  @Test
  public void rasterGeometryMatchesTheSharedConvention() {
    ElevationRaster raster = new ElevationRaster();
    ElevationCells.configureCellRaster(raster, 5, 45, 1200);
    assertEquals(6001, raster.nrows);
    assertEquals(6001, raster.ncols);
    assertEquals(1.0 / 1200, raster.cellsize, 1e-12);
    assertEquals(5 - 0.5 / 1200, raster.xllcorner, 1e-12);
    assertEquals(45 - 0.5 / 1200, raster.yllcorner, 1e-12);
    assertEquals(ElevationCells.NODATA, raster.noDataValue);
  }
}
