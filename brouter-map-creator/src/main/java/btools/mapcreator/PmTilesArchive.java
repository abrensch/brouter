package btools.mapcreator;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Reader for PMTiles v3 archives, written against the public specification
 * (https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md).
 * <p>
 * Bytes are pulled through a {@link ByteSource}, so one reader serves both a local archive
 * file and a remote archive addressed with HTTP range requests. The 700+ GB Mapterhorn
 * planet archive is only usable in the latter form.
 *
 * @see <a href="https://mapterhorn.com">Mapterhorn</a>
 */
public final class PmTilesArchive implements Closeable {

  public static final int HEADER_LEN = 127;

  public static final int COMPRESSION_NONE = 1;
  public static final int COMPRESSION_GZIP = 2;

  public static final int TILETYPE_PNG = 2;
  public static final int TILETYPE_WEBP = 4;

  private static final int LEAF_CACHE_ENTRIES = 256;
  private static final int MAX_DIR_DEPTH = 4;

  /**
   * Random access to the raw archive bytes.
   */
  public interface ByteSource extends Closeable {
    byte[] read(long offset, int length) throws IOException;
  }

  private final ByteSource source;

  private long rootDirOffset;
  private long rootDirLength;
  private long leafDirsOffset;
  private long tileDataOffset;
  private String headerId;
  private int internalCompression;
  private int tileCompression;
  private int tileType;
  private int minZoom;
  private int maxZoom;
  private double minLon;
  private double minLat;
  private double maxLon;
  private double maxLat;

  private List<DirEntry> rootDir;

  private final Map<Long, List<DirEntry>> leafCache = new LinkedHashMap<>(16, 0.75f, true) {
    @Override
    protected boolean removeEldestEntry(Map.Entry<Long, List<DirEntry>> eldest) {
      return size() > LEAF_CACHE_ENTRIES;
    }
  };

  private PmTilesArchive(ByteSource source) {
    this.source = source;
  }

  /**
   * Open an archive given either a local file path or an http(s) URL.
   */
  public static PmTilesArchive open(String location) throws IOException {
    ByteSource src = location.startsWith("http://") || location.startsWith("https://")
      ? new HttpRangeByteSource(location)
      : new FileByteSource(new File(location));
    try {
      return open(src);
    } catch (IOException | RuntimeException e) {
      src.close();
      throw e;
    }
  }

  /**
   * Open an archive over a caller-supplied source. The caller keeps ownership of the
   * source when this throws.
   */
  public static PmTilesArchive open(ByteSource source) throws IOException {
    PmTilesArchive archive = new PmTilesArchive(source);
    byte[] header = archive.readHeader();
    byte[] rawRootDir = source.read(archive.rootDirOffset,
      checkedInt(archive.rootDirLength, "root directory length"));
    // identity = header + root directory: the root digests every tile offset/length
    // (directly or via leaf pointers), so different builds cannot share it
    byte[] idInput = new byte[header.length + rawRootDir.length];
    System.arraycopy(header, 0, idInput, 0, header.length);
    System.arraycopy(rawRootDir, 0, idInput, header.length, rawRootDir.length);
    archive.headerId = sha256Hex(idInput);
    archive.rootDir = archive.parseDirectory(rawRootDir);
    return archive;
  }

  public int minZoom() {
    return minZoom;
  }

  public int maxZoom() {
    return maxZoom;
  }

  public int tileType() {
    return tileType;
  }

  public double minLon() {
    return minLon;
  }

  public double minLat() {
    return minLat;
  }

  public double maxLon() {
    return maxLon;
  }

  public double maxLat() {
    return maxLat;
  }

  /**
   * Byte position of one tile's body within the archive. Distinct tiles may share a
   * location: PMTiles de-duplicates identical tiles into runs.
   */
  public static final class TileLocation {
    public final long offset;
    public final int length;

    TileLocation(long offset, int length) {
      this.offset = offset;
      this.length = length;
    }

    /** Adjacent or identical locations can be served by one contiguous read. */
    public boolean isContiguousWith(TileLocation prev) {
      return offset == prev.offset + prev.length
        || (offset == prev.offset && length == prev.length);
    }
  }

  /**
   * @return the raw (decompressed) bytes of the requested tile, or null when the archive
   * does not contain it. Absent tiles are normal: Mapterhorn omits open water.
   * <p>
   * Safe to call from several threads. Only the directory walk is serialised; the tile
   * body, which is where the round-trip latency lives, is read outside the lock.
   */
  public byte[] getTile(int z, int x, int y) throws IOException {
    TileLocation ref = locateTile(z, x, y);
    if (ref == null) {
      return null;
    }
    return decompressTile(source.read(ref.offset, ref.length));
  }

  /**
   * Resolve a tile to its byte location without reading the body, or null when absent.
   * Callers batching many tiles can group contiguous locations (the archive is usually
   * clustered: Hilbert order == byte order) and fetch each group with one
   * {@link #readRange} call, then slice with {@link TileLocation#offset}.
   */
  public synchronized TileLocation locateTile(int z, int x, int y) throws IOException {
    long[] ref = findEntry(zxyToTileId(z, x, y), rootDir, 0);
    return ref == null ? null : new TileLocation(ref[0], checkedInt(ref[1], "tile length"));
  }

  /**
   * Read raw archive bytes, e.g. one contiguous run of tile bodies. Thread-safe.
   */
  public byte[] readRange(long offset, int length) throws IOException {
    return source.read(offset, length);
  }

  /**
   * Undo the archive's tile compression on one tile body sliced out of a range read.
   */
  public byte[] decompressTile(byte[] raw) throws IOException {
    return tileCompression == COMPRESSION_GZIP ? gunzip(raw) : raw;
  }

  /**
   * A fingerprint of this archive build, for binding external tile caches to their
   * source: the SHA-256 of the header plus the raw root directory, which digests every
   * tile offset and length (directly or through leaf pointers).
   */
  public String archiveId() {
    return headerId;
  }

  @Override
  public void close() throws IOException {
    synchronized (this) {
      leafCache.clear();
      rootDir = null;
    }
    source.close();
  }

  // ---------------------------------------------------------------- header

  private byte[] readHeader() throws IOException {
    byte[] buf = source.read(0L, HEADER_LEN);
    ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);

    byte[] magic = new byte[7];
    bb.get(magic);
    if (!Arrays.equals(magic, new byte[]{'P', 'M', 'T', 'i', 'l', 'e', 's'})) {
      throw new IOException("not a PMTiles archive (bad magic)");
    }
    int version = bb.get() & 0xff;
    if (version != 3) {
      throw new IOException("only PMTiles v3 is supported, found v" + version);
    }

    rootDirOffset = bb.getLong();
    rootDirLength = bb.getLong();
    bb.getLong(); // metadata offset
    bb.getLong(); // metadata length
    leafDirsOffset = bb.getLong();
    bb.getLong(); // leaf dirs length
    tileDataOffset = bb.getLong();
    bb.getLong(); // tile data length
    bb.getLong(); // num addressed tiles
    bb.getLong(); // num tile entries
    bb.getLong(); // num tile contents

    bb.get(); // clustered
    internalCompression = bb.get() & 0xff;
    tileCompression = bb.get() & 0xff;
    tileType = bb.get() & 0xff;
    minZoom = bb.get() & 0xff;
    maxZoom = bb.get() & 0xff;
    minLon = bb.getInt() / 1e7;
    minLat = bb.getInt() / 1e7;
    maxLon = bb.getInt() / 1e7;
    maxLat = bb.getInt() / 1e7;

    if (internalCompression != COMPRESSION_NONE && internalCompression != COMPRESSION_GZIP) {
      throw new IOException("unsupported PMTiles directory compression: " + internalCompression);
    }
    if (tileCompression != COMPRESSION_NONE && tileCompression != COMPRESSION_GZIP) {
      throw new IOException("unsupported PMTiles tile compression: " + tileCompression);
    }
    return buf;
  }

  // ------------------------------------------------------------- directories

  private List<DirEntry> parseDirectory(byte[] raw) throws IOException {
    if (internalCompression == COMPRESSION_GZIP) {
      raw = gunzip(raw);
    }
    return deserializeDirectory(raw);
  }

  private List<DirEntry> readDirectory(long offset, long length) throws IOException {
    return parseDirectory(source.read(offset, checkedInt(length, "directory length")));
  }

  private List<DirEntry> readLeafDirectory(long offset, long length) throws IOException {
    List<DirEntry> cached = leafCache.get(offset);
    if (cached != null) {
      return cached;
    }
    List<DirEntry> entries = readDirectory(leafDirsOffset + offset, length);
    leafCache.put(offset, entries);
    return entries;
  }

  static List<DirEntry> deserializeDirectory(byte[] data) throws IOException {
    int[] pos = {0};
    int numEntries = checkedInt(readVarint(data, pos), "directory entry count");
    if (numEntries <= 0) {
      return Collections.emptyList();
    }

    long[] tileIds = new long[numEntries];
    long last = 0L;
    for (int i = 0; i < numEntries; i++) {
      last += readVarint(data, pos);
      tileIds[i] = last;
    }
    long[] runLengths = new long[numEntries];
    for (int i = 0; i < numEntries; i++) {
      runLengths[i] = readVarint(data, pos);
    }
    long[] lengths = new long[numEntries];
    for (int i = 0; i < numEntries; i++) {
      lengths[i] = readVarint(data, pos);
    }
    // an offset of zero means "directly after the previous entry"
    long[] offsets = new long[numEntries];
    for (int i = 0; i < numEntries; i++) {
      long v = readVarint(data, pos);
      if (v == 0L) {
        if (i == 0) {
          throw new IOException("invalid PMTiles directory: leading zero offset");
        }
        offsets[i] = offsets[i - 1] + lengths[i - 1];
      } else {
        offsets[i] = v - 1L;
      }
    }

    List<DirEntry> entries = new ArrayList<>(numEntries);
    for (int i = 0; i < numEntries; i++) {
      entries.add(new DirEntry(tileIds[i], runLengths[i], offsets[i], lengths[i]));
    }
    return entries;
  }

  private long[] findEntry(long tileId, List<DirEntry> dir, int depth) throws IOException {
    if (depth > MAX_DIR_DEPTH) {
      // an absent tile is normal, but a directory tree this deep is not: fail loudly
      // instead of quietly reporting terrain as missing
      throw new IOException("PMTiles directory nesting exceeds depth " + MAX_DIR_DEPTH
        + " (corrupt or cyclic archive)");
    }
    if (dir == null || dir.isEmpty()) {
      return null;
    }
    // last entry with entry.tileId <= tileId
    int lo = 0;
    int hi = dir.size() - 1;
    int found = -1;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      if (dir.get(mid).tileId <= tileId) {
        found = mid;
        lo = mid + 1;
      } else {
        hi = mid - 1;
      }
    }
    if (found < 0) {
      return null;
    }
    DirEntry e = dir.get(found);
    if (e.runLength == 0L) {
      return findEntry(tileId, readLeafDirectory(e.offset, e.length), depth + 1);
    }
    if (tileId < e.tileId + e.runLength) {
      return new long[]{tileDataOffset + e.offset, e.length};
    }
    return null;
  }

  // ------------------------------------------------------- hilbert tile ids

  /**
   * Number of tiles on all zoom levels below z, i.e. the tile id of (z, 0, 0).
   * Closed form of sum(4^i) for i in [0, z).
   */
  public static long hilbertBase(int z) {
    return ((1L << (2 * z)) - 1L) / 3L;
  }

  public static long zxyToTileId(int z, int x, int y) {
    if (z == 0) {
      return 0L;
    }
    return hilbertBase(z) + xyToHilbertDistance(z, x, y);
  }

  /**
   * Standard Hilbert xy-to-distance, matching the PMTiles reference implementation.
   */
  public static long xyToHilbertDistance(int order, long x, long y) {
    long d = 0L;
    for (long s = 1L << (order - 1); s > 0L; s >>= 1) {
      long rx = (x & s) > 0L ? 1L : 0L;
      long ry = (y & s) > 0L ? 1L : 0L;
      d += s * s * ((3L * rx) ^ ry);
      if (ry == 0L) {
        if (rx == 1L) {
          x = s - 1L - x;
          y = s - 1L - y;
        }
        long t = x;
        x = y;
        y = t;
      }
    }
    return d;
  }

  // -------------------------------------------------------------- utilities

  /**
   * Narrow a length/count from the archive with a range check. An unchecked cast could
   * truncate a corrupt 64-bit value to a small positive int and silently parse garbage.
   */
  static int checkedInt(long v, String what) throws IOException {
    if (v < 0L || v > Integer.MAX_VALUE) {
      throw new IOException("implausible PMTiles " + what + ": " + v);
    }
    return (int) v;
  }

  static String sha256Hex(byte[] data) {
    try {
      byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(data);
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
      }
      return sb.toString();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  static long readVarint(byte[] data, int[] pos) throws IOException {
    long result = 0L;
    int shift = 0;
    while (true) {
      if (pos[0] >= data.length) {
        throw new IOException("truncated varint in PMTiles directory");
      }
      int b = data[pos[0]++] & 0xff;
      result |= (long) (b & 0x7f) << shift;
      if ((b & 0x80) == 0) {
        return result;
      }
      shift += 7;
      if (shift > 63) {
        throw new IOException("varint too long in PMTiles directory");
      }
    }
  }

  static byte[] gunzip(byte[] data) throws IOException {
    try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(data));
         ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(64, data.length * 4))) {
      byte[] buf = new byte[8192];
      int n;
      while ((n = gis.read(buf)) >= 0) {
        bos.write(buf, 0, n);
      }
      return bos.toByteArray();
    }
  }

  static final class DirEntry {
    final long tileId;
    final long runLength;
    final long offset;
    final long length;

    DirEntry(long tileId, long runLength, long offset, long length) {
      this.tileId = tileId;
      this.runLength = runLength;
      this.offset = offset;
      this.length = length;
    }
  }

  // ----------------------------------------------------------- byte sources

  /**
   * Reads from a local archive file.
   */
  public static final class FileByteSource implements ByteSource {
    private final RandomAccessFile raf;
    private final FileChannel channel;

    public FileByteSource(File file) throws IOException {
      this.raf = new RandomAccessFile(file, "r");
      this.channel = raf.getChannel();
    }

    @Override
    public byte[] read(long offset, int length) throws IOException {
      ByteBuffer buf = ByteBuffer.allocate(length);
      int read = 0;
      while (read < length) {
        int n = channel.read(buf, offset + read);
        if (n < 0) {
          break;
        }
        read += n;
      }
      if (read < length) {
        throw new IOException("short read at " + offset + ": wanted " + length + ", got " + read);
      }
      return buf.array();
    }

    @Override
    public void close() throws IOException {
      channel.close();
      raf.close();
    }
  }

  /**
   * Reads from a remote archive with HTTP range requests. Mapterhorn serves the planet
   * archive behind a CDN that honours {@code accept-ranges: bytes}.
   */
  public static final class HttpRangeByteSource implements ByteSource {
    private static final int MAX_ATTEMPTS = 3;
    private static final int TIMEOUT_MS = 30000;

    private final String url;

    public HttpRangeByteSource(String url) {
      this.url = url;
    }

    @Override
    public byte[] read(long offset, int length) throws IOException {
      IOException last = null;
      for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
        try {
          return rangeGet(offset, length);
        } catch (PermanentHttpException e) {
          throw e; // 4xx will not improve on retry
        } catch (RateLimitedException e) {
          last = e;
          if (attempt + 1 >= MAX_ATTEMPTS) {
            break;
          }
          // honour the server's Retry-After, capped so one tile cannot stall a build
          sleepBeforeRetry(Math.max(200L << attempt, Math.min(e.retryAfterMs, 10000L)));
        } catch (IOException e) {
          last = e;
          if (attempt + 1 >= MAX_ATTEMPTS) {
            break; // no point sleeping after the final attempt
          }
          sleepBeforeRetry(200L << attempt);
        }
      }
      throw new IOException("range read failed at " + offset + " (+" + length + ")", last);
    }

    private static void sleepBeforeRetry(long delayMs) throws IOException {
      try {
        Thread.sleep(delayMs);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        throw new IOException("interrupted", ie);
      }
    }

    /** A deterministic HTTP failure that a retry cannot fix. */
    private static final class PermanentHttpException extends IOException {
      PermanentHttpException(String message) {
        super(message);
      }
    }

    /** HTTP 429: retryable, carrying the server's requested delay. */
    private static final class RateLimitedException extends IOException {
      final long retryAfterMs;

      RateLimitedException(String message, long retryAfterMs) {
        super(message);
        this.retryAfterMs = retryAfterMs;
      }
    }

    private byte[] rangeGet(long offset, int length) throws IOException {
      HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
      con.setRequestMethod("GET");
      con.setRequestProperty("Range", "bytes=" + offset + "-" + (offset + length - 1));
      con.setRequestProperty("User-Agent", "BRouter map-creator");
      con.setConnectTimeout(TIMEOUT_MS);
      con.setReadTimeout(TIMEOUT_MS);

      int code = con.getResponseCode();
      if (code != HttpURLConnection.HTTP_PARTIAL) {
        String retryAfter = con.getHeaderField("Retry-After");
        drainAndDisconnect(con);
        String msg = "expected 206 for range request, got " + code + " from " + url;
        if (code == 429) {
          long ms = 1000L;
          try {
            ms = Long.parseLong(retryAfter.trim()) * 1000L;
          } catch (RuntimeException ignored) {
            // absent or non-numeric Retry-After: keep the default
          }
          throw new RateLimitedException(msg, ms);
        }
        if (code >= 400 && code < 500) {
          throw new PermanentHttpException(msg);
        }
        throw new IOException(msg);
      }
      // read the body to the end and do not call disconnect(), so the connection returns
      // to the keep-alive pool. Measured against Mapterhorn's CDN this saves little on
      // its own -- a range request costs ~140 ms of round-trip latency regardless of
      // size -- so callers should also fetch tiles concurrently.
      byte[] out = new byte[length];
      try (InputStream is = con.getInputStream()) {
        int read = 0;
        while (read < length) {
          int n = is.read(out, read, length - read);
          if (n < 0) {
            throw new IOException("short range read: wanted " + length + ", got " + read);
          }
          read += n;
        }
      }
      return out;
    }

    private static void drainAndDisconnect(HttpURLConnection con) {
      try (InputStream err = con.getErrorStream()) {
        if (err != null) {
          byte[] sink = new byte[4096];
          while (err.read(sink) >= 0) {
            continue;
          }
        }
      } catch (IOException ignored) {
        // best effort only
      }
      con.disconnect();
    }

    @Override
    public void close() {
      // nothing to release
    }
  }
}
