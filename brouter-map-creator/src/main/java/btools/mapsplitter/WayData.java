package btools.mapsplitter;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import btools.util.LongList;

/**
 * Container for waydata on the preprocessor level
 *
 * @author ab
 */
public class WayData extends MapCreatorBase
{
  public long wid;
  public LongList nodes;
  
  public int startNodeIdx;

  private int minx;
  private int miny;
  private int maxx;
  private int maxy;

  public int zoom = -1; // the zoom level this node is on
  public int nativeIndex; // the index along all NATIVE ways of it's tile

  public void calcBBox( List<NodeData> nodeList )
  {
    int nn = nodes.size();
    for( int i=0; i<nn; i++ )
    {
      NodeData n = nodeList.get((int)nodes.get(i));
      if ( i == 0 )
      {
        minx = maxx = n.ilon;
        miny = maxy = n.ilat;
      }
      else
      {
        if ( n.ilon < minx ) minx = n.ilon;
        if ( n.ilon > maxx ) maxx = n.ilon;
        if ( n.ilat < miny ) miny = n.ilat;
        if ( n.ilat > maxy ) maxy = n.ilat;
      }              
    }
  }
 
  public boolean inBBox( int z, int x, int y )
  {
    int shift = 28-z;
    int x0 = x << shift;
    int x1 = (x+1) << shift;
    int y0 = y << shift;
    int y1 = (y+1) << shift;
    boolean outofbox = x1 < minx || x0 >= maxx  || y1 < miny || y0 >= maxy;
    return !outofbox;
  }

  public WayData( long id  )
  {
    wid = id;
    nodes = new LongList( 16 );
  }

  public WayData( long id, LongList nodes  )
  {
    wid = id;
    this.nodes = nodes;
  }

  public WayData( DataInputStream di ) throws Exception
  {
    zoom = di.readInt();
    nativeIndex = di.readInt();
    nodes = new LongList( 16 );
    wid = readId( di) ;
    for (;;)
    {
      String key = di.readUTF();
      if ( key.length() == 0 ) break;
      String value = di.readUTF();
      putTag( key, value );
    }
    for (;;)
    {
      long nid = readId( di );
      if ( nid == -1 ) break;
      nodes.add( nid );
    }
  }    

  public void writeTo( DataOutputStream dos ) throws Exception  
  {
    dos.writeInt( zoom );
    dos.writeInt( nativeIndex );
    writeId( dos, wid );
    if ( getTagsOrNull() != null )
    {
      for( Map.Entry<String,String> me : getTagsOrNull().entrySet() )
      {
        if ( me.getKey().length() > 0 )
        {
          dos.writeUTF( me.getKey() );
          dos.writeUTF( me.getValue() );
        }
      }
    }
    dos.writeUTF( "" );
    
    int size = nodes.size();
    for( int i=0; i < size; i++ )
    {
      writeId( dos, nodes.get( i ) );
    }
    writeId( dos, -1 ); // stopbyte
  }

  public static void sortByStartNode( List<WayData> ways )
  {
    Collections.sort( ways, new Comparator<WayData>()
    {
      @Override
      public int compare(WayData w1, WayData w2)
      {
        long d = w1.startNodeIdx - w2.startNodeIdx;
        
        // for equal start indexes sort by wid
        if ( d == 0L )
        {
          d = w1.wid - w2.wid;
        }
        return d == 0 ? 0 : ( d < 0 ? -1 : 1 );
      }
    } );

  }

  @Override
  public boolean equals( Object o )
  {
    if ( o instanceof WayData )
    {
      WayData w = (WayData) o;
      return w.wid == wid;
    }
    return false;
  }

  @Override
  public int hashCode()
  {
    return (int)((wid >> 32) ^ wid);
  }
}
