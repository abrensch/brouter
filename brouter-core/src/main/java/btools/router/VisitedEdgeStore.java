package btools.router;

/**
 * SAFE-3: primitive open-addressing store for the greedy planner's visited-edge
 * tracking, replacing the boxed {@code HashMap<Long,Integer>} (reuse counts) +
 * {@code HashMap<Long,Double>} (first-visit cumulative distance) pair.
 *
 * <p>The two former maps are maintained in lock-step — a key gains both its
 * count and its first-visit position on the first visit, and loses both
 * together when its count drops to zero — so they are folded into one table
 * with a {@code count} and a {@code firstPos} column per slot. This removes the
 * {@code Long}/{@code Integer}/{@code Double} autoboxing on the planner's
 * hottest per-segment loop.
 *
 * <p><b>Result-preservation invariants</b> (SAFE-3 — these keep the primitive
 * store behaviour-identical to the boxed maps it replaced):
 * <ul>
 *   <li>The store is only ever accessed by keyed point operations — never
 *       iterated — so the internal slot layout never influences a routing
 *       decision.</li>
 *   <li>Occupancy is tracked with an explicit {@code used} flag, NOT a sentinel
 *       key: edge keys (packed by ReuseClassifier's edge-key encoding) span the full 64-bit range, so any
 *       reserved key value (0L, -1L, MIN_VALUE, …) could be a real edge.</li>
 *   <li>{@code firstPos} can legitimately be {@code 0.0} (a 1-metre first edge
 *       at loop start), so presence is the {@code used} flag, NOT a sentinel
 *       double.</li>
 *   <li>{@link #count} returns {@code 0} for an absent key; stored counts are
 *       always {@code >= 1}, so {@code 0} unambiguously means "absent",
 *       reproducing the former {@code prev == null || prev == 0} test.</li>
 * </ul>
 * Deletion uses Knuth's backward-shift algorithm (no tombstones) so the table
 * stays a clean linear-probe structure across the planner's accept/undo churn.
 *
 * <p>Not thread-safe; one instance is confined to a single {@code plan()} call.
 */
final class VisitedEdgeStore {

  private long[] keys;
  private int[] counts;
  private double[] firstPos;
  private boolean[] used;
  private int size;
  private int mask;
  private int threshold;

  VisitedEdgeStore() {
    allocate(16);
  }

  private void allocate(int capacity) {
    keys = new long[capacity];
    counts = new int[capacity];
    firstPos = new double[capacity];
    used = new boolean[capacity];
    mask = capacity - 1;
    threshold = (capacity * 3) / 5; // grow past 60% load
    size = 0;
  }

  boolean isEmpty() {
    return size == 0;
  }

  int size() {
    return size;
  }

  /** Index of the slot holding {@code key}, or the first empty slot on its probe chain. */
  private int slotOf(long key) {
    int i = hashIndex(key);
    while (used[i] && keys[i] != key) {
      i = (i + 1) & mask;
    }
    return i;
  }

  private int hashIndex(long key) {
    long h = key * 0x9E3779B97F4A7C15L; // mix; key may already be well-distributed
    return (int) (h ^ (h >>> 32)) & mask;
  }

  /** Reuse count for {@code key}, or 0 if absent (counts are always >= 1 when present). */
  int count(long key) {
    int i = slotOf(key);
    return used[i] ? counts[i] : 0;
  }

  boolean containsKey(long key) {
    return used[slotOf(key)];
  }

  /** First-visit cumulative distance for {@code key}. Precondition: {@link #containsKey}. */
  double firstPos(long key) {
    return firstPos[slotOf(key)];
  }

  /**
   * Set the first-visit position for {@code key}, creating the slot (with
   * count 0) if absent. Mirrors the former {@code edgeFirstPos.put}, which in
   * the planner always precedes the matching {@link #increment}.
   */
  void setFirstPos(long key, double pos) {
    int i = slotOf(key);
    if (used[i]) {
      firstPos[i] = pos;
      return;
    }
    used[i] = true;
    keys[i] = key;
    counts[i] = 0;
    firstPos[i] = pos;
    if (++size > threshold) {
      grow();
    }
  }

  /** Increment {@code key}'s count, inserting it with count 1 if absent. */
  void increment(long key) {
    int i = slotOf(key);
    if (used[i]) {
      counts[i]++;
      return;
    }
    used[i] = true;
    keys[i] = key;
    counts[i] = 1;
    firstPos[i] = 0.0;
    if (++size > threshold) {
      grow();
    }
  }

  /**
   * Decrement {@code key}'s count by one. Precondition: the key is present with
   * count &gt; 1 — the sole caller ({@code removeVisitedEdges}) calls {@link #remove}
   * instead when the count would reach 0, so this never drives a slot to a
   * negative ("zombie") count in practice. The {@code used[i]} check keeps a
   * stray call on an absent key a harmless no-op rather than corrupting a slot.
   */
  void decrement(long key) {
    int i = slotOf(key);
    if (used[i]) {
      counts[i]--;
    }
  }

  /** Remove {@code key} entirely (count + firstPos) if present, via backward-shift deletion. */
  void remove(long key) {
    int i = slotOf(key);
    if (!used[i]) {
      return;
    }
    used[i] = false;
    size--;
    // Backward-shift: pull up any following entry whose ideal slot is not
    // cyclically within (hole, j], keeping every key reachable by linear probe.
    int j = i;
    while (true) {
      j = (j + 1) & mask;
      if (!used[j]) {
        break;
      }
      int k = hashIndex(keys[j]);
      // Element at j is correctly placed (skip) iff k is cyclically in (i, j].
      boolean correctlyPlaced = (i <= j) ? (i < k && k <= j) : (i < k || k <= j);
      if (correctlyPlaced) {
        continue;
      }
      keys[i] = keys[j];
      counts[i] = counts[j];
      firstPos[i] = firstPos[j];
      used[i] = true;
      used[j] = false;
      i = j;
    }
  }

  private void grow() {
    long[] oldKeys = keys;
    int[] oldCounts = counts;
    double[] oldFirstPos = firstPos;
    boolean[] oldUsed = used;
    allocate((mask + 1) * 2);
    for (int s = 0; s < oldUsed.length; s++) {
      if (!oldUsed[s]) {
        continue;
      }
      int i = slotOf(oldKeys[s]); // always an empty slot in the fresh table
      used[i] = true;
      keys[i] = oldKeys[s];
      counts[i] = oldCounts[s];
      firstPos[i] = oldFirstPos[s];
      size++;
    }
  }
}
