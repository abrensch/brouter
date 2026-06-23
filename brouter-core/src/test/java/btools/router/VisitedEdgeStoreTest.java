package btools.router;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link VisitedEdgeStore}, the primitive open-addressing
 * reuse-count + first-visit-position store used by the greedy planner. The
 * differential fuzz in {@code GreedyLoopPerfInvariantTest} exercises the
 * planner's operation grammar against reference maps; these tests pin the API
 * contracts directly — the documented preconditions, the grow-rehash, and the
 * "no reserved sentinel key" invariant.
 */
public class VisitedEdgeStoreTest {

  @Test
  public void absentKeyHasZeroCountAndIsNotPresent() {
    VisitedEdgeStore s = new VisitedEdgeStore();
    assertTrue(s.isEmpty());
    assertEquals("absent key counts as 0", 0, s.count(12345L));
    assertFalse(s.containsKey(12345L));
    assertEquals(0, s.size());
  }

  @Test
  public void incrementInsertsThenAccumulates() {
    VisitedEdgeStore s = new VisitedEdgeStore();
    s.increment(7L);
    assertEquals(1, s.count(7L));
    assertTrue(s.containsKey(7L));
    s.increment(7L);
    assertEquals(2, s.count(7L));
    assertEquals("distinct-key count, not traversal count", 1, s.size());
  }

  @Test
  public void removeAbsentIsNoOpAndPresentClearsBothColumns() {
    VisitedEdgeStore s = new VisitedEdgeStore();
    s.remove(99L); // absent → no-op
    assertEquals(0, s.size());
    s.increment(99L);
    s.setFirstPos(99L, 17.0);
    s.remove(99L);
    assertFalse(s.containsKey(99L));
    assertEquals(0, s.count(99L));
    assertTrue(s.isEmpty());
  }

  @Test
  public void decrementBelowPreconditionLeavesZeroCountButStillPresent() {
    // The documented precondition for decrement() is "present with count > 1";
    // the planner upholds it by using remove() at count 1. Calling decrement on
    // a count-1 key drops the count to 0 WITHOUT freeing the slot. Pinned as
    // characterization so the "stored counts are always >= 1" invariant is
    // visibly the caller's responsibility, not the store's.
    VisitedEdgeStore s = new VisitedEdgeStore();
    s.increment(5L); // count 1
    s.decrement(5L); // precondition-violating call
    assertEquals("count drops to 0", 0, s.count(5L));
    assertTrue("decrement does not free the slot", s.containsKey(5L));
    assertEquals("size unchanged by decrement", 1, s.size());
  }

  @Test
  public void setFirstPosCreatesWithZeroCountThenIncrementPreservesIt() {
    VisitedEdgeStore s = new VisitedEdgeStore();
    s.setFirstPos(3L, 42.0);
    assertTrue(s.containsKey(3L));
    assertEquals("setFirstPos creates the slot with count 0", 0, s.count(3L));
    assertEquals(42.0, s.firstPos(3L), 0.0);
    s.increment(3L); // present → count becomes 1, firstPos untouched
    assertEquals(1, s.count(3L));
    assertEquals("firstPos preserved across increment", 42.0, s.firstPos(3L), 0.0);
  }

  @Test
  public void setFirstPosOverwritesWhenAlreadyPresent() {
    VisitedEdgeStore s = new VisitedEdgeStore();
    s.increment(8L); // increment seeds firstPos = 0.0
    assertEquals(0.0, s.firstPos(8L), 0.0);
    s.setFirstPos(8L, 1234.5);
    assertEquals("overwrites the existing firstPos", 1234.5, s.firstPos(8L), 0.0);
    assertEquals("count unchanged by setFirstPos", 1, s.count(8L));
  }

  @Test
  public void fullRange64BitKeysAreStored_noReservedSentinel() {
    // edgeKey values span the full 64-bit range, so 0L / -1L / MIN / MAX must
    // all be storable as real keys (occupancy is a flag, not a sentinel key).
    VisitedEdgeStore s = new VisitedEdgeStore();
    long[] tricky = {0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE};
    for (long k : tricky) {
      s.increment(k);
    }
    for (long k : tricky) {
      assertTrue("key " + k + " present", s.containsKey(k));
      assertEquals(1, s.count(k));
    }
    assertEquals(tricky.length, s.size());
  }

  @Test
  public void growPreservesEveryKeyCountAndFirstPos() {
    // Initial capacity 16 → threshold 9; 20 distinct keys forces two grows
    // (at cap 16 and again at cap 32). Every key's count and firstPos must
    // survive the rehash.
    VisitedEdgeStore s = new VisitedEdgeStore();
    int n = 20;
    for (int i = 0; i < n; i++) {
      long k = 1000L + i;
      s.setFirstPos(k, i * 100.0);
      for (int c = 0; c <= i; c++) {
        s.increment(k); // count = i + 1
      }
    }
    assertEquals(n, s.size());
    for (int i = 0; i < n; i++) {
      long k = 1000L + i;
      assertEquals("count survived grow for key " + k, i + 1, s.count(k));
      assertEquals("firstPos survived grow for key " + k, i * 100.0, s.firstPos(k), 0.0);
    }
  }
}
