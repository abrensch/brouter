package btools.mapsplitter;

import java.util.TreeMap;

/**
 * Encoder for characters, using simple (1st order) huffmann
 */
public final class CharEncoder extends HuffmanTreeEncoder<Character>
{
  private long[] alphabet;
  private int range;

  private TreeMap<Character,Integer> chars = new TreeMap<Character,Integer>();

  public void encode( Character c )
  {
    if ( pass == 1 )
    {
      chars.put( c, null );
    }
    super.encode( c );
  }

  public void encodeDictionary( BitWriteBuffer bwb )
  {
    if ( pass == 1 ) // means 2...
    {
      int idx = 0;
      alphabet = new long[chars.size()];
      range = chars.size()-1;
      for ( Character c : chars.keySet() )
      {
System.out.println( "assigning index " + idx + " to char=" + c );      
        alphabet[idx] = c;
        chars.put( c, Integer.valueOf( idx++ ) );
      }
    }
    if ( alphabet != null )
    {
      bwb.encodeSortedArray( alphabet );
    }
    super.encodeDictionary( bwb );
  }

  protected void encodeItem( Character c )
  {
     int idx = chars.get( c ).intValue();
System.out.println( "encoding item: c=" + c + " idx=" + idx );
     bwb.encodeBounded( range, idx );
  }

  @Override
  public boolean itemEquals( Character c1, Character c2 )
  {
    if ( c1 == null )
    {
      return c2 == null;
    }
    if ( c2 == null )
    {
      return false;
    }
    return c1.charValue() == c2.charValue();
  }

  @Override
  public int itemHashCode( Character c)
  {
    return c == 0 ? 0 : c.charValue();
  }
}
