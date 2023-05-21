package btools.util;

import org.junit.Assert;
import org.junit.Test;

import java.util.Random;

public class SortedHeapTest {
  @Test
  public void sortedHeapTest1() {
    SortedHeap<String> sh = new SortedHeap<>();
    Random rnd = new Random();
    for (int i = 0; i < 100000; i++) {
      int val = rnd.nextInt(1000000);
      sh.add(val, "" + val);
      val = rnd.nextInt(1000000);
      sh.add(val, "" + val);
      sh.popLowestKeyValue();
    }

    int cnt = 0;
    int lastval = 0;
    for (; ; ) {
      String s = sh.popLowestKeyValue();
      if (s == null) break;
      cnt++;
      int val = Integer.parseInt(s);
      Assert.assertTrue("sorting test", val >= lastval);
      lastval = val;
    }
    Assert.assertEquals("total count test", 100000, cnt);

  }

  @Test
  public void sortedHeapTest2() {
    SortedHeap<String> sh = new SortedHeap<>();
    Random rnd = new Random();
    for (int i = 0; i < 100000; i++) {
      sh.add(i, "" + i);
    }

    int cnt = 0;
    int expected = 0;
    for (; ; ) {
      String s = sh.popLowestKeyValue();
      if (s == null) break;
      cnt++;
      int val = Integer.parseInt(s);
      Assert.assertEquals("sequence test", val, expected);
      expected++;
    }
    Assert.assertEquals("total count test", 100000, cnt);

  }
}
