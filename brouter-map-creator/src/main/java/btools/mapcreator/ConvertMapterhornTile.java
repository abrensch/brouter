package btools.mapcreator;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Builds BRouter elevation rasters (.bef) from a Mapterhorn PMTiles archive of
 * Terrarium-encoded terrain tiles.
 * <p>
 * The output is byte-compatible with what {@link ElevationRasterTileConverter} produces
 * from hgt input (the shared conventions live in {@link ElevationCells}): a 5x5 degree
 * lat/lon grid of 5*rowLength+1 samples per axis, holding elevations in whole metres,
 * north row first. PosUnifier therefore needs no changes.
 * <p>
 * <b>Resampling.</b> Mapterhorn tiles live in Web Mercator, so their pixels are square on
 * the ground and finer than the target grid: at zoom 12 a pixel is 12.9 m at 47.5 deg N,
 * while a 1 arcsecond cell there spans 30.9 m in latitude by 20.9 m in longitude. Each
 * output cell therefore covers roughly 2.4 x 1.6 source pixels. Point-sampling that would
 * alias, and the aliasing lands directly in total-ascent, which BRouter sums over every
 * segment. Instead each output cell takes the area-weighted mean of the source pixels its
 * footprint overlaps -- a separable box filter, since the source grid is regular in
 * (x, y) and the footprint is an axis-aligned rectangle in the same space.
 * <p>
 * Two documented assumptions of that filter: source pixel i is treated as covering
 * mercator [i, i+1) with its value at the centre, the standard XYZ raster-tile
 * convention (even if a source deviated by half a pixel, a uniform ~6 m horizontal shift
 * leaves path ascent nearly unchanged); and water is VALID data in Mapterhorn (lake and
 * sea surfaces carry their elevation), so a shoreline cell averages land with water --
 * the value is low by up to the local relief across that one cell, which is inherent to
 * any raster DEM at this resolution.
 * <p>
 * <b>Integrity.</b> Output and cache files are written to a temp name and atomically
 * renamed, each written .bef is decoded back and compared pixel-for-pixel before it
 * replaces anything, and a tile cache directory is bound to the archive it was filled
 * from. A -bbox run refuses to overwrite an existing (complete) .bef.
 * <p>
 * Usage:
 * <pre>
 * ConvertMapterhornTile &lt;pmtiles&gt; &lt;out-dir&gt; &lt;cell&gt; [options]
 *   &lt;pmtiles&gt;  local archive path, or an https:// URL read with range requests
 *   &lt;cell&gt;     'srtm_38_03' or 'srtm_E10_N45', or '&lt;lon&gt;,&lt;lat&gt;' of the
 *              south-west corner, or 'all' (planet; skips existing .bef files and
 *              continues past failed cells, exiting non-zero at the end)
 * options:
 *   -arcsec &lt;1|3&gt;  output resolution, default 3
 *   -zoom &lt;z&gt;      source zoom, default 12 for 1", 11 for 3"
 *   -cache &lt;dir&gt;   on-disk raw tile cache, strongly recommended; bound to the archive
 *   -threads &lt;n&gt;   concurrent tile fetches, default 8
 *   -bbox &lt;minLon,minLat,maxLon,maxLat&gt;  only fill this window, rest stays no-data
 * </pre>
 * Example:
 * <pre>
 * java -Xmx3g ... ConvertMapterhornTile https://download.mapterhorn.com/planet.pmtiles \
 *     ./bef srtm_38_03 -arcsec 1 -cache /var/tmp/mapterhorn
 * </pre>
 * At 1 arcsecond a cell raster is 648 MB and the read-back verification transiently
 * holds a second copy, so run with at least -Xmx2g (3 arcsecond cells need far less).
 */
public class ConvertMapterhornTile implements AutoCloseable {

  private static final double MAX_MERCATOR_LAT = 85.0511287798066;

  /** Fraction of an output cell that must be covered by valid source pixels. */
  private static final double MIN_COVERAGE = 0.5;

  /** Keep clear of the coder's sentinels: -32768 no-data, -32766 border skip. */
  private static final short MAX_ELEVATION = 32000;
  private static final short MIN_ELEVATION = -32000;

  private static final short NODATA = ElevationCells.NODATA;

  /** Mapterhorn ships 512 pixel tiles; a mismatch is caught on the first decode. */
  static final int TILE_SIZE = 512;

  /** Concurrent tile fetches. Kept modest: this is somebody else's public CDN. */
  static final int FETCH_THREADS = 8;

  /** Upper bound for one coalesced range read of contiguous tile bodies. */
  private static final int MAX_RUN_BYTES = 64 * 1024 * 1024;

  /** Upper bound for the decoded float stripe held for one source tile row. */
  private static final long MAX_STRIPE_BYTES = 512L * 1024L * 1024L;

  /** Upper bound for source pixels visited by one conversion. */
  private static final long MAX_SOURCE_PIXELS = 8_000_000_000L;

  private static final long GIBIBYTE = 1024L * 1024L * 1024L;

  static final long DEFAULT_CACHE_MAX_BYTES = 10L * GIBIBYTE;

  static final String OUTPUT_LOCK_SUFFIX = ".mapterhorn-output.lock";

  private final PmTilesArchive archive;
  private final int zoom;
  private final MapterhornTileCache cache;
  private final boolean closeCache;

  private final int tileSize;
  private final long worldPx;

  // the horizontal footprint of each output column, as a CSR-style sparse matrix
  private int[] colOffset;
  private long[] colSrcX;
  private double[] colWeight;
  private double[] colFootprint;

  // one decoded row of source tiles, held as tileSize rows of stripeWidth pixels
  private float[] stripe;
  private long stripeX0;
  private int stripeWidth;
  private int stripeTileY = Integer.MIN_VALUE;

  // memo of the most recently reduced source row: source rows are consumed in
  // monotonically increasing order and only the boundary row is shared between two
  // adjacent output rows, so one slot is provably enough
  private long reducedJ = Long.MIN_VALUE;
  private double[] reducedSumRow;
  private double[] reducedWeightRow;

  private final AtomicInteger tilesFound = new AtomicInteger();
  private final AtomicInteger tilesMissing = new AtomicInteger();

  private final ExecutorService pool;

  ConvertMapterhornTile(PmTilesArchive archive, int zoom, File cacheDir, int tileSize) throws IOException {
    this(archive, zoom, cacheDir == null ? null : new MapterhornTileCache(
      cacheDir, archive.archiveId(), DEFAULT_CACHE_MAX_BYTES), tileSize, FETCH_THREADS,
      cacheDir != null);
  }

  ConvertMapterhornTile(PmTilesArchive archive, int zoom, MapterhornTileCache cache,
                       int tileSize, int threads) throws IOException {
    this(archive, zoom, cache, tileSize, threads, false);
  }

  private ConvertMapterhornTile(PmTilesArchive archive, int zoom, MapterhornTileCache cache,
                                int tileSize, int threads, boolean closeCache)
    throws IOException {
    this.archive = archive;
    this.zoom = zoom;
    this.cache = cache;
    this.closeCache = closeCache;
    this.tileSize = tileSize;
    long computedWorldPx;
    ExecutorService createdPool;
    try {
      if (zoom < 0 || zoom > 30) {
        throw new IOException("zoom " + zoom + " is outside the supported range 0..30");
      }
      if (tileSize <= 0) {
        throw new IOException("tile size must be positive: " + tileSize);
      }
      try {
        computedWorldPx = Math.multiplyExact((long) tileSize, 1L << zoom);
      } catch (ArithmeticException e) {
        throw new IOException("Mapterhorn world pixel size is too large", e);
      }
      createdPool = Executors.newFixedThreadPool(threads, r -> {
        Thread t = new Thread(r, "mapterhorn-fetch");
        t.setDaemon(true);
        return t;
      });
    } catch (IOException | RuntimeException | Error e) {
      if (closeCache && cache != null) {
        try {
          cache.close();
        } catch (IOException closeFailure) {
          e.addSuppressed(closeFailure);
        }
      }
      throw e;
    }
    this.worldPx = computedWorldPx;
    this.pool = createdPool;
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 3) {
      printUsage();
      return;
    }
    String source = args[0];
    File outDir = new File(args[1]);
    String cell = args[2];

    int arcsec = 3;
    int zoom = -1;
    int threads = FETCH_THREADS;
    File cacheDir = null;
    long cacheMaxBytes = DEFAULT_CACHE_MAX_BYTES;
    double[] bbox = null;

    for (int i = 3; i < args.length; i++) {
      String opt = args[i];
      if (i + 1 >= args.length) {
        throw new IllegalArgumentException("missing value for " + opt);
      }
      String val = args[++i];
      switch (opt) {
        case "-arcsec":
          arcsec = Integer.parseInt(val);
          if (arcsec != 1 && arcsec != 3) {
            throw new IllegalArgumentException("-arcsec must be 1 or 3");
          }
          break;
        case "-zoom":
          zoom = Integer.parseInt(val);
          break;
        case "-threads":
          threads = Integer.parseInt(val);
          if (threads < 1) {
            throw new IllegalArgumentException("-threads must be at least 1");
          }
          break;
        case "-cache":
          cacheDir = new File(val);
          break;
        case "-cache-max-gb":
          cacheMaxBytes = parseCacheMaxBytes(val);
          break;
        case "-bbox":
          bbox = parseBbox(val);
          break;
        default:
          throw new IllegalArgumentException("unknown option " + opt);
      }
    }

    int rowLength = arcsec == 1 ? ElevationCells.SRTM1_ROW_LENGTH : ElevationCells.SRTM3_ROW_LENGTH;

    int exitStatus = 0;
    try (OutputDirectoryLock outputLock = OutputDirectoryLock.acquire(outDir);
         PmTilesArchive archive = PmTilesArchive.open(source);
         MapterhornTileCache cache = cacheDir == null ? null
           : new MapterhornTileCache(cacheDir, archive.archiveId(), cacheMaxBytes)) {
      File lockedOutDir = outputLock.directory();
      if (archive.tileType() == PmTilesArchive.TILETYPE_WEBP) {
        TerrariumTileDecoder.requireWebPSupport();
      }
      zoom = selectZoom(archive, zoom, arcsec);
      System.out.println("mapterhorn: zoom=" + zoom + " arcsec=" + arcsec
        + " tileType=" + archive.tileType() + " bounds=" + archive.minLon() + ","
        + archive.minLat() + " .. " + archive.maxLon() + "," + archive.maxLat());

      if ("all".equals(cell)) {
        // Validate every selected target before any cell is changed.
        int failed = 0;
        int rimSkipped = 0;
        List<int[]> selectedCorners = new ArrayList<>();
        for (int[] corner : ElevationCells.worldCellCorners()) {
          if (bbox != null && !(bbox[0] <= corner[0] && bbox[2] >= corner[0] + 5
            && bbox[1] <= corner[1] && bbox[3] >= corner[1] + 5)) {
            // in bulk mode only FULLY contained cells are converted: a rim cell would
            // be written windowed (mostly no-data) under its full-cell name, and
            // skip-existing would then keep that partial file forever
            if (bbox[0] < corner[0] + 5 && bbox[2] > corner[0]
              && bbox[1] < corner[1] + 5 && bbox[3] > corner[1]) {
              rimSkipped++;
            }
            continue;
          }
          selectedCorners.add(corner);
        }
        Map<File, MapterhornOutputMetadata.Config> targets = new LinkedHashMap<>();
        for (int[] corner : selectedCorners) {
          File output = outputFile(lockedOutDir, corner[0], corner[1]);
          targets.put(output, outputConfig(archive, zoom, rowLength, TILE_SIZE,
            null, corner[0], corner[1]));
        }
        MapterhornOutputMetadata.preflightAll(targets);

        // A failed conversion remains pending and does not stop later cells.
        for (int[] corner : selectedCorners) {
          try {
            convertOneLocked(archive, zoom, cache, outputLock, corner[0], corner[1],
              rowLength, null, threads, true, TILE_SIZE);
          } catch (IOException | RuntimeException e) {
            failed++;
            System.err.println("cell " + corner[0] + "," + corner[1] + " FAILED: " + e);
          }
        }
        if (rimSkipped > 0) {
          System.out.println(rimSkipped + " cells only partially inside -bbox were skipped;"
            + " convert them individually for windowed output");
        }
        if (failed > 0) {
          System.err.println(failed + " cells failed; rerun to retry them");
          exitStatus = 1;
        }
      } else {
        int[] corner = parseCell(cell);
        convertOneLocked(archive, zoom, cache, outputLock, corner[0], corner[1],
          rowLength, bbox, threads, false, TILE_SIZE);
      }
    }
    if (exitStatus != 0) {
      System.exit(exitStatus);
    }
  }

  private static void printUsage() {
    System.out.println("usage: ConvertMapterhornTile <pmtiles> <out-dir> <cell> [options]");
    System.out.println("  <pmtiles>  local archive path, or https:// URL (range requests)");
    System.out.println("  <cell>     'srtm_38_03' | 'srtm_E10_N45' | '<lon>,<lat>' south-west corner | 'all'");
    System.out.println("options:");
    System.out.println("  -arcsec <1|3>  output resolution (default 3)");
    System.out.println("  -zoom <z>      source zoom (default 12 for 1\", 11 for 3\")");
    System.out.println("  -cache <dir>   on-disk raw tile cache (bound to the source archive)");
    System.out.println("  -cache-max-gb <n>  positive integer cache budget (default 10)");
    System.out.println("  -threads <n>   concurrent tile fetches (default " + FETCH_THREADS + ")");
    System.out.println("  -bbox <minLon,minLat,maxLon,maxLat>  only fill this window");
  }

  static long parseCacheMaxBytes(String value) {
    long gibibytes;
    try {
      gibibytes = Long.parseLong(value);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("-cache-max-gb must be a positive integer", e);
    }
    if (gibibytes <= 0L) {
      throw new IllegalArgumentException("-cache-max-gb must be a positive integer");
    }
    try {
      return Math.multiplyExact(gibibytes, GIBIBYTE);
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException("-cache-max-gb is too large", e);
    }
  }

  static void convertOne(PmTilesArchive archive, int zoom, MapterhornTileCache cache, File outDir,
                         int lonStart, int latStart, int rowLength, double[] bbox,
                         int threads, boolean skipExisting)
    throws IOException {
    convertOne(archive, zoom, cache, outDir, lonStart, latStart, rowLength, bbox,
      threads, skipExisting, TILE_SIZE);
  }

  static void convertOne(PmTilesArchive archive, int zoom, MapterhornTileCache cache, File outDir,
                         int lonStart, int latStart, int rowLength, double[] bbox,
                         int threads, boolean skipExisting, int tileSize)
    throws IOException {
    try (OutputDirectoryLock outputLock = OutputDirectoryLock.acquire(outDir)) {
      convertOneLocked(archive, zoom, cache, outputLock, lonStart, latStart,
        rowLength, bbox, threads, skipExisting, tileSize);
    }
  }

  static void convertOneLocked(PmTilesArchive archive, int zoom, MapterhornTileCache cache,
                               OutputDirectoryLock outputLock, int lonStart, int latStart,
                               int rowLength, double[] bbox, int threads,
                               boolean skipExisting, int tileSize)
    throws IOException {
    File outDir = outputLock.directory();
    if (bbox != null && (bbox[0] >= lonStart + 5 || bbox[2] <= lonStart
      || bbox[1] >= latStart + 5 || bbox[3] <= latStart)) {
      return; // cell entirely outside the requested window
    }
    String name = PosUnifier.UseRasterRd5FileName
      ? ElevationRasterTileConverter.genFilenameRd5(lonStart, latStart)
      : ElevationRasterTileConverter.genFilenameOld(lonStart, latStart);
    File out = outputFile(outDir, lonStart, latStart);
    MapterhornOutputMetadata.Config config = outputConfig(
      archive, zoom, rowLength, tileSize, bbox, lonStart, latStart);
    boolean explicitFullOverwrite = !skipExisting && bbox == null;
    boolean ownedBeforePrepare = MapterhornOutputMetadata.isOwned(out, config);

    // Do not claim an unknown file until the immutable archive proves this cell has
    // source data. Otherwise a no-source explicit overwrite could delete user data.
    if (explicitFullOverwrite && !ownedBeforePrepare) {
      try (ConvertMapterhornTile probe =
             new ConvertMapterhornTile(archive, zoom, cache, tileSize, threads)) {
        if (!probe.cellHasAnyTile(lonStart, latStart, rowLength, bbox)) {
          System.out.println("  no source tiles for " + name + ", skipped");
          return;
        }
      }
    }

    MapterhornOutputMetadata.Decision decision = MapterhornOutputMetadata.prepare(
      out, config, explicitFullOverwrite);
    if (decision == MapterhornOutputMetadata.Decision.SKIP) {
      System.out.println("  " + name + " exists with matching provenance, skipped");
      return;
    }

    try (ConvertMapterhornTile converter =
           new ConvertMapterhornTile(archive, zoom, cache, tileSize, threads)) {
      if (!converter.cellHasAnyTile(lonStart, latStart, rowLength, bbox)) {
        MapterhornOutputMetadata.abandon(out, config, ownedBeforePrepare);
        System.out.println("  no source tiles for " + name + ", skipped");
        return;
      }
      ElevationRaster raster = converter.buildRaster(lonStart, latStart, rowLength, bbox);
      if (raster == null) {
        MapterhornOutputMetadata.abandon(out, config, ownedBeforePrepare);
        System.out.println("  no source tiles for " + name + ", skipped");
        return;
      }
      MapterhornOutputMetadata.CompletedFile completed = writeVerified(raster, out);
      MapterhornOutputMetadata.complete(out, config, completed);
      System.out.println("  wrote " + out + " (" + converter.tilesFound.get() + " tiles read, "
        + converter.tilesMissing.get() + " missing)");
    }
  }

  /**
   * Encode to a temp file, decode it back and compare every pixel, then atomically move
   * it into place. A crash mid-write leaves only a temp file; a coder or disk fault is
   * caught before the file can be consumed.
   */
  static MapterhornOutputMetadata.CompletedFile writeVerified(ElevationRaster raster,
                                                               File out) throws IOException {
    return writeVerified(raster, out,
      input -> new ElevationRasterCoder().decodeRaster(input),
      ConvertMapterhornTile::atomicMove);
  }

  @FunctionalInterface
  interface RasterDecodeOperation {
    ElevationRaster decode(InputStream input) throws IOException;
  }

  static MapterhornOutputMetadata.CompletedFile writeVerified(
      ElevationRaster raster, File out, RasterDecodeOperation decodeOperation,
      MapterhornOutputMetadata.AtomicMoveOperation atomicMoveOperation) throws IOException {
    if (decodeOperation == null) {
      throw new IllegalArgumentException("raster decode operation is required");
    }
    if (atomicMoveOperation == null) {
      throw new IllegalArgumentException("atomic move operation is required");
    }
    Path outputPath = out.toPath();
    requireRegularOrAbsentOutput(outputPath);
    Path parent = outputPath.getParent() == null ? Path.of(".") : outputPath.getParent();
    Path tmp = Files.createTempFile(parent, out.getName() + ".tmp-", null);
    try {
      try (OutputStream os = new BufferedOutputStream(Channels.newOutputStream(
          Files.newByteChannel(tmp, StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)))) {
        new ElevationRasterCoder().encodeRaster(raster, os);
      }
      ElevationRaster decoded;
      MessageDigest digest = sha256Digest();
      try (InputStream fileInput = new BufferedInputStream(Channels.newInputStream(
          Files.newByteChannel(tmp, StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS)));
           DigestInputStream digestInput = new DigestInputStream(fileInput, digest)) {
        decoded = decodeOperation.decode(digestInput);
        byte[] drain = new byte[8192];
        while (digestInput.read(drain) >= 0) {
          // The decoder may stop before EOF; provenance covers every encoded byte.
        }
      }
      verifyReadBack(raster, decoded, out);
      requireRegularOrAbsentOutput(outputPath);
      atomicMoveOperation.move(tmp, outputPath);
      BasicFileAttributes published = Files.readAttributes(outputPath,
        BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (published.isSymbolicLink() || !published.isRegularFile()) {
        throw new IOException("published Mapterhorn output is not a regular file: " + out);
      }
      return new MapterhornOutputMetadata.CompletedFile(published.size(),
        published.lastModifiedTime().toMillis(),
        MapterhornOutputMetadata.hex(digest.digest()));
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  static void verifyReadBack(ElevationRaster expected, ElevationRaster actual, File out)
      throws IOException {
    if (actual == null || actual.ncols != expected.ncols || actual.nrows != expected.nrows
      || actual.halfcol != expected.halfcol
      || Double.doubleToLongBits(actual.xllcorner)
        != Double.doubleToLongBits(expected.xllcorner)
      || Double.doubleToLongBits(actual.yllcorner)
        != Double.doubleToLongBits(expected.yllcorner)
      || Double.doubleToLongBits(actual.cellsize)
        != Double.doubleToLongBits(expected.cellsize)
      || actual.noDataValue != expected.noDataValue) {
      throw new IOException("read-back mismatch on " + out + ": raster header differs");
    }
    if (actual.eval_array == null || expected.eval_array == null
      || actual.eval_array.length != expected.eval_array.length) {
      throw new IOException("read-back mismatch on " + out + ": pixel count differs");
    }
    for (int i = 0; i < expected.eval_array.length; i++) {
      if (actual.eval_array[i] != expected.eval_array[i]) {
        throw new IOException("read-back mismatch on " + out + " at pixel " + i
          + ": wrote " + expected.eval_array[i] + ", read " + actual.eval_array[i]);
      }
    }
  }

  static void atomicMove(Path from, Path to) throws IOException {
    Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
  }

  private static void requireRegularOrAbsentOutput(Path output) throws IOException {
    BasicFileAttributes attributes;
    try {
      attributes = Files.readAttributes(output, BasicFileAttributes.class,
        LinkOption.NOFOLLOW_LINKS);
    } catch (NoSuchFileException e) {
      return;
    }
    if (attributes.isSymbolicLink()) {
      throw new IOException("refusing symbolic link Mapterhorn output entry: " + output);
    }
    if (!attributes.isRegularFile()) {
      throw new IOException("Mapterhorn output is not a regular file: " + output);
    }
  }

  private static MessageDigest sha256Digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("SHA-256 is required by the Java runtime", e);
    }
  }

  private static File outputFile(File outDir, int lonStart, int latStart) {
    String name = PosUnifier.UseRasterRd5FileName
      ? ElevationRasterTileConverter.genFilenameRd5(lonStart, latStart)
      : ElevationRasterTileConverter.genFilenameOld(lonStart, latStart);
    return new File(outDir, name);
  }

  private static MapterhornOutputMetadata.Config outputConfig(
      PmTilesArchive archive, int zoom, int rowLength, int tileSize, double[] bbox,
      int lonStart, int latStart) {
    String naming = PosUnifier.UseRasterRd5FileName ? "rd5" : "legacy";
    String coverage = bbox == null ? "full" : "bbox="
      + Double.toString(bbox[0]) + "," + Double.toString(bbox[1]) + ","
      + Double.toString(bbox[2]) + "," + Double.toString(bbox[3]);
    return new MapterhornOutputMetadata.Config(archive.archiveId(), zoom, rowLength,
      tileSize, naming, coverage, lonStart, latStart);
  }

  static final class OutputDirectoryLock implements AutoCloseable {
    private final Path outputDirectory;
    private final FileChannel channel;
    private final FileLock lock;
    private boolean closed;

    private OutputDirectoryLock(Path outputDirectory, FileChannel channel, FileLock lock) {
      this.outputDirectory = outputDirectory;
      this.channel = channel;
      this.lock = lock;
    }

    static OutputDirectoryLock acquire(File requestedDirectory) throws IOException {
      OutputLocation location = OutputLocation.resolve(requestedDirectory);
      BasicFileAttributes lockAttributes = attributesIfPresent(location.lockFile);
      if (lockAttributes != null) {
        requireRegularFile(location.lockFile, lockAttributes,
          "Mapterhorn output sibling lock file");
      }

      FileChannel channel = FileChannel.open(location.lockFile,
        StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
      try {
        lockAttributes = Files.readAttributes(location.lockFile,
          BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        requireRegularFile(location.lockFile, lockAttributes,
          "Mapterhorn output sibling lock file");
        FileLock lock;
        try {
          lock = channel.tryLock();
        } catch (OverlappingFileLockException e) {
          throw alreadyLocked(location.outputDirectory, e);
        }
        if (lock == null) {
          throw alreadyLocked(location.outputDirectory, null);
        }
        Path preparedDirectory = prepareDirectory(location.outputDirectory);
        return new OutputDirectoryLock(preparedDirectory, channel, lock);
      } catch (IOException | RuntimeException | Error e) {
        try {
          channel.close();
        } catch (IOException closeFailure) {
          e.addSuppressed(closeFailure);
        }
        throw e;
      }
    }

    File directory() {
      if (closed) {
        throw new IllegalStateException("Mapterhorn output-directory lock is closed");
      }
      return outputDirectory.toFile();
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

    private static Path prepareDirectory(Path directory) throws IOException {
      BasicFileAttributes attributes = attributesIfPresent(directory);
      if (attributes == null) {
        try {
          Files.createDirectory(directory);
        } catch (FileAlreadyExistsException e) {
          // Validate a concurrent filesystem change below while holding our lock.
        }
        attributes = attributesIfPresent(directory);
      }
      if (attributes == null) {
        throw new IOException("cannot create Mapterhorn output directory " + directory);
      }
      rejectSymbolicLink(directory, attributes);
      if (!attributes.isDirectory()) {
        throw new IOException("Mapterhorn output path is not a directory: " + directory);
      }
      return directory.toRealPath();
    }

    private static BasicFileAttributes attributesIfPresent(Path path) throws IOException {
      try {
        return Files.readAttributes(path, BasicFileAttributes.class,
          LinkOption.NOFOLLOW_LINKS);
      } catch (NoSuchFileException e) {
        return null;
      }
    }

    private static void requireRegularFile(Path path, BasicFileAttributes attributes,
                                           String description) throws IOException {
      rejectSymbolicLink(path, attributes);
      if (!attributes.isRegularFile()) {
        throw new IOException(description + " is not a regular file: " + path);
      }
    }

    private static void rejectSymbolicLink(Path path, BasicFileAttributes attributes)
        throws IOException {
      if (attributes.isSymbolicLink()) {
        throw new IOException("refusing symbolic link Mapterhorn output entry: " + path);
      }
    }

    private static IOException alreadyLocked(Path directory, Throwable cause) {
      return new IOException("Mapterhorn output directory " + directory
        + " is already in use by another process or converter instance", cause);
    }
  }

  private static final class OutputLocation {
    final Path outputDirectory;
    final Path lockFile;

    OutputLocation(Path outputDirectory, Path lockFile) {
      this.outputDirectory = outputDirectory;
      this.lockFile = lockFile;
    }

    static OutputLocation resolve(File requestedDirectory) throws IOException {
      if (requestedDirectory == null) {
        throw new IllegalArgumentException("output directory is required");
      }
      Path requested = requestedDirectory.toPath().toAbsolutePath().normalize();
      Path requestedParent = requested.getParent();
      Path directoryName = requested.getFileName();
      if (requestedParent == null || directoryName == null) {
        throw new IOException("a filesystem root cannot be used as Mapterhorn output: "
          + requested);
      }
      Files.createDirectories(requestedParent);
      Path realParent = requestedParent.toRealPath();
      BasicFileAttributes parentAttributes = Files.readAttributes(realParent,
        BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (parentAttributes.isSymbolicLink() || !parentAttributes.isDirectory()) {
        throw new IOException("Mapterhorn output parent is not a real directory: "
          + realParent);
      }
      Path outputDirectory = realParent.resolve(directoryName.toString()).normalize();
      Path lockFile = realParent.resolve("." + directoryName + OUTPUT_LOCK_SUFFIX).normalize();
      return new OutputLocation(outputDirectory, lockFile);
    }
  }

  static double[] parseBbox(String s) {
    String[] p = s.split(",");
    if (p.length != 4) {
      throw new IllegalArgumentException("-bbox needs minLon,minLat,maxLon,maxLat");
    }
    double[] b = new double[4];
    for (int i = 0; i < 4; i++) {
      try {
        b[i] = Double.parseDouble(p[i].trim());
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("-bbox values must be numbers", e);
      }
      if (!Double.isFinite(b[i])) {
        throw new IllegalArgumentException("-bbox values must be finite");
      }
    }
    if (b[0] < -180.0 || b[0] > 180.0 || b[2] < -180.0 || b[2] > 180.0
        || b[1] < -90.0 || b[1] > 90.0 || b[3] < -90.0 || b[3] > 90.0) {
      throw new IllegalArgumentException("-bbox longitude must be within -180..180 and latitude"
        + " within -90..90");
    }
    if (b[0] >= b[2] || b[1] >= b[3]) {
      throw new IllegalArgumentException("-bbox min must be below max");
    }
    return b;
  }

  static int selectZoom(PmTilesArchive archive, int requested, int arcsec) {
    int selected = requested < 0 ? (arcsec == 1 ? 12 : 11) : requested;
    if (requested < 0) {
      selected = Math.max(archive.minZoom(), Math.min(archive.maxZoom(), selected));
    }
    if (selected < archive.minZoom() || selected > archive.maxZoom() || selected > 30) {
      throw new IllegalArgumentException("zoom " + selected + " outside archive range "
        + archive.minZoom() + ".." + archive.maxZoom());
    }
    return selected;
  }

  /**
   * Accepts a cell name in either naming scheme, or a plain '&lt;lon&gt;,&lt;lat&gt;'
   * corner. Malformed or out-of-range cells fail loudly instead of parsing to a
   * nonsense corner.
   *
   * @return {lonStart, latStart} of the south-west corner
   */
  static int[] parseCell(String cell) {
    if (cell.startsWith("srtm_")) {
      return ElevationCells.cornerFromCellName(cell);
    }
    String[] p = cell.split(",");
    if (p.length != 2) {
      throw new IllegalArgumentException("cell must be 'srtm_NN_NN', 'srtm_E10_N45' or '<lon>,<lat>'");
    }
    int lon = Integer.parseInt(p[0].trim());
    int lat = Integer.parseInt(p[1].trim());
    ElevationCells.requireCellCorner(lon, lat, cell);
    return new int[]{lon, lat};
  }

  // ---------------------------------------------------------------- raster

  /** Output indices and their effective source-sampling rectangle. */
  static final class SamplingWindow {
    final int rowFrom;
    final int rowTo;
    final int colFrom;
    final int colTo;
    final double minLon;
    final double minLat;
    final double maxLon;
    final double maxLat;
    final boolean empty;

    SamplingWindow(int rowFrom, int rowTo, int colFrom, int colTo,
                   double minLon, double minLat, double maxLon, double maxLat,
                   boolean empty) {
      this.rowFrom = rowFrom;
      this.rowTo = rowTo;
      this.colFrom = colFrom;
      this.colTo = colTo;
      this.minLon = minLon;
      this.minLat = minLat;
      this.maxLon = maxLon;
      this.maxLat = maxLat;
      this.empty = empty;
    }
  }

  SamplingWindow samplingWindow(int lonStart, int latStart, int rowLength, double[] bbox)
      throws IOException {
    if (rowLength <= 0) {
      throw new IOException("row length must be positive: " + rowLength);
    }
    int n = checkedInt(checkedAdd(checkedMultiply(5L, rowLength, "output row count"),
      1L, "output row count"), "output row count");
    double cellsize = 1.0 / rowLength;

    int rowFrom = 0;
    int rowTo = n - 1;
    int colFrom = 0;
    int colTo = n - 1;
    if (bbox != null) {
      if (bbox[0] >= lonStart + 5.0 || bbox[2] <= lonStart
        || bbox[1] >= latStart + 5.0 || bbox[3] <= latStart) {
        return emptySamplingWindow();
      }
      // imageRow 0 is the north edge, so a higher latitude means a lower row index
      rowFrom = clamp((int) Math.floor((latStart + 5.0 - bbox[3]) / cellsize), 0, n - 1);
      rowTo = clamp((int) Math.ceil((latStart + 5.0 - bbox[1]) / cellsize), 0, n - 1);
      colFrom = clamp((int) Math.floor((bbox[0] - lonStart) / cellsize), 0, n - 1);
      colTo = clamp((int) Math.ceil((bbox[2] - lonStart) / cellsize), 0, n - 1);
    }
    if (rowFrom > rowTo || colFrom > colTo) {
      return emptySamplingWindow();
    }

    double minLon = lonStart + colFrom * cellsize - 0.5 * cellsize;
    double maxLon = lonStart + colTo * cellsize + 0.5 * cellsize;
    double minLat = latStart + 5.0 - rowTo * cellsize - 0.5 * cellsize;
    double maxLat = latStart + 5.0 - rowFrom * cellsize + 0.5 * cellsize;

    double[] lonIntersection = intersectArchiveLongitude(minLon, maxLon);
    if (lonIntersection == null) {
      return emptySamplingWindow();
    }
    minLon = lonIntersection[0];
    maxLon = lonIntersection[1];
    minLat = Math.max(minLat, Math.max(archive.minLat(), -MAX_MERCATOR_LAT));
    maxLat = Math.min(maxLat, Math.min(archive.maxLat(), MAX_MERCATOR_LAT));
    if (minLon >= maxLon || minLat >= maxLat) {
      return emptySamplingWindow();
    }
    return new SamplingWindow(rowFrom, rowTo, colFrom, colTo,
      minLon, minLat, maxLon, maxLat, false);
  }

  private SamplingWindow emptySamplingWindow() {
    return new SamplingWindow(0, -1, 0, -1,
      Double.NaN, Double.NaN, Double.NaN, Double.NaN, true);
  }

  /**
   * Intersect longitude on a periodic world. Full-world archives keep the sampling
   * margin across +/-180; regional bounds are shifted by one world when the cell is on
   * the opposite side of the antimeridian.
   */
  private double[] intersectArchiveLongitude(double minLon, double maxLon) {
    double archiveMin = archive.minLon();
    double archiveMax = archive.maxLon();
    if (archiveMax - archiveMin >= 360.0 - 1e-7) {
      return new double[]{minLon, maxLon};
    }

    double requestedCentre = 0.5 * (minLon + maxLon);
    double archiveCentre = 0.5 * (archiveMin + archiveMax);
    int nearestShift = (int) Math.rint((requestedCentre - archiveCentre) / 360.0);
    double bestWidth = 0.0;
    double bestMin = 0.0;
    double bestMax = 0.0;
    for (int delta = -1; delta <= 1; delta++) {
      double shift = (nearestShift + delta) * 360.0;
      double overlapMin = Math.max(minLon, archiveMin + shift);
      double overlapMax = Math.min(maxLon, archiveMax + shift);
      double width = overlapMax - overlapMin;
      if (width > bestWidth) {
        bestWidth = width;
        bestMin = overlapMin;
        bestMax = overlapMax;
      }
    }
    return bestWidth > 0.0 ? new double[]{bestMin, bestMax} : null;
  }

  /**
   * Cheap directory-only probe: does the archive hold any tile the resampler would
   * touch for this cell? Saves the full raster allocation and row loop for open-ocean
   * cells, which are most of the planet.
   * <p>
   * The probed area matches buildRaster's real sampling window, which extends HALF AN
   * OUTPUT CELL beyond the 5x5-degree box on every side (edge samples own a footprint
   * centred on the cell border). Probing only the nominal box would skip a cell whose
   * sole tiles sit in that margin when the box edge coincides with a tile boundary.
   */
  boolean cellHasAnyTile(int lonStart, int latStart, int rowLength, double[] bbox)
      throws IOException {
    SamplingWindow window = samplingWindow(lonStart, latStart, rowLength, bbox);
    if (window.empty) {
      return false;
    }
    validateWorkWindow(window);

    long txMin = tileXMin(window);
    long txMax = tileXMax(window);
    long sourceYFrom = sourceYFrom(window);
    long sourceYTo = sourceYToExclusive(window);
    long tiles = 1L << zoom;
    long tyMin = Math.max(0L, Math.floorDiv(sourceYFrom, (long) tileSize));
    long tyMax = Math.min(tiles - 1L,
      Math.floorDiv(sourceYTo - 1L, (long) tileSize));
    for (long ty = tyMin; ty <= tyMax; ty++) {
      for (long tx = txMin; tx <= txMax; tx++) {
        int wrappedTx = (int) Math.floorMod(tx, tiles);
        if (archive.locateTile(zoom, wrappedTx, (int) ty) != null) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * @return the 5x5 degree raster, or null when the archive holds no tile for this cell
   */
  ElevationRaster buildRaster(int lonStart, int latStart, int rowLength, double[] bbox)
    throws IOException {
    // reset per-run state so a reused instance cannot see a stale stripe, memo or
    // tile counters (a stale tilesFound would defeat the empty-cell bail below)
    stripeTileY = Integer.MIN_VALUE;
    reducedJ = Long.MIN_VALUE;
    tilesFound.set(0);
    tilesMissing.set(0);

    SamplingWindow window = samplingWindow(lonStart, latStart, rowLength, bbox);
    if (window.empty) {
      return null;
    }
    validateWorkWindow(window);

    int n = checkedInt(checkedAdd(checkedMultiply(5L, rowLength, "output row count"),
      1L, "output row count"), "output row count");
    double cellsize = 1.0 / rowLength;
    int rasterElements = checkedInt(checkedMultiply(n, n, "output raster size"),
      "output raster size");

    buildColumnWeights(lonStart, cellsize, n, window);
    prepareStripe(window);

    short[] pixels = new short[rasterElements];
    Arrays.fill(pixels, NODATA);

    double[] acc = new double[n];
    double[] accWeight = new double[n];

    long t0 = System.currentTimeMillis();
    for (int imageRow = window.rowFrom; imageRow <= window.rowTo; imageRow++) {
      double lat = latStart + 5 - imageRow * cellsize;
      double latTop = Math.min(window.maxLat, lat + 0.5 * cellsize);
      double latBottom = Math.max(window.minLat, lat - 0.5 * cellsize);
      double yTop = mercatorY(latTop);
      double yBottom = mercatorY(latBottom);
      if (yBottom <= yTop) {
        continue;
      }

      Arrays.fill(acc, window.colFrom, window.colTo + 1, 0.0);
      Arrays.fill(accWeight, window.colFrom, window.colTo + 1, 0.0);

      long j0 = floorToLong(yTop, "source row start");
      long j1 = ceilToLong(yBottom, "source row end");
      for (long j = j0; j < j1; j++) {
        if (j < 0 || j >= worldPx) {
          continue;
        }
        double wy = Math.min(j + 1, yBottom) - Math.max(j, yTop);
        if (wy <= 0.0) {
          continue;
        }
        reduceSourceRow(j, window.colFrom, window.colTo);
        double[] sum = reducedSumRow;
        double[] weight = reducedWeightRow;
        for (int c = window.colFrom; c <= window.colTo; c++) {
          acc[c] += wy * sum[c];
          accWeight[c] += wy * weight[c];
        }
      }

      int base = imageRow * n;
      for (int c = window.colFrom; c <= window.colTo; c++) {
        double fullWeight = (yBottom - yTop) * colFootprint[c];
        if (accWeight[c] >= MIN_COVERAGE * fullWeight && accWeight[c] > 0.0) {
          long v = Math.round(acc[c] / accWeight[c]);
          pixels[base + c] = (short) Math.max(MIN_ELEVATION, Math.min(MAX_ELEVATION, v));
        }
      }

      if ((imageRow - window.rowFrom) % 1000 == 0) {
        System.out.println("  row " + (imageRow - window.rowFrom) + "/"
          + (window.rowTo - window.rowFrom)
          + "  tiles=" + tilesFound.get() + "  " + (System.currentTimeMillis() - t0) + " ms");
      }
    }

    if (tilesFound.get() == 0) {
      return null; // open water, or outside the archive's coverage
    }

    ElevationRaster raster = new ElevationRaster();
    ElevationCells.configureCellRaster(raster, lonStart, latStart, rowLength);
    raster.eval_array = pixels;
    return raster;
  }

  private double mercatorY(double lat) {
    double phi = Math.toRadians(lat);
    return (1.0 - Math.log(Math.tan(phi) + 1.0 / Math.cos(phi)) / Math.PI) / 2.0 * worldPx;
  }

  private double mercatorX(double lon) {
    return (lon + 180.0) / 360.0 * worldPx;
  }

  void validateWorkWindow(SamplingWindow window) throws IOException {
    if (window.empty) {
      return;
    }

    long txMin = tileXMin(window);
    long tileColumns = checkedAdd(checkedSubtract(tileXMax(window), txMin,
      "stripe tile columns"), 1L, "stripe tile columns");
    long stripeWidth = checkedMultiply(tileColumns, tileSize, "stripe width");
    long stripeFloats = checkedMultiply(stripeWidth, tileSize, "stripe float count");
    long stripeBytes = checkedMultiply(stripeFloats, Float.BYTES, "stripe byte count");
    if (stripeBytes > MAX_STRIPE_BYTES) {
      throw workTooLarge("float stripe needs " + stripeBytes + " bytes; maximum is "
        + MAX_STRIPE_BYTES, null);
    }

    long sourceWidth = checkedSubtract(sourceXToExclusive(window), sourceXFrom(window),
      "source pixel width");
    long sourceHeight = checkedSubtract(sourceYToExclusive(window), sourceYFrom(window),
      "source pixel height");
    long sourcePixels = checkedMultiply(sourceWidth, sourceHeight, "source pixel count");
    if (sourcePixels > MAX_SOURCE_PIXELS) {
      throw workTooLarge("box filter would visit about " + sourcePixels
        + " source pixels; maximum is " + MAX_SOURCE_PIXELS, null);
    }
  }

  private long sourceXFrom(SamplingWindow window) throws IOException {
    return floorToLong(mercatorX(window.minLon), "source column start");
  }

  private long sourceXToExclusive(SamplingWindow window) throws IOException {
    return ceilToLong(mercatorX(window.maxLon), "source column end");
  }

  private long sourceYFrom(SamplingWindow window) throws IOException {
    return floorToLong(mercatorY(window.maxLat), "source row start");
  }

  private long sourceYToExclusive(SamplingWindow window) throws IOException {
    return ceilToLong(mercatorY(window.minLat), "source row end");
  }

  private long tileXMin(SamplingWindow window) throws IOException {
    return Math.floorDiv(sourceXFrom(window), (long) tileSize);
  }

  private long tileXMax(SamplingWindow window) throws IOException {
    return Math.floorDiv(checkedAdd(sourceXToExclusive(window), -1L,
      "source column end"), (long) tileSize);
  }

  private static long floorToLong(double value, String what) throws IOException {
    double rounded = Math.floor(value);
    if (!Double.isFinite(rounded) || rounded < -0x1p63 || rounded >= 0x1p63) {
      throw workTooLarge(what + " is outside the long range: " + value, null);
    }
    return (long) rounded;
  }

  private static long ceilToLong(double value, String what) throws IOException {
    double rounded = Math.ceil(value);
    if (!Double.isFinite(rounded) || rounded < -0x1p63 || rounded >= 0x1p63) {
      throw workTooLarge(what + " is outside the long range: " + value, null);
    }
    return (long) rounded;
  }

  private static int checkedInt(long value, String what) throws IOException {
    if (value < 0L || value > Integer.MAX_VALUE) {
      throw workTooLarge(what + " exceeds the Java array limit: " + value, null);
    }
    return (int) value;
  }

  private static long checkedAdd(long left, long right, String what) throws IOException {
    try {
      return Math.addExact(left, right);
    } catch (ArithmeticException e) {
      throw workTooLarge(what + " overflow", e);
    }
  }

  private static long checkedSubtract(long left, long right, String what) throws IOException {
    try {
      long value = Math.subtractExact(left, right);
      if (value < 0L) {
        throw workTooLarge(what + " is negative: " + value, null);
      }
      return value;
    } catch (ArithmeticException e) {
      throw workTooLarge(what + " overflow", e);
    }
  }

  private static long checkedMultiply(long left, long right, String what) throws IOException {
    try {
      return Math.multiplyExact(left, right);
    } catch (ArithmeticException e) {
      throw workTooLarge(what + " overflow", e);
    }
  }

  private static IOException workTooLarge(String detail, Throwable cause) {
    String message = "Mapterhorn work is too large: " + detail
      + "; lower -zoom or narrow -bbox";
    return cause == null ? new IOException(message) : new IOException(message, cause);
  }

  /**
   * Each output column overlaps two or three source columns. Precompute those overlaps
   * once: they are the same for every row, because longitude maps linearly onto x.
   */
  private void buildColumnWeights(int lonStart, double cellsize, int n,
                                  SamplingWindow window) throws IOException {
    long maxSpan = checkedAdd(ceilToLong(cellsize / 360.0 * worldPx,
      "source columns per output column"), 1L, "source columns per output column");
    long activeColumns = (long) window.colTo - window.colFrom + 1L;
    int weightCapacity = checkedInt(checkedMultiply(activeColumns, maxSpan,
      "column weight count"), "column weight count");

    colOffset = new int[checkedInt(checkedAdd(n, 1L, "column offset count"),
      "column offset count")];
    colSrcX = new long[weightCapacity];
    colWeight = new double[colSrcX.length];
    colFootprint = new double[n];

    int k = 0;
    for (int c = 0; c < n; c++) {
      colOffset[c] = k;
      if (c < window.colFrom || c > window.colTo) {
        continue;
      }
      double lon = lonStart + c * cellsize;
      double xa = mercatorX(Math.max(window.minLon, lon - 0.5 * cellsize));
      double xb = mercatorX(Math.min(window.maxLon, lon + 0.5 * cellsize));
      if (xb <= xa) {
        continue;
      }
      colFootprint[c] = xb - xa;
      long i0 = floorToLong(xa, "source column start");
      long i1 = ceilToLong(xb, "source column end");
      for (long i = i0; i < i1; i++) {
        double w = Math.min(i + 1, xb) - Math.max(i, xa);
        if (w > 0.0) {
          if (k >= colSrcX.length) {
            throw new IOException("Mapterhorn column weight count is too large");
          }
          colSrcX[k] = i;
          colWeight[k] = w;
          k++;
        }
      }
    }
    colOffset[n] = k;
  }

  private void prepareStripe(SamplingWindow window) throws IOException {
    long txMin = tileXMin(window);
    long tileColumns = checkedAdd(checkedSubtract(tileXMax(window), txMin,
      "stripe tile columns"), 1L, "stripe tile columns");
    stripeX0 = checkedMultiply(txMin, tileSize, "stripe source offset");
    stripeWidth = checkedInt(checkedMultiply(tileColumns, tileSize, "stripe width"),
      "stripe width");
    int stripeElements = checkedInt(checkedMultiply(stripeWidth, tileSize,
      "stripe float count"), "stripe float count");
    stripe = new float[stripeElements];
  }

  /**
   * Horizontally reduce one source pixel row onto the output columns. The result is
   * memoized: adjacent output rows share exactly the source row on their common
   * boundary, and source rows are consumed in monotonically increasing order, so a
   * single-slot memo captures all reuse.
   */
  private void reduceSourceRow(long j, int colFrom, int colTo) throws IOException {
    if (reducedJ == j) {
      return;
    }
    ensureStripe(checkedInt(Math.floorDiv(j, (long) tileSize), "source tile row"));

    if (reducedSumRow == null) {
      reducedSumRow = new double[colOffset.length - 1];
      reducedWeightRow = new double[colOffset.length - 1];
    }
    double[] sum = reducedSumRow;
    double[] weight = reducedWeightRow;

    int stripeRow = checkedInt(j - (long) stripeTileY * tileSize, "stripe row");
    int rowBase = checkedInt(checkedMultiply(stripeRow, stripeWidth,
      "stripe row offset"), "stripe row offset");
    for (int c = colFrom; c <= colTo; c++) {
      double s = 0.0;
      double w = 0.0;
      for (int k = colOffset[c]; k < colOffset[c + 1]; k++) {
        long relative = colSrcX[k] - stripeX0;
        if (relative < 0 || relative >= stripeWidth) {
          continue;
        }
        int i = (int) relative;
        float v = stripe[rowBase + i];
        if (!Float.isNaN(v)) {
          s += colWeight[k] * v;
          w += colWeight[k];
        }
      }
      sum[c] = s;
      weight[c] = w;
    }
    reducedJ = j;
  }

  // ----------------------------------------------------------------- tiles

  /**
   * Decode one row of source tiles into the stripe. Output rows are produced north to
   * south, so source rows increase monotonically and every tile is decoded exactly once.
   * <p>
   * Tiles absent from the cache are resolved to their archive byte locations first, then
   * byte-contiguous runs (the archive is clustered: Hilbert order == byte order) are
   * fetched with ONE range request each and sliced -- a range request against the CDN
   * costs ~140 ms of round-trip latency whatever its size. Fetching and decoding run on
   * a small pool; it is deliberately small, this is somebody else's public CDN.
   */
  private void ensureStripe(int tileY) throws IOException {
    if (tileY == stripeTileY) {
      return;
    }
    long txMin = Math.floorDiv(stripeX0, (long) tileSize);
    long tiles = 1L << zoom;
    int count = stripeWidth / tileSize;

    AtomicReferenceArray<TerrariumTileDecoder.Tile> decoded = new AtomicReferenceArray<>(count);
    List<Future<?>> tasks = new ArrayList<>();
    List<MissedTile> misses = new ArrayList<>();

    for (int k = 0; k < count; k++) {
      int tx = (int) Math.floorMod(txMin + k, tiles);
      PmTilesArchive.TileLocation loc = archive.locateTile(zoom, tx, tileY);
      if (loc == null) {
        tilesMissing.incrementAndGet();
        continue;
      }
      byte[] cached = cache == null ? null : cache.read(loc);
      if (cached != null) {
        tilesFound.incrementAndGet();
        int slot = k;
        tasks.add(pool.submit(() -> {
          decoded.set(slot, TerrariumTileDecoder.decode(cached, tileSize));
          return null;
        }));
        continue;
      }
      misses.add(new MissedTile(k, loc));
    }

    for (List<MissedTile> run : groupContiguousRuns(misses)) {
      tasks.add(pool.submit(() -> {
        fetchRun(run, decoded);
        return null;
      }));
    }

    // await ALL tasks even when one fails: an abandoned worker would later be
    // interrupted by close() while possibly blocked inside FileChannel.read, and an
    // interrupt there closes the archive's channel for every remaining cell
    IOException failure = null;
    for (Future<?> task : tasks) {
      try {
        await(task);
      } catch (IOException e) {
        if (failure == null) {
          failure = e;
        } else {
          failure.addSuppressed(e);
        }
      }
    }
    if (failure != null) {
      throw failure;
    }

    for (int k = 0; k < count; k++) {
      TerrariumTileDecoder.Tile tile = decoded.getAndSet(k, null);
      int x = k * tileSize;
      if (tile == null) {
        // absent tile: mark its column band as no-data (present bands are fully
        // overwritten below, so a whole-stripe prefill would be wasted work)
        for (int row = 0; row < tileSize; row++) {
          int base = row * stripeWidth + x;
          Arrays.fill(stripe, base, base + tileSize, Float.NaN);
        }
        continue;
      }
      if (tile.size() != tileSize) {
        throw new IOException("inconsistent tile size: expected " + tileSize
          + ", got " + tile.size());
      }
      float[] src = tile.elevations();
      for (int row = 0; row < tileSize; row++) {
        System.arraycopy(src, row * tileSize, stripe, row * stripeWidth + x, tileSize);
      }
    }
    stripeTileY = tileY;
  }

  static final class MissedTile {
    final int slot;
    final PmTilesArchive.TileLocation loc;

    MissedTile(int slot, PmTilesArchive.TileLocation loc) {
      this.slot = slot;
      this.loc = loc;
    }
  }

  /**
   * Group tiles whose bodies are adjacent (or identical, for de-duplication runs) in the
   * archive, so each group costs one range request.
   */
  static List<List<MissedTile>> groupContiguousRuns(List<MissedTile> misses) {
    List<MissedTile> sorted = new ArrayList<>(misses);
    sorted.sort((a, b) -> Long.compare(a.loc.offset, b.loc.offset));

    List<List<MissedTile>> runs = new ArrayList<>();
    List<MissedTile> run = null;
    long runStart = 0;
    for (MissedTile m : sorted) {
      if (run != null) {
        MissedTile prev = run.get(run.size() - 1);
        long runBytes = prev.loc.offset + prev.loc.length - runStart;
        if (m.loc.isContiguousWith(prev.loc) && runBytes + m.loc.length <= MAX_RUN_BYTES) {
          run.add(m);
          continue;
        }
      }
      run = new ArrayList<>();
      run.add(m);
      runStart = m.loc.offset;
      runs.add(run);
    }
    return runs;
  }

  /**
   * One range request for a whole run, then slice, cache and decode each member.
   */
  void fetchRun(List<MissedTile> run,
                AtomicReferenceArray<TerrariumTileDecoder.Tile> decoded) throws IOException {
    MissedTile first = run.get(0);
    MissedTile last = run.get(run.size() - 1);
    long start = first.loc.offset;
    int length = (int) (last.loc.offset + last.loc.length - start);
    byte[] block = archive.readRange(start, length);

    for (MissedTile m : run) {
      int from = (int) (m.loc.offset - start);
      byte[] raw = Arrays.copyOfRange(block, from, from + m.loc.length);
      raw = archive.decompressTile(raw);
      if (cache != null) {
        cache.write(m.loc, raw);
      }
      tilesFound.incrementAndGet();
      decoded.set(m.slot, TerrariumTileDecoder.decode(raw, tileSize));
    }
  }

  private static void await(Future<?> future) throws IOException {
    try {
      future.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted while fetching tiles", e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof IOException) {
        throw (IOException) cause;
      }
      throw new IOException("tile fetch failed", cause);
    }
  }

  private static int clamp(int v, int lo, int hi) {
    return v < lo ? lo : (Math.min(v, hi));
  }

  @Override
  public void close() throws IOException {
    // shut down gracefully first: shutdownNow() interrupts workers, and an interrupt
    // inside FileChannel.read closes the SHARED archive channel for all later cells
    pool.shutdown();
    try {
      if (!pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)) {
        pool.shutdownNow();
      }
    } catch (InterruptedException e) {
      pool.shutdownNow();
      Thread.currentThread().interrupt();
    }
    if (closeCache && cache != null) {
      cache.close();
    }
  }
}
