package btools.mapdecoder;

/**
 * Decoder for static huffmann coded data
 */
public abstract class HuffmannTreeDecoder<V>
{
  private Object tree;
  protected BitReadBuffer brb;

  protected HuffmannTreeDecoder( BitReadBuffer brb )
  {
    this.brb = brb;
    tree = decodeTree();
  }

  public V decode()
  {
    Object node = tree;
    while (node instanceof TreeNode)
    {
      TreeNode tn = (TreeNode) node;
      node = brb.decodeBit() ? tn.child2 : tn.child1;
    }
    if ( node == null )
    {
      return decodeItem(); // inline item
    }
    return (V) node;
  }


  protected Object decodeTree()
  {
    boolean isNode = brb.decodeBit();
    if ( isNode )
    {
      TreeNode node = new TreeNode();
      node.child1 = decodeTree();
      node.child2 = decodeTree();
      return node;
    }
    boolean isInlinePrefix = brb.decodeBit();
    if ( isInlinePrefix )
    {
      return null;
    }
    return decodeItem();
  }
  

  private static final class TreeNode
  {
    public Object child1;
    public Object child2;
  }

  protected abstract V decodeItem();
}
