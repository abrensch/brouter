package btools.mapcreator;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class MapterhornTileCacheTest {

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  @Test
  public void equalLocationsShareOneCacheFile() throws IOException {
    File dir = tmp.newFolder();
    PmTilesArchive.TileLocation location = new PmTilesArchive.TileLocation(4096L, 12);
    try (MapterhornTileCache cache = new MapterhornTileCache(
        dir, "archive-a", 1024L)) {
      cache.write(location, new byte[]{1, 2, 3});
      cache.write(location, new byte[]{1, 2, 3});

      assertArrayEquals(new byte[]{1, 2, 3}, cache.read(location));
      assertEquals(3L, cache.usedBytes());
      assertEquals(1L, regularTileFiles(dir));
    }
  }

  @Test
  public void exactBudgetAcceptsEntryThenDisablesLaterWritesOnce() throws IOException {
    File dir = tmp.newFolder();
    PmTilesArchive.TileLocation first = new PmTilesArchive.TileLocation(10L, 2);
    PmTilesArchive.TileLocation later = new PmTilesArchive.TileLocation(20L, 1);
    PmTilesArchive.TileLocation latest = new PmTilesArchive.TileLocation(30L, 1);
    ByteArrayOutputStream warning = new ByteArrayOutputStream();
    PrintStream originalErr = System.err;
    try (MapterhornTileCache cache = new MapterhornTileCache(dir, "archive-a", 3L)) {
      try {
        System.setErr(new PrintStream(warning));
        cache.write(first, new byte[]{1, 2, 3});
        cache.write(later, new byte[]{4});
        cache.write(latest, new byte[]{5});
      } finally {
        System.setErr(originalErr);
      }

      assertFalse(cache.isWriteEnabled());
      assertEquals(3L, cache.usedBytes());
      assertArrayEquals(new byte[]{1, 2, 3}, cache.read(first));
      assertNull(cache.read(later));
      assertNull(cache.read(latest));
      assertEquals(1L, regularTileFiles(dir));
      String message = warning.toString("UTF-8");
      assertTrue(message, message.contains("cache budget"));
      assertEquals("warning must be printed once", message.indexOf("cache budget"),
        message.lastIndexOf("cache budget"));
    }
  }

  @Test
  public void mismatchedArchiveIdFails() throws IOException {
    File dir = tmp.newFolder();
    try (MapterhornTileCache ignored = new MapterhornTileCache(
        dir, "archive-a", 1024L)) {
      // initialize and release the cache before testing its persistent identity
    }

    try (MapterhornTileCache ignored = new MapterhornTileCache(
        dir, "archive-b", 1024L)) {
      fail("expected a cache bound to another archive to fail");
    } catch (IOException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("different archive"));
    }
  }

  @Test
  public void nonEmptyUnidentifiedCacheIsRejected() throws IOException {
    File dir = tmp.newFolder();
    Files.write(new File(dir, "old.tile").toPath(), new byte[]{1});

    assertNeedsEmptyDirectory(dir);
    assertFalse(new File(dir, "archive.id").exists());
  }

  @Test
  public void legacyPlainArchiveIdIsRejected() throws IOException {
    File dir = tmp.newFolder();
    Files.write(new File(dir, "archive.id").toPath(),
      "archive-a\n".getBytes(StandardCharsets.UTF_8));

    assertNeedsEmptyDirectory(dir);
  }

  @Test
  public void metadataContainsSchemaTwoAndArchiveId() throws IOException {
    File dir = tmp.newFolder();
    try (MapterhornTileCache ignored = new MapterhornTileCache(
        dir, "archive-a", 1024L)) {
      // construction initializes the metadata while holding the root lock
    }

    File idFile = new File(dir, "archive.id");
    Properties metadata = new Properties();
    try (java.io.InputStream in = Files.newInputStream(idFile.toPath())) {
      metadata.load(in);
    }
    assertEquals("2", metadata.getProperty("schema"));
    assertEquals("archive-a", metadata.getProperty("archiveId"));
    assertEquals(0L, generatedTemporaryFiles(dir));
  }

  @Test
  public void staleIdentityTemporaryAloneDoesNotBlockInitialization() throws IOException {
    File dir = tmp.newFolder();
    File staleIdentity = new File(dir, "archive.id.tmp17");
    Files.write(staleIdentity.toPath(), new byte[]{1, 2, 3});

    try (MapterhornTileCache initialized = new MapterhornTileCache(
        dir, "archive-a", 1024L)) {
      assertFalse(staleIdentity.exists());
      assertTrue(new File(dir, "archive.id").isFile());
      assertEquals(0L, initialized.usedBytes());
    }
    try (MapterhornTileCache reopened = new MapterhornTileCache(
        dir, "archive-a", 1024L)) {
      assertEquals(0L, reopened.usedBytes());
    }
  }

  @Test
  public void unrelatedIdentityTemporaryDoesNotMakeRootEmpty() throws IOException {
    File dir = tmp.newFolder();
    File unrelated = new File(dir, "archive.id.tmp17.extra");
    Files.write(unrelated.toPath(), new byte[]{1});

    assertNeedsEmptyDirectory(dir);
    assertTrue("unrelated temporary must be preserved", unrelated.isFile());
    assertFalse(new File(dir, "archive.id").exists());
  }

  @Test
  public void startupCountsExistingTileBytes() throws IOException {
    File dir = tmp.newFolder();
    PmTilesArchive.TileLocation location = new PmTilesArchive.TileLocation(100L, 10);
    try (MapterhornTileCache first = new MapterhornTileCache(
        dir, "archive-a", 1024L)) {
      first.write(location, new byte[]{1, 2, 3, 4});
    }

    try (MapterhornTileCache reopened = new MapterhornTileCache(
        dir, "archive-a", 1024L)) {
      assertEquals(4L, reopened.usedBytes());
      assertArrayEquals(new byte[]{1, 2, 3, 4}, reopened.read(location));
    }
  }

  @Test
  public void startupRemovesOnlyCacheGeneratedTemporaryFiles() throws IOException {
    File dir = tmp.newFolder();
    try (MapterhornTileCache ignored = new MapterhornTileCache(
        dir, "archive-a", 1024L)) {
      // initialize the cache before adding simulated leftovers
    }
    File nested = new File(dir, "tiles/ab");
    assertTrue(nested.mkdirs());
    File staleIdentity = new File(dir, "archive.id.tmp17");
    File staleTile = new File(nested, "abcdef-12.tile.tmp18");
    File unrelated = new File(dir, "metadata.tmp");
    Files.write(staleIdentity.toPath(), new byte[]{1});
    Files.write(staleTile.toPath(), new byte[]{2});
    Files.write(unrelated.toPath(), new byte[]{3});

    try (MapterhornTileCache ignored = new MapterhornTileCache(
        dir, "archive-a", 1024L)) {
      // startup cleanup runs during construction
    }

    assertFalse(staleIdentity.exists());
    assertFalse(staleTile.exists());
    assertTrue("an unrelated .tmp file must be preserved", unrelated.isFile());
  }

  @Test
  public void offsetPrefixShardingIsStable() throws IOException {
    File dir = tmp.newFolder();
    try (MapterhornTileCache cache = new MapterhornTileCache(
        dir, "archive-a", 1024L)) {
      cache.write(new PmTilesArchive.TileLocation(0xabcdef12L, 0x34), new byte[]{1});
      cache.write(new PmTilesArchive.TileLocation(1L, 1), new byte[]{2});

      assertTrue(new File(dir, "tiles/ab/abcdef12-34.tile").isFile());
      assertTrue(new File(dir, "tiles/00/1-1.tile").isFile());
    }
  }

  @Test(timeout = 5000L)
  public void secondCacheInstanceFailsWhileRootIsLocked() throws IOException {
    File dir = tmp.newFolder();
    try (MapterhornTileCache first = new MapterhornTileCache(
        dir, "archive-a", 1024L)) {
      try (MapterhornTileCache ignored = new MapterhornTileCache(
          dir, "archive-a", 1024L)) {
        fail("expected cache-root lock contention");
      } catch (IOException e) {
        assertTrue(e.getMessage(), e.getMessage().contains("already in use"));
      }
    }

    try (MapterhornTileCache reopened = new MapterhornTileCache(
        dir, "archive-a", 1024L)) {
      assertEquals(0L, reopened.usedBytes());
    }
  }

  @Test
  public void internalLockNamedFileMakesUnidentifiedRootNonEmpty() throws IOException {
    File dir = tmp.newFolder();
    File lockFile = new File(dir, ".mapterhorn-cache.lock");
    Files.write(lockFile.toPath(), new byte[0]);

    assertNeedsEmptyDirectory(dir);

    assertTrue("an internal lock-named file must not be ignored", lockFile.isFile());
    assertFalse(new File(dir, "archive.id").exists());
  }

  @Test(timeout = 10000L)
  public void concurrentEmptyRootConstructorsHaveOneInitializer() throws Exception {
    File dir = new File(tmp.getRoot(), "empty-race-cache");
    CountDownLatch siblingLocked = new CountDownLatch(1);
    CountDownLatch allowInitialization = new CountDownLatch(1);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    Future<MapterhornTileCache> creator = pool.submit(() -> new MapterhornTileCache(
      dir, "archive-a", 1024L, MapterhornTileCacheTest::moveAtomically,
      path -> {
        siblingLocked.countDown();
        await(allowInitialization, "allow empty-root initialization");
      }));
    try {
      await(siblingLocked, "lock sibling before empty-root initialization");
      assertFalse("the cache root must not exist before the sibling is locked",
        dir.exists());
      assertCacheLocked(dir);

      allowInitialization.countDown();
      try (MapterhornTileCache initialized = creator.get(5L, TimeUnit.SECONDS)) {
        assertEquals(0L, initialized.usedBytes());
        assertTrue(new File(dir, "archive.id").isFile());
      }
      try (MapterhornTileCache reopened = new MapterhornTileCache(
          dir, "archive-a", 1024L)) {
        assertEquals(0L, reopened.usedBytes());
      }
    } finally {
      allowInitialization.countDown();
      pool.shutdownNow();
    }
  }

  @Test(timeout = 10000L)
  public void recreatedSiblingLockStillHasOneWinner() throws Exception {
    File dir = initializedCacheDirectory("recreated-lock-cache");
    File siblingLock = siblingLockFile(dir);
    assertTrue("test premise: persistent sibling lock must exist", siblingLock.isFile());
    assertTrue("external cleanup removes the inactive sibling lock", siblingLock.delete());

    CountDownLatch siblingLocked = new CountDownLatch(1);
    CountDownLatch allowWinner = new CountDownLatch(1);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    Future<MapterhornTileCache> winner = pool.submit(() -> new MapterhornTileCache(
      dir, "archive-a", 1024L, MapterhornTileCacheTest::moveAtomically,
      path -> {
        siblingLocked.countDown();
        await(allowWinner, "allow recreated-lock winner");
      }));
    try {
      await(siblingLocked, "recreate and lock sibling");
      assertTrue(siblingLock.isFile());
      assertCacheLocked(dir);
      assertCacheLocked(dir);

      allowWinner.countDown();
      try (MapterhornTileCache initialized = winner.get(5L, TimeUnit.SECONDS)) {
        assertEquals(0L, initialized.usedBytes());
      }
      try (MapterhornTileCache reopened = new MapterhornTileCache(
          dir, "archive-a", 1024L)) {
        assertEquals(0L, reopened.usedBytes());
      }
    } finally {
      allowWinner.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  public void nestedNonexistentParentsUseSiblingLock() throws IOException {
    File dir = new File(tmp.getRoot(), "nested/parents/cache");
    try (MapterhornTileCache initialized = new MapterhornTileCache(
        dir, "archive-a", 1024L)) {
      assertTrue(new File(dir, "archive.id").isFile());
      assertTrue(siblingLockFile(dir).isFile());
    }
    try (MapterhornTileCache reopened = new MapterhornTileCache(
        dir, "archive-a", 1024L)) {
      assertEquals(0L, reopened.usedBytes());
    }
  }

  @Test
  public void filesystemRootCannotBeACache() throws IOException {
    File filesystemRoot = File.listRoots()[0];
    try (MapterhornTileCache ignored = new MapterhornTileCache(
        filesystemRoot, "archive-a", 1024L)) {
      fail("expected a filesystem-root cache to be rejected");
    } catch (IOException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("filesystem root"));
    }
  }

  @Test
  public void siblingLockSymbolicLinkIsRejected() throws IOException {
    File dir = new File(tmp.getRoot(), "sibling-link-cache");
    Path sibling = siblingLockFile(dir).toPath();
    Path external = tmp.newFile("external-sibling-lock").toPath();
    Files.createSymbolicLink(sibling, external);

    assertSymbolicLinkRejected(dir);
  }

  @Test
  public void siblingLockMustBeARegularFile() throws IOException {
    File dir = new File(tmp.getRoot(), "sibling-directory-cache");
    File sibling = siblingLockFile(dir);
    assertTrue(sibling.mkdir());

    try (MapterhornTileCache ignored = new MapterhornTileCache(
        dir, "archive-a", 1024L)) {
      fail("expected a non-regular sibling lock to be rejected");
    } catch (IOException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("not a regular file"));
    }
  }

  @Test
  public void requestedRootSymbolicLinkIsRejected() throws IOException {
    File target = tmp.newFolder("root-target");
    Path link = tmp.getRoot().toPath().resolve("root-link");
    Files.createSymbolicLink(link, target.toPath());

    assertSymbolicLinkRejected(link.toFile());
    assertFalse("a linked target must not be initialized",
      new File(target, "archive.id").exists());
  }

  @Test
  public void archiveIdentitySymbolicLinkIsRejected() throws IOException {
    File dir = initializedCacheDirectory("id-link-cache");
    Path identity = dir.toPath().resolve("archive.id");
    Path external = tmp.newFile("external-id").toPath();
    Files.delete(identity);
    Files.createSymbolicLink(identity, external);

    assertSymbolicLinkRejected(dir);
  }

  @Test
  public void tilesDirectorySymbolicLinkIsRejected() throws IOException {
    File dir = initializedCacheDirectory("tiles-link-cache");
    Path external = tmp.newFolder("external-tiles").toPath();
    Files.createSymbolicLink(dir.toPath().resolve("tiles"), external);

    assertSymbolicLinkRejected(dir);
  }

  @Test
  public void shardDirectorySymbolicLinkIsRejected() throws IOException {
    File dir = initializedCacheDirectory("shard-link-cache");
    Path tiles = Files.createDirectory(dir.toPath().resolve("tiles"));
    Path external = tmp.newFolder("external-shard").toPath();
    Files.createSymbolicLink(tiles.resolve("ab"), external);

    assertSymbolicLinkRejected(dir);
  }

  @Test
  public void tileEntrySymbolicLinkIsRejected() throws IOException {
    File dir = initializedCacheDirectory("entry-link-cache");
    Path shard = Files.createDirectories(dir.toPath().resolve("tiles/ab"));
    Path external = tmp.newFile("external-tile").toPath();
    Files.createSymbolicLink(shard.resolve("abcdef-12.tile"), external);

    assertSymbolicLinkRejected(dir);
  }

  @Test
  public void generatedTemporarySymbolicLinkIsRejected() throws IOException {
    File dir = initializedCacheDirectory("temp-link-cache");
    Path shard = Files.createDirectories(dir.toPath().resolve("tiles/ab"));
    Path external = tmp.newFile("external-temp").toPath();
    Files.createSymbolicLink(shard.resolve("abcdef-12.tile.tmp42"), external);

    assertSymbolicLinkRejected(dir);
    assertTrue("startup must not delete through the link", Files.exists(external));
  }

  @Test
  public void identityTemporarySymbolicLinkIsRejectedBeforeInitialization() throws IOException {
    File dir = tmp.newFolder("identity-temp-link-cache");
    Path external = tmp.newFile("external-identity-temp").toPath();
    Files.createSymbolicLink(dir.toPath().resolve("archive.id.tmp17"), external);

    assertSymbolicLinkRejected(dir);
    assertTrue("startup must not delete through the link", Files.exists(external));
    assertFalse(new File(dir, "archive.id").exists());
  }

  @Test
  public void failedInitializationKeepsFutureLockExclusion() throws IOException {
    File dir = tmp.newFolder();
    try (MapterhornTileCache ignored = new MapterhornTileCache(
        dir, "archive-a", 1024L, (from, to) -> {
          throw new AtomicMoveNotSupportedException(
            from.toString(), to.toString(), "injected");
        })) {
      fail("expected atomic identity publication to fail");
    } catch (AtomicMoveNotSupportedException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("injected"));
    }

    File siblingLock = siblingLockFile(dir);
    assertTrue("failed initialization must retain the persistent sibling lock",
      siblingLock.isFile());
    assertFalse(new File(dir, "archive.id").exists());

    try (MapterhornTileCache initialized = new MapterhornTileCache(
        dir, "archive-a", 1024L)) {
      assertCacheLocked(dir);
      assertTrue(siblingLock.isFile());
    }
    try (MapterhornTileCache reopened = new MapterhornTileCache(
        dir, "archive-a", 1024L)) {
      assertEquals(0L, reopened.usedBytes());
    }
  }

  @Test
  public void failedAtomicTileMoveReleasesReservationAndAccounting() throws IOException {
    File dir = tmp.newFolder();
    AtomicBoolean failTileMove = new AtomicBoolean(true);
    PmTilesArchive.TileLocation location = new PmTilesArchive.TileLocation(0xabcdL, 4);
    try (MapterhornTileCache cache = new MapterhornTileCache(
        dir, "archive-a", 3L, (from, to) -> {
          if (to.getFileName().toString().endsWith(".tile")
            && failTileMove.getAndSet(false)) {
            throw new AtomicMoveNotSupportedException(
              from.toString(), to.toString(), "injected");
          }
          moveAtomically(from, to);
        })) {
      try {
        cache.write(location, new byte[]{1, 2, 3});
        fail("expected atomic tile publication to fail");
      } catch (AtomicMoveNotSupportedException expected) {
        assertTrue(expected.getMessage(), expected.getMessage().contains("injected"));
      }

      assertEquals(0L, cache.usedBytes());
      assertTrue(cache.isWriteEnabled());
      assertNull(cache.read(location));
      assertEquals(0L, generatedTemporaryFiles(dir));

      cache.write(location, new byte[]{1, 2, 3});
      assertEquals(3L, cache.usedBytes());
      assertArrayEquals(new byte[]{1, 2, 3}, cache.read(location));
    }
  }

  @Test(timeout = 10000L)
  public void concurrentEqualKeyWritesPublishOneEntry() throws Exception {
    File dir = tmp.newFolder();
    CountDownLatch publicationStarted = new CountDownLatch(1);
    CountDownLatch allowPublication = new CountDownLatch(1);
    PmTilesArchive.TileLocation location = new PmTilesArchive.TileLocation(0xcafeL, 7);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try (MapterhornTileCache cache = new MapterhornTileCache(
        dir, "archive-a", 1024L, (from, to) -> {
          if (to.getFileName().toString().endsWith(".tile")) {
            publicationStarted.countDown();
            await(allowPublication, "allow tile publication");
          }
          moveAtomically(from, to);
        })) {
      Future<?> first = pool.submit(() -> {
        cache.write(location, new byte[]{1, 2, 3});
        return null;
      });
      await(publicationStarted, "start tile publication");

      Future<?> duplicate = pool.submit(() -> {
        cache.write(location, new byte[]{1, 2, 3});
        return null;
      });
      duplicate.get(2L, TimeUnit.SECONDS);
      allowPublication.countDown();
      first.get(2L, TimeUnit.SECONDS);

      assertArrayEquals(new byte[]{1, 2, 3}, cache.read(location));
      assertEquals(3L, cache.usedBytes());
      assertEquals(1L, regularTileFiles(dir));
    } finally {
      allowPublication.countDown();
      pool.shutdownNow();
    }
  }

  @Test(timeout = 10000L)
  public void concurrentWritesNeverReserveBeyondBudget() throws Exception {
    File dir = tmp.newFolder();
    int workers = 8;
    CountDownLatch ready = new CountDownLatch(workers);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(workers);
    try (MapterhornTileCache cache = new MapterhornTileCache(
        dir, "archive-a", 9L)) {
      List<Future<?>> writes = new ArrayList<>();
      for (int i = 0; i < workers; i++) {
        final int key = i;
        writes.add(pool.submit(() -> {
          ready.countDown();
          await(start, "start concurrent cache writes");
          cache.write(new PmTilesArchive.TileLocation(0x100L + key, 3),
            new byte[]{1, 2, 3});
          return null;
        }));
      }
      await(ready, "ready concurrent cache writes");
      start.countDown();
      for (Future<?> write : writes) {
        write.get(5L, TimeUnit.SECONDS);
      }

      assertEquals(9L, cache.usedBytes());
      assertEquals(3L, regularTileFiles(dir));
      assertFalse(cache.isWriteEnabled());
    } finally {
      start.countDown();
      pool.shutdownNow();
    }
  }

  private File initializedCacheDirectory(String name) throws IOException {
    File dir = tmp.newFolder(name);
    try (MapterhornTileCache ignored = new MapterhornTileCache(
        dir, "archive-a", 1024L)) {
      return dir;
    }
  }

  private void assertNeedsEmptyDirectory(File dir) throws IOException {
    try (MapterhornTileCache ignored = new MapterhornTileCache(
        dir, "archive-a", 1024L)) {
      fail("expected an unidentified cache to fail");
    } catch (IOException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("empty cache directory"));
    }
  }

  private void assertCacheLocked(File dir) throws IOException {
    try (MapterhornTileCache ignored = new MapterhornTileCache(
        dir, "archive-a", 1024L)) {
      fail("expected cache-root lock contention");
    } catch (IOException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("already in use"));
    }
  }

  private void assertSymbolicLinkRejected(File dir) throws IOException {
    try (MapterhornTileCache ignored = new MapterhornTileCache(
        dir, "archive-a", 1024L)) {
      fail("expected a symbolic link to be rejected");
    } catch (IOException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("symbolic link"));
    }
  }

  private static void moveAtomically(Path from, Path to) throws IOException {
    Files.move(from, to, StandardCopyOption.ATOMIC_MOVE,
      StandardCopyOption.REPLACE_EXISTING);
  }

  private static File siblingLockFile(File cacheRoot) throws IOException {
    Path root = cacheRoot.toPath().toAbsolutePath().normalize();
    Path realParent = root.getParent().toRealPath();
    return realParent.resolve("." + root.getFileName()
      + ".mapterhorn-cache.lock").toFile();
  }

  private static void await(CountDownLatch latch, String action) throws IOException {
    try {
      if (!latch.await(5L, TimeUnit.SECONDS)) {
        throw new IOException("timed out waiting to " + action);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted while waiting to " + action, e);
    }
  }

  private static long regularTileFiles(File dir) throws IOException {
    try (java.util.stream.Stream<Path> files = Files.walk(dir.toPath())) {
      return files.filter(Files::isRegularFile)
        .filter(path -> path.getFileName().toString().endsWith(".tile"))
        .count();
    }
  }

  private static long generatedTemporaryFiles(File dir) throws IOException {
    try (java.util.stream.Stream<Path> files = Files.walk(dir.toPath())) {
      return files.filter(Files::isRegularFile)
        .filter(path -> path.getFileName().toString().matches(
          "archive\\.id\\.tmp[0-9]+|[0-9a-f]+-[0-9a-f]+\\.tile\\.tmp[0-9]+"))
        .count();
    }
  }
}
