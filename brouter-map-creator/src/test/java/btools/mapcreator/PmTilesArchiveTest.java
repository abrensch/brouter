package btools.mapcreator;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public class PmTilesArchiveTest {

  /**
   * Tile ids for zoom 0 and 1, straight from the PMTiles v3 specification.
   */
  @Test
  public void tileIdsMatchSpecification() {
    assertEquals(0L, PmTilesArchive.zxyToTileId(0, 0, 0));
    assertEquals(1L, PmTilesArchive.zxyToTileId(1, 0, 0));
    assertEquals(2L, PmTilesArchive.zxyToTileId(1, 0, 1));
    assertEquals(3L, PmTilesArchive.zxyToTileId(1, 1, 1));
    assertEquals(4L, PmTilesArchive.zxyToTileId(1, 1, 0));
    assertEquals(5L, PmTilesArchive.zxyToTileId(2, 0, 0));
  }

  @Test
  public void hilbertBaseCountsTilesBelowZoom() {
    assertEquals(0L, PmTilesArchive.hilbertBase(0));
    assertEquals(1L, PmTilesArchive.hilbertBase(1));
    assertEquals(5L, PmTilesArchive.hilbertBase(2));
    assertEquals(21L, PmTilesArchive.hilbertBase(3));
    // the zoom used for 1" output against Mapterhorn
    assertEquals(((1L << 24) - 1L) / 3L, PmTilesArchive.hilbertBase(12));
  }

  /**
   * Within a zoom level the Hilbert mapping must be a bijection, otherwise tiles would
   * silently collide in the directory.
   */
  @Test
  public void tileIdsAreDenseAndUniquePerZoom() {
    for (int z = 0; z <= 6; z++) {
      int n = 1 << z;
      Set<Long> seen = new HashSet<>();
      long base = PmTilesArchive.hilbertBase(z);
      for (int x = 0; x < n; x++) {
        for (int y = 0; y < n; y++) {
          long id = PmTilesArchive.zxyToTileId(z, x, y);
          assertTrue("id below base at z=" + z, id >= base);
          assertTrue("id above level at z=" + z, id < base + (long) n * n);
          assertTrue("duplicate tile id at z=" + z, seen.add(id));
        }
      }
      assertEquals((long) n * n, seen.size());
    }
  }

  @Test
  public void varintRoundTrip() throws IOException {
    long[] values = {0L, 1L, 127L, 128L, 300L, 16383L, 16384L, 1L << 31, (1L << 62) - 1L};
    for (long v : values) {
      ByteArrayOutputStream o = new ByteArrayOutputStream();
      PmTilesTestArchive.writeVarint(o, v);
      int[] pos = {0};
      assertEquals(v, PmTilesArchive.readVarint(o.toByteArray(), pos));
      assertEquals(o.size(), pos[0]);
    }
  }

  @Test(expected = IOException.class)
  public void truncatedVarintFails() throws IOException {
    PmTilesArchive.readVarint(new byte[]{(byte) 0x80}, new int[]{0});
  }

  @Test
  public void readsTilesBackFromUncompressedArchive() throws IOException {
    byte[] a = "tile-a".getBytes(StandardCharsets.UTF_8);
    byte[] b = "tile-b".getBytes(StandardCharsets.UTF_8);
    PmTilesTestArchive builder = new PmTilesTestArchive()
      .zoomRange(3, 3)
      .put(3, 1, 2, a)
      .put(3, 5, 6, b);

    try (PmTilesArchive archive = PmTilesArchive.open(builder.asByteSource())) {
      assertEquals(3, archive.minZoom());
      assertEquals(3, archive.maxZoom());
      assertEquals(PmTilesArchive.TILETYPE_PNG, archive.tileType());
      assertArrayEquals(a, archive.getTile(3, 1, 2));
      assertArrayEquals(b, archive.getTile(3, 5, 6));
      assertNull("absent tile must read as null", archive.getTile(3, 7, 7));
    }
  }

  /**
   * Mapterhorn's planet archive gzips its directories and leaves tiles uncompressed.
   */
  @Test
  public void readsTilesFromGzippedDirectories() throws IOException {
    byte[] payload = "gzipped-dirs".getBytes(StandardCharsets.UTF_8);
    PmTilesTestArchive builder = new PmTilesTestArchive()
      .zoomRange(0, 4)
      .internalCompression(PmTilesArchive.COMPRESSION_GZIP)
      .tileType(PmTilesArchive.TILETYPE_WEBP)
      .put(4, 9, 3, payload);

    try (PmTilesArchive archive = PmTilesArchive.open(builder.asByteSource())) {
      assertEquals(PmTilesArchive.TILETYPE_WEBP, archive.tileType());
      assertArrayEquals(payload, archive.getTile(4, 9, 3));
    }
  }

  @Test
  public void gunzipsTilePayloadWhenTileCompressionIsGzip() throws IOException {
    byte[] payload = "compressed-tile".getBytes(StandardCharsets.UTF_8);
    PmTilesTestArchive builder = new PmTilesTestArchive()
      .zoomRange(2, 2)
      .tileCompression(PmTilesArchive.COMPRESSION_GZIP)
      .put(2, 1, 1, payload);

    try (PmTilesArchive archive = PmTilesArchive.open(builder.asByteSource())) {
      assertArrayEquals(payload, archive.getTile(2, 1, 1));
    }
  }

  @Test
  public void exposesArchiveBounds() throws IOException {
    try (PmTilesArchive archive = PmTilesArchive.open(
      new PmTilesTestArchive().zoomRange(0, 12).put(0, 0, 0, new byte[]{1}).asByteSource())) {
      assertEquals(-180.0, archive.minLon(), 1e-6);
      assertEquals(180.0, archive.maxLon(), 1e-6);
      assertEquals(-85.0511287, archive.minLat(), 1e-6);
      assertEquals(85.0511287, archive.maxLat(), 1e-6);
    }
  }

  /**
   * The production planet archive stores its entries in gzipped LEAF directories; the
   * root only holds pointers. This exercises the leaf recursion and the leaf cache.
   */
  @Test
  public void readsTilesThroughLeafDirectories() throws IOException {
    byte[] a = "leaf-a".getBytes(StandardCharsets.UTF_8);
    byte[] b = "leaf-b".getBytes(StandardCharsets.UTF_8);
    PmTilesTestArchive builder = new PmTilesTestArchive()
      .zoomRange(4, 4)
      .useLeafDirectory(true)
      .internalCompression(PmTilesArchive.COMPRESSION_GZIP)
      .put(4, 2, 3, a)
      .put(4, 9, 6, b);

    try (PmTilesArchive archive = PmTilesArchive.open(builder.asByteSource())) {
      assertArrayEquals(a, archive.getTile(4, 2, 3));
      assertArrayEquals(b, archive.getTile(4, 9, 6));
      assertNull(archive.getTile(4, 0, 0));
      // second lookup of the same leaf comes from the leaf cache
      assertArrayEquals(a, archive.getTile(4, 2, 3));
    }
  }

  /**
   * The offset==0 encoding ('this entry follows the previous one') is what a clustered
   * archive uses for byte-contiguous tiles.
   */
  @Test
  public void decodesContiguousOffsetEncoding() throws IOException {
    byte[] a = "contig-a".getBytes(StandardCharsets.UTF_8);
    byte[] b = "contig-b".getBytes(StandardCharsets.UTF_8);
    byte[] c = "contig-c".getBytes(StandardCharsets.UTF_8);
    PmTilesTestArchive builder = new PmTilesTestArchive()
      .zoomRange(3, 3)
      .contiguousOffsets(true)
      .put(3, 1, 1, a)
      .put(3, 2, 1, b)
      .put(3, 3, 3, c);

    try (PmTilesArchive archive = PmTilesArchive.open(builder.asByteSource())) {
      assertArrayEquals(a, archive.getTile(3, 1, 1));
      assertArrayEquals(b, archive.getTile(3, 2, 1));
      assertArrayEquals(c, archive.getTile(3, 3, 3));
    }
  }

  /**
   * PMTiles de-duplicates identical tiles: one entry with runLength=n serves n
   * consecutive tile ids. Flat-terrain regions of real archives rely on this.
   */
  @Test
  public void resolvesTilesInsideDeduplicationRuns() throws IOException {
    int z = 3;
    long base = PmTilesArchive.hilbertBase(z);
    byte[] shared = "run-payload".getBytes(StandardCharsets.UTF_8);
    PmTilesTestArchive builder = new PmTilesTestArchive()
      .zoomRange(z, z)
      .putRun(base + 10, 3, shared);

    // find the zxy coordinates of the three consecutive ids covered by the run
    try (PmTilesArchive archive = PmTilesArchive.open(builder.asByteSource())) {
      int n = 1 << z;
      int hits = 0;
      for (int x = 0; x < n; x++) {
        for (int y = 0; y < n; y++) {
          long id = PmTilesArchive.zxyToTileId(z, x, y);
          byte[] got = archive.getTile(z, x, y);
          if (id >= base + 10 && id < base + 13) {
            assertArrayEquals("id " + id, shared, got);
            hits++;
          } else {
            assertNull("id " + id, got);
          }
        }
      }
      assertEquals(3, hits);
    }
  }

  /**
   * A corrupt length that would wrap to a small positive int must fail loudly, never
   * silently parse a truncated directory.
   */
  @Test
  public void implausibleDirectoryLengthFailsLoudly() throws IOException {
    byte[] archive = new PmTilesTestArchive().zoomRange(1, 1)
      .put(1, 0, 0, new byte[]{42}).build();
    // patch rootDirLength (header offset 16, little-endian) to 2^32 + 100
    PmTilesTestArchive.putLong(archive, 16, (1L << 32) + 100L);
    try {
      PmTilesArchive.open(new PmTilesTestArchive.MemoryByteSource(archive));
      fail("expected an IOException for an implausible directory length");
    } catch (IOException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("implausible"));
    }
  }

  /**
   * A cyclic leaf-directory structure must throw, not report every tile as absent:
   * silent NODATA from a corrupt archive is indistinguishable from ocean.
   */
  @Test
  public void cyclicLeafDirectoryFailsLoudly() throws IOException {
    // hand-build: root -> leaf pointer (runLength 0) -> leaf containing a pointer to itself
    java.util.List<long[]> self = new java.util.ArrayList<>();
    self.add(new long[]{0L, 0L, 0L, 0L}); // filled below once length is known
    byte[] leaf = PmTilesTestArchive.serializeDirectory(self, false);
    self.set(0, new long[]{0L, 0L, 0L, leaf.length});
    leaf = PmTilesTestArchive.serializeDirectory(self, false);

    java.util.List<long[]> root = new java.util.ArrayList<>();
    root.add(new long[]{0L, 0L, 0L, leaf.length});
    byte[] rootDir = PmTilesTestArchive.serializeDirectory(root, false);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] header = new PmTilesTestArchive().zoomRange(0, 0).put(0, 0, 0, new byte[]{1}).build();
    // reuse a valid header, then override the directory layout fields
    byte[] archive = new byte[PmTilesArchive.HEADER_LEN + rootDir.length + leaf.length];
    System.arraycopy(header, 0, archive, 0, PmTilesArchive.HEADER_LEN);
    PmTilesTestArchive.putLong(archive, 8, PmTilesArchive.HEADER_LEN);                    // rootDirOffset
    PmTilesTestArchive.putLong(archive, 16, rootDir.length);                              // rootDirLength
    PmTilesTestArchive.putLong(archive, 40, PmTilesArchive.HEADER_LEN + rootDir.length);  // leafDirsOffset
    PmTilesTestArchive.putLong(archive, 48, leaf.length);                                 // leafDirsLength
    PmTilesTestArchive.putLong(archive, 56, archive.length);                              // tileDataOffset
    PmTilesTestArchive.putLong(archive, 64, 0L);                                          // tileDataLength
    archive[97] = PmTilesArchive.COMPRESSION_NONE;
    System.arraycopy(rootDir, 0, archive, PmTilesArchive.HEADER_LEN, rootDir.length);
    System.arraycopy(leaf, 0, archive, PmTilesArchive.HEADER_LEN + rootDir.length, leaf.length);
    out.write(archive);

    try (PmTilesArchive pm = PmTilesArchive.open(new PmTilesTestArchive.MemoryByteSource(out.toByteArray()))) {
      pm.getTile(0, 0, 0);
      fail("expected an IOException for a cyclic directory");
    } catch (IOException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("nesting exceeds"));
    }
  }

  @Test
  public void archiveIdChangesWithTheArchive() throws IOException {
    try (PmTilesArchive a = PmTilesArchive.open(new PmTilesTestArchive().zoomRange(1, 1)
      .put(1, 0, 0, new byte[]{1}).asByteSource());
         PmTilesArchive b = PmTilesArchive.open(new PmTilesTestArchive().zoomRange(1, 2)
           .put(1, 0, 0, new byte[]{1}).asByteSource())) {
      assertEquals(64, a.archiveId().length());
      assertNotEquals("different archives must have different ids",
        a.archiveId(), b.archiveId());
    }
  }

}
