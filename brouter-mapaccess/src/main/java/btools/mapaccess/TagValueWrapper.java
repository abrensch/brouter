package btools.mapaccess;


import btools.util.TagValueValidator;
import btools.statcoding.BitInputStream;
import btools.statcoding.BitOutputStream;
import btools.util.BitCoderContext;

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
    BitCoderContext src = new BitCoderContext(data);
    for (; ; ) {
      int delta = src.decodeVarBits();
      bos.encodeUnsignedVarBits(delta);
      if (delta == 0) {
        break;
      }
      int data = src.decodeVarBits();
      bos.encodeUnsignedVarBits(data);
    }
  }

  public static TagValueWrapper readFromBitStream(BitInputStream bis, DataBuffers buffers, TagValueValidator validator ) throws IOException {
    byte[] buffer = buffers.tagbuf1;
    BitCoderContext ctx = buffers.bctx1;
    ctx.reset(buffer);

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
        ctx.encodeVarBits(0);
        break;
      }
      inum += (int)delta;

      long data = bis.decodeUnsignedVarBits();

      if (validator == null || validator.isLookupIdxUsed(inum)) {
        hasdata = true;
        ctx.encodeVarBits(inum - lastEncodedInum);
        ctx.encodeVarBits((int)data);
        lastEncodedInum = inum;
      }
    }

    byte[] res;
    int len = ctx.closeAndGetEncodedLength();
    if (validator == null) {
      res = new byte[len];
      System.arraycopy(buffer, 0, res, 0, len);
    } else {
      res = validator.unify(buffer, 0, len);
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
