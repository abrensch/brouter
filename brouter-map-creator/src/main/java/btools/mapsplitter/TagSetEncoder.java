package btools.mapsplitter;

import java.util.Comparator;
import java.util.HashMap;
import java.util.PriorityQueue;

/**
 * Encoder for a set of tags
 *
 * It detects identical sets and sorts them
 * into a huffman-tree according to their frequencies
 *
 * Adapted for 3-pass encoding (counters -> statistics -> encoding )
 * but doesn't do anything at pass1
 */
public final class TagSetEncoder
{
  private HashMap<TagSet, TagSet> identityMap;
  private BitWriteBuffer bwb;
  private int pass;
  private TagSet freq1;

  public void encodeTagSet( int[] data )
  {
    if ( pass == 1 )
    {
      return;
    }
    TagSet tvsProbe = new TagSet();
    tvsProbe.data = data;
    TagSet tvs = identityMap.get( tvsProbe );
    if ( pass == 3 )
    {
      if ( tvs.frequency == 1 )
      {
        bwb.encodeBounded( freq1.range - 1, freq1.code );
        encodeTagSequence( bwb, data );
      }
      else
      {
        bwb.encodeBounded( tvs.range - 1, tvs.code );
      }
    }
    else if ( pass == 2 )
    {
      if ( tvs == null )
      {
        tvs = tvsProbe;
        identityMap.put( tvs, tvs );
      }
      tvs.frequency++;
    }
  }

  public void encodeDictionary( BitWriteBuffer bwb )
  {
    if ( ++pass == 3 )
    {
      freq1 = new TagSet();
      PriorityQueue<TagSet> queue = new PriorityQueue<TagSet>(2*identityMap.size(), new TagSet.FrequencyComparator());
      for( TagSet ts : identityMap.values() )
      {
         if ( ts.frequency > 1 )
         {
           queue.add( ts );
         }
         else
         {
           freq1.frequency++;
         }
      }
      queue.add( freq1 );
      while (queue.size() > 1)
      {
        TagSet node = new TagSet();
        node.child1 = queue.poll();
        node.child2 = queue.poll();
        node.frequency = node.child1.frequency + node.child2.frequency;
        queue.add( node );
      }
      TagSet root = queue.poll();
      root.encode( bwb, 1, 0 );
    }
    this.bwb = bwb;
  }

  public TagSetEncoder()
  {
    identityMap = new HashMap<TagSet, TagSet>();
  }

  private static void encodeTagSequence( BitWriteBuffer bwb, int[] data )
  {
    int tagcount = data.length;
    bwb.encodeInt( tagcount );
    int lastIdx = -1;
    for( int i=0; i<tagcount; i++ )
    {
      int idx = data[i];
      bwb.encodeInt( idx - lastIdx -1 );
      lastIdx = idx;
    }
  }

  public static final class TagSet
  {
    public int[] data;
    public int frequency;
    public int code;
    public int range;
    public TagSet child1;
    public TagSet child2;

    public void encode( BitWriteBuffer bwb, int range, int code )
    {
      this.range = range;
      this.code = code;
      boolean isNode = child1 != null;
      bwb.encodeBit( isNode );
      if ( isNode )
      {
        child1.encode( bwb, range << 1, code );
        child2.encode( bwb, range << 1, code + range );
      }
      else
      {
        bwb.encodeBit( data == null );
        if ( data != null )
        {
          encodeTagSequence( bwb, data );
        }
      }
    }
    
    @Override
    public boolean equals( Object o )
    {
      if ( o instanceof TagSet )
      {
        TagSet tvs = (TagSet) o;
        if ( data == null )
        {
          return tvs.data == null;
        }
        if ( tvs.data == null )
        {
          return data == null;
        }
        if ( data.length != tvs.data.length )
        {
          return false;
        }
        for ( int i = 0; i < data.length; i++ )
        {
          if ( data[i] != tvs.data[i] )
          {
            return false;
          }
        }
        return true;
      }
      return false;
    }

    @Override
    public int hashCode()
    {
      if ( data == null )
      {
        return 0;
      }
      int h = 17;
      for ( int i = 0; i < data.length; i++ )
      {
        h = ( h << 8 ) + data[i];
      }
      return h;
    }

    public static class FrequencyComparator implements Comparator<TagSet>
    {

      @Override
      public int compare(TagSet tvs1, TagSet tvs2) {
        if ( tvs1.frequency < tvs2.frequency )
          return -1;
        if ( tvs1.frequency > tvs2.frequency )
          return 1;
        return 0;
      }
    }

  }
}
