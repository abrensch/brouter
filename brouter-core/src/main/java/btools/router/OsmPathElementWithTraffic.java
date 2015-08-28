package btools.router;

import java.io.IOException;


/**
 * Extension to OsmPathElement to count traffic load
 *
 * @author ab
 */

public final class OsmPathElementWithTraffic extends OsmPathElement
{
  private int registerCount;
  private float farTraffic;
  private float nearTraffic;
  
  public void register()
  {
    if ( registerCount++ == 0 )
    {
      if ( origin instanceof OsmPathElementWithTraffic )
      {
        OsmPathElementWithTraffic ot = (OsmPathElementWithTraffic)origin;
        ot.register();
        ot.farTraffic += farTraffic;
        ot.nearTraffic += nearTraffic;
        farTraffic = 0;
        nearTraffic = 0;
      }
    }
  }
  
  @Override
  public void addTraffic( float traffic )
  {
    this.farTraffic += traffic;
    this.nearTraffic += traffic;
  }

  // unregister from origin if our registercount is 0, else do nothing

public static double maxtraffic = 0.;

  public boolean unregister( RoutingContext rc ) throws IOException
  {
    if ( --registerCount == 0 )
    {
      if ( origin instanceof OsmPathElementWithTraffic )
      {
        OsmPathElementWithTraffic ot = (OsmPathElementWithTraffic)origin;
        
        int costdelta = cost-ot.cost;
        ot.farTraffic += farTraffic*Math.exp(-costdelta/rc.farTrafficDecayLength);
        ot.nearTraffic += nearTraffic*Math.exp(-costdelta/rc.nearTrafficDecayLength);

if ( costdelta > 0 && farTraffic > maxtraffic ) maxtraffic = farTraffic;
        
        int t2 = cost == ot.cost ? -1 : (int)(rc.farTrafficWeight*farTraffic + rc.nearTrafficWeight*nearTraffic);
        
        if ( t2 > 4000 || t2 == -1 )
        {
          // System.out.println( "unregistered: " + this + " origin=" + ot + " farTraffic =" + farTraffic + " nearTraffic =" + nearTraffic + " cost=" + cost );
          if ( rc.trafficOutputStream != null )
          {
            rc.trafficOutputStream.writeLong( getIdFromPos());
            rc.trafficOutputStream.writeLong( ot.getIdFromPos());
            rc.trafficOutputStream.writeInt( t2 );
          }
        }
        farTraffic = 0;
        nearTraffic = 0;
      }
      return true;
    }
    return false;
  }
}
