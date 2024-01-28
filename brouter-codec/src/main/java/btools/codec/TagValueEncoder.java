package btools.codec;

import btools.statcoding.huffman.HuffmanEncoder;
import btools.util.BitCoderContext;

import java.io.IOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Encoder for way-/node-descriptions
 * <p>
 * Using the Huffman encoder from the statcoding lib
 * (which is a 2-pass encoder using a static tree)
 */
public final class TagValueEncoder extends HuffmanEncoder<TagValueWrapper> {
  @Override
  protected  void encodeObjectToStream(TagValueWrapper tagValues) throws IOException {
    tagValues.writeToBitstream( bos );
  }

  public void encodeTagValues( byte[] tagValues ) throws IOException {
    TagValueWrapper w = new TagValueWrapper();
    w.data = tagValues;
    encodeObject(w);
  }
}
