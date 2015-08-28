/**
 * Container for link between two Osm nodes (pre-pocessor version)
 *
 * @author ab
 */
package btools.mapcreator;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;

import btools.util.CompactLongMap;
import btools.util.FrozenLongMap;


public class OsmTrafficMap
{
  int minLon;
  int minLat;
  int maxLon;
  int maxLat;

  public static class OsmTrafficElement
  {
    public long node2;
    public int traffic;
    public OsmTrafficElement next;
  }
  
  private CompactLongMap<OsmTrafficElement> map = new CompactLongMap<OsmTrafficElement>();

  public long[] load( File file, int minLon, int minLat, int maxLon, int maxLat, boolean includeMotorways ) throws Exception
  {
    this.minLon = minLon;
    this.minLat = minLat;
    this.maxLon = maxLon;
    this.maxLat = maxLat;

      int trafficElements = 0;
      DataInputStream is = new DataInputStream( new BufferedInputStream( new FileInputStream( file ) ) );
      try
      {
        for(;;)
        {
          long n1 = is.readLong();
          long n2 = is.readLong();
          int traffic = is.readInt();
          if ( traffic == -1 && !includeMotorways )
          {
            continue;
          }
          if ( isInsideBounds( n1 ) || isInsideBounds( n2 ) )
          {
            if ( addElement( n1, n2, traffic ) )
            {
              trafficElements++;
            }
          }
        }
      }
      catch( EOFException eof ) {}
      finally{ is.close(); }
      
      FrozenLongMap fmap = new FrozenLongMap<OsmTrafficElement>( map );
      map = fmap;
      System.out.println( "read traffic-elements: " + trafficElements );
      return fmap.getKeyArray();
  }


  public boolean addElement( long n1, long n2, int traffic )
  {
    OsmTrafficElement e = getElement( n1, n2 );
    if ( e == null )
    {
      e = new OsmTrafficElement();
      e.node2 = n2;
      e.traffic = traffic;

      OsmTrafficElement e0 = map.get( n1 );
      if ( e0 != null )
      {
        while( e0.next != null )
        {
          e0 = e0.next;
        }
        e0.next = e;
      }
      else
      {
        map.fastPut( n1, e );
      }
      return true;
    }
    e.traffic = e.traffic == -1 || traffic == -1 ? -1 : e.traffic + traffic;
    return false;
  }
  
  private boolean isInsideBounds( long id )
  {
    int ilon = (int)(id >> 32);
    int ilat = (int)(id & 0xffffffff);
    
    return ilon >= minLon && ilon < maxLon && ilat >= minLat && ilat < maxLat;
  }

  public int getTrafficClass( long n1, long n2 )
  {
    int traffic1 = getTraffic( n1, n2 );
    int traffic2 = getTraffic( n2, n1 );
    int traffic = traffic1 == -1 || traffic2 == -1 ? -1 : traffic1 > traffic2 ? traffic1 : traffic2;
    return getTrafficClassForTraffic( traffic );
  }

  public int getTrafficClassForTraffic( int traffic )
  {
    if ( traffic <      0 ) return -1;
    if ( traffic <  20000 ) return 0;
    if ( traffic <  40000 ) return 1;
    if ( traffic <  80000 ) return 2;
    if ( traffic < 160000 ) return 3;
    if ( traffic < 320000 ) return 4;
    if ( traffic < 640000 ) return 5;
    if ( traffic <1280000 ) return 6;
    return 7;
  }

  private int getTraffic( long n1, long n2 )
  {
    OsmTrafficElement e = getElement( n1, n2 );
    return e == null ? 0 : e.traffic;
  }

  public void freeze()
  {
  }
	
  private OsmTrafficElement getElement( long n1, long n2 )
  {
    OsmTrafficElement e = map.get( n1 );
    while( e != null )
    {
	  if ( e.node2 == n2 )
	  {
        return e;
	  }
	  e = e.next;
    }
    return null;
  }
  
  public OsmTrafficElement getElement( long n )
  {
    return map.get( n );
  }
}
