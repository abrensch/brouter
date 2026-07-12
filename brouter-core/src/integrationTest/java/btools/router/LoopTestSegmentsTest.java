package btools.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.Set;

import org.junit.Assume;
import org.junit.Test;

/**
 * Unit tests for {@link LoopTestSegments}. Tile-name/coverage logic runs
 * offline on every build; the live network download ({@link #fetchDownloadsMissingTile})
 * fetches the smallest tile to a temp dir and is therefore skipped in hermetic
 * mode ({@code -Dloop.segments.nodownload=true}), under which downloads are off.
 */
public class LoopTestSegmentsTest {

  @Test
  public void tileNameMapsCoordinatesToTile() {
    assertEquals("E10_N45.rd5", LoopTestSegments.tileName(11.40, 47.26)); // Innsbruck
    assertEquals("E0_N35.rd5", LoopTestSegments.tileName(2.65, 39.57));   // Mallorca
    assertEquals("E5_N50.rd5", LoopTestSegments.tileName(8.72, 50.00));   // Dreieich
    assertEquals("E0_N40.rd5", LoopTestSegments.tileName(3.50, 44.50));   // Lozère
    assertEquals("W5_N45.rd5", LoopTestSegments.tileName(-3.0, 45.0));    // west of Greenwich
  }

  @Test
  public void boundaryStartPullsInNeighbourTile() {
    // Dreieich sits on the lat-50 tile boundary: a southbound loop needs N45 too.
    Set<String> dreieich = LoopTestSegments.tilesFor(8.72, 50.00, 0.7);
    assertTrue(dreieich.contains("E5_N50.rd5"));
    assertTrue(dreieich.contains("E5_N45.rd5"));

    // Mid-tile start needs exactly one tile.
    Set<String> innsbruck = LoopTestSegments.tilesFor(11.40, 47.26, 0.7);
    assertEquals(1, innsbruck.size());
    assertTrue(innsbruck.contains("E10_N45.rd5"));
  }

  @Test
  public void nodownloadSkipsNetworkForMissingTile() throws Exception {
    // Hermetic mode never touches the network: a missing tile reports failure
    // (rather than attempting a download) and nothing is created on disk.
    File tmp = Files.createTempDirectory("seg-nodownload-test").toFile();
    tmp.deleteOnExit();
    String tile = "E0_N35.rd5";
    System.setProperty("loop.segments.nodownload", "true");
    try {
      assertFalse("missing tile must report unavailable", LoopTestSegments.fetch(tmp, tile));
      assertFalse("no tile should be created", new File(tmp, tile).exists());
    } finally {
      System.clearProperty("loop.segments.nodownload");
    }
  }

  @Test
  public void noupdateKeepsExistingTileWithoutNetwork() throws Exception {
    // download-if-missing-only: a present tile is accepted as-is and the
    // freshness check is skipped, so the call succeeds offline and the file is
    // left byte-for-byte untouched.
    File tmp = Files.createTempDirectory("seg-noupdate-test").toFile();
    tmp.deleteOnExit();
    String tile = "E0_N35.rd5";
    File existing = new File(tmp, tile);
    Files.write(existing.toPath(), new byte[] {1, 2, 3, 4});
    long lenBefore = existing.length();
    System.setProperty("loop.segments.noupdate", "true");
    try {
      assertTrue("present tile must be accepted as-is", LoopTestSegments.fetch(tmp, tile));
      assertEquals("file must be untouched", lenBefore, existing.length());
    } finally {
      System.clearProperty("loop.segments.noupdate");
    }
  }

  @Test
  public void fetchDownloadsMissingTile() throws Exception {
    // Live network download: skip in hermetic mode, where downloads are disabled
    // and fetch() reports failure by design (see nodownloadSkipsNetworkForMissingTile).
    Assume.assumeFalse("live-download test skipped in hermetic mode (-Dloop.segments.nodownload=true)",
        Boolean.getBoolean("loop.segments.nodownload"));
    File tmp = Files.createTempDirectory("seg-fetch-test").toFile();
    tmp.deleteOnExit();
    String smallest = "E0_N35.rd5"; // ~16 MB, the lightest test tile
    assertTrue("fetch should report success", LoopTestSegments.fetch(tmp, smallest));
    File got = new File(tmp, smallest);
    assertTrue("tile should exist after fetch", got.isFile());
    assertTrue("tile should be non-trivial", got.length() > 1_000_000);
    // Second call re-validates via If-Modified-Since: an up-to-date tile yields
    // a 304 and is NOT re-downloaded, so its modification time is unchanged.
    long modAfterFirst = got.lastModified();
    assertTrue(LoopTestSegments.fetch(tmp, smallest));
    assertEquals("up-to-date tile must not be refreshed", modAfterFirst, got.lastModified());
    got.delete();
  }
}
