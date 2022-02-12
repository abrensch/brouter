/**
 * Container for routig configs
 *
 * @author ab
 */
package btools.router;

import java.io.DataOutput;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import btools.expressions.BExpressionContext;
import btools.expressions.BExpressionContextNode;
import btools.expressions.BExpressionContextWay;
import btools.mapaccess.GeometryDecoder;
import btools.mapaccess.OsmLink;
import btools.util.CompactLongSet;

public final class RoutingContext
{
  public void setAlternativeIdx( int idx )
  {
    alternativeIdx = idx;
  }
  public int getAlternativeIdx(int min, int max)
  {
    return alternativeIdx < min ? min : (alternativeIdx > max ? max : alternativeIdx);
  }
  public int alternativeIdx = 0;
  public String localFunction;
  public long profileTimestamp;

  public Map<String,String> keyValues;
  
  public String rawTrackPath;

  public CompactLongSet badTRs;
  public Map<Long,SuspectInfo> suspectNodes;
  public boolean foundNodeBlock;
  public SuspectInfo foundWayBlock;
  
  public int maxcost;
  
  public String getProfileName()
  {
    String name = localFunction == null ? "unknown" : localFunction;
    if ( name.endsWith( ".brf" ) ) name = name.substring( 0, localFunction.length() - 4 );
    int idx = name.lastIndexOf( File.separatorChar );
    if ( idx >= 0 ) name = name.substring( idx+1 );
    return name;
  }

  public BExpressionContextWay expctxWay;
  public BExpressionContextNode expctxNode;
  
  public GeometryDecoder geometryDecoder = new GeometryDecoder();

  public int memoryclass = 64;
  
  public int downhillcostdiv;
  public int downhillcutoff;
  public int uphillcostdiv;
  public int uphillcutoff;
  public boolean carMode;
  public boolean bikeMode;
  public boolean considerTurnRestrictions = true;
  public boolean processUnusedTags;
  public boolean forceSecondaryData;
  public double pass1coefficient;
  public double pass2coefficient;
  public int elevationpenaltybuffer;
  public int elevationmaxbuffer;
  public int elevationbufferreduce;

  public double cost1speed;
  public double additionalcostfactor;
  public double changetime;
  public double buffertime;
  public double waittimeadjustment;
  public double inittimeadjustment;
  public double starttimeoffset;
  public boolean transitonly;
  
  
  private void setModel( String className )
  {
    if ( className == null )
    {
      pm = new StdModel();
    }
    else
    {
      try
      {
        Class clazz = Class.forName( className );
        pm = (OsmPathModel) clazz.newInstance();
      }
      catch( Exception e )
      {
        throw new RuntimeException( "Cannot create path-model: " + e );
      }
    }
    initModel();
  }
  
  public void initModel()
  {
    pm.init( expctxWay, expctxNode, keyValues );
  }  

  public long getKeyValueChecksum()
  {
    long s = 0L;
    if ( keyValues != null )
    {
      for( Map.Entry<String,String> e : keyValues.entrySet() )
      {
        s += e.getKey().hashCode() + e.getValue().hashCode();
      }
    }
    return s;
  }

  public void readGlobalConfig()
  {
    BExpressionContext expctxGlobal = expctxWay; // just one of them...

    setModel( expctxGlobal._modelClass );
  
    downhillcostdiv = (int)expctxGlobal.getVariableValue( "downhillcost", 0.f );
    downhillcutoff = (int)(expctxGlobal.getVariableValue( "downhillcutoff", 0.f )*10000);
    uphillcostdiv = (int)expctxGlobal.getVariableValue( "uphillcost", 0.f );
    uphillcutoff = (int)(expctxGlobal.getVariableValue( "uphillcutoff", 0.f )*10000);
    if ( downhillcostdiv != 0 ) downhillcostdiv = 1000000/downhillcostdiv;
    if ( uphillcostdiv != 0 ) uphillcostdiv = 1000000/uphillcostdiv;
    carMode = 0.f != expctxGlobal.getVariableValue( "validForCars", 0.f );
    bikeMode = 0.f != expctxGlobal.getVariableValue( "validForBikes", 0.f );

    // turn-restrictions used per default for car profiles
    considerTurnRestrictions = 0.f != expctxGlobal.getVariableValue( "considerTurnRestrictions", carMode && considerTurnRestrictions ? 1.f : 0.f );

    // process tags not used in the profile (to have them in the data-tab)
    processUnusedTags = 0.f != expctxGlobal.getVariableValue( "processUnusedTags", 0.f );

    forceSecondaryData = 0.f != expctxGlobal.getVariableValue( "forceSecondaryData", 0.f );
    pass1coefficient = expctxGlobal.getVariableValue( "pass1coefficient", 1.5f );
    pass2coefficient = expctxGlobal.getVariableValue( "pass2coefficient", 0.f );
    elevationpenaltybuffer = (int)(expctxGlobal.getVariableValue( "elevationpenaltybuffer", 5.f )*1000000);
    elevationmaxbuffer = (int)(expctxGlobal.getVariableValue( "elevationmaxbuffer", 10.f )*1000000);
    elevationbufferreduce = (int)(expctxGlobal.getVariableValue( "elevationbufferreduce", 0.f )*10000);

    cost1speed           = expctxGlobal.getVariableValue( "cost1speed", 22.f );
    additionalcostfactor = expctxGlobal.getVariableValue( "additionalcostfactor", 1.5f );
    changetime           = expctxGlobal.getVariableValue( "changetime", 180.f );
    buffertime           = expctxGlobal.getVariableValue( "buffertime", 120.f );
    waittimeadjustment   = expctxGlobal.getVariableValue( "waittimeadjustment", 0.9f );
    inittimeadjustment   = expctxGlobal.getVariableValue( "inittimeadjustment", 0.2f );
    starttimeoffset      = expctxGlobal.getVariableValue( "starttimeoffset", 0.f );
    transitonly          = expctxGlobal.getVariableValue( "transitonly", 0.f ) != 0.f;

    farTrafficWeight        = expctxGlobal.getVariableValue( "farTrafficWeight", 2.f );
    nearTrafficWeight        = expctxGlobal.getVariableValue( "nearTrafficWeight", 2.f );
    farTrafficDecayLength      = expctxGlobal.getVariableValue( "farTrafficDecayLength", 30000.f );
    nearTrafficDecayLength      = expctxGlobal.getVariableValue( "nearTrafficDecayLength", 3000.f );
    trafficDirectionFactor      = expctxGlobal.getVariableValue( "trafficDirectionFactor", 0.9f );
    trafficSourceExponent      = expctxGlobal.getVariableValue( "trafficSourceExponent", -0.7f );
    trafficSourceMinDist      = expctxGlobal.getVariableValue( "trafficSourceMinDist", 3000.f );

    showspeed = 0.f != expctxGlobal.getVariableValue( "showspeed", 0.f );
    inverseRouting = 0.f != expctxGlobal.getVariableValue( "inverseRouting", inverseRouting ? 1.f : 0.f );

    int tiMode = (int)expctxGlobal.getVariableValue( "turnInstructionMode", 0.f );
    if ( tiMode != 1 ) // automatic selection from coordinate source
    {
      turnInstructionMode = tiMode;
    }
    turnInstructionCatchingRange = expctxGlobal.getVariableValue( "turnInstructionCatchingRange", 40.f );
    turnInstructionRoundabouts = expctxGlobal.getVariableValue( "turnInstructionRoundabouts", 1.f ) != 0.f;
  }

  public List<OsmNodeNamed> nogopoints = null;
  private List<OsmNodeNamed> keepnogopoints = null;

  public Integer startDirection;
  public boolean startDirectionValid;

  private double coslat;
  private double cosangle;
  public boolean nogomatch = false;
  public boolean isEndpoint = false;

  public boolean shortestmatch = false;
  public double wayfraction;
  public int ilatshortest;
  public int ilonshortest;

  public boolean countTraffic;
  public boolean inverseDirection;
  public DataOutput trafficOutputStream;

  public double farTrafficWeight;
  public double nearTrafficWeight;
  public double farTrafficDecayLength;
  public double nearTrafficDecayLength;
  public double trafficDirectionFactor;
  public double trafficSourceExponent;
  public double trafficSourceMinDist;

  public boolean showspeed;
  public boolean inverseRouting;
  
  public OsmPrePath firstPrePath;

  public int turnInstructionMode; // 0=none, 1=auto, 2=locus, 3=osmand, 4=comment-style, 5=gpsies-style
  public double turnInstructionCatchingRange;
  public boolean turnInstructionRoundabouts;

  public static void prepareNogoPoints( List<OsmNodeNamed> nogos )
  {
    for( OsmNodeNamed nogo : nogos )
    {
      if (nogo instanceof OsmNogoPolygon)
      {
        continue;
      }
      String s = nogo.name;
      int idx = s.indexOf( ' ' );
      if ( idx > 0 ) s = s.substring( 0 , idx );
      int ir = 20; // default radius
      if ( s.length() > 4 )
      {
        try { ir = Integer.parseInt( s.substring( 4 ) ); }
        catch( Exception e ) { /* ignore */ }
      }
      nogo.radius = ir / 110984.; //  6378000. / 57.3;
    }
  }

  public void cleanNogolist( List<OsmNodeNamed> waypoints )
  {
    if ( nogopoints == null ) return;
    List<OsmNodeNamed> nogos = new ArrayList<OsmNodeNamed>();
    for( OsmNodeNamed nogo : nogopoints )
    {
      int radiusInMeter = (int)(nogo.radius * 111894.);
      boolean goodGuy = true;
      for( OsmNodeNamed wp : waypoints )
      {
        if ( wp.calcDistance( nogo ) < radiusInMeter
            && (!(nogo instanceof OsmNogoPolygon)
                || (((OsmNogoPolygon)nogo).isClosed 
                    ? ((OsmNogoPolygon)nogo).isWithin(wp.ilon, wp.ilat)
                        : ((OsmNogoPolygon)nogo).isOnPolyline(wp.ilon, wp.ilat))))
        {
          goodGuy = false;
          break;
        }
      }
      if ( goodGuy ) nogos.add( nogo );
    }
    nogopoints = nogos.isEmpty() ? null : nogos;
  }

  public long[] getNogoChecksums()
  {
    long[] cs = new long[3];
    int n = nogopoints == null ? 0 : nogopoints.size();
    for( int i=0; i<n; i++ )
    {
    	OsmNodeNamed nogo = nogopoints.get(i);
    	cs[0] += nogo.ilon;
    	cs[1] += nogo.ilat;
    	cs[2] += (long) ( nogo.radius*111894.*10.);
    }
    return cs;
  }
  
  public void setWaypoint( OsmNodeNamed wp, boolean endpoint )
  {
    keepnogopoints = nogopoints;
    nogopoints = new ArrayList<OsmNodeNamed>();
    nogopoints.add( wp );
    if ( keepnogopoints != null ) nogopoints.addAll( keepnogopoints );
    isEndpoint = endpoint;
  }

  public void unsetWaypoint()
  {
    nogopoints = keepnogopoints;
    isEndpoint = false;
  }

  public int calcDistance( int lon1, int lat1, int lon2, int lat2 )
  {
    double l = (lat2 - 90000000) * 0.00000001234134;
    double l2 = l*l;
    double l4 = l2*l2;
    coslat = 1.- l2 + l4 / 6.;
    double coslat6 = coslat*0.000001;

    double dx = (lon2 - lon1 ) * coslat6;
    double dy = (lat2 - lat1 ) * 0.000001;
    double d = Math.sqrt( dy*dy + dx*dx );

    shortestmatch = false;

    if ( nogopoints != null && !nogopoints.isEmpty() && d > 0. )
    {
      for( int ngidx = 0; ngidx < nogopoints.size(); ngidx++ )
      {
        OsmNodeNamed nogo = nogopoints.get(ngidx);
        double x1 = (lon1 - nogo.ilon) * coslat6;
        double y1 = (lat1 - nogo.ilat) * 0.000001;
        double x2 = (lon2 - nogo.ilon) * coslat6;
        double y2 = (lat2 - nogo.ilat) * 0.000001;
        double r12 = x1*x1 + y1*y1;
        double r22 = x2*x2 + y2*y2;
        double radius = Math.abs( r12 < r22 ? y1*dx - x1*dy : y2*dx - x2*dy ) / d;

        if ( radius < nogo.radius ) // 20m
        {
          double s1 = x1*dx + y1*dy;
          double s2 = x2*dx + y2*dy;


          if ( s1 < 0. ) { s1 = -s1; s2 = -s2; }
          if ( s2 > 0. )
          {
            radius = Math.sqrt( s1 < s2 ? r12 : r22 );
            if ( radius > nogo.radius ) continue; // 20m ^ 2
          }
          if ( nogo.isNogo )
          {
            if (!(nogo instanceof OsmNogoPolygon)
                || ((OsmNogoPolygon)nogo).intersects(lon1, lat1, lon2, lat2))
            {
              nogomatch = true;
            }
          }
          else
          {
            shortestmatch = true;
            nogo.radius = radius; // shortest distance to way
            // calculate remaining distance
            if ( s2 < 0. )
            {
              wayfraction = -s2 / (d*d);
              double xm = x2 - wayfraction*dx;
              double ym = y2 - wayfraction*dy;
              ilonshortest = (int)(xm / coslat6 + nogo.ilon);
              ilatshortest = (int)(ym / 0.000001 + nogo.ilat);
            }
            else if ( s1 > s2 )
            {
              wayfraction = 0.;
              ilonshortest = lon2;
              ilatshortest = lat2;
            }
            else
            {
              wayfraction = 1.;
              ilonshortest = lon1;
              ilatshortest = lat1;
            }

            // here it gets nasty: there can be nogo-points in the list
            // *after* the shortest distance point. In case of a shortest-match
            // we use the reduced way segment for nogo-matching, in order not
            // to cut our escape-way if we placed a nogo just in front of where we are
            if ( isEndpoint )
            {
              wayfraction = 1. - wayfraction;
              lon2 = ilonshortest;
              lat2 = ilatshortest;
            }
            else
            {
              nogomatch = false;
              lon1 = ilonshortest;
              lat1 = ilatshortest;
            }
            dx = (lon2 - lon1 ) * coslat6;
            dy = (lat2 - lat1 ) * 0.000001;
            d = Math.sqrt( dy*dy + dx*dx );
          }
        }
      }
    }
    double dd = d * 110984.; //  6378000. / 57.3;
    return (int)(dd + 1.0 );
  }

  // assumes that calcDistance/calcCosAngle called in sequence, so coslat valid
  public double getCosAngle()
  {
    return cosangle;
  }

  public double getCosLat()
  {
    return coslat;
  }

  public double calcAngle( int lon0, int lat0,  int lon1, int lat1, int lon2, int lat2 )
  {
    double dlat1 = (lat1 - lat0);
    double dlon1 = (lon1 - lon0) * coslat;
    double dlat2 = (lat2 - lat1);
    double dlon2 = (lon2 - lon1) * coslat;

    double dd = Math.sqrt( (dlat1*dlat1 + dlon1*dlon1)*(dlat2*dlat2 + dlon2*dlon2) );
    if ( dd == 0. ) { cosangle = 1.; return 0.; }
    double sinp = (dlat1*dlon2 - dlon1*dlat2)/dd;
    double cosp = (dlat1*dlat2 + dlon1*dlon2)/dd;
    cosangle = cosp;

    double p;
    if ( sinp > -0.7071 && sinp < 0.7071 )
    {
      p = asin( sinp );
      if ( cosp < 0. )
      {
        p = 180. - p;
      }
    }
    else
    {
      p = 90. - asin( cosp );
      if ( sinp < 0. )
      {
        p = - p;
      }
    }
    if ( p > 180. )
    {
      p -= 360.;
    }
    return p;
  }

  private static double asin( double x )
  {
    double x2 = x*x;
    double x4 = x2*x2;
    return x * ( 57.4539 + 9.57565 * x2 + 4.30904 * x4 + 2.56491 * x2*x4 );
  }
  
  public OsmPathModel pm;
  
  public OsmPrePath createPrePath( OsmPath origin, OsmLink link )
  {
    OsmPrePath p = pm.createPrePath();
    if ( p != null )
    {
      p.init( origin, link, this );
    }
    return p;
  }

  public OsmPath createPath( OsmLink link )
  {
    OsmPath p = pm.createPath();
    p.init( link );
    return p;
  }

  public OsmPath createPath( OsmPath origin, OsmLink link, OsmTrack refTrack, boolean detailMode )
  {
    OsmPath p = pm.createPath();
    p.init( origin, link, refTrack, detailMode, this );
    return p;
  }
  
}
