package btools.util;

import org.junit.Assert;
import org.junit.Test;

public class ByteDataIOTest {
  @Test
  public void varLengthEncodeDecodeTest() {
    byte[] ab = new byte[4000];
    ByteDataWriter w = new ByteDataWriter(ab);
    for (int i = 0; i < 1000; i++) {
      w.writeVarLengthUnsigned(i);
    }
    ByteDataReader r = new ByteDataReader(ab);

    for (int i = 0; i < 1000; i++) {
      int value = r.readVarLengthUnsigned();
      Assert.assertEquals("value mismatch", value, i);
    }
  }
}
