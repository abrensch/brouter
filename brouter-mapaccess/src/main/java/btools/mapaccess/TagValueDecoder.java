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
    long size = bis.decodeUnsignedVarBits();
    if (size == 0L) {
      return null;
    }
    byte[] res = new byte[(int) (size - 1L)];
    for (int i = 0; i < res.length; i++) {
      res[i] = (byte) bis.decodeBits(8);
    }
    if (validator != null) {
      res = validator.reduce(res);
    }
    if (res == null || res.length == 0) {
      return null;
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
