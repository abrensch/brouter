package btools.util;

import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class CompactMapTest {
  @Test
  public void hashMapComparisonTest() {
    hashMapComparison(0, 1);
    hashMapComparison(1, 1);
    hashMapComparison(2, 2);
    hashMapComparison(3, 3);
    hashMapComparison(4, 4);
    hashMapComparison(5, 5);
    hashMapComparison(7, 10);
    hashMapComparison(8, 10);
    hashMapComparison(10000, 20000);
  }

  private void hashMapComparison(int mapsize, int trycount) {
    Random rand = new Random(12345);
    Map<Long, String> hmap = new HashMap<>();
    CompactLongMap<String> cmap_slow = new CompactLongMap<>();
    CompactLongMap<String> cmap_fast = new CompactLongMap<>();

    for (int i = 0; i < mapsize; i++) {
      String s = "" + i;
      long k = mapsize < 10 ? i : rand.nextInt(20000);
      Long KK = k;

      if (!hmap.containsKey(KK)) {
        hmap.put(KK, s);
        cmap_slow.put(k, s);
        cmap_fast.fastPut(k, s);
      }
    }

    for (int i = 0; i < trycount * 2; i++) {
      if (i == trycount) {
        cmap_slow = new FrozenLongMap<>(cmap_slow);
        cmap_fast = new FrozenLongMap<>(cmap_fast);
      }
      long k = mapsize < 10 ? i : rand.nextInt(20000);
      Long KK = k;
      String s = hmap.get(KK);

      boolean contained = hmap.containsKey(KK);
      Assert.assertEquals("containsKey missmatch (slow)", contained, cmap_slow.contains(k));
      Assert.assertEquals("containsKey missmatch (fast)", contained, cmap_fast.contains(k));

      if (contained) {
        Assert.assertEquals("object missmatch (fast)", s, cmap_fast.get(k));
        Assert.assertEquals("object missmatch (slow)", s, cmap_slow.get(k));
      }
    }
  }
}
