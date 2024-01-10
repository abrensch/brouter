package btools.codec;

import btools.util.ByteDataWriter;

/**
 * a micro-cache is a data cache for an area of some square kilometers or some
 * hundreds or thousands nodes
 * <p>
 * This is the basic io-unit: always a full microcache is loaded from the
 * data-file if a node is requested at a position not yet covered by the caches
 * already loaded
 * <p>
 * The nodes are represented in a compact way (typical 20-50 bytes per node),
 * but in a way that they do not depend on each other, and garbage collection is
 * supported to remove the nodes already consumed from the cache.
 * <p>
 * The cache-internal data representation is different from that in the
 * data-files, where a cache is encoded as a whole, allowing more
 * redundancy-removal for a more compact encoding
 */
public class MicroCache extends ByteDataWriter {
  protected int[] faid;
  protected int[] fapos;
  protected int size = 0;

  private int p2size; // next power of 2 of size

  public static boolean debug = false;

  protected MicroCache(byte[] ab) {
    super(ab);
  }

  public final static MicroCache emptyNonVirgin = new MicroCache(null);

  public static MicroCache emptyCache() {
    return new MicroCache(null); // TODO: singleton?
  }

  protected void init(int size) {
    this.size = size;
    p2size = 0x40000000;
    while (p2size > size)
      p2size >>= 1;
  }

  public final void finishNode(long id) {
    fapos[size] = aboffset;
    faid[size] = shrinkId(id);
    size++;
  }

  public final void discardNode() {
    aboffset = startPos(size);
  }

  public final int getSize() {
    return size;
  }

  public final int getDataSize() {
    return ab == null ? 0 : ab.length;
  }


  protected final int startPos(int n) {
    return n > 0 ? fapos[n - 1] & 0x7fffffff : 0;
  }

  /**
   * expand a 32-bit micro-cache-internal id into a 64-bit (lon|lat) global-id
   *
   * @see #shrinkId
   */
  public long expandId(int id32) {
    throw new IllegalArgumentException("expandId for empty cache");
  }

  /**
   * shrink a 64-bit (lon|lat) global-id into a a 32-bit micro-cache-internal id
   *
   * @see #expandId
   */
  public int shrinkId(long id64) {
    throw new IllegalArgumentException("shrinkId for empty cache");
  }

  /**
   * @return true if the given lon/lat position is internal for that micro-cache
   */
  public boolean isInternal(int ilon, int ilat) {
    throw new IllegalArgumentException("isInternal for empty cache");
  }

  /**
   * (stasticially) encode the micro-cache into the format used in the datafiles
   *
   * @param buffer byte array to encode into (considered big enough)
   * @return the size of the encoded data
   */
  public int encodeMicroCache(byte[] buffer) {
    throw new IllegalArgumentException("encodeMicroCache for empty cache");
  }

}
