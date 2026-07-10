package btools.server;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import btools.mapcreator.OsmFastCutter;
import btools.mapcreator.PmTilesArchive;
import btools.mapcreator.PmTilesTestArchive;
import btools.mapcreator.PosUnifier;
import btools.mapcreator.WayLinker;
import btools.router.OsmNodeNamed;
import btools.router.OsmTrack;
import btools.router.RoutingContext;
import btools.router.RoutingEngine;
import btools.router.RoutingParamCollector;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Shared scaffolding for end-to-end elevation tests: builds .bef cells from bundled
 * Mapterhorn tile fixtures, runs the real map-creation pipeline (OsmFastCutter,
 * PosUnifier, WayLinker) over a bundled OSM extract, and routes over the produced rd5.
 * Fully offline.
 */
final class Rd5TestHarness {

  static final int ZOOM = 12;

  private Rd5TestHarness() {
  }

  static File profileDir() {
    File profileDir = new File("../misc/profiles2");
    assertTrue("profiles dir not found", profileDir.isDirectory());
    return profileDir;
  }

  /**
   * Build one 1-arcsecond .bef cell from bundled real Mapterhorn tiles into
   * {@code srtmDir}, through the converter's public CLI entry point.
   *
   * @param tiles z12 {x, y} tile coordinates; resources /mapterhorn/12_x_y.webp
   */
  static void buildBef(Class<?> owner, int[][] tiles, int lonStart, int latStart,
                       double[] bbox, File workDir, File srtmDir) throws Exception {
    PmTilesTestArchive builder = new PmTilesTestArchive()
      .zoomRange(ZOOM, ZOOM)
      .tileType(PmTilesArchive.TILETYPE_WEBP);
    for (int[] t : tiles) {
      builder.put(ZOOM, t[0], t[1], resource(owner, "/mapterhorn/12_" + t[0] + "_" + t[1] + ".webp"));
    }
    File pmtiles = new File(workDir, "fixture.pmtiles");
    java.nio.file.Files.write(pmtiles.toPath(), builder.build());
    btools.mapcreator.ConvertMapterhornTile.main(new String[]{
      pmtiles.getAbsolutePath(), srtmDir.getAbsolutePath(), lonStart + "," + latStart,
      "-arcsec", "1",
      "-bbox", bbox[0] + "," + bbox[1] + "," + bbox[2] + "," + bbox[3]});
  }

  /**
   * Run the map-creation pipeline over a bundled OSM extract and return the segments dir.
   */
  static File buildRd5(Class<?> owner, String pbfResource, File workDir, File srtmDir)
    throws Exception {
    File mapFile = new File(workDir, "map.pbf");
    try (InputStream is = owner.getResourceAsStream(pbfResource);
         OutputStream os = new FileOutputStream(mapFile)) {
      assertNotNull("missing test resource " + pbfResource, is);
      is.transferTo(os);
    }
    return buildRd5FromFile(mapFile, workDir, srtmDir);
  }

  /**
   * Run the map-creation pipeline over an OSM extract file and return the segments dir.
   */
  static File buildRd5FromFile(File mapFile, File workDir, File srtmDir) throws Exception {
    System.setProperty("avoidMapPolling", "true");
    File profileDir = profileDir();
    File nodes = mkdir(workDir, "nodetiles");
    File ways = mkdir(workDir, "waytiles");
    File nodes55 = mkdir(workDir, "nodes55");
    File ways55 = mkdir(workDir, "waytiles55");
    File lookupFile = new File(profileDir, "lookups.dat");
    File borderFile = new File(workDir, "bordernids.dat");
    OsmFastCutter.doCut(lookupFile, nodes, ways, nodes55, ways55, borderFile,
      new File(workDir, "cycleways.dat"), new File(workDir, "restrictions.dat"),
      new File(profileDir, "all.brf"), new File(profileDir, "trekking.brf"),
      new File(profileDir, "softaccess.brf"), mapFile, null);

    File unodes55 = mkdir(workDir, "unodes55");
    File bordernodes = new File(workDir, "bordernodes.dat");
    new PosUnifier().process(nodes55, unodes55, borderFile, bordernodes,
      srtmDir.getAbsolutePath(), null);

    File segments = mkdir(workDir, "segments");
    new WayLinker().process(unodes55, ways55, bordernodes,
      new File(workDir, "restrictions.dat"), lookupFile,
      new File(profileDir, "all.brf"), segments, "rd5");
    return segments;
  }

  /**
   * Route over the segments and return the found track.
   */
  static OsmTrack route(File segments, String profile, String lonlats) {
    RoutingContext rc = new RoutingContext();
    rc.localFunction = new File(profileDir(), profile + ".brf").getAbsolutePath();
    List<OsmNodeNamed> waypoints = new RoutingParamCollector().getWayPointList(lonlats);
    RoutingEngine engine = new RoutingEngine(null, null, segments, waypoints, rc, 0);
    engine.doRun(0);
    assertNull("routing failed: " + engine.getErrorMessage(), engine.getErrorMessage());
    OsmTrack track = engine.getFoundTrack();
    assertNotNull("no track found", track);
    return track;
  }

  static byte[] resource(Class<?> owner, String path) throws IOException {
    try (InputStream is = owner.getResourceAsStream(path)) {
      assertNotNull("missing test resource " + path, is);
      return is.readAllBytes();
    }
  }

  static File mkdir(File parent, String name) {
    File dir = new File(parent, name);
    assertTrue("cannot create " + dir, dir.mkdirs() || dir.isDirectory());
    return dir;
  }
}
