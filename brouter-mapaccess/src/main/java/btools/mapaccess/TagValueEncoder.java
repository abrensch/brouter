package btools.mapaccess;

import btools.mapaccess.TagValueWrapper;
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
  protected  void encodeObjectToStream(TagValueWrapper tagValues) throws IOException {
    tagValues.writeToBitstream( bos );
  }

  private TagValueWrapper lastObject ;

  public void encodeTagValues( byte[] tagValues ) throws IOException {
    TagValueWrapper w = new TagValueWrapper();
    w.data = tagValues;

    if ( w.equals( lastObject))  {
      System.out.println( "tw-repeat!" );
    } else {
      System.out.println( "tw-new" );
      lastObject = w;
    }
    encodeObject(w);
  }
}
