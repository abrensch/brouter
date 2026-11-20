package btools.mapaccess;

import btools.statcoding.huffman.HuffmanEncoder;

import java.io.IOException;

/**
 * Encoder for way-/node-descriptions
 * <p>
 * Using the Huffman encoder from the statcoding lib
 * (which is a 2-pass encoder using a static tree)
 */
public final class TagValueEncoder extends HuffmanEncoder<TagValueWrapper> {
  @Override
  protected void encodeObjectToStream(TagValueWrapper tagValues) throws IOException {
    // write byte array bit-aligned to stream, null/empty aware
    byte[] data = tagValues.data;
    bos.encodeUnsignedVarBits(data == null ? 0L : (long) data.length + 1L);
    if (data != null) {
      for (byte b : data) {
        bos.encodeBits(8, b & 0xffL);
      }
    }
  }

  public void encodeTagValues( byte[] tagValues ) throws IOException {
    TagValueWrapper w = new TagValueWrapper();
    w.data = tagValues;
    encodeObject(w);
  }
}
