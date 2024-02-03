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

  public DataBuffers buffers;
  public TagValueValidator validator;

  public TagValueDecoder( DataBuffers buffers, TagValueValidator validator) {
    this.buffers = buffers;
    this.validator = validator;
  }
  @Override
  protected  TagValueWrapper decodeObjectFromStream() throws IOException {
    return TagValueWrapper.readFromBitStream( bis, buffers, validator );
  }
}
