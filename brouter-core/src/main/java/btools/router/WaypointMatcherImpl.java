package btools.router;

import java.util.List;

import btools.codec.WaypointMatcher;
import btools.mapaccess.OsmNode;
import btools.mapaccess.OsmNodePairSet;

/**
 * the WaypointMatcher is feeded by the decoder with geoemtries of ways that are
 * already check for allowed access according to the current routing profile
 * 
 * It matches these geometries against the list of waypoints to find the best
 * match for each waypoint
 */
public final class WaypointMatcherImpl implements WaypointMatcher
{
  private List<MatchedWaypoint> waypoints;
  private OsmNodePairSet islandPairs;

  private int lonStart;
  private int latStart;
  private int lonTarget;
  private int latTarget;
  private boolean anyUpdate;
  private int lonLast;
  private int latLast;

  public WaypointMatcherImpl( List<MatchedWaypoint> waypoints, double maxDistance, OsmNodePairSet islandPairs )
  {
    this.waypoints = waypoints;
    this.islandPairs = islandPairs;
    for ( MatchedWaypoint mwp : waypoints )
    {
      mwp.radius = maxDistance * 110984.; //  6378000. / 57.3;
    }
  }

  private void checkSegment( int lon1, int lat1, int lon2, int lat2 )
  {
    // todo: bounding-box pre-filter

    double l = ( lat2 - 90000000 ) * 0.00000001234134;
    double l2 = l * l;
    double l4 = l2 * l2;
    double coslat = 1. - l2 + l4 / 6.;
    double coslat6 = coslat * 0.000001;

    double dx = ( lon2 - lon1 ) * coslat6;
    double dy = ( lat2 - lat1 ) * 0.000001;
    double d = Math.sqrt( dy * dy + dx * dx );
    if ( d == 0. )
      return;

    for ( MatchedWaypoint mwp : waypoints )
    {
      OsmNodeNamed wp = mwp.waypoint;

      double x1 = ( lon1 - wp.ilon ) * coslat6;
      double y1 = ( lat1 - wp.ilat ) * 0.000001;
      double x2 = ( lon2 - wp.ilon ) * coslat6;
      double y2 = ( lat2 - wp.ilat ) * 0.000001;
      double r12 = x1 * x1 + y1 * y1;
      double r22 = x2 * x2 + y2 * y2;
      double radius = Math.abs( r12 < r22 ? y1 * dx - x1 * dy : y2 * dx - x2 * dy ) / d;

      if ( radius < mwp.radius )
      {
        double s1 = x1 * dx + y1 * dy;
        double s2 = x2 * dx + y2 * dy;

        if ( s1 < 0. )
        {
          s1 = -s1;
          s2 = -s2;
        }
        if ( s2 > 0. )
        {
          radius = Math.sqrt( s1 < s2 ? r12 : r22 );
          if ( radius > mwp.radius )
            continue;
        }
        // new match for that waypoint
        mwp.radius = radius; // shortest distance to way
        mwp.hasUpdate = true;
        anyUpdate = true;
        // calculate crosspoint
        if ( mwp.crosspoint == null )
          mwp.crosspoint = new OsmNodeNamed();
        if ( s2 < 0. )
        {
          double wayfraction = -s2 / ( d * d );
          double xm = x2 - wayfraction * dx;
          double ym = y2 - wayfraction * dy;
          mwp.crosspoint.ilon = (int) ( xm / coslat6 + wp.ilon );
          mwp.crosspoint.ilat = (int) ( ym / 0.000001 + wp.ilat );
        }
        else if ( s1 > s2 )
        {
          mwp.crosspoint.ilon = lon2;
          mwp.crosspoint.ilat = lat2;
        }
        else
        {
          mwp.crosspoint.ilon = lon1;
          mwp.crosspoint.ilat = lat1;
        }
      }
    }
  }

  @Override
  public boolean start( int ilonStart, int ilatStart, int ilonTarget, int ilatTarget )
  {
    if ( islandPairs.size() > 0 )
    {
      long n1 = ( (long) ilonStart ) << 32 | ilatStart;
      long n2 = ( (long) ilonTarget ) << 32 | ilatTarget;
      if ( islandPairs.hasPair( n1, n2 ) )
      {
        return false;
      }
    }
    lonLast = lonStart = ilonStart;
    latLast = latStart = ilatStart;
    lonTarget = ilonTarget;
    latTarget = ilatTarget;
    anyUpdate = false;
    return true;
  }

  @Override
  public void transferNode( int ilon, int ilat )
  {
    checkSegment( lonLast, latLast, ilon, ilat );
    lonLast = ilon;
    latLast = ilat;
  }

  @Override
  public void end()
  {
    checkSegment( lonLast, latLast, lonTarget, latTarget );
    if ( anyUpdate )
    {
      for ( MatchedWaypoint mwp : waypoints )
      {
        if ( mwp.hasUpdate )
        {
          mwp.hasUpdate = false;
          mwp.node1 = new OsmNode( lonStart, latStart );
          mwp.node2 = new OsmNode( lonTarget, latTarget );
        }
      }
    }
  }
}
