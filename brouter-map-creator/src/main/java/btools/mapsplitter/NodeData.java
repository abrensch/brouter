package btools.mapsplitter;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import btools.util.DiffCoderDataInputStream;
import btools.util.DiffCoderDataOutputStream;

/**
 * Container for node data on the preprocessor level
 *
 * @author ab
 */
public class NodeData extends MapCreatorBase
{
  public long nid;
  public int ilon;
  public int ilat;
  public byte[] description;
  public short selev = Short.MIN_VALUE;

  public long gid; // geo-id
 
  public int zoom = -1; // the zoom level this node is on
  public int nativeIndex; // the index along all NATIVE nodes of it's tile
  
  public transient int localeIndex; // the index along all USED nodes of it's tile
  public transient boolean used; // whether this node is used by a way

  public NodeData( long id, double lon, double lat )
  {
    nid = id;
    double y = gudermannianInv( lat );
    ilat = (int)( (1.-y/Math.PI )*( 1L << 27 )+ 0.5);
    ilon = (int)( ( lon/180. + 1. )*( 1L << 27 ) + 0.5 );
  }

  public NodeData( long id, int ilon, int ilat )
  {
    this.nid = id;
    this.ilat =  ilat;
    this.ilon =  ilon;
  }

  public boolean inBBox( int z, int x, int y )
  {
    int shift = 28-z;
    int x0 = x << shift;
    int x1 = (x+1) << shift;
    int y0 = y << shift;
    int y1 = (y+1) << shift;
    boolean outofbox = x1 < ilon || x0 >= ilon  || y1 < ilat || y0 >= ilat;
    return !outofbox;
  }

  public static double gudermannianInv(double latitude)
  {
    double sign = latitude < 0. ? -1. : 1.;
    double sin = Math.sin( latitude * (Math.PI / 180.) * sign);
    return sign * (Math.log((1.0 + sin) / (1.0 - sin)) / 2.0);
  }

  public static double gudermannian(double y)
  {
    return Math.atan(Math.sinh(y)) * (180. / Math.PI);
  }
 

  public double getLon()
  {
    return (((double)ilon)/( 1L << 27 ) - 1.)*180.;
  }
  
  public double getLat()
  {
    double y = (1. - ((double)ilat)/( 1L << 27 ))*Math.PI;
    return gudermannian(y);
  }

  public void calcGeoId()
  {
    if ( zoom < 0 ) throw new IllegalArgumentException( "no zoom level yet" );
  
    gid = 0L;

    for( long bm = 1L << (27-zoom); bm > 0; bm >>= 1 )
    {
      gid <<= 2;
      if ( ( ilon & bm ) != 0 ) gid |= 1;
      if ( ( ilat & bm ) != 0 ) gid |= 2;
    }
  }
  
  public static void sortByGeoId( List<NodeData> nodes )
  {
    Collections.sort( nodes, new Comparator<NodeData>()
    {
      @Override
      public int compare(NodeData n1, NodeData n2)
      {
        long d = n1.gid - n2.gid;
        
        // for equal positions sort by nid
        if ( d == 0L )
        {
          d = n1.nid - n2.nid;
        }
        return d == 0 ? 0 : ( d < 0 ? -1 : 1 );
      }
    } );

  }

  public NodeData( DiffCoderDataInputStream dis ) throws Exception
  {
    zoom = dis.readInt();
    nativeIndex = dis.readInt();
    nid  = dis.readDiffed( 0 );
    ilon = (int)dis.readDiffed( 1 );
    ilat = (int)dis.readDiffed( 2 );
    for (;;)
    {
      String key = dis.readUTF();
      if ( key.length() == 0 ) break;
      String value = dis.readUTF();
      putTag( key, value );
    }
  }

  public void writeTo( DiffCoderDataOutputStream dos ) throws Exception  
  {
    dos.writeInt( zoom );
    dos.writeInt( nativeIndex );
    dos.writeDiffed( nid,  0 );
    dos.writeDiffed( ilon, 1 );
    dos.writeDiffed( ilat, 2 );
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
  }

  private int mercatorLon( long x, long z )
  {
    return (int) ( ( 360000000L * x ) >> z );
  }

  private int mercatorLat( long y, long z )
  {
    double n = Math.PI - ( 2.0 * Math.PI * y ) / ( 1L << z );
    double d = Math.toDegrees( Math.atan( Math.sinh( n ) ) );
    return (int) ( ( d + 90. ) * 1000000. + 0.5 );
  }

  @Override
  public boolean equals( Object o )
  {
    if ( o instanceof NodeData )
    {
      NodeData n = (NodeData) o;
      return n.nid == nid;
    }
    return false;
  }

  @Override
  public int hashCode()
  {
    return (int)((nid >> 32) ^ nid);
  }
}
