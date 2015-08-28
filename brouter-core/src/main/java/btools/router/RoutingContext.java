/**
 * Container for routig configs
 *
 * @author ab
 */
package btools.router;

import java.io.DataOutput;
import java.util.ArrayList;
import java.util.List;

import btools.expressions.BExpressionContext;
import btools.expressions.BExpressionContextNode;
import btools.expressions.BExpressionContextWay;
import btools.mapaccess.DistanceChecker;
import btools.mapaccess.OsmTransferNode;

public final class RoutingContext implements DistanceChecker
{
  public void setAlternativeIdx( int idx )
  {
    if ( idx < 0 ) idx = 0;
    if ( idx > 3 ) idx = 3;
    alternativeIdx = idx;
  }
  public int getAlternativeIdx()
  {
    return alternativeIdx;
  }
  public int alternativeIdx = 0;
  public String localFunction;

  public String rawTrackPath;
  
  public String getProfileName()
  {
    String name = localFunction == null ? "unknown" : localFunction;
    if ( name.endsWith( ".brf" ) ) name = name.substring( 0, localFunction.length() - 4 );
    int idx = name.lastIndexOf( '/' );
    if ( idx >= 0 ) name = name.substring( idx+1 );
    return name;
  }

  public BExpressionContextWay expctxWay;
  public BExpressionContextNode expctxNode;

  public boolean serversizing = false;
  
  public int downhillcostdiv;
  public int downhillcutoff;
  public int uphillcostdiv;
  public int uphillcutoff;
  public boolean carMode;
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
  public double starttimeoffset;
  
  public void readGlobalConfig( BExpressionContext expctxGlobal )
  {
    downhillcostdiv = (int)expctxGlobal.getVariableValue( "downhillcost", 0.f );
    downhillcutoff = (int)(expctxGlobal.getVariableValue( "downhillcutoff", 0.f )*10000);
    uphillcostdiv = (int)expctxGlobal.getVariableValue( "uphillcost", 0.f );
    uphillcutoff = (int)(expctxGlobal.getVariableValue( "uphillcutoff", 0.f )*10000);
    if ( downhillcostdiv != 0 ) downhillcostdiv = 1000000/downhillcostdiv;
    if ( uphillcostdiv != 0 ) uphillcostdiv = 1000000/uphillcostdiv;
    carMode = 0.f != expctxGlobal.getVariableValue( "validForCars", 0.f );
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
    starttimeoffset      = expctxGlobal.getVariableValue( "starttimeoffset", 0.f );

    farTrafficWeight        = expctxGlobal.getVariableValue( "farTrafficWeight", 2.f );
    nearTrafficWeight        = expctxGlobal.getVariableValue( "nearTrafficWeight", 2.f );
    farTrafficDecayLength      = expctxGlobal.getVariableValue( "farTrafficDecayLength", 30000.f );
    nearTrafficDecayLength      = expctxGlobal.getVariableValue( "nearTrafficDecayLength", 3000.f );
    trafficDirectionFactor      = expctxGlobal.getVariableValue( "trafficDirectionFactor", 0.9f );
    trafficSourceExponent      = expctxGlobal.getVariableValue( "trafficSourceExponent", -0.7f );
    trafficSourceMinDist      = expctxGlobal.getVariableValue( "trafficSourceMinDist", 3000.f );
  }

  public RoutingMessageHandler messageHandler = new RoutingMessageHandler();

  public List<OsmNodeNamed> nogopoints = null;
  private List<OsmNodeNamed> keepnogopoints = null;

  private double coslat;
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

  public static void prepareNogoPoints( List<OsmNodeNamed> nogos )
  {
    for( OsmNodeNamed nogo : nogos )
    {
        String s = nogo.name;
        int idx = s.indexOf( ' ' );
        if ( idx > 0 ) s = s.substring( 0 , idx );
        int ir = 20; // default radius
        if ( s.length() > 4 )
        {
          try { ir = Integer.parseInt( s.substring( 4 ) ); }
          catch( Exception e ) { /* ignore */ }
        }
        nogo.radius = ir / 111894.; //  6378000. / 57.;
    }
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

    if ( d > 0. && nogopoints != null )
    {
      for( OsmNodeNamed nogo : nogopoints )
      {
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
          if ( nogo.isNogo ) nogomatch = true;
          else
          {
            shortestmatch = true;
            nogo.radius = radius; // shortest distance to way
            // calculate remaining distance
            if ( s2 < 0. )
            {
              double distance = d > 0. ? -s2 / d : 0.;
              wayfraction = d > 0. ? distance / d : 0.;
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
    double dd = d * 111894.7368; //  6378000. / 57.;
    return (int)(dd + 1.0 );
  }

  // assumes that calcDistance/calcCosAngle called in sequence, so coslat valid
  public double calcCosAngle( int lon0, int lat0,  int lon1, int lat1, int lon2, int lat2 )
  {
    double dlat1 = (lat1 - lat0);
    double dlon1 = (lon1 - lon0) * coslat;
    double dlat2 = (lat2 - lat1);
    double dlon2 = (lon2 - lon1) * coslat;

    double dd = Math.sqrt( (dlat1*dlat1 + dlon1*dlon1)*(dlat2*dlat2 + dlon2*dlon2) );
    if ( dd == 0. ) return 0.;
    double cosp = (dlat1*dlat2 + dlon1*dlon2)/dd;
    return 1.-cosp; // don't care to really do acos..
  }

  @Override
  public boolean isWithinRadius( int ilon0, int ilat0, OsmTransferNode firstTransfer, int ilon1, int ilat1 )
  {
    OsmNodeNamed wp = nogopoints.get(0);
    double keepRadius = wp.radius;
    try
    {
      int ilon = ilon0;
      int ilat = ilat0;
      for( OsmTransferNode trans = firstTransfer; trans != null; trans = trans.next )
      {
        calcDistance( ilon, ilat, trans.ilon, trans.ilat );
        ilon = trans.ilon;
        ilat = trans.ilat;
      }
      calcDistance( ilon, ilat, ilon1, ilat1 );
      return wp.radius < keepRadius;
    }
    finally
    {
      wp.radius = keepRadius;
    }
  }

}
