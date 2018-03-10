/**
 * Container for link between two Osm nodes
 *
 * @author ab
 */
package btools.router;

import java.util.Map;

import btools.expressions.BExpressionContextNode;
import btools.expressions.BExpressionContextWay;


final class KinematicModelDummy extends KinematicModel
{
  public OsmPrePath createPrePath()
  {
    return null;
  }

  public OsmPath createPath()
  {
    return null;
  }

  public KinematicModelDummy()
  {
   turnAngleDecayLength = 50.;
   f_roll = 232.;
   f_air = 0.4;
   f_recup = 600.;
   p_standby = 250.;
   recup_efficiency = 0.7;
   totalweight = 1640.;
   vmax = 60./ 3.6;
   leftWaySpeed = 12.f / 3.6;
   rightWaySpeed = 12.f / 3.6;
  }
  public boolean useNewtonApprox;

  // derived values
  public double  xweight = 1./( 2. * f_air * vmax * vmax * vmax - p_standby );
  public double  timecost0 = 1./vmax + xweight*(f_roll + f_air*vmax*vmax + p_standby/vmax );
  
  @Override
  public void init( BExpressionContextWay expctxWay, BExpressionContextNode expctxNode, Map<String,String> extraParams )
  {
  }
  
  public float getWayMaxspeed()
  {
    return 100.f;
  }

  public float getWayMinspeed()
  {
    return 0.f;
  }

  public float getNodeMaxspeed()
  {
    return 999.f;
  }
}
