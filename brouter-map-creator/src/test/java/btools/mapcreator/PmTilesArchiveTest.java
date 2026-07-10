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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
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

  @Test
  public void tileIdsRejectNegativeAndUpperBoundCoordinates() {
    assertInvalidTileCoordinates(0, -1, 0);
    assertInvalidTileCoordinates(0, 0, -1);
    assertInvalidTileCoordinates(0, 1, 0);
    assertInvalidTileCoordinates(1, 2, 0);
    assertInvalidTileCoordinates(1, 0, 2);
    assertInvalidTileCoordinates(30, 1 << 30, 0);
  }

  @Test
  public void hilbertHelpersRejectUnsupportedZoomsAndCoordinates() {
    assertIllegalArgument(() -> PmTilesArchive.hilbertBase(-1));
    assertIllegalArgument(() -> PmTilesArchive.hilbertBase(32));
    assertIllegalArgument(() -> PmTilesArchive.xyToHilbertDistance(-1, 0, 0));
    assertIllegalArgument(() -> PmTilesArchive.xyToHilbertDistance(32, 0, 0));
    assertIllegalArgument(() -> PmTilesArchive.xyToHilbertDistance(2, -1, 0));
    assertIllegalArgument(() -> PmTilesArchive.xyToHilbertDistance(2, 0, 4));
    assertIllegalArgument(() -> PmTilesArchive.zxyToTileId(-1, 0, 0));
    assertIllegalArgument(() -> PmTilesArchive.zxyToTileId(32, 0, 0));
  }

  @Test
  public void zoomThirtyOneTileIdsStayInSignedLongRange() {
    long levelTiles = 1L << 62;
    long base = (levelTiles - 1L) / 3L;

    assertEquals(base, PmTilesArchive.hilbertBase(31));
    assertEquals(levelTiles - 1L,
      PmTilesArchive.xyToHilbertDistance(31, Integer.MAX_VALUE, 0));
    assertEquals(base + levelTiles - 1L,
      PmTilesArchive.zxyToTileId(31, Integer.MAX_VALUE, 0));
  }

  @Test
  public void locateTileValidatesCoordinatesButKeepsAbsentZoomsNormal() throws IOException {
    try (PmTilesArchive archive = PmTilesArchive.open(new PmTilesTestArchive()
      .zoomRange(1, 1).put(1, 0, 0, new byte[]{1}).asByteSource())) {
      assertNull(archive.locateTile(0, 0, 0));
      assertNull(archive.locateTile(2, 0, 0));
      assertIllegalArgument(() -> archive.locateTile(1, -1, 0));
      assertIllegalArgument(() -> archive.locateTile(1, 2, 0));
      assertIllegalArgument(() -> archive.locateTile(32, 0, 0));
    }
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
  public void unsignedVarintMustFitInSixtyFourBits() throws IOException {
    byte[] bytes = new byte[10];
    Arrays.fill(bytes, (byte) 0x80);
    bytes[9] = 0x02;
    try {
      PmTilesArchive.readVarint(bytes, new int[]{0});
      fail("expected an IOException for an unsigned varint wider than 64 bits");
    } catch (IOException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("varint"));
    }
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

  @Test
  public void rootDirectoryMustFitInitialSixteenKiB() throws IOException {
    byte[] bytes = new PmTilesTestArchive().zoomRange(0, 0).build();
    PmTilesTestArchive.putLong(bytes, 16,
      16384L - PmTilesArchive.HEADER_LEN + 1L);
    assertOpenFails(bytes, "root directory");
  }

  @Test
  public void sectionEndOverflowFailsBeforeAnyRead() throws IOException {
    byte[] bytes = new PmTilesTestArchive().zoomRange(0, 0).build();
    PmTilesTestArchive.putLong(bytes, 56, Long.MAX_VALUE);
    PmTilesTestArchive.putLong(bytes, 64, 1L);
    assertOpenFails(bytes, "overflow");
  }

  @Test
  public void sectionPastSourceLengthFailsBeforeAnyRead() throws IOException {
    byte[] bytes = new PmTilesTestArchive().zoomRange(0, 0).build();
    PmTilesTestArchive.putLong(bytes, 64, 1L);
    assertOpenFails(bytes, "source length");
  }

  @Test
  public void negativeSectionLengthFails() throws IOException {
    byte[] bytes = new PmTilesTestArchive().zoomRange(0, 0).build();
    PmTilesTestArchive.putLong(bytes, 32, -1L);
    assertOpenFails(bytes, "metadata");
  }

  @Test
  public void sectionsMustBeInSpecificationOrder() throws IOException {
    byte[] bytes = new PmTilesTestArchive().zoomRange(0, 0).build();
    PmTilesTestArchive.putLong(bytes, 24, PmTilesArchive.HEADER_LEN);
    assertOpenFails(bytes, "section order");
  }

  @Test
  public void zoomRangeMustBeSupportedAndOrdered() throws IOException {
    byte[] unsupported = new PmTilesTestArchive().zoomRange(0, 0).build();
    unsupported[101] = 32;
    assertOpenFails(unsupported, "zoom");

    byte[] reversed = new PmTilesTestArchive().zoomRange(0, 0).build();
    reversed[100] = 10;
    reversed[101] = 9;
    assertOpenFails(reversed, "zoom");
  }

  @Test
  public void geographicBoundsMustBeValidAndOrdered() throws IOException {
    byte[] outsideWorld = new PmTilesTestArchive().zoomRange(0, 0).build();
    PmTilesTestArchive.putInt(outsideWorld, 102, -1800000001);
    assertOpenFails(outsideWorld, "geographic bounds");

    byte[] reversed = new PmTilesTestArchive().zoomRange(0, 0).build();
    PmTilesTestArchive.putInt(reversed, 102, 10000000);
    PmTilesTestArchive.putInt(reversed, 110, 0);
    assertOpenFails(reversed, "geographic bounds");
  }

  @Test
  public void directoryEntryCountHasFixedAllocationLimit() throws IOException {
    ByteArrayOutputStream data = new ByteArrayOutputStream();
    PmTilesTestArchive.writeVarint(data, 250001L);
    assertDirectoryFails(data.toByteArray(), "entry count");
  }

  @Test
  public void directoryEntryCountMustFitFourVarintArrays() throws IOException {
    assertDirectoryFails(new byte[]{2, 0, 0, 0, 0, 0, 0, 0}, "entry count");
  }

  @Test
  public void directoryTileIdsMustIncrease() throws IOException {
    assertDirectoryFails(directory(
      new long[]{0L, 1L, 0L, 1L},
      new long[]{0L, 1L, 1L, 1L}), "tile ID");
  }

  @Test
  public void directoryTileIdAdditionMustNotOverflow() throws IOException {
    ByteArrayOutputStream data = new ByteArrayOutputStream();
    PmTilesTestArchive.writeVarint(data, 2L);
    PmTilesTestArchive.writeVarint(data, Long.MAX_VALUE);
    PmTilesTestArchive.writeVarint(data, 1L);
    for (int i = 0; i < 6; i++) {
      PmTilesTestArchive.writeVarint(data, 1L);
    }
    assertDirectoryFails(data.toByteArray(), "tile ID");
  }

  @Test
  public void directoryEntryLengthMustBePositive() throws IOException {
    assertDirectoryFails(directory(new long[]{0L, 1L, 0L, 0L}), "length");
  }

  @Test
  public void contiguousDirectoryOffsetAdditionMustNotOverflow() throws IOException {
    ByteArrayOutputStream data = new ByteArrayOutputStream();
    PmTilesTestArchive.writeVarint(data, 2L);
    PmTilesTestArchive.writeVarint(data, 0L);
    PmTilesTestArchive.writeVarint(data, 1L);
    PmTilesTestArchive.writeVarint(data, 1L);
    PmTilesTestArchive.writeVarint(data, 1L);
    PmTilesTestArchive.writeVarint(data, 2L);
    PmTilesTestArchive.writeVarint(data, 1L);
    PmTilesTestArchive.writeVarint(data, Long.MAX_VALUE);
    PmTilesTestArchive.writeVarint(data, 0L);
    assertDirectoryFails(data.toByteArray(), "offset");
  }

  @Test
  public void compressedLeafDirectoryHasStoredSizeLimit() throws IOException {
    byte[] root = directory(new long[]{0L, 0L, 0L, 16L * 1024L * 1024L + 1L});
    byte[] prefix = archiveWithSections(root, new byte[0], new byte[0]);
    long leafOffset = PmTilesArchive.HEADER_LEN + root.length;
    long declaredSize = leafOffset + 16L * 1024L * 1024L + 1L;
    PmTilesTestArchive.putLong(prefix, 48, 16L * 1024L * 1024L + 1L);
    PmTilesTestArchive.putLong(prefix, 56, declaredSize);
    try (PmTilesArchive archive = PmTilesArchive.open(
      new PrefixByteSource(prefix, declaredSize))) {
      assertLocateFails(archive, 0, 0, 0, "compressed leaf directory");
    }
  }

  @Test
  public void directTileBodyHasStoredSizeLimit() throws IOException {
    byte[] root = directory(new long[]{0L, 1L, 0L, 64L * 1024L * 1024L + 1L});
    try (PmTilesArchive archive = PmTilesArchive.open(new PmTilesTestArchive.MemoryByteSource(
      archiveWithSections(root, new byte[0], new byte[0])))) {
      assertLocateFails(archive, 0, 0, 0, "tile body");
    }
  }

  @Test
  public void directTileRunAdditionMustNotOverflow() throws IOException {
    byte[] root = directory(new long[]{1L, Long.MAX_VALUE, 0L, 1L});
    try (PmTilesArchive archive = PmTilesArchive.open(new PmTilesTestArchive.MemoryByteSource(
      archiveWithSections(root, new byte[0], new byte[]{1})))) {
      assertLocateFails(archive, 1, 0, 0, "run");
    }
  }

  @Test
  public void directTileMustStayInsideTileData() throws IOException {
    byte[] root = directory(new long[]{0L, 1L, 1L, 1L});
    try (PmTilesArchive archive = PmTilesArchive.open(new PmTilesTestArchive.MemoryByteSource(
      archiveWithSections(root, new byte[0], new byte[]{1})))) {
      assertLocateFails(archive, 0, 0, 0, "tile data");
    }
  }

  @Test
  public void leafPointerMustStayInsideLeafData() throws IOException {
    byte[] root = directory(new long[]{0L, 0L, 0L, 1L});
    try (PmTilesArchive archive = PmTilesArchive.open(new PmTilesTestArchive.MemoryByteSource(
      archiveWithSections(root, new byte[0], new byte[0])))) {
      assertLocateFails(archive, 0, 0, 0, "leaf data");
    }
  }

  @Test
  public void gzipOutputCannotExceedExplicitLimit() throws IOException {
    byte[] compressed = PmTilesTestArchive.gzip(new byte[9]);
    try {
      PmTilesArchive.gunzip(compressed, 8, "test gzip payload");
      fail("expected bounded gzip output rejection");
    } catch (IOException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("test gzip payload"));
      assertTrue(e.getMessage(), e.getMessage().contains("exceeds"));
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

  @Test
  public void archiveIdIncludesTileContentVersion() throws IOException {
    PmTilesTestArchive one = new PmTilesTestArchive().zoomRange(1, 1)
      .put(1, 0, 0, new byte[]{1});
    PmTilesTestArchive two = new PmTilesTestArchive().zoomRange(1, 1)
      .put(1, 0, 0, new byte[]{2});
    try (PmTilesArchive a = PmTilesArchive.open(one.asByteSource());
         PmTilesArchive b = PmTilesArchive.open(two.asByteSource())) {
      assertNotEquals(a.archiveId(), b.archiveId());
    }
  }

  @Test
  public void memoryByteSourceExposesImmutableSnapshotMetadata() throws IOException {
    byte[] data = {1, 2, 3};
    PmTilesTestArchive.MemoryByteSource source = new PmTilesTestArchive.MemoryByteSource(data);
    String versionId = source.versionId();

    data[0] = 9;

    assertEquals(3L, source.size());
    assertEquals(versionId, source.versionId());
    assertArrayEquals(new byte[]{1, 2, 3}, source.read(0, 3));
  }

  @Test
  public void fileByteSourceRejectsChangedFile() throws IOException {
    Path path = Files.createTempFile("pmtiles-source", ".pmtiles");
    try {
      Files.write(path, new byte[]{1, 2, 3, 4});
      try (PmTilesArchive.FileByteSource source =
             new PmTilesArchive.FileByteSource(path.toFile())) {
        assertEquals(4L, source.size());
        assertArrayEquals(new byte[]{1, 2}, source.read(0, 2));

        FileTime originalModifiedTime = Files.getLastModifiedTime(path);
        Files.write(path, new byte[]{5, 6, 7, 8});
        Files.setLastModifiedTime(path,
          FileTime.fromMillis(originalModifiedTime.toMillis() + 2000L));
        assertEquals("rewrite must keep the same size", 4L, Files.size(path));
        assertNotEquals("test must change modified time", originalModifiedTime,
          Files.getLastModifiedTime(path));
        try {
          source.read(0, 2);
          fail("expected changed file rejection");
        } catch (IOException e) {
          assertTrue(e.getMessage(), e.getMessage().contains("changed"));
        }
      }
    } finally {
      Files.deleteIfExists(path);
    }
  }

  @Test
  public void fileByteSourceRejectsInvalidRangeBeforeAllocation() throws IOException {
    Path path = Files.createTempFile("pmtiles-source", ".pmtiles");
    try {
      Files.write(path, new byte[]{1, 2, 3, 4});
      try (PmTilesArchive.FileByteSource source =
             new PmTilesArchive.FileByteSource(path.toFile())) {
        assertInvalidRange(source, -1L, 1);
        assertInvalidRange(source, 0L, 0);
        assertInvalidRange(source, Long.MAX_VALUE, 2);
      }
    } finally {
      Files.deleteIfExists(path);
    }
  }

  private static void assertInvalidRange(PmTilesArchive.ByteSource source, long offset, int length)
      throws IOException {
    try {
      source.read(offset, length);
      fail("expected invalid range rejection");
    } catch (IOException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("range"));
    }
  }

  private static void assertInvalidTileCoordinates(int z, int x, int y) {
    assertIllegalArgument(() -> PmTilesArchive.zxyToTileId(z, x, y));
  }

  private static void assertIllegalArgument(ThrowingOperation operation) {
    try {
      operation.run();
      fail("expected invalid PMTiles tile coordinates to be rejected");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("PMTiles"));
    } catch (IOException e) {
      throw new AssertionError("expected IllegalArgumentException", e);
    }
  }

  @FunctionalInterface
  private interface ThrowingOperation {
    void run() throws IOException;
  }

  private static byte[] directory(long[]... entries) {
    return PmTilesTestArchive.serializeDirectory(Arrays.asList(entries), false);
  }

  private static byte[] archiveWithSections(byte[] root, byte[] leaf, byte[] tiles)
      throws IOException {
    byte[] template = new PmTilesTestArchive().zoomRange(0, 0).build();
    byte[] archive = new byte[PmTilesArchive.HEADER_LEN + root.length + leaf.length + tiles.length];
    System.arraycopy(template, 0, archive, 0, PmTilesArchive.HEADER_LEN);
    long rootOffset = PmTilesArchive.HEADER_LEN;
    long metadataOffset = rootOffset + root.length;
    long leafOffset = metadataOffset;
    long tileOffset = leafOffset + leaf.length;
    PmTilesTestArchive.putLong(archive, 8, rootOffset);
    PmTilesTestArchive.putLong(archive, 16, root.length);
    PmTilesTestArchive.putLong(archive, 24, metadataOffset);
    PmTilesTestArchive.putLong(archive, 32, 0L);
    PmTilesTestArchive.putLong(archive, 40, leafOffset);
    PmTilesTestArchive.putLong(archive, 48, leaf.length);
    PmTilesTestArchive.putLong(archive, 56, tileOffset);
    PmTilesTestArchive.putLong(archive, 64, tiles.length);
    System.arraycopy(root, 0, archive, (int) rootOffset, root.length);
    System.arraycopy(leaf, 0, archive, (int) leafOffset, leaf.length);
    System.arraycopy(tiles, 0, archive, (int) tileOffset, tiles.length);
    return archive;
  }

  private static void assertOpenFails(byte[] bytes, String expectedMessage) throws IOException {
    try (PmTilesArchive ignored = PmTilesArchive.open(
      new PmTilesTestArchive.MemoryByteSource(bytes))) {
      fail("expected PMTiles open failure containing: " + expectedMessage);
    } catch (IOException e) {
      assertTrue(e.getMessage(), e.getMessage().contains(expectedMessage));
    }
  }

  private static void assertDirectoryFails(byte[] bytes, String expectedMessage)
      throws IOException {
    try {
      PmTilesArchive.deserializeDirectory(bytes);
      fail("expected directory failure containing: " + expectedMessage);
    } catch (IOException e) {
      assertTrue(e.getMessage(), e.getMessage().contains(expectedMessage));
    }
  }

  private static void assertLocateFails(PmTilesArchive archive, int z, int x, int y,
                                        String expectedMessage) throws IOException {
    try {
      archive.locateTile(z, x, y);
      fail("expected tile lookup failure containing: " + expectedMessage);
    } catch (IOException e) {
      assertTrue(e.getMessage(), e.getMessage().contains(expectedMessage));
    }
  }

  private static final class PrefixByteSource implements PmTilesArchive.ByteSource {
    private final byte[] prefix;
    private final long declaredSize;

    PrefixByteSource(byte[] prefix, long declaredSize) {
      this.prefix = prefix.clone();
      this.declaredSize = declaredSize;
    }

    @Override
    public byte[] read(long offset, int length) throws IOException {
      long end = PmTilesArchive.checkedReadEnd(offset, length);
      if (end > prefix.length) {
        throw new IOException("test source does not store the requested large range");
      }
      return Arrays.copyOfRange(prefix, (int) offset, (int) end);
    }

    @Override
    public long size() {
      return declaredSize;
    }

    @Override
    public String versionId() {
      return "prefix:" + declaredSize + ":" + PmTilesArchive.sha256Hex(prefix);
    }

    @Override
    public void close() {
      // nothing to release
    }
  }

}
