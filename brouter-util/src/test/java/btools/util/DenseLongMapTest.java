package btools.util;

import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public class DenseLongMapTest {
  @Test
  public void hashMapComparisonTest() {
    hashMapComparison(100000, 100000, 100000);
    hashMapComparison(100000, 100000, 13000000);
  }

  private void hashMapComparison(int mapsize, int trycount, long keyrange) {
    Random rand = new Random(12345);
    Map<Long, Integer> hmap = new HashMap<>();
    DenseLongMap dmap = new DenseLongMap(512);

    for (int i = 0; i < mapsize; i++) {
      int value = i % 255;
      long k = (long) (rand.nextDouble() * keyrange);
      Long KK = k;

      hmap.put(KK, value);
      dmap.put(k, value); // duplicate puts allowed!
    }

    for (int i = 0; i < trycount; i++) {
      long k = (long) (rand.nextDouble() * keyrange);
      Long KK = k;
      Integer VV = hmap.get(KK);
      int hvalue = VV == null ? -1 : VV;
      int dvalue = dmap.getInt(k);

      if (hvalue != dvalue) {
        Assert.fail("value missmatch for key " + k + " hashmap=" + hvalue + " densemap=" + dvalue);
      }
    }
  }

  @Test
  public void oneBitTest() {
    int keyrange = 300000;
    int mapputs = 100000;
    int trycount = 100000;

    Random rand = new Random(12345);
    Set<Long> hset = new HashSet<>();

    DenseLongMap dmap = new DenseLongMap(512);
    for (int i = 0; i < mapputs; i++) {
      long k = (long) (rand.nextDouble() * keyrange);
      hset.add(k);
      dmap.put(k, 0);
    }
    for (int i = 0; i < trycount; i++) {
      long k = (long) (rand.nextDouble() * keyrange);
      boolean hcontains = hset.contains(k);
      boolean dcontains = dmap.getInt(k) == 0;

      if (hcontains != dcontains) {
        Assert.fail("value missmatch for key " + k + " hashset=" + hcontains + " densemap=" + dcontains);
      }
    }
  }

  // @Test - memory test disabled for load reasons
  public void memoryUsageTest() {
    int keyrange = 32000000;
    int mapputs = keyrange * 2;

    Random rand = new Random(12345);
    DenseLongMap dmap = new DenseLongMap(6);

    System.gc();
    long mem1 = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

    for (int i = 0; i < mapputs; i++) {
      int value = i % 63;
      long k = (long) (rand.nextDouble() * keyrange);
      dmap.put(k, value);
    }

    System.gc();
    long mem2 = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

    long memusage = mem2 - mem1;

    if (memusage > (keyrange / 8) * 7) {
      Assert.fail("memory usage too high: " + memusage + " for keyrange " + keyrange);
    }

    // need to use the map again for valid memory measure
    Assert.assertEquals("out of range test", -1, dmap.getInt(-1));
  }

}
