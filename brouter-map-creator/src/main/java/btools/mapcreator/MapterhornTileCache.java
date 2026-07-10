package btools.mapcreator;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Persistent cache of decompressed Mapterhorn tiles, keyed by their PMTiles byte range.
 * One cache instance owns byte accounting and an exclusive root lock for its lifetime;
 * completed entries are never removed, while writes stop once the configured budget
 * would be exceeded.
 */
public final class MapterhornTileCache implements AutoCloseable {

  private static final String ID_FILE = "archive.id";
  private static final String LOCK_SUFFIX = ".mapterhorn-cache.lock";
  private static final String TILES_DIRECTORY = "tiles";
  private static final String CACHE_SCHEMA = "2";
  private static final AtomicInteger TMP_SEQUENCE = new AtomicInteger();
  private static final Pattern ID_TEMPORARY =
    Pattern.compile("archive\\.id\\.tmp[0-9]+");
  private static final Pattern TILE_TEMPORARY =
    Pattern.compile("[0-9a-f]+-[0-9a-f]+\\.tile\\.tmp[0-9]+");
  private static final Pattern SHARD_DIRECTORY = Pattern.compile("[0-9a-f]{2}");

  private final Path root;
  private final Path tilesDirectory;
  private final long maxBytes;
  private final AtomicMoveOperation atomicMoveOperation;
  private final CacheRootLock rootLock;
  private final Set<Path> reservations = new HashSet<>();

  private long usedBytes;
  private boolean writeEnabled = true;
  private boolean warned;
  private boolean closed;
  private int activeUses;

  @FunctionalInterface
  interface AtomicMoveOperation {
    void move(Path from, Path to) throws IOException;
  }

  @FunctionalInterface
  interface RootLockOperation {
    void afterLock(Path path) throws IOException;
  }

  public MapterhornTileCache(File root, String archiveId, long maxBytes) throws IOException {
    this(root, archiveId, maxBytes, MapterhornTileCache::atomicMove);
  }

  MapterhornTileCache(File root, String archiveId, long maxBytes,
                      AtomicMoveOperation atomicMoveOperation) throws IOException {
    this(root, archiveId, maxBytes, atomicMoveOperation,
      MapterhornTileCache::ignoreRootLock);
  }

  MapterhornTileCache(File root, String archiveId, long maxBytes,
                      AtomicMoveOperation atomicMoveOperation,
                      RootLockOperation rootLockOperation) throws IOException {
    if (root == null) {
      throw new IllegalArgumentException("cache directory is required");
    }
    if (archiveId == null || archiveId.isEmpty()) {
      throw new IllegalArgumentException("archive ID is required");
    }
    if (maxBytes <= 0L) {
      throw new IllegalArgumentException("cache byte budget must be positive");
    }
    if (atomicMoveOperation == null) {
      throw new IllegalArgumentException("atomic move operation is required");
    }
    if (rootLockOperation == null) {
      throw new IllegalArgumentException("root lock operation is required");
    }
    CacheLocation location = CacheLocation.resolve(root);
    CacheRootLock acquiredLock = CacheRootLock.acquire(
      location.cacheRoot, location.lockFile, rootLockOperation);
    Path preparedRoot;
    try {
      preparedRoot = prepareRoot(location.cacheRoot);
    } catch (IOException | RuntimeException | Error e) {
      try {
        acquiredLock.close();
      } catch (IOException closeFailure) {
        e.addSuppressed(closeFailure);
      }
      throw e;
    }
    this.root = preparedRoot;
    this.tilesDirectory = this.root.resolve(TILES_DIRECTORY);
    this.maxBytes = maxBytes;
    this.atomicMoveOperation = atomicMoveOperation;
    this.rootLock = acquiredLock;

    try {
      initializeIdentity(archiveId);
      usedBytes = cleanTemporaryFilesAndCountTiles();
      if (usedBytes > maxBytes) {
        disableWrites();
      }
    } catch (IOException | RuntimeException | Error e) {
      try {
        rootLock.close();
      } catch (IOException closeFailure) {
        e.addSuppressed(closeFailure);
      }
      throw e;
    }
  }

  /** @return cached decompressed tile bytes, or null when this location is not cached. */
  public byte[] read(PmTilesArchive.TileLocation location) throws IOException {
    beginUse();
    try {
      Path path = file(location);
      if (!requireDirectoryIfPresent(tilesDirectory)
        || !requireDirectoryIfPresent(path.getParent())) {
        return null;
      }
      BasicFileAttributes attributes = attributesIfPresent(path);
      if (attributes == null) {
        return null;
      }
      requireRegularFile(path, attributes, "tile cache entry");
      try (InputStream in = Channels.newInputStream(Files.newByteChannel(
          path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
        return in.readAllBytes();
      }
    } finally {
      endUse();
    }
  }

  /**
   * Store decompressed bytes if this key is new and the byte budget has room. Equal
   * locations are idempotent, including while another cache worker is writing the key.
   */
  public void write(PmTilesArchive.TileLocation location, byte[] bytes) throws IOException {
    if (bytes == null) {
      throw new IllegalArgumentException("tile bytes are required");
    }
    beginUse();
    try {
      writeEntry(location, bytes);
    } finally {
      endUse();
    }
  }

  public synchronized long usedBytes() {
    return usedBytes;
  }

  public synchronized boolean isWriteEnabled() {
    return writeEnabled;
  }

  @Override
  public void close() throws IOException {
    boolean interrupted = false;
    synchronized (this) {
      if (closed) {
        return;
      }
      closed = true;
      while (activeUses > 0) {
        try {
          wait();
        } catch (InterruptedException e) {
          interrupted = true;
        }
      }
    }
    try {
      rootLock.close();
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private void writeEntry(PmTilesArchive.TileLocation location, byte[] bytes)
    throws IOException {
    Path target = file(location);
    ensureDirectory(tilesDirectory, "tile cache tiles directory");
    ensureDirectory(target.getParent(), "tile cache shard directory");
    synchronized (this) {
      BasicFileAttributes attributes = attributesIfPresent(target);
      if (attributes != null) {
        requireRegularFile(target, attributes, "tile cache entry");
        return;
      }
      if (reservations.contains(target)) {
        return;
      }
      if (!writeEnabled) {
        return;
      }
      if (bytes.length > maxBytes - usedBytes) {
        disableWrites();
        return;
      }
      usedBytes += bytes.length;
      reservations.add(target);
    }

    Path temporary = target.resolveSibling(
      target.getFileName() + ".tmp" + TMP_SEQUENCE.incrementAndGet());
    boolean complete = false;
    try {
      writeNewRegularFile(temporary, bytes);
      ensureDirectory(tilesDirectory, "tile cache tiles directory");
      ensureDirectory(target.getParent(), "tile cache shard directory");
      BasicFileAttributes targetAttributes = attributesIfPresent(target);
      if (targetAttributes != null) {
        rejectSymbolicLink(target, targetAttributes);
        throw new IOException("tile cache entry appeared while it was being written: "
          + target);
      }
      atomicMoveOperation.move(temporary, target);
      complete = true;
    } finally {
      try {
        deleteTemporaryIfPresent(temporary);
      } finally {
        synchronized (this) {
          reservations.remove(target);
          if (!complete) {
            usedBytes -= bytes.length;
          }
        }
      }
    }
  }

  private Path file(PmTilesArchive.TileLocation location) {
    if (location == null) {
      throw new IllegalArgumentException("tile location is required");
    }
    String offset = Long.toUnsignedString(location.offset, 16);
    String shard = offset.length() < 2 ? "00" : offset.substring(0, 2);
    Path path = tilesDirectory.resolve(shard)
      .resolve(offset + "-" + Integer.toHexString(location.length) + ".tile")
      .normalize();
    if (!path.startsWith(root)) {
      throw new IllegalArgumentException("tile location resolves outside the cache root");
    }
    return path;
  }

  private void initializeIdentity(String archiveId) throws IOException {
    Path idPath = root.resolve(ID_FILE);
    if (isUninitializedDirectory()) {
      writeIdentity(idPath, archiveId);
      return;
    }
    BasicFileAttributes idAttributes = attributesIfPresent(idPath);
    if (idAttributes == null) {
      throw unidentifiedCache();
    }
    requireRegularFile(idPath, idAttributes, "tile cache archive identity");

    Properties identity = new Properties();
    try (InputStream in = Channels.newInputStream(Files.newByteChannel(
        idPath, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
      identity.load(in);
    } catch (IllegalArgumentException e) {
      throw unidentifiedCache();
    }
    String schema = identity.getProperty("schema");
    String existingArchiveId = identity.getProperty("archiveId");
    if (!CACHE_SCHEMA.equals(schema) || existingArchiveId == null
      || existingArchiveId.isEmpty()) {
      throw unidentifiedCache();
    }
    if (!archiveId.equals(existingArchiveId)) {
      throw new IOException("tile cache " + root + " was filled from a different archive"
        + " (cache id " + existingArchiveId + ", archive id " + archiveId + ");"
        + " use an empty cache directory or delete this one");
    }
  }

  private void writeIdentity(Path idPath, String archiveId) throws IOException {
    Properties identity = new Properties();
    identity.setProperty("schema", CACHE_SCHEMA);
    identity.setProperty("archiveId", archiveId);
    Path temporary = idPath.resolveSibling(
      idPath.getFileName() + ".tmp" + TMP_SEQUENCE.incrementAndGet());
    try {
      BasicFileAttributes temporaryAttributes = attributesIfPresent(temporary);
      if (temporaryAttributes != null) {
        rejectSymbolicLink(temporary, temporaryAttributes);
        throw new IOException("tile cache identity temporary file already exists: "
          + temporary);
      }
      try (OutputStream out = Channels.newOutputStream(Files.newByteChannel(temporary,
          StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
          LinkOption.NOFOLLOW_LINKS))) {
        identity.store(out, "Mapterhorn tile cache");
      }
      atomicMoveOperation.move(temporary, idPath);
    } finally {
      deleteTemporaryIfPresent(temporary);
    }
  }

  private boolean isUninitializedDirectory() throws IOException {
    boolean uninitialized = true;
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
      for (Path entry : entries) {
        BasicFileAttributes attributes = Files.readAttributes(entry,
          BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        rejectSymbolicLink(entry, attributes);
        uninitialized = false;
      }
      return uninitialized;
    }
  }

  private IOException unidentifiedCache() {
    return new IOException("tile cache " + root + " has no valid schema 2 archive ID;"
      + " use an empty cache directory or delete this one");
  }

  private long cleanTemporaryFilesAndCountTiles() throws IOException {
    CacheStartupVisitor visitor = new CacheStartupVisitor(root);
    Files.walkFileTree(root, visitor);
    return visitor.tileBytes;
  }

  private synchronized void disableWrites() {
    writeEnabled = false;
    if (!warned) {
      warned = true;
      System.err.println("mapterhorn: tile cache budget of " + maxBytes
        + " bytes reached; existing entries remain readable, new writes are disabled");
    }
  }

  private synchronized void beginUse() {
    if (closed) {
      throw new IllegalStateException("tile cache is closed: " + root);
    }
    activeUses++;
  }

  private synchronized void endUse() {
    activeUses--;
    if (activeUses == 0) {
      notifyAll();
    }
  }

  private static Path prepareRoot(Path requested) throws IOException {
    BasicFileAttributes attributes = attributesIfPresent(requested);
    if (attributes == null) {
      try {
        Files.createDirectory(requested);
      } catch (FileAlreadyExistsException e) {
        // Another process cannot hold this cache's sibling lock, but validate races.
      }
      attributes = attributesIfPresent(requested);
    }
    if (attributes == null) {
      throw new IOException("cannot create tile cache directory " + requested);
    }
    rejectSymbolicLink(requested, attributes);
    requireDirectory(requested, attributes, "tile cache root");

    Path realRoot = requested.toRealPath();
    BasicFileAttributes realAttributes = Files.readAttributes(realRoot,
      BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    rejectSymbolicLink(realRoot, realAttributes);
    requireDirectory(realRoot, realAttributes, "tile cache root");
    return realRoot;
  }

  private static boolean requireDirectoryIfPresent(Path path) throws IOException {
    BasicFileAttributes attributes = attributesIfPresent(path);
    if (attributes == null) {
      return false;
    }
    rejectSymbolicLink(path, attributes);
    requireDirectory(path, attributes, "tile cache directory");
    return true;
  }

  private static void ensureDirectory(Path path, String description) throws IOException {
    BasicFileAttributes attributes = attributesIfPresent(path);
    if (attributes == null) {
      try {
        Files.createDirectory(path);
      } catch (FileAlreadyExistsException e) {
        // A concurrent cache worker may have created it. Validate that entry below.
      }
      attributes = attributesIfPresent(path);
    }
    if (attributes == null) {
      throw new IOException("cannot create " + description + ": " + path);
    }
    rejectSymbolicLink(path, attributes);
    requireDirectory(path, attributes, description);
  }

  private static void writeNewRegularFile(Path path, byte[] bytes) throws IOException {
    BasicFileAttributes attributes = attributesIfPresent(path);
    if (attributes != null) {
      rejectSymbolicLink(path, attributes);
      throw new IOException("tile cache temporary file already exists: " + path);
    }
    try (OutputStream out = Channels.newOutputStream(Files.newByteChannel(path,
        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
        LinkOption.NOFOLLOW_LINKS))) {
      out.write(bytes);
    }
  }

  private static void deleteTemporaryIfPresent(Path path) throws IOException {
    BasicFileAttributes attributes = attributesIfPresent(path);
    if (attributes == null) {
      return;
    }
    rejectSymbolicLink(path, attributes);
    requireRegularFile(path, attributes, "tile cache temporary file");
    Files.delete(path);
  }

  private static BasicFileAttributes attributesIfPresent(Path path) throws IOException {
    try {
      return Files.readAttributes(path, BasicFileAttributes.class,
        LinkOption.NOFOLLOW_LINKS);
    } catch (NoSuchFileException e) {
      return null;
    }
  }

  private static void rejectSymbolicLink(Path path, BasicFileAttributes attributes)
    throws IOException {
    if (attributes.isSymbolicLink()) {
      throw new IOException("symbolic link is not allowed in tile cache: " + path);
    }
  }

  private static void requireDirectory(Path path, BasicFileAttributes attributes,
                                       String description) throws IOException {
    if (!attributes.isDirectory()) {
      throw new IOException(description + " is not a directory: " + path);
    }
  }

  private static void requireRegularFile(Path path, BasicFileAttributes attributes,
                                         String description) throws IOException {
    rejectSymbolicLink(path, attributes);
    if (!attributes.isRegularFile()) {
      throw new IOException(description + " is not a regular file: " + path);
    }
  }

  private static boolean isGeneratedTemporary(Path root, Path path) {
    Path relative = root.relativize(path);
    String name = path.getFileName().toString();
    if (relative.getNameCount() == 1) {
      return ID_TEMPORARY.matcher(name).matches();
    }
    return relative.getNameCount() == 3
      && TILES_DIRECTORY.equals(relative.getName(0).toString())
      && SHARD_DIRECTORY.matcher(relative.getName(1).toString()).matches()
      && TILE_TEMPORARY.matcher(name).matches();
  }

  private static void atomicMove(Path from, Path to) throws IOException {
    Files.move(from, to, StandardCopyOption.ATOMIC_MOVE,
      StandardCopyOption.REPLACE_EXISTING);
  }

  private static void ignoreRootLock(Path path) {
    // Production lock acquisition needs no callback.
  }

  private static final class CacheLocation {
    private final Path cacheRoot;
    private final Path lockFile;

    private CacheLocation(Path cacheRoot, Path lockFile) {
      this.cacheRoot = cacheRoot;
      this.lockFile = lockFile;
    }

    static CacheLocation resolve(File root) throws IOException {
      Path requestedRoot = root.toPath().toAbsolutePath().normalize();
      Path requestedParent = requestedRoot.getParent();
      Path rootName = requestedRoot.getFileName();
      if (requestedParent == null || rootName == null) {
        throw new IOException("a filesystem root cannot be used as a tile cache: "
          + requestedRoot);
      }
      Files.createDirectories(requestedParent);
      Path realParent = requestedParent.toRealPath();
      BasicFileAttributes parentAttributes = Files.readAttributes(realParent,
        BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      rejectSymbolicLink(realParent, parentAttributes);
      requireDirectory(realParent, parentAttributes, "tile cache parent");

      Path cacheRoot = realParent.resolve(rootName.toString()).normalize();
      Path lockFile = realParent.resolve("." + rootName + LOCK_SUFFIX).normalize();
      return new CacheLocation(cacheRoot, lockFile);
    }
  }

  private static final class CacheStartupVisitor extends SimpleFileVisitor<Path> {
    private final Path root;
    private long tileBytes;

    CacheStartupVisitor(Path root) {
      this.root = root;
    }

    @Override
    public FileVisitResult preVisitDirectory(Path directory,
                                             BasicFileAttributes attributes)
      throws IOException {
      rejectSymbolicLink(directory, attributes);
      requireDirectory(directory, attributes, "tile cache entry");
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
      throws IOException {
      rejectSymbolicLink(file, attributes);
      requireRegularFile(file, attributes, "tile cache entry");
      if (isGeneratedTemporary(root, file)) {
        Files.delete(file);
      } else if (file.getFileName().toString().endsWith(".tile")) {
        try {
          tileBytes = Math.addExact(tileBytes, attributes.size());
        } catch (ArithmeticException e) {
          throw new IOException("tile cache size exceeds the supported range", e);
        }
      }
      return FileVisitResult.CONTINUE;
    }
  }

  private static final class CacheRootLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;
    private boolean closed;

    private CacheRootLock(FileChannel channel, FileLock lock) {
      this.channel = channel;
      this.lock = lock;
    }

    static CacheRootLock acquire(Path root, Path lockPath,
                                 RootLockOperation rootLockOperation) throws IOException {
      BasicFileAttributes attributes = attributesIfPresent(lockPath);
      if (attributes != null) {
        requireRegularFile(lockPath, attributes, "tile cache sibling lock file");
      }

      FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
      try {
        attributes = Files.readAttributes(lockPath,
          BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        requireRegularFile(lockPath, attributes, "tile cache sibling lock file");
        FileLock lock;
        try {
          lock = channel.tryLock();
        } catch (OverlappingFileLockException e) {
          throw locked(root, e);
        }
        if (lock == null) {
          throw locked(root, null);
        }
        rootLockOperation.afterLock(lockPath);
        return new CacheRootLock(channel, lock);
      } catch (IOException | RuntimeException | Error e) {
        try {
          channel.close();
        } catch (IOException closeFailure) {
          e.addSuppressed(closeFailure);
        }
        throw e;
      }
    }

    private static IOException locked(Path root, Throwable cause) {
      return new IOException("tile cache " + root
        + " is already in use by another process or cache instance", cause);
    }

    @Override
    public synchronized void close() throws IOException {
      if (closed) {
        return;
      }
      closed = true;
      IOException failure = null;
      try {
        lock.release();
      } catch (IOException e) {
        failure = e;
      }
      try {
        channel.close();
      } catch (IOException e) {
        if (failure == null) {
          failure = e;
        } else {
          failure.addSuppressed(e);
        }
      }
      if (failure != null) {
        throw failure;
      }
    }
  }
}
