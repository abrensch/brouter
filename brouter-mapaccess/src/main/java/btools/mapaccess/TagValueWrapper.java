package btools.mapaccess;


import btools.util.TagValueValidator;
import btools.statcoding.BitInputStream;
import btools.statcoding.BitOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * TagValueWrapper wraps a description bitmap
 * to add the access-type
 */
public final class TagValueWrapper {
  public byte[] data;
  public int accessType;

  @Override
  public boolean equals( Object obj ) {
    return obj instanceof TagValueWrapper && Arrays.equals( data, ((TagValueWrapper)obj).data );
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(data);
  }

  public void writeToBitstream( BitOutputStream bos ) throws IOException {
    if (data == null) {
      bos.encodeUnsignedVarBits(0L);
      return;
    }
    try ( BitInputStream src = new BitInputStream(data) ) {
      for (; ; ) {
        long delta = src.decodeUnsignedVarBits();
        bos.encodeUnsignedVarBits(delta);
        if (delta == 0) {
          break;
        }
        long data = src.decodeUnsignedVarBits();
        bos.encodeUnsignedVarBits(data);
      }
    }
  }

  public static TagValueWrapper readFromBitStream(BitInputStream bis, DataBuffers buffers, TagValueValidator validator ) throws IOException {

    BitOutputStream bos = buffers.tagEncoder;
    buffers.tagBuffer.reset();
    bos.close(); // close also resets the bit-buffer (but not the byte-counter!)

    int inum = 0;
    int lastEncodedInum = 0;

    boolean hasdata = false;
    for (; ; ) {
      long delta = bis.decodeUnsignedVarBits();
      if (!hasdata) {
        if (delta == 0L) {
          return null;
        }
      }
      if (delta == 0L) {
        bos.encodeUnsignedVarBits(0);
        break;
      }
      inum += (int)delta;

      long data = bis.decodeUnsignedVarBits();

      if (validator == null || validator.isLookupIdxUsed(inum)) {
        hasdata = true;
        bos.encodeUnsignedVarBits(inum - lastEncodedInum);
        bos.encodeUnsignedVarBits(data);
        lastEncodedInum = inum;
      }
    }

    bos.close();
    byte[] res;
    int len = buffers.tagBuffer.getSize();
    if (validator == null) {
      res = new byte[len];
      System.arraycopy(buffers.tagBuffer.getBuffer(),0, res, 0, len );
    } else {
      res = validator.unify(buffers.tagBuffer.getBuffer(), 0, len);
    }

    int accessType = validator == null ? 2 : validator.accessType(res);
    if (accessType > 0) {
      TagValueWrapper w = new TagValueWrapper();
      w.data = res;
      w.accessType = accessType;
      return w;
    }
    return null;
  }
}
