package btools.mapaccess;

/**
 * The decoded content of one micro-cache, kept by its OsmFile so that a later
 * routing pass can weave the same cell into its node graph without decoding
 * the bit stream and the tag sets again (see DirectWeaver).
 *
 * It is a flat recording of what DirectWeaver read, in the order it read it:
 * the numbers in one int array, the node descriptions, way tags and
 * geometries in one object array. None of these are modified after decoding,
 * so they are shared between the passes.
 */
final class WovenCell {
  int[] ints = new int[256];
  Object[] objs = new Object[64];
  int nints;
  int nobjs;
  private int geometryBytes;

  void add(int value) {
    if (nints == ints.length) {
      int[] grown = new int[2 * nints];
      System.arraycopy(ints, 0, grown, 0, nints);
      ints = grown;
    }
    ints[nints++] = value;
  }

  void add(Object value) {
    if (nobjs == objs.length) {
      Object[] grown = new Object[2 * nobjs];
      System.arraycopy(objs, 0, grown, 0, nobjs);
      objs = grown;
    }
    objs[nobjs++] = value;
  }

  void addGeometry(byte[] geometry) {
    add(geometry);
    if (geometry != null) {
      geometryBytes += 16 + geometry.length;
    }
  }

  /**
   * Trim the arrays once the cell is complete.
   */
  void freeze() {
    if (nints < ints.length) {
      int[] trimmed = new int[nints];
      System.arraycopy(ints, 0, trimmed, 0, nints);
      ints = trimmed;
    }
    if (nobjs < objs.length) {
      Object[] trimmed = new Object[nobjs];
      System.arraycopy(objs, 0, trimmed, 0, nobjs);
      objs = trimmed;
    }
  }

  /**
   * The memory this recording holds on its own, in bytes. Node descriptions
   * and way tags are not counted: they are the shared arrays of the tag
   * value cache.
   */
  int getDataSize() {
    return 48 + 4 * ints.length + 8 * objs.length + geometryBytes;
  }
}
