package btools.mapdecoder;

/**
 * Decoder for unicode characters, using simple (1st order) huffmann
 */
public final class CharDecoder extends HuffmannTreeDecoder<Character>
{
  private long[] alphabet;
  private int range;
  private char[] buffer = new char[64];
  
  public CharDecoder( BitReadBuffer brb )
  {
    super( brb );
  }
  
  @Override
  protected Object decodeTree()
  {
    alphabet = brb.decodeSortedArray();
    range = alphabet.length - 1;
System.out.println( "decoded alphabet of length " + alphabet.length + " idx3 = " + alphabet[3] ); 
    return super.decodeTree();
  }

  protected Character decodeItem()
  {
    int idx = (int)brb.decodeBounded( range );
    long lc = alphabet[idx];
System.out.println( "decoded item: c=" + ((char)lc) + " idx=" + idx );
    return Character.valueOf( (char)lc );
  }

  public String decodeString()
  {
    int n = brb.decodeInt();
    char[] b = n <= buffer.length ? buffer : new char[n];
    for( int i=0; i<n; i++ )
    {
      b[i] = decode().charValue();
    }
    return new String( b, 0, n );
  }
}
