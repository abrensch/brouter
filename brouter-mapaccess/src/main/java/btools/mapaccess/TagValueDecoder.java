package btools.mapaccess;

import btools.util.TagValueValidator;
import btools.statcoding.huffman.HuffmanDecoder;

import java.io.IOException;

/**
 * Decoder for way-/node-descriptions
 * <p>
 * Using the Huffman decoder from the statcoding lib
 * (which is a 2-pass encoder using a static tree)
 */
public final class TagValueDecoder extends HuffmanDecoder<TagValueWrapper> {

  public TagValueValidator validator;

  public TagValueDecoder( TagValueValidator validator) {
    this.validator = validator;
  }
  @Override
  protected TagValueWrapper decodeObjectFromStream() throws IOException {

    // decode the byte array from the bitstream
    long size = bis.decodeUnsignedVarBits();
    if (size == 0L) {
      return null;
    }
    byte[] res = new byte[(int) (size - 1L)];
    for (int i = 0; i < res.length; i++) {
      res[i] = (byte) bis.decodeBits(8);
    }

    // reduce it to the tags actually used
    res = validator == null ? res : validator.reduce(res);

    // check access type, unify if accessible
    int accessType = validator == null ? 2 : validator.accessType(res);
    if (accessType > 0) {
      TagValueWrapper w = new TagValueWrapper();
      w.data = validator == null ? res : validator.unify(res);
      w.accessType = accessType;
      return w;
    }
    return null;
  }
}
