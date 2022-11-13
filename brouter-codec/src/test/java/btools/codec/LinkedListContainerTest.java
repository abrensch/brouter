package btools.codec;

import org.junit.Assert;
import org.junit.Test;

public class LinkedListContainerTest {
  @Test
  public void linkedListTest1() {
    int nlists = 553;

    LinkedListContainer llc = new LinkedListContainer(nlists, null);

    for (int ln = 0; ln < nlists; ln++) {
      for (int i = 0; i < 10; i++) {
        llc.addDataElement(ln, ln * i);
      }
    }

    for (int i = 0; i < 10; i++) {
      for (int ln = 0; ln < nlists; ln++) {
        llc.addDataElement(ln, ln * i);
      }
    }

    for (int ln = 0; ln < nlists; ln++) {
      int cnt = llc.initList(ln);
      Assert.assertEquals("list size test", 20, cnt);

      for (int i = 19; i >= 0; i--) {
        int data = llc.getDataElement();
        Assert.assertEquals("data value test", ln * (i % 10), data);
      }
    }

    Assert.assertThrows("no more elements expected", IllegalArgumentException.class, () -> llc.getDataElement());
  }
}
