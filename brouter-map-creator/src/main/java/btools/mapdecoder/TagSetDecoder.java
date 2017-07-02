package btools.mapdecoder;

/**
 * Decoder for a set of tags
 *
 * Only tagsets detected at least twice
 * have their own huffmann-codes, those
 * detected only once are coded inline
 */
public final class TagSetDecoder extends HuffmannTreeDecoder<int[]>
{
  public TagSetDecoder( BitReadBuffer brb )
  {
    super( brb );
  }

  protected int[] decodeItem()
  {
    int tagcount = brb.decodeInt();
    int[] data = new int[tagcount];
    int lastIdx = -1;
    for( int i=0; i<tagcount; i++ )
    {
      int idx = lastIdx + 1 + brb.decodeInt();
      data[i] = idx;
      lastIdx = idx;
    }
    return data;
  }
}
