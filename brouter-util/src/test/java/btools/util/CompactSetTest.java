package btools.util;

import org.junit.Assert;
import org.junit.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

public class CompactSetTest {
  @Test
  public void hashSetComparisonTest() {
    hashSetComparison(0, 1);
    hashSetComparison(1, 1);
    hashSetComparison(2, 2);
    hashSetComparison(3, 3);
    hashSetComparison(4, 4);
    hashSetComparison(5, 5);
    hashSetComparison(7, 10);
    hashSetComparison(8, 10);
    hashSetComparison(10000, 20000);
  }

  private void hashSetComparison(int setsize, int trycount) {
    Random rand = new Random(12345);
    Set<Long> hset = new HashSet<>();
    CompactLongSet cset_slow = new CompactLongSet();
    CompactLongSet cset_fast = new CompactLongSet();

    for (int i = 0; i < setsize; i++) {
      long k = setsize < 10 ? i : rand.nextInt(20000);
      Long KK = k;

      if (!hset.contains(KK)) {
        hset.add(KK);
        cset_slow.add(k);
        cset_fast.fastAdd(k);
      }
    }

    for (int i = 0; i < trycount * 2; i++) {
      if (i == trycount) {
        cset_slow = new FrozenLongSet(cset_slow);
        cset_fast = new FrozenLongSet(cset_fast);
      }
      long k = setsize < 10 ? i : rand.nextInt(20000);
      Long KK = k;

      boolean contained = hset.contains(KK);
      Assert.assertEquals("contains missmatch (slow)", contained, cset_slow.contains(k));
      Assert.assertEquals("contains missmatch (fast)", contained, cset_fast.contains(k));
    }
  }
}
