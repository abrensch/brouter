package btools.mapcreator;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The resampler is checked against fields whose area-average is known in closed form.
 * <p>
 * A box filter reproduces a constant exactly, and averages a linear field to its value at
 * the footprint centroid. The footprint of output column c is the longitude interval
 * [lon-cellsize/2, lon+cellsize/2], which maps linearly onto mercator x, so its centroid
 * is exactly mercatorX(lon). That makes an x-ramp an exact test of the column weights.
 */
public class ConvertMapterhornTileTest {

  private static final int ZOOM = 6;
  private static final int TILE = 64;
  private static final long WORLD_PX = (long) TILE << ZOOM; // 4096

  private static final int LON_START = 0;
  private static final int LAT_START = 45;
  private static final int ROW_LENGTH = 4; // 0.25 degree cells
  private static final int N = 5 * ROW_LENGTH + 1; // 21

  private static double mercatorX(double lon) {
    return (lon + 180.0) / 360.0 * WORLD_PX;
  }

  /** Tiles covering the 5x5 degree cell at (0E, 45N) with a small margin. */
  private static PmTilesTestArchive archiveOf(TerrainTiles.Elevation field) throws IOException {
    PmTilesTestArchive builder = new PmTilesTestArchive().zoomRange(ZOOM, ZOOM);
    for (int tx = 30; tx <= 34; tx++) {
      for (int ty = 20; ty <= 24; ty++) {
        builder.put(ZOOM, tx, ty, TerrainTiles.tilePng(TILE, tx, ty, field));
      }
    }
    return builder;
  }

  private static ElevationRaster convert(PmTilesTestArchive builder, double[] bbox) throws IOException {
    try (PmTilesArchive archive = PmTilesArchive.open(builder.asByteSource());
         ConvertMapterhornTile converter = new ConvertMapterhornTile(archive, ZOOM, null, TILE)) {
      return converter.buildRaster(LON_START, LAT_START, ROW_LENGTH, bbox);
    }
  }

  @Test
  public void rasterGeometryMatchesTheHgtConvention() throws IOException {
    ElevationRaster raster = convert(archiveOf((x, y) -> 300.0), null);
    assertNotNull(raster);
    assertEquals(N, raster.nrows);
    assertEquals(N, raster.ncols);
    assertEquals(1.0 / ROW_LENGTH, raster.cellsize, 1e-12);
    // corners sit half a cell outside the degree grid, exactly as ElevationRasterTileConverter does
    assertEquals(LON_START - 0.5 / ROW_LENGTH, raster.xllcorner, 1e-12);
    assertEquals(LAT_START - 0.5 / ROW_LENGTH, raster.yllcorner, 1e-12);
    assertEquals(Short.MIN_VALUE, raster.noDataValue);
    assertFalse(raster.halfcol);
  }

  @Test
  public void constantFieldIsReproducedExactly() throws IOException {
    ElevationRaster raster = convert(archiveOf((x, y) -> 300.0), null);
    for (int i = 0; i < raster.eval_array.length; i++) {
      assertEquals("pixel " + i, 300, raster.eval_array[i]);
    }
  }

  /**
   * A field linear in source x must average to its value at the cell centre. Point
   * sampling would pass this too, but the wrong footprint or unnormalised weights
   * would not.
   */
  @Test
  public void linearFieldAveragesToTheCellCentre() throws IOException {
    // sampled at pixel centres, so pixel i carries f(i + 0.5)
    TerrainTiles.Elevation ramp = (gx, gy) -> 100.0 + 0.5 * (gx + 0.5);
    ElevationRaster raster = convert(archiveOf(ramp), null);

    for (int col = 0; col < N; col++) {
      double expected = 100.0 + 0.5 * mercatorX(LON_START + col * (1.0 / ROW_LENGTH));
      for (int row = 0; row < N; row++) {
        short actual = raster.eval_array[row * N + col];
        assertNotEquals("no-data at row " + row + " col " + col,
          (long) Short.MIN_VALUE, (long) actual);
        assertEquals("row " + row + " col " + col, expected, actual, 0.55);
      }
    }
  }

  /**
   * eval_array holds the north row first; ElevationRaster.get() reads it back flipped.
   * A field that grows southwards catches an inverted axis.
   */
  @Test
  public void northRowIsStoredFirst() throws IOException {
    // mercator y grows southwards
    ElevationRaster raster = convert(archiveOf((gx, gy) -> 100.0 + gy), null);

    short north = raster.eval_array[0];
    short south = raster.eval_array[(N - 1) * N];
    assertTrue("north row must hold the smaller mercator y, got " + north + " vs " + south,
      north < south);

    // and the raster's own reader agrees about which latitude is which
    int ilon = (int) ((LON_START + 2.5 + 180.0) * 1e6);
    short high = raster.getElevation(ilon, (int) ((LAT_START + 4.5 + 90.0) * 1e6));
    short low = raster.getElevation(ilon, (int) ((LAT_START + 0.5 + 90.0) * 1e6));
    assertTrue("getElevation must see the north sample as smaller", high < low);
  }

  /**
   * getElevation returns quarter-metres, which is what PosUnifier writes into the rd5.
   */
  @Test
  public void rasterReaderReturnsQuarterMetres() throws IOException {
    ElevationRaster raster = convert(archiveOf((x, y) -> 300.0), null);
    int ilon = (int) ((LON_START + 2.5 + 180.0) * 1e6);
    int ilat = (int) ((LAT_START + 2.5 + 90.0) * 1e6);
    assertEquals(1200, raster.getElevation(ilon, ilat));
  }

  @Test
  public void survivesTheBefCoderRoundTrip() throws IOException {
    ElevationRaster raster = convert(archiveOf((gx, gy) -> 100.0 + (gx % 97) + (gy % 31)), null);

    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    new ElevationRasterCoder().encodeRaster(raster, bos);
    ElevationRaster decoded = new ElevationRasterCoder()
      .decodeRaster(new ByteArrayInputStream(bos.toByteArray()));

    assertEquals(raster.nrows, decoded.nrows);
    assertEquals(raster.ncols, decoded.ncols);
    assertEquals(raster.cellsize, decoded.cellsize, 1e-12);
    assertEquals(raster.xllcorner, decoded.xllcorner, 1e-12);
    assertEquals(raster.yllcorner, decoded.yllcorner, 1e-12);
    for (int i = 0; i < raster.eval_array.length; i++) {
      assertEquals("pixel " + i, raster.eval_array[i], decoded.eval_array[i]);
    }
  }

  /**
   * Mapterhorn omits open water. An empty cell must be skipped, not written as a raster
   * full of no-data.
   */
  @Test
  public void cellWithNoSourceTilesIsSkipped() throws IOException {
    PmTilesTestArchive empty = new PmTilesTestArchive().zoomRange(ZOOM, ZOOM)
      .put(ZOOM, 0, 0, TerrainTiles.constantPng(TILE, 10.0));
    assertNull(convert(empty, null));
  }

  /**
   * A hole in the source must become no-data, never an interpolated guess pulled in from
   * whatever pixels happened to survive.
   */
  @Test
  public void missingTilesBecomeNoData() throws IOException {
    PmTilesTestArchive builder = new PmTilesTestArchive().zoomRange(ZOOM, ZOOM);
    for (int tx = 30; tx <= 34; tx++) {
      for (int ty = 20; ty <= 24; ty++) {
        if (tx == 32 && ty == 22) {
          continue; // punch a hole
        }
        builder.put(ZOOM, tx, ty, TerrainTiles.tilePng(TILE, tx, ty, (gx, gy) -> 500.0));
      }
    }
    ElevationRaster raster = convert(builder, null);
    assertNotNull(raster);

    int noData = 0;
    int valid = 0;
    for (short v : raster.eval_array) {
      if (v == Short.MIN_VALUE) {
        noData++;
      } else {
        assertEquals(500, v);
        valid++;
      }
    }
    assertTrue("the hole must produce no-data", noData > 0);
    assertTrue("the rest must survive", valid > 0);

    // the no-data must land WHERE the hole is: tile (32,22) covers lon 0..5.625,
    // lat ~43.6..48.9, so a point inside both the hole and the cell must be no-data
    // while a point in a present tile keeps its value
    assertEquals("inside the missing tile", Short.MIN_VALUE,
      raster.eval_array[rowColIndex(46.0, 2.5)]);
    assertEquals("inside a present tile", 500,
      raster.eval_array[rowColIndex(49.5, 2.5)]);
  }

  @Test
  public void bboxRestrictsTheFilledWindow() throws IOException {
    double[] bbox = {1.0, 46.0, 2.0, 47.0};
    ElevationRaster raster = convert(archiveOf((x, y) -> 300.0), bbox);
    assertNotNull(raster);

    // a point well inside the window is filled
    int inside = rowColIndex(46.5, 1.5);
    assertEquals(300, raster.eval_array[inside]);

    // a point well outside stays no-data
    int outside = rowColIndex(48.5, 3.5);
    assertEquals(Short.MIN_VALUE, raster.eval_array[outside]);
  }

  @Test
  public void defaultZoomIsClampedToArchiveRange() throws IOException {
    try (PmTilesArchive regional = PmTilesArchive.open(
        new PmTilesTestArchive().zoomRange(13, 17).asByteSource());
         PmTilesArchive merged = PmTilesArchive.open(
           new PmTilesTestArchive().zoomRange(0, 17).asByteSource())) {
      assertEquals(13, ConvertMapterhornTile.selectZoom(regional, -1, 1));
      assertEquals(12, ConvertMapterhornTile.selectZoom(merged, -1, 1));
      assertEquals(11, ConvertMapterhornTile.selectZoom(merged, -1, 3));
    }
  }

  @Test
  public void explicitZoomMustBeInsideTheInclusiveArchiveRange() throws IOException {
    try (PmTilesArchive merged = PmTilesArchive.open(
        new PmTilesTestArchive().zoomRange(0, 17).asByteSource());
         PmTilesArchive regional = PmTilesArchive.open(
           new PmTilesTestArchive().zoomRange(5, 17).asByteSource())) {
      assertEquals(0, ConvertMapterhornTile.selectZoom(merged, 0, 3));
      assertIllegalZoom(regional, 4);
      assertIllegalZoom(merged, 18);
    }
  }

  @Test
  public void cellOutsideArchiveBoundsNeedsNoPostOpenReads() throws IOException {
    PmTilesTestArchive fixture = new PmTilesTestArchive()
      .zoomRange(ZOOM, ZOOM)
      .bounds(10.0, 10.0, 20.0, 20.0)
      .useLeafDirectory(true)
      .put(ZOOM, 0, 0, new byte[]{1});
    CountingByteSource source = new CountingByteSource(fixture.asByteSource());
    try (PmTilesArchive archive = PmTilesArchive.open(source);
         ConvertMapterhornTile converter = new ConvertMapterhornTile(
           archive, ZOOM, null, TILE)) {
      int readsAfterOpen = source.readCount();
      assertFalse(converter.cellHasAnyTile(LON_START, LAT_START, ROW_LENGTH, null));
      assertEquals("archive bounds should avoid a directory read",
        readsAfterOpen, source.readCount());
    }
  }

  @Test
  public void bboxNarrowsBothTileAxesInOceanProbe() throws IOException {
    double[] bbox = {1.0, 46.0, 2.0, 47.0};
    PmTilesTestArchive westOnly = new PmTilesTestArchive().zoomRange(ZOOM, ZOOM)
      .put(ZOOM, 31, 22, new byte[]{1});
    try (PmTilesArchive archive = PmTilesArchive.open(westOnly.asByteSource());
         ConvertMapterhornTile converter = new ConvertMapterhornTile(
           archive, ZOOM, null, TILE)) {
      assertFalse("bbox must exclude the western tile column",
        converter.cellHasAnyTile(LON_START, LAT_START, ROW_LENGTH, bbox));
    }

    PmTilesTestArchive northOnly = new PmTilesTestArchive().zoomRange(ZOOM, ZOOM)
      .put(ZOOM, 32, 21, new byte[]{1});
    try (PmTilesArchive archive = PmTilesArchive.open(northOnly.asByteSource());
         ConvertMapterhornTile converter = new ConvertMapterhornTile(
           archive, ZOOM, null, TILE)) {
      assertFalse("bbox must exclude the northern tile row",
        converter.cellHasAnyTile(LON_START, LAT_START, ROW_LENGTH, bbox));
    }
  }

  @Test
  public void fullZ17CellIsRejectedByWorkPreflight() throws IOException {
    try (PmTilesArchive archive = PmTilesArchive.open(
        new PmTilesTestArchive().zoomRange(17, 17).asByteSource());
         ConvertMapterhornTile converter = new ConvertMapterhornTile(
           archive, 17, null, ConvertMapterhornTile.TILE_SIZE)) {
      ConvertMapterhornTile.SamplingWindow window =
        converter.samplingWindow(LON_START, LAT_START, 3600, null);
      try {
        converter.validateWorkWindow(window);
        fail("expected a full z17 cell to exceed the work limits");
      } catch (IOException e) {
        assertTrue(e.getMessage(), e.getMessage().contains("-zoom"));
        assertTrue(e.getMessage(), e.getMessage().contains("-bbox"));
      }
    }
  }

  @Test
  public void smallZ17BboxPassesWorkPreflight() throws IOException {
    try (PmTilesArchive archive = PmTilesArchive.open(
        new PmTilesTestArchive().zoomRange(17, 17).asByteSource());
         ConvertMapterhornTile converter = new ConvertMapterhornTile(
           archive, 17, null, ConvertMapterhornTile.TILE_SIZE)) {
      ConvertMapterhornTile.SamplingWindow window = converter.samplingWindow(
        LON_START, LAT_START, 3600, new double[]{1.0, 46.0, 1.01, 46.01});
      converter.validateWorkWindow(window);
    }
  }

  @Test
  public void tallNarrowZ17WindowIsRejectedBySourcePixelLimit() throws IOException {
    try (PmTilesArchive archive = PmTilesArchive.open(
        new PmTilesTestArchive().zoomRange(17, 17).asByteSource());
         ConvertMapterhornTile converter = new ConvertMapterhornTile(
           archive, 17, null, ConvertMapterhornTile.TILE_SIZE)) {
      ConvertMapterhornTile.SamplingWindow window = converter.samplingWindow(
        LON_START, LAT_START, 3600, new double[]{1.0, 45.0, 1.05, 50.0});
      try {
        converter.validateWorkWindow(window);
        fail("expected a tall z17 window to exceed the source pixel limit");
      } catch (IOException e) {
        assertTrue(e.getMessage(), e.getMessage().contains("source pixels"));
        assertTrue(e.getMessage(), e.getMessage().contains("-zoom"));
        assertTrue(e.getMessage(), e.getMessage().contains("-bbox"));
      }
    }
  }

  @Test
  public void workSizeOverflowBecomesIOException() throws IOException {
    try (PmTilesArchive archive = PmTilesArchive.open(
        new PmTilesTestArchive().zoomRange(30, 30).asByteSource());
         ConvertMapterhornTile converter = new ConvertMapterhornTile(
           archive, 30, null, Integer.MAX_VALUE)) {
      ConvertMapterhornTile.SamplingWindow window =
        converter.samplingWindow(LON_START, LAT_START, ROW_LENGTH, null);
      try {
        converter.validateWorkWindow(window);
        fail("expected checked size overflow");
      } catch (NegativeArraySizeException e) {
        fail("size overflow must be reported as IOException");
      } catch (IOException expected) {
        assertTrue(expected.getMessage(), expected.getMessage().contains("too large"));
      }
    }
  }

  private static int rowColIndex(double lat, double lon) {
    double cellsize = 1.0 / ROW_LENGTH;
    int imageRow = (int) Math.round((LAT_START + 5 - lat) / cellsize);
    int col = (int) Math.round((lon - LON_START) / cellsize);
    return imageRow * N + col;
  }

  // ------------------------------------------------------------- integrity

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  /**
   * The coalesced fetch path must survive gzip tile compression: run blocks are sliced
   * first and decompressed per tile. The field varies per position, so a slice-boundary
   * mix-up (one tile's bytes landing in another's slot) cannot pass unnoticed - a
   * constant field would be blind to tile permutations.
   */
  @Test
  public void gzipTileCompressionSurvivesTheCoalescedFetch() throws IOException {
    TerrainTiles.Elevation field = (gx, gy) -> 100.0 + (gx % 251) + 2.0 * (gy % 127);
    PmTilesTestArchive builder = new PmTilesTestArchive().zoomRange(ZOOM, ZOOM)
      .tileCompression(PmTilesArchive.COMPRESSION_GZIP);
    for (int tx = 30; tx <= 34; tx++) {
      for (int ty = 20; ty <= 24; ty++) {
        builder.put(ZOOM, tx, ty, TerrainTiles.tilePng(TILE, tx, ty, field));
      }
    }
    ElevationRaster gzipped = convert(builder, null);
    assertNotNull(gzipped);

    // reference: the identical field through the uncompressed path
    ElevationRaster plain = convert(archiveOf(field), null);
    assertArrayEquals(plain.eval_array, gzipped.eval_array);
  }

  /**
   * A second conversion over a warm tile cache must reproduce the first one exactly,
   * and the cache directory must contain no leftover temp files.
   */
  @Test
  public void tileCacheReproducesTheFirstRunExactly() throws IOException {
    File cacheDir = tmp.newFolder("cache");
    PmTilesTestArchive builder = archiveOf((gx, gy) -> 100.0 + (gx % 13) + (gy % 7));

    ElevationRaster first;
    ElevationRaster second;
    try (PmTilesArchive archive = PmTilesArchive.open(builder.asByteSource());
         ConvertMapterhornTile converter = new ConvertMapterhornTile(archive, ZOOM, cacheDir, TILE)) {
      first = converter.buildRaster(LON_START, LAT_START, ROW_LENGTH, null);
    }
    try (PmTilesArchive archive = PmTilesArchive.open(builder.asByteSource());
         ConvertMapterhornTile converter = new ConvertMapterhornTile(archive, ZOOM, cacheDir, TILE)) {
      second = converter.buildRaster(LON_START, LAT_START, ROW_LENGTH, null);
    }
    assertArrayEquals(first.eval_array, second.eval_array);

    try (java.util.stream.Stream<java.nio.file.Path> walk = Files.walk(cacheDir.toPath())) {
      assertTrue("no temp files may remain in the cache",
        walk.noneMatch(p -> p.getFileName().toString().contains(".tmp")));
    }
  }

  @Test
  public void testOnlyCacheConstructorBindsDirectoryToArchive() throws IOException {
    File cacheDir = tmp.newFolder("cache");
    try (PmTilesArchive archive = PmTilesArchive.open(
        new PmTilesTestArchive().zoomRange(1, 1).put(1, 0, 0, new byte[]{1}).asByteSource());
         ConvertMapterhornTile ignored = new ConvertMapterhornTile(archive, 1, cacheDir, TILE)) {
      // construction initializes the cache identity
    }
    try (PmTilesArchive other = PmTilesArchive.open(
      new PmTilesTestArchive().zoomRange(1, 2).put(1, 0, 0, new byte[]{1}).asByteSource())) {
      new ConvertMapterhornTile(other, 1, cacheDir, TILE).close();
      fail("expected rejection of a cache dir filled from a different archive");
    } catch (IOException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("different archive"));
    }
  }

  @Test
  public void testOnlyConverterClosesTheCacheItCreates() throws IOException {
    File cacheDir = tmp.newFolder("owned-cache");
    String archiveId;
    try (PmTilesArchive archive = PmTilesArchive.open(
        new PmTilesTestArchive().zoomRange(1, 1).put(1, 0, 0, new byte[]{1}).asByteSource())) {
      archiveId = archive.archiveId();
      try (ConvertMapterhornTile ignored = new ConvertMapterhornTile(
          archive, 1, cacheDir, TILE)) {
        // converter owns the cache created by this constructor
      }
    }

    try (MapterhornTileCache reopened = new MapterhornTileCache(
        cacheDir, archiveId, 1024L)) {
      assertEquals(0L, reopened.usedBytes());
    }
  }

  @Test
  public void failedTestOnlyConverterConstructionReleasesItsCache() throws IOException {
    File cacheDir = tmp.newFolder("failed-owned-cache");
    String archiveId;
    try (PmTilesArchive archive = PmTilesArchive.open(
        new PmTilesTestArchive().zoomRange(1, 1).put(1, 0, 0, new byte[]{1}).asByteSource())) {
      archiveId = archive.archiveId();
      try {
        new ConvertMapterhornTile(archive, -1, cacheDir, TILE).close();
        fail("expected invalid converter configuration");
      } catch (IOException e) {
        assertTrue(e.getMessage(), e.getMessage().contains("zoom"));
      }
    }

    try (MapterhornTileCache reopened = new MapterhornTileCache(
        cacheDir, archiveId, 1024L)) {
      assertEquals(0L, reopened.usedBytes());
    }
  }

  @Test
  public void converterDoesNotCloseAPassedSharedCache() throws IOException {
    File cacheDir = tmp.newFolder("shared-cache");
    String archiveId;
    try (PmTilesArchive archive = PmTilesArchive.open(
        new PmTilesTestArchive().zoomRange(1, 1).put(1, 0, 0, new byte[]{1}).asByteSource())) {
      archiveId = archive.archiveId();
      try (MapterhornTileCache shared = new MapterhornTileCache(
          cacheDir, archiveId, 1024L)) {
        try (ConvertMapterhornTile ignored = new ConvertMapterhornTile(
            archive, 1, shared, TILE, 1)) {
          // one cell uses the shared cache
        }
        try (MapterhornTileCache ignored = new MapterhornTileCache(
            cacheDir, archiveId, 1024L)) {
          fail("expected the passed shared cache to remain locked");
        } catch (IOException e) {
          assertTrue(e.getMessage(), e.getMessage().contains("already in use"));
        }
      }
    }

    try (MapterhornTileCache reopened = new MapterhornTileCache(
        cacheDir, archiveId, 1024L)) {
      assertEquals(0L, reopened.usedBytes());
    }
  }

  @Test
  public void absentArchiveEntriesCreateNoTileCacheFiles() throws IOException {
    File cacheDir = tmp.newFolder("absent-cache");
    PmTilesTestArchive distant = new PmTilesTestArchive().zoomRange(ZOOM, ZOOM)
      .put(ZOOM, 0, 0, TerrainTiles.constantPng(TILE, 10.0));
    try (PmTilesArchive archive = PmTilesArchive.open(distant.asByteSource());
         ConvertMapterhornTile converter = new ConvertMapterhornTile(
           archive, ZOOM, cacheDir, TILE)) {
      assertNull(converter.buildRaster(LON_START, LAT_START, ROW_LENGTH, null));
    }

    try (java.util.stream.Stream<java.nio.file.Path> files =
           Files.walk(cacheDir.toPath())) {
      assertEquals(0L, files.filter(Files::isRegularFile)
        .filter(path -> path.getFileName().toString().endsWith(".tile"))
        .count());
    }
  }

  @Test
  public void cacheBudgetDefaultsToExactlyTenGibibytes() {
    assertEquals(10L * 1024L * 1024L * 1024L,
      ConvertMapterhornTile.DEFAULT_CACHE_MAX_BYTES);
  }

  @Test
  public void cacheMaxGigabytesMustBePositiveAndFitLong() {
    assertEquals(3L * 1024L * 1024L * 1024L,
      ConvertMapterhornTile.parseCacheMaxBytes("3"));
    assertInvalidCacheBudget("0");
    assertInvalidCacheBudget("-1");
    assertInvalidCacheBudget("1.5");
    assertInvalidCacheBudget(Long.toString(Long.MAX_VALUE));
  }

  /**
   * End-to-end convertOne: the .bef and completed provenance land atomically, decode
   * to the same pixels, a second all-mode run skips them, and a -bbox run refuses to
   * clobber the complete cell.
   */
  @Test
  public void convertOneWritesVerifiedFileAndGuardsOverwrites() throws IOException {
    File outDir = tmp.newFolder("bef");
    PmTilesTestArchive builder = archiveOf((gx, gy) -> 500.0);
    try (PmTilesArchive archive = PmTilesArchive.open(builder.asByteSource())) {
      ConvertMapterhornTile.convertOne(archive, ZOOM, null, outDir,
        LON_START, LAT_START, ROW_LENGTH, null, 2, false, TILE);

      String name = ElevationRasterTileConverter.genFilenameOld(LON_START, LAT_START);
      File out = new File(outDir, name);
      File metadataFile = new File(out.getPath() + MapterhornOutputMetadata.SUFFIX);
      assertTrue("output file must exist", out.isFile());
      assertTrue("output provenance must exist", metadataFile.isFile());
      assertEquals("no temp files may remain", 2, outDir.list().length);

      Properties metadata = new Properties();
      try (java.io.InputStream in = Files.newInputStream(metadataFile.toPath())) {
        metadata.load(in);
      }
      assertEquals("complete", metadata.getProperty("state"));
      assertEquals(PmTilesArchive.sha256Hex(Files.readAllBytes(out.toPath())),
        metadata.getProperty("sha256"));

      ElevationRaster decoded;
      try (java.io.InputStream is = new java.io.BufferedInputStream(new java.io.FileInputStream(out))) {
        decoded = new ElevationRasterCoder().decodeRaster(is);
      }
      assertEquals(N, decoded.nrows);
      assertEquals(500, decoded.eval_array[rowColIndex(47.5, 2.5)]);

      // skip-existing (planet mode) leaves the file untouched
      long modified = out.lastModified();
      ConvertMapterhornTile.convertOne(archive, ZOOM, null, outDir,
        LON_START, LAT_START, ROW_LENGTH, null, 2, true, TILE);
      assertEquals(modified, out.lastModified());

      // a windowed run must not truncate the complete cell
      try {
        ConvertMapterhornTile.convertOne(archive, ZOOM, null, outDir,
          LON_START, LAT_START, ROW_LENGTH, new double[]{1.0, 46.0, 2.0, 47.0}, 2, false, TILE);
        fail("expected refusal to overwrite with a -bbox conversion");
      } catch (IOException e) {
        assertTrue(e.getMessage(), e.getMessage().contains("refusing to overwrite"));
      }
    }
  }

  @Test
  public void allModeRejectsUnknownOutputBeforeCreatingAnotherCellsSidecar()
      throws Exception {
    File archiveFile = tmp.newFile("preflight.pmtiles");
    Files.write(archiveFile.toPath(),
      new PmTilesTestArchive().zoomRange(ZOOM, ZOOM).build());
    File outDir = tmp.newFolder("preflight-bef");
    File first = new File(outDir,
      ElevationRasterTileConverter.genFilenameOld(0, 45));
    File unknown = new File(outDir,
      ElevationRasterTileConverter.genFilenameOld(5, 45));
    Files.write(unknown.toPath(), new byte[]{1, 2, 3});

    try {
      ConvertMapterhornTile.main(new String[]{archiveFile.getPath(), outDir.getPath(), "all",
        "-zoom", Integer.toString(ZOOM), "-bbox", "0,45,10,50"});
      fail("expected all-mode preflight to reject unknown output");
    } catch (IOException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("provenance"));
    }

    assertFalse(new File(first.getPath() + MapterhornOutputMetadata.SUFFIX).exists());
    assertArrayEquals(new byte[]{1, 2, 3},
      Files.readAllBytes(unknown.toPath()));
  }

  @Test
  public void explicitNoSourceConversionPreservesUnknownExistingOutput()
      throws IOException {
    File outDir = tmp.newFolder("unknown-no-source");
    File output = new File(outDir,
      ElevationRasterTileConverter.genFilenameOld(LON_START, LAT_START));
    byte[] unknown = {9, 8, 7};
    Files.write(output.toPath(), unknown);
    PmTilesTestArchive empty = new PmTilesTestArchive().zoomRange(ZOOM, ZOOM);

    try (PmTilesArchive archive = PmTilesArchive.open(empty.asByteSource())) {
      ConvertMapterhornTile.convertOne(archive, ZOOM, null, outDir,
        LON_START, LAT_START, ROW_LENGTH, null, 1, false, TILE);
    }

    assertArrayEquals(unknown, Files.readAllBytes(output.toPath()));
    assertFalse(new File(output.getPath() + MapterhornOutputMetadata.SUFFIX).exists());
  }

  @Test
  public void explicitNoSourceConversionPreservesUnknownExistingSidecar()
      throws IOException {
    File outDir = tmp.newFolder("unknown-sidecar-no-source");
    File output = new File(outDir,
      ElevationRasterTileConverter.genFilenameOld(LON_START, LAT_START));
    File sidecar = new File(output.getPath() + MapterhornOutputMetadata.SUFFIX);
    byte[] unknown = "unrelated metadata\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Files.write(sidecar.toPath(), unknown);
    PmTilesTestArchive empty = new PmTilesTestArchive().zoomRange(ZOOM, ZOOM);

    try (PmTilesArchive archive = PmTilesArchive.open(empty.asByteSource())) {
      ConvertMapterhornTile.convertOne(archive, ZOOM, null, outDir,
        LON_START, LAT_START, ROW_LENGTH, null, 1, false, TILE);
    }

    assertFalse(output.exists());
    assertArrayEquals(unknown, Files.readAllBytes(sidecar.toPath()));
  }

  @Test
  public void staleOutputTempNamesDoNotBlockVerifiedWrite() throws IOException {
    File outDir = tmp.newFolder("stale-output-temp");
    File output = new File(outDir, "cell.bef");
    for (int i = 1; i <= 256; i++) {
      Files.write(new File(output.getPath() + ".tmp" + i).toPath(),
        new byte[]{1});
    }
    ElevationRaster raster = new ElevationRaster();
    ElevationCells.configureCellRaster(raster, LON_START, LAT_START, 1);
    raster.eval_array = new short[raster.ncols * raster.nrows];
    java.util.Arrays.fill(raster.eval_array, (short) 123);

    MapterhornOutputMetadata.CompletedFile completed =
      ConvertMapterhornTile.writeVerified(raster, output);

    assertTrue(output.isFile());
    assertEquals(output.length(), completed.length);
    assertEquals(PmTilesArchive.sha256Hex(Files.readAllBytes(output.toPath())),
      completed.sha256);
  }

  @Test
  public void verifiedWriteRejectsSymbolicLinkOutput() throws IOException {
    File outDir = tmp.newFolder("verified-linked-output");
    File external = tmp.newFile("verified-external-output");
    File output = new File(outDir, "cell.bef");
    Files.createSymbolicLink(output.toPath(), external.toPath());
    ElevationRaster raster = new ElevationRaster();
    ElevationCells.configureCellRaster(raster, LON_START, LAT_START, 1);
    raster.eval_array = new short[raster.ncols * raster.nrows];

    try {
      ConvertMapterhornTile.writeVerified(raster, output);
      fail("expected verified write to reject a symbolic output");
    } catch (IOException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("symbolic link"));
    }
    assertTrue(Files.isSymbolicLink(output.toPath()));
  }

  @Test
  public void readBackVerifierChecksAllSevenHeaderFields() throws IOException {
    ElevationRaster expected = smallRaster();
    String[] fields = {
      "ncols", "nrows", "halfcol", "xllcorner", "yllcorner", "cellsize", "noDataValue"
    };
    for (String field : fields) {
      ElevationRaster actual = copyRaster(expected);
      switch (field) {
        case "ncols":
          actual.ncols++;
          break;
        case "nrows":
          actual.nrows++;
          break;
        case "halfcol":
          actual.halfcol = !actual.halfcol;
          break;
        case "xllcorner":
          actual.xllcorner += 1.0;
          break;
        case "yllcorner":
          actual.yllcorner += 1.0;
          break;
        case "cellsize":
          actual.cellsize *= 2.0;
          break;
        case "noDataValue":
          actual.noDataValue++;
          break;
        default:
          throw new AssertionError(field);
      }
      try {
        ConvertMapterhornTile.verifyReadBack(expected, actual, new File(field + ".bef"));
        fail("expected " + field + " mismatch");
      } catch (IOException mismatch) {
        assertTrue(mismatch.getMessage(), mismatch.getMessage().contains("header"));
      }
    }
  }

  @Test
  public void injectedHeaderMismatchFailsBeforeOutputPublication() throws IOException {
    File outDir = tmp.newFolder("header-mismatch-before-publish");
    File output = new File(outDir, "cell.bef");
    ElevationRaster raster = smallRaster();

    try {
      ConvertMapterhornTile.writeVerified(raster, output, input -> {
        ElevationRaster decoded = new ElevationRasterCoder().decodeRaster(input);
        decoded.cellsize *= 2.0;
        return decoded;
      }, ConvertMapterhornTile::atomicMove);
      fail("expected injected header mismatch");
    } catch (IOException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("header"));
    }

    assertFalse("header mismatch must fail before publication", output.exists());
  }

  @Test
  public void atomicBefMoveFailurePreservesTargetAndPendingRecovery() throws IOException {
    File outDir = tmp.newFolder("atomic-bef-failure");
    File output = new File(outDir, "cell.bef");
    byte[] original = {7, 8, 9};
    Files.write(output.toPath(), original);
    ElevationRaster raster = smallRaster();
    MapterhornOutputMetadata.Config config = new MapterhornOutputMetadata.Config(
      "archive-a", ZOOM, 1, TILE, "legacy", "full", LON_START, LAT_START);
    assertEquals(MapterhornOutputMetadata.Decision.WRITE,
      MapterhornOutputMetadata.prepare(output, config, true));

    try {
      ConvertMapterhornTile.writeVerified(raster, output,
        input -> new ElevationRasterCoder().decodeRaster(input), (from, to) -> {
          throw new AtomicMoveNotSupportedException(
            from.toString(), to.toString(), "injected .bef failure");
        });
      fail("expected atomic .bef publication failure");
    } catch (AtomicMoveNotSupportedException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("injected"));
    }

    assertArrayEquals(original, Files.readAllBytes(output.toPath()));
    File sidecar = new File(output.getPath() + MapterhornOutputMetadata.SUFFIX);
    assertEquals("pending", loadProperties(sidecar).getProperty("state"));

    MapterhornOutputMetadata.CompletedFile completed =
      ConvertMapterhornTile.writeVerified(raster, output);
    MapterhornOutputMetadata.complete(output, config, completed);
    assertEquals("complete", loadProperties(sidecar).getProperty("state"));
  }

  @Test
  public void atomicSidecarMoveFailurePreservesPendingAndPublishedOutput()
      throws IOException {
    File outDir = tmp.newFolder("atomic-sidecar-failure");
    File output = new File(outDir, "cell.bef");
    ElevationRaster raster = smallRaster();
    MapterhornOutputMetadata.Config config = new MapterhornOutputMetadata.Config(
      "archive-a", ZOOM, 1, TILE, "legacy", "full", LON_START, LAT_START);
    assertEquals(MapterhornOutputMetadata.Decision.WRITE,
      MapterhornOutputMetadata.prepare(output, config, false));
    MapterhornOutputMetadata.CompletedFile completed =
      ConvertMapterhornTile.writeVerified(raster, output);
    byte[] published = Files.readAllBytes(output.toPath());

    try {
      MapterhornOutputMetadata.complete(output, config, completed, (from, to) -> {
        throw new AtomicMoveNotSupportedException(
          from.toString(), to.toString(), "injected sidecar failure");
      });
      fail("expected atomic sidecar publication failure");
    } catch (AtomicMoveNotSupportedException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("injected"));
    }

    File sidecar = new File(output.getPath() + MapterhornOutputMetadata.SUFFIX);
    assertArrayEquals(published, Files.readAllBytes(output.toPath()));
    assertEquals("pending", loadProperties(sidecar).getProperty("state"));

    MapterhornOutputMetadata.complete(output, config, completed);
    assertEquals("complete", loadProperties(sidecar).getProperty("state"));
  }

  @Test(timeout = 10000L)
  public void outputDirectoryLockPreventsMixedConfigInterleavingAndReopens()
      throws Exception {
    File outDir = new File(tmp.getRoot(), "locked-output");
    File output = new File(outDir,
      ElevationRasterTileConverter.genFilenameOld(LON_START, LAT_START));
    MapterhornOutputMetadata.Config configA = new MapterhornOutputMetadata.Config(
      "archive-a", ZOOM, 1, TILE, "legacy", "full", LON_START, LAT_START);
    ElevationRaster raster = smallRaster();
    CountDownLatch pendingWritten = new CountDownLatch(1);
    CountDownLatch allowComplete = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    byte[] otherArchive = new PmTilesTestArchive().zoomRange(ZOOM, ZOOM).build();
    Future<?> first = pool.submit(() -> {
      try (ConvertMapterhornTile.OutputDirectoryLock ignored =
             ConvertMapterhornTile.OutputDirectoryLock.acquire(outDir)) {
        MapterhornOutputMetadata.prepare(output, configA, false);
        pendingWritten.countDown();
        await(allowComplete, "allow first output completion");
        MapterhornOutputMetadata.CompletedFile completed =
          ConvertMapterhornTile.writeVerified(raster, output);
        MapterhornOutputMetadata.complete(output, configA, completed);
      }
      return null;
    });
    try {
      await(pendingWritten, "first pending sidecar");
      Future<?> second = pool.submit(() -> {
        try (PmTilesArchive archive = PmTilesArchive.open(
            new PmTilesTestArchive.MemoryByteSource(otherArchive))) {
          ConvertMapterhornTile.convertOne(archive, ZOOM, null, outDir,
            LON_START, LAT_START, 1, null, 1, false, TILE);
        }
        return null;
      });
      try {
        second.get(2L, TimeUnit.SECONDS);
        fail("expected the second output-directory run to fail fast");
      } catch (ExecutionException expected) {
        assertTrue(expected.getCause().getMessage(),
          expected.getCause() instanceof IOException);
        assertTrue(expected.getCause().getMessage(),
          expected.getCause().getMessage().contains("already in use"));
      }

      File sidecar = new File(output.getPath() + MapterhornOutputMetadata.SUFFIX);
      Properties pending = loadProperties(sidecar);
      assertEquals("pending", pending.getProperty("state"));
      assertEquals("archive-a", pending.getProperty("archiveId"));
      assertFalse(output.exists());

      allowComplete.countDown();
      first.get(2L, TimeUnit.SECONDS);
      try (ConvertMapterhornTile.OutputDirectoryLock ignored =
             ConvertMapterhornTile.OutputDirectoryLock.acquire(outDir)) {
        assertEquals(MapterhornOutputMetadata.Decision.SKIP,
          MapterhornOutputMetadata.prepare(output, configA, false));
      }
      File siblingLock = new File(tmp.getRoot(), ".locked-output"
        + ConvertMapterhornTile.OUTPUT_LOCK_SUFFIX);
      assertTrue("the sibling lock file must persist", siblingLock.isFile());
    } finally {
      allowComplete.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  public void outputDirectoryAndSiblingLockSymbolicLinksAreRejected() throws IOException {
    File target = tmp.newFolder("linked-output-target");
    File linkedOutput = new File(tmp.getRoot(), "linked-output-dir");
    Files.createSymbolicLink(linkedOutput.toPath(), target.toPath());
    assertOutputLockSymlinkRejected(linkedOutput);

    File output = new File(tmp.getRoot(), "sibling-link-output");
    File external = tmp.newFile("external-output-lock");
    File sibling = new File(tmp.getRoot(), ".sibling-link-output"
      + ConvertMapterhornTile.OUTPUT_LOCK_SUFFIX);
    Files.createSymbolicLink(sibling.toPath(), external.toPath());
    assertOutputLockSymlinkRejected(output);
  }

  @Test
  public void noSourceConversionRemovesMatchingPendingOwnedOutput()
      throws IOException {
    File outDir = tmp.newFolder("owned-no-source");
    File output = new File(outDir,
      ElevationRasterTileConverter.genFilenameOld(LON_START, LAT_START));
    Files.write(output.toPath(), new byte[]{4, 5, 6});
    PmTilesTestArchive empty = new PmTilesTestArchive().zoomRange(ZOOM, ZOOM);

    try (PmTilesArchive archive = PmTilesArchive.open(empty.asByteSource())) {
      MapterhornOutputMetadata.Config config = new MapterhornOutputMetadata.Config(
        archive.archiveId(), ZOOM, ROW_LENGTH, TILE, "legacy", "full",
        LON_START, LAT_START);
      assertEquals(MapterhornOutputMetadata.Decision.WRITE,
        MapterhornOutputMetadata.prepare(output, config, true));

      ConvertMapterhornTile.convertOne(archive, ZOOM, null, outDir,
        LON_START, LAT_START, ROW_LENGTH, null, 1, true, TILE);
    }

    assertFalse(output.exists());
    assertFalse(new File(output.getPath() + MapterhornOutputMetadata.SUFFIX).exists());
  }

  @Test
  public void converterRejectsSymbolicLinkOutput() throws IOException {
    File outDir = tmp.newFolder("linked-output");
    File external = tmp.newFile("external-output");
    File output = new File(outDir,
      ElevationRasterTileConverter.genFilenameOld(LON_START, LAT_START));
    Files.createSymbolicLink(output.toPath(), external.toPath());

    try (PmTilesArchive archive = PmTilesArchive.open(
        archiveOf((gx, gy) -> 200.0).asByteSource())) {
      try {
        ConvertMapterhornTile.convertOne(archive, ZOOM, null, outDir,
          LON_START, LAT_START, ROW_LENGTH, null, 1, false, TILE);
        fail("expected symbolic output rejection");
      } catch (IOException expected) {
        assertTrue(expected.getMessage(), expected.getMessage().contains("symbolic link"));
      }
    }
    assertTrue(Files.isSymbolicLink(output.toPath()));
  }

  @Test
  public void parseCellAcceptsBothSchemesAndRejectsGarbage() {
    assertArrayEquals(new int[]{5, 45}, ConvertMapterhornTile.parseCell("srtm_38_03"));
    assertArrayEquals(new int[]{5, 45}, ConvertMapterhornTile.parseCell("srtm_E5_N45"));
    assertArrayEquals(new int[]{0, 45}, ConvertMapterhornTile.parseCell("0,45"));
    String[] bad = {"srtm_99_99", "srtm_38", "3,47", "0", "x,y"};
    for (String cell : bad) {
      try {
        ConvertMapterhornTile.parseCell(cell);
        fail("expected rejection of " + cell);
      } catch (IllegalArgumentException expected) {
        // good
      }
    }
  }

  @Test
  public void bboxMustBeFiniteOrderedAndInsideWorldBounds() {
    assertArrayEquals(new double[]{-180.0, -90.0, 180.0, 90.0},
      ConvertMapterhornTile.parseBbox("-180,-90,180,90"), 0.0);

    String[] invalid = {
      "NaN,-90,180,90",
      "-180,NaN,180,90",
      "-Infinity,-90,180,90",
      "-180,-90,Infinity,90",
      "-181,-90,180,90",
      "-180,-91,180,90",
      "-180,-90,181,90",
      "-180,-90,180,91",
      "0,0,0,1",
      "0,1,1,1"
    };
    for (String bbox : invalid) {
      assertInvalidBbox(bbox);
    }
  }

  @Test
  public void contiguousRunsAreGroupedAndDeduplicated() {
    ConvertMapterhornTile.MissedTile a = missed(0, 100, 50);   // 100..149
    ConvertMapterhornTile.MissedTile b = missed(1, 150, 30);   // adjacent
    ConvertMapterhornTile.MissedTile c = missed(2, 150, 30);   // identical (dedup run)
    ConvertMapterhornTile.MissedTile d = missed(3, 500, 10);   // gap
    java.util.List<java.util.List<ConvertMapterhornTile.MissedTile>> runs =
      ConvertMapterhornTile.groupContiguousRuns(java.util.Arrays.asList(d, b, a, c));
    assertEquals(2, runs.size());
    assertEquals(3, runs.get(0).size());
    assertEquals(1, runs.get(1).size());
  }

  /**
   * A genuine MULTI-MEMBER run through the real slice-and-decompress path: two gzip
   * tiles stored back-to-back are grouped into one range read, sliced at the interior
   * byte boundary, gunzipped per member and decoded. The full-cell conversion tests
   * cannot force this, because a 5-degree stripe's tile pairs at this zoom straddle the
   * Hilbert half-world seam and are never byte-adjacent.
   */
  @Test
  public void multiMemberGzipRunSlicesAndDecompressesEachTile() throws IOException {
    PmTilesTestArchive builder = new PmTilesTestArchive().zoomRange(ZOOM, ZOOM)
      .tileCompression(PmTilesArchive.COMPRESSION_GZIP)
      .put(ZOOM, 10, 11, TerrainTiles.constantPng(TILE, 500.0))
      .put(ZOOM, 20, 21, TerrainTiles.constantPng(TILE, 700.0));

    try (PmTilesArchive archive = PmTilesArchive.open(builder.asByteSource());
         ConvertMapterhornTile converter = new ConvertMapterhornTile(archive, ZOOM, null, TILE)) {
      PmTilesArchive.TileLocation locA = archive.locateTile(ZOOM, 10, 11);
      PmTilesArchive.TileLocation locB = archive.locateTile(ZOOM, 20, 21);
      // only two payloads in the archive, so they are byte-adjacent in id order
      PmTilesArchive.TileLocation first = locA.offset <= locB.offset ? locA : locB;
      PmTilesArchive.TileLocation second = locA.offset <= locB.offset ? locB : locA;
      assertTrue("test premise: payloads must be byte-adjacent",
        second.isContiguousWith(first));

      java.util.List<ConvertMapterhornTile.MissedTile> run = java.util.Arrays.asList(
        new ConvertMapterhornTile.MissedTile(0, first),
        new ConvertMapterhornTile.MissedTile(1, second));
      java.util.concurrent.atomic.AtomicReferenceArray<TerrariumTileDecoder.Tile> decoded =
        new java.util.concurrent.atomic.AtomicReferenceArray<>(2);
      converter.fetchRun(run, decoded);

      double firstValue = locA.offset <= locB.offset ? 500.0 : 700.0;
      assertEquals(firstValue, decoded.get(0).get(TILE / 2, TILE / 2), 1.0 / 256.0);
      assertEquals(1200.0 - firstValue, decoded.get(1).get(TILE / 2, TILE / 2), 1.0 / 256.0);
    }
  }

  /**
   * A run must not grow past the read-size cap, even for byte-adjacent tiles.
   */
  @Test
  public void contiguousRunsRespectTheSizeCap() {
    int big = 40 * 1024 * 1024; // two of these exceed the 64 MB cap
    ConvertMapterhornTile.MissedTile a = missed(0, 0, big);
    ConvertMapterhornTile.MissedTile b = missed(1, big, big);          // adjacent, over cap
    ConvertMapterhornTile.MissedTile c = missed(2, 2L * big, 100);     // adjacent to b
    java.util.List<java.util.List<ConvertMapterhornTile.MissedTile>> runs =
      ConvertMapterhornTile.groupContiguousRuns(java.util.Arrays.asList(a, b, c));
    assertEquals("cap must split the run", 2, runs.size());
    assertEquals(1, runs.get(0).size());
    assertEquals(2, runs.get(1).size());
  }

  /**
   * The ocean-skip probe must cover the resampler's REAL window, which extends half an
   * output cell beyond the 5x5-degree box: a tile touching only that margin still
   * counts. Tile (30,20) at this zoom ends exactly at lon 0 = LON_START, so it lies
   * entirely outside the nominal box and only inside the west margin.
   */
  @Test
  public void oceanProbeSeesTilesInTheHalfCellMargin() throws IOException {
    // LON_START=0 maps exactly onto the boundary between tiles 31 and 32 at this zoom,
    // so tile 31 (lon -5.625..0) intersects ONLY the half-cell west margin
    PmTilesTestArchive marginOnly = new PmTilesTestArchive().zoomRange(ZOOM, ZOOM)
      .put(ZOOM, 31, 21, TerrainTiles.constantPng(TILE, 10.0));
    try (PmTilesArchive archive = PmTilesArchive.open(marginOnly.asByteSource());
         ConvertMapterhornTile converter = new ConvertMapterhornTile(archive, ZOOM, null, TILE)) {
      assertTrue("tile ending exactly on the cell edge must be probed",
        converter.cellHasAnyTile(LON_START, LAT_START, ROW_LENGTH, null));
    }
    PmTilesTestArchive farAway = new PmTilesTestArchive().zoomRange(ZOOM, ZOOM)
      .put(ZOOM, 5, 5, TerrainTiles.constantPng(TILE, 10.0));
    try (PmTilesArchive archive = PmTilesArchive.open(farAway.asByteSource());
         ConvertMapterhornTile converter = new ConvertMapterhornTile(archive, ZOOM, null, TILE)) {
      assertFalse("a distant tile must not be probed",
        converter.cellHasAnyTile(LON_START, LAT_START, ROW_LENGTH, null));
    }
  }

  @Test
  public void oceanProbeWrapsTheHalfCellMarginAtTheAntimeridian() throws IOException {
    PmTilesTestArchive wrappedMarginOnly = new PmTilesTestArchive().zoomRange(ZOOM, ZOOM)
      .put(ZOOM, 0, 22, new byte[]{1});
    try (PmTilesArchive archive = PmTilesArchive.open(wrappedMarginOnly.asByteSource());
         ConvertMapterhornTile converter = new ConvertMapterhornTile(
           archive, ZOOM, null, TILE)) {
      assertTrue("the margin east of 180 degrees must wrap to tile x=0",
        converter.cellHasAnyTile(175, LAT_START, ROW_LENGTH, null));
    }
  }

  private static void assertIllegalZoom(PmTilesArchive archive, int requested) {
    try {
      ConvertMapterhornTile.selectZoom(archive, requested, 3);
      fail("expected rejection of zoom " + requested);
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("outside archive range"));
    }
  }

  private static void assertInvalidCacheBudget(String value) {
    try {
      ConvertMapterhornTile.parseCacheMaxBytes(value);
      fail("expected rejection of cache budget " + value);
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("-cache-max-gb"));
    }
  }

  private static void assertInvalidBbox(String value) {
    try {
      ConvertMapterhornTile.parseBbox(value);
      fail("expected rejection of bbox " + value);
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("-bbox"));
    }
  }

  private static ElevationRaster smallRaster() {
    ElevationRaster raster = new ElevationRaster();
    ElevationCells.configureCellRaster(raster, LON_START, LAT_START, 1);
    raster.eval_array = new short[raster.ncols * raster.nrows];
    java.util.Arrays.fill(raster.eval_array, (short) 123);
    return raster;
  }

  private static ElevationRaster copyRaster(ElevationRaster source) {
    ElevationRaster copy = new ElevationRaster();
    copy.ncols = source.ncols;
    copy.nrows = source.nrows;
    copy.halfcol = source.halfcol;
    copy.xllcorner = source.xllcorner;
    copy.yllcorner = source.yllcorner;
    copy.cellsize = source.cellsize;
    copy.noDataValue = source.noDataValue;
    copy.eval_array = source.eval_array.clone();
    return copy;
  }

  private static Properties loadProperties(File file) throws IOException {
    Properties properties = new Properties();
    try (java.io.InputStream in = Files.newInputStream(file.toPath())) {
      properties.load(in);
    }
    return properties;
  }

  private static void await(CountDownLatch latch, String description) throws IOException {
    try {
      if (!latch.await(5L, TimeUnit.SECONDS)) {
        throw new IOException("timed out waiting to " + description);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted waiting to " + description, e);
    }
  }

  private static void assertOutputLockSymlinkRejected(File outDir) throws IOException {
    try (ConvertMapterhornTile.OutputDirectoryLock ignored =
           ConvertMapterhornTile.OutputDirectoryLock.acquire(outDir)) {
      fail("expected output-directory symbolic link rejection");
    } catch (IOException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("symbolic link"));
    }
  }

  private static final class CountingByteSource implements PmTilesArchive.ByteSource {
    private final PmTilesArchive.ByteSource delegate;
    private int reads;

    CountingByteSource(PmTilesArchive.ByteSource delegate) {
      this.delegate = delegate;
    }

    int readCount() {
      return reads;
    }

    @Override
    public byte[] read(long offset, int length) throws IOException {
      reads++;
      return delegate.read(offset, length);
    }

    @Override
    public long size() throws IOException {
      return delegate.size();
    }

    @Override
    public String versionId() throws IOException {
      return delegate.versionId();
    }

    @Override
    public void close() throws IOException {
      delegate.close();
    }
  }

  private static ConvertMapterhornTile.MissedTile missed(int slot, long offset, int length) {
    return new ConvertMapterhornTile.MissedTile(slot,
      new PmTilesArchive.TileLocation(offset, length));
  }
}
