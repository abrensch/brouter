package btools.mapcreator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.zip.GZIPOutputStream;

/**
 * Builds a minimal, valid PMTiles v3 archive in memory, so the reader and the converter
 * can be tested without touching the network or the 700 GB Mapterhorn planet archive.
 * <p>
 * Covers the structural variants the production archive uses: root-only and leaf
 * directories, gzip directory compression, gzip tile compression, run-length tile
 * de-duplication, and the offset==0 'contiguous entry' encoding.
 */
public final class PmTilesTestArchive {

  private static final class Entry {
    final long tileId;
    final long runLength;
    final byte[] payload;

    Entry(long tileId, long runLength, byte[] payload) {
      this.tileId = tileId;
      this.runLength = runLength;
      this.payload = payload;
    }
  }

  private final SortedMap<Long, Entry> tiles = new TreeMap<>();
  private int minZoom;
  private int maxZoom;
  private int tileType = PmTilesArchive.TILETYPE_PNG;
  private int internalCompression = PmTilesArchive.COMPRESSION_NONE;
  private int tileCompression = PmTilesArchive.COMPRESSION_NONE;
  private boolean useLeafDirectory;
  private boolean contiguousOffsets;

  public PmTilesTestArchive zoomRange(int min, int max) {
    this.minZoom = min;
    this.maxZoom = max;
    return this;
  }

  public PmTilesTestArchive tileType(int type) {
    this.tileType = type;
    return this;
  }

  public PmTilesTestArchive internalCompression(int compression) {
    this.internalCompression = compression;
    return this;
  }

  public PmTilesTestArchive tileCompression(int compression) {
    this.tileCompression = compression;
    return this;
  }

  /** Store all entries in one leaf directory, with the root pointing at it. */
  public PmTilesTestArchive useLeafDirectory(boolean leaf) {
    this.useLeafDirectory = leaf;
    return this;
  }

  /** Encode directory offsets with the offset==0 'follows previous entry' shorthand. */
  public PmTilesTestArchive contiguousOffsets(boolean contiguous) {
    this.contiguousOffsets = contiguous;
    return this;
  }

  public PmTilesTestArchive put(int z, int x, int y, byte[] payload) {
    long id = PmTilesArchive.zxyToTileId(z, x, y);
    tiles.put(id, new Entry(id, 1L, payload));
    return this;
  }

  /** One directory entry serving {@code runLength} consecutive tile ids (de-duplication). */
  public PmTilesTestArchive putRun(long tileId, long runLength, byte[] payload) {
    tiles.put(tileId, new Entry(tileId, runLength, payload));
    return this;
  }

  public byte[] build() throws IOException {
    ByteArrayOutputStream tileData = new ByteArrayOutputStream();
    List<long[]> entries = new ArrayList<>(); // tileId, runLength, offset, length
    for (Map.Entry<Long, Entry> e : tiles.entrySet()) {
      Entry entry = e.getValue();
      byte[] payload = tileCompression == PmTilesArchive.COMPRESSION_GZIP
        ? gzip(entry.payload) : entry.payload;
      entries.add(new long[]{entry.tileId, entry.runLength, tileData.size(), payload.length});
      tileData.write(payload);
    }

    byte[] leafDirs = new byte[0];
    byte[] rootDir;
    if (useLeafDirectory && !entries.isEmpty()) {
      byte[] leaf = serializeDirectory(entries, contiguousOffsets);
      if (internalCompression == PmTilesArchive.COMPRESSION_GZIP) {
        leaf = gzip(leaf);
      }
      leafDirs = leaf;
      // a leaf pointer: runLength 0, offset relative to the leaf section
      List<long[]> rootEntries = new ArrayList<>();
      rootEntries.add(new long[]{entries.get(0)[0], 0L, 0L, leaf.length});
      rootDir = serializeDirectory(rootEntries, false);
    } else {
      rootDir = serializeDirectory(entries, contiguousOffsets);
    }
    if (internalCompression == PmTilesArchive.COMPRESSION_GZIP) {
      rootDir = gzip(rootDir);
    }

    long rootDirOffset = PmTilesArchive.HEADER_LEN;
    long metadataOffset = rootDirOffset + rootDir.length;
    long leafDirsOffset = metadataOffset; // no metadata
    long tileDataOffset = leafDirsOffset + leafDirs.length;

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] header = new byte[PmTilesArchive.HEADER_LEN];
    System.arraycopy("PMTiles".getBytes("US-ASCII"), 0, header, 0, 7);
    header[7] = 3;
    putLong(header, 8, rootDirOffset);
    putLong(header, 16, rootDir.length);
    putLong(header, 24, metadataOffset);
    putLong(header, 32, 0L);
    putLong(header, 40, leafDirsOffset);
    putLong(header, 48, leafDirs.length);
    putLong(header, 56, tileDataOffset);
    putLong(header, 64, tileData.size());
    putLong(header, 72, tiles.size());
    putLong(header, 80, tiles.size());
    putLong(header, 88, tiles.size());
    header[96] = 1; // clustered
    header[97] = (byte) internalCompression;
    header[98] = (byte) tileCompression;
    header[99] = (byte) tileType;
    header[100] = (byte) minZoom;
    header[101] = (byte) maxZoom;
    putInt(header, 102, -1800000000);
    putInt(header, 106, -850511287);
    putInt(header, 110, 1800000000);
    putInt(header, 114, 850511287);
    header[118] = 0;
    putInt(header, 119, 0);
    putInt(header, 123, 0);

    out.write(header);
    out.write(rootDir);
    out.write(leafDirs);
    out.write(tileData.toByteArray());
    return out.toByteArray();
  }

  public PmTilesArchive.ByteSource asByteSource() throws IOException {
    return new MemoryByteSource(build());
  }

  public static byte[] serializeDirectory(List<long[]> entries, boolean contiguousOffsets) {
    ByteArrayOutputStream o = new ByteArrayOutputStream();
    writeVarint(o, entries.size());
    long last = 0L;
    for (long[] e : entries) {
      writeVarint(o, e[0] - last);
      last = e[0];
    }
    for (long[] e : entries) {
      writeVarint(o, e[1]);
    }
    for (long[] e : entries) {
      writeVarint(o, e[3]);
    }
    for (int i = 0; i < entries.size(); i++) {
      long[] e = entries.get(i);
      boolean followsPrevious = i > 0
        && e[2] == entries.get(i - 1)[2] + entries.get(i - 1)[3];
      if (contiguousOffsets && followsPrevious) {
        writeVarint(o, 0L);
      } else {
        writeVarint(o, e[2] + 1L);
      }
    }
    return o.toByteArray();
  }

  public static void writeVarint(ByteArrayOutputStream o, long v) {
    while ((v & ~0x7fL) != 0L) {
      o.write((int) ((v & 0x7f) | 0x80));
      v >>>= 7;
    }
    o.write((int) v);
  }

  public static void putLong(byte[] b, int off, long v) {
    for (int i = 0; i < 8; i++) {
      b[off + i] = (byte) (v >>> (8 * i));
    }
  }

  private static void putInt(byte[] b, int off, int v) {
    for (int i = 0; i < 4; i++) {
      b[off + i] = (byte) (v >>> (8 * i));
    }
  }

  public static byte[] gzip(byte[] data) throws IOException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (GZIPOutputStream gos = new GZIPOutputStream(bos)) {
      gos.write(data);
    }
    return bos.toByteArray();
  }

  public static final class MemoryByteSource implements PmTilesArchive.ByteSource {
    private final byte[] data;

    public MemoryByteSource(byte[] data) {
      this.data = data;
    }

    @Override
    public byte[] read(long offset, int length) throws IOException {
      if (offset < 0 || offset + length > data.length) {
        throw new IOException("read past end of archive");
      }
      return Arrays.copyOfRange(data, (int) offset, (int) offset + length);
    }

    @Override
    public void close() {
      // nothing to release
    }
  }
}
