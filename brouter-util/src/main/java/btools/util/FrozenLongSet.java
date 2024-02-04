package btools.util;

/**
 * Frozen instance of Memory efficient Set
 * <p>
 * This one is readily sorted into a singe array for faster access
 *
 * @author ab
 */
public class FrozenLongSet extends CompactLongSet {
  private long[] faid;
  private int size = 0;
  private int p2size; // next power of 2 of size

  public FrozenLongSet(CompactLongSet set) {
    size = set.size();

    faid = new long[size];

    set.moveToFrozenArray(faid);

    p2size = 0x40000000;
    while (p2size > size) p2size >>= 1;
  }

  @Override
  public boolean add(long id) {
    throw new RuntimeException("cannot add on FrozenLongSet");
  }

  @Override
  public void fastAdd(long id) {
    throw new RuntimeException("cannot add on FrozenLongSet");
  }

  /**
   * @return the number of entries in this set
   */
  @Override
  public int size() {
    return size;
  }


  /**
   * @return true if "id" is contained in this set.
   */
  @Override
  public boolean contains(long id) {
    if (size == 0) {
      return false;
    }
    long[] a = faid;
    int offset = p2size;
    int n = 0;

    while (offset > 0) {
      int nn = n + offset;
      if (nn < size && a[nn] <= id) {
        n = nn;
      }
      offset >>= 1;
    }
    return a[n] == id;
  }

}
