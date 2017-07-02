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
public abstract class HuffmanTreeEncoder<V>
{
  private HashMap<TreeNode, TreeNode> identityMap;
  protected BitWriteBuffer bwb;
  protected int pass;
  private TreeNode freq1;

  public void encode( V data )
  {
    if ( pass == 1 )
    {
      return;
    }
    TreeNode probe = new TreeNode();
    probe.data = data;
    TreeNode tn = identityMap.get( probe );
    if ( pass == 3 )
    {
      if ( tn.frequency == 1 )
      {
        bwb.encodeBounded( freq1.range - 1, freq1.code );
        encodeItem( data );
      }
      else
      {
        bwb.encodeBounded( tn.range - 1, tn.code );
      }
    }
    else if ( pass == 2 )
    {
      if ( tn == null )
      {
        tn = probe;
        identityMap.put( tn, tn );
      }
      tn.frequency++;
    }
  }

  public void encodeDictionary( BitWriteBuffer bwb )
  {
    this.bwb = bwb;
    if ( ++pass == 3 )
    {
      freq1 = new TreeNode();
      PriorityQueue<TreeNode> queue = new PriorityQueue<TreeNode>(2*identityMap.size(), new Comparator<TreeNode>()
      {
        @Override
        public int compare(TreeNode tn1, TreeNode tn2)
        {
          if ( tn1.frequency < tn2.frequency )
            return -1;
          if ( tn1.frequency > tn2.frequency )
            return 1;
          return 0;
        }
      } );
      queue.add( freq1 );
      while (queue.size() > 1)
      {
        TreeNode node = new TreeNode();
        node.child1 = queue.poll();
        node.child2 = queue.poll();
        node.frequency = node.child1.frequency + node.child2.frequency;
        queue.add( node );
      }
      TreeNode root = queue.poll();
      root.encode( 1, 0 );
    }
  }

  public HuffmanTreeEncoder()
  {
    identityMap = new HashMap<TreeNode, TreeNode>();
  }

  protected abstract void encodeItem( V data );

  protected abstract boolean itemEquals( V i1, V i2 );

  protected abstract int itemHashCode( V i);

  public final class TreeNode
  {
    public V data;
    public int frequency;
    public int code;
    public int range;
    public TreeNode child1;
    public TreeNode child2;

    public void encode( int range, int code )
    {
      this.range = range;
      this.code = code;
      boolean isNode = child1 != null;
      bwb.encodeBit( isNode );
      if ( isNode )
      {
        child1.encode( range << 1, code );
        child2.encode( range << 1, code + range );
      }
      else
      {
        bwb.encodeBit( data == null );
        if ( data != null )
        {
          encodeItem( data );
        }
      }
    }
    
    @Override
    public boolean equals( Object o )
    {      
      return itemEquals( ((TreeNode)o).data, data );
    }

    @Override
    public int hashCode()
    {
      return itemHashCode( data );
    }
  }
}
