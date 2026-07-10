package btools.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import btools.mapcreator.PmTilesTestArchive;
import btools.mapcreator.TerrainTiles;
import btools.router.OsmPathElement;
import btools.router.OsmTrack;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * End-to-end proof that Mapterhorn elevation survives the WHOLE production pipeline:
 * a PMTiles archive is converted to .bef, the map-creator chain (OsmFastCutter,
 * PosUnifier, WayLinker) builds an rd5 segment from the Dreieich fixture map, and the
 * routing engine routes over that segment. Every track point must carry exactly the
 * elevation that was encoded into the terrain tiles.
 * <p>
 * The terrain is synthetic (a constant 250 m plateau, encoded as Terrarium PNG tiles in
 * an in-memory-built archive written to disk), so the expected elevation of every node
 * is known exactly: 250 m = selev 1000 quarter-metres. No network access.
 */
public class MapterhornSegmentTest {

  private static final double PLATEAU_M = 250.0;
  private static final int ZOOM = 6;
  private static final int TILE_SIZE = 512; // production tile size, so main() accepts it

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  @After
  public void clearMapPollingProperty() {
    System.clearProperty("avoidMapPolling");
  }

  @Test
  public void routedTrackCarriesTheMapterhornElevation() throws Exception {
    System.setProperty("avoidMapPolling", "true");

    File mapFile = new File("../brouter-map-creator/src/test/resources/dreieich.pbf");
    assertTrue("dreieich.pbf fixture not found at " + mapFile.getAbsolutePath(), mapFile.isFile());
    File profileDir = new File("../misc/profiles2");
    assertTrue("profiles dir not found", profileDir.isDirectory());

    // 1. a Mapterhorn-like archive: constant 250 m plateau around Dreieich (8.7E, 50.0N)
    File pmtiles = tmp.newFile("plateau.pmtiles");
    Files.write(pmtiles.toPath(), buildPlateauArchive());

    // 2. convert both .bef cells the Dreieich map straddles (the lat-50 cell boundary)
    File srtmDir = tmp.newFolder("srtm");
    btools.mapcreator.ConvertMapterhornTile.main(new String[]{
      pmtiles.getAbsolutePath(), srtmDir.getAbsolutePath(), "srtm_38_02",
      "-arcsec", "3", "-bbox", "8.4,49.6,9.2,50.4"});
    btools.mapcreator.ConvertMapterhornTile.main(new String[]{
      pmtiles.getAbsolutePath(), srtmDir.getAbsolutePath(), "srtm_38_03",
      "-arcsec", "3", "-bbox", "8.4,49.6,9.2,50.4"});
    assertTrue(new File(srtmDir, "srtm_38_02.bef").isFile());
    assertTrue(new File(srtmDir, "srtm_38_03.bef").isFile());

    // 3. the real map-creation pipeline via the shared harness
    File tmpDir = tmp.newFolder("work");
    Files.copy(mapFile.toPath(), new File(tmpDir, "map.pbf").toPath());
    File segments = Rd5TestHarness.buildRd5FromFile(new File(tmpDir, "map.pbf"), tmpDir, srtmDir);

    File rd5 = new File(segments, "E5_N50.rd5");
    assertTrue("rd5 segment not built", rd5.isFile());
    assertTrue("rd5 implausibly small: " + rd5.length(), rd5.length() > 1000);

    // 4. route over the segment file and check the elevation of every track point
    OsmTrack track = Rd5TestHarness.route(segments, "trekking",
      "8.6832,50.0074|8.7250,49.9916");
    assertTrue("track too short: " + track.nodes.size() + " points", track.nodes.size() >= 10);

    // The rd5 codec reconstructs transfer-node (shape point) elevations by integer
    // interpolation between network nodes, which can truncate one quarter-metre; that
    // applies to every elevation source. So: each point exact to within 1 quarter-metre
    // (0.25 m) - AND most points exactly right, so a systematic one-quarter-metre bias
    // across the board cannot hide inside the tolerance.
    short expected = (short) (PLATEAU_M * 4);
    int exact = 0;
    for (OsmPathElement point : track.nodes) {
      assertTrue("elevation at " + (point.getILat() / 1e6 - 90) + ","
          + (point.getILon() / 1e6 - 180) + ": expected ~" + expected
          + " quarter-metres, got " + point.getSElev(),
        Math.abs(point.getSElev() - expected) <= 1);
      if (point.getSElev() == expected) {
        exact++;
      }
    }
    assertTrue("truncation must be the exception, not a systematic offset: "
        + exact + "/" + track.nodes.size() + " exact",
      exact * 2 > track.nodes.size());
    assertFalse(track.nodes.isEmpty());
  }

  /**
   * Tiles (32..34, 20..22) at zoom 6 cover lon 0..16.9, lat 40.9..55.8 -- both Dreieich
   * cells with margin.
   */
  private static byte[] buildPlateauArchive() throws IOException {
    PmTilesTestArchive builder = new PmTilesTestArchive().zoomRange(ZOOM, ZOOM);
    for (int tx = 32; tx <= 34; tx++) {
      for (int ty = 20; ty <= 22; ty++) {
        builder.put(ZOOM, tx, ty, TerrainTiles.constantPng(TILE_SIZE, PLATEAU_M));
      }
    }
    return builder.build();
  }

}
