package btools.util;

import java.util.Arrays;

/**
 * dynamic list of primitive longs
 *
 * @author ab
 */
public class LongList {
  private long[] a;
  private int size;

  public LongList(int capacity) {
    a = new long[Math.max(4,capacity)];
  }

  public void add(long value) {
    if (size == a.length) {
      a = Arrays.copyOf( a, 2 * size );
    }
    a[size++] = value;
  }

  public long get(int idx) {
    if (idx >= size) {
      throw new IndexOutOfBoundsException("list size=" + size + " idx=" + idx);
    }
    return a[idx];
  }

  public int size() {
    return size;
  }

}
