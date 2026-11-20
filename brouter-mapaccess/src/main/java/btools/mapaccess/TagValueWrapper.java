package btools.mapaccess;

import java.util.Arrays;

/**
 * TagValueWrapper wraps a description bitmap
 * (together with an access type) to provide an
 * object instance as needed by the Huffman-Codec
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
}
