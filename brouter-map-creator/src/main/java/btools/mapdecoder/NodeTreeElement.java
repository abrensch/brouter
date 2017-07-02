package btools.mapdecoder;

import java.util.List;


public class NodeTreeElement
{
  public int offset;
  
  public int nnodes;

  public NodeTreeElement child0;

  public NodeTreeElement child1;
  
  public List<OsmNode> nodes;
  
  public static NodeTreeElement createNodeTree( long[] values, int offset, int subsize, long nextbit, long mask )
  {
    if ( nextbit == 0 )
    {
      return null;
    }

    if ( subsize < 1 )
    {
      return null;
    }

    long data = mask & values[offset];
    mask |= nextbit;

    // count 0-bit-fraction
    int i = offset;
    int end = subsize + offset;
    for ( ; i < end; i++ )
    {
      if ( ( values[i] & mask ) != data )
      {
        break;
      }
    }
    int size1 = i - offset;
    int size2 = subsize - size1;

System.out.println( "createNodeTree: offset=" + offset + " subsize=" + subsize + " size1=" + size1 + " size2=" + size2 );
    
    NodeTreeElement nte = new NodeTreeElement();
    nte.offset = offset;
    nte.nnodes = subsize;

    nte.child0 = createNodeTree( values, offset, size1, nextbit >> 1, mask );
    nte.child1 = createNodeTree( values, i, size2, nextbit >> 1, mask );
    
    return nte;
  }
  
  public String toString()
  {
    return " child0=" + (child0 != null ) + " child1=" + (child1 != null );
  }
  
}
