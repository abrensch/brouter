/**
 * Container for link between two Osm nodes
 *
 * @author ab
 */
package btools.router;

import btools.expressions.BExpressionContext;
import btools.expressions.BExpressionContextNode;
import btools.expressions.BExpressionContextWay;


class KinematicModel extends OsmPathModel
{
  public OsmPrePath createPrePath()
  {
    return new KinematicPrePath();
  }

  public OsmPath createPath()
  {
    return new KinematicPath();
  }

  public double turnAngleDecayLength;
  public double f_roll;
  public double f_air;
  public double f_recup;
  public double p_standby;
  public double recup_efficiency;
  public double totalweight;
  public double vmax;
  public double leftWaySpeed;
  public double rightWaySpeed;

  // derived values
  public double xweight; // the weight-factor between time and energy for cost calculation
  public double timecost0; // minimum possible "energy-adjusted-time" per meter
  
  private int wayIdxMaxspeed;
  private int wayIdxMinspeed;

  private int nodeIdxMaxspeed;

  protected BExpressionContextWay ctxWay;
  protected BExpressionContextNode ctxNode;


  @Override
  public void init( BExpressionContextWay expctxWay, BExpressionContextNode expctxNode )
  {
    ctxWay = expctxWay;
    ctxNode = expctxNode;
  
    BExpressionContext expctxGlobal = expctxWay; // just one of them...

    turnAngleDecayLength = expctxGlobal.getVariableValue( "turnAngleDecayLength", 50.f );
    f_roll = expctxGlobal.getVariableValue( "f_roll", 232.f );
    f_air = expctxGlobal.getVariableValue( "f_air", 0.4f );
    f_recup = expctxGlobal.getVariableValue( "f_recup", 400.f );
    p_standby = expctxGlobal.getVariableValue( "p_standby", 250.f );
    recup_efficiency = expctxGlobal.getVariableValue( "recup_efficiency", 0.7f );
    totalweight = expctxGlobal.getVariableValue( "totalweight", 1640.f );
    vmax = expctxGlobal.getVariableValue( "vmax", 80.f ) / 3.6;
    leftWaySpeed = expctxGlobal.getVariableValue( "leftWaySpeed", 12.f ) / 3.6;
    rightWaySpeed = expctxGlobal.getVariableValue( "rightWaySpeed", 12.f ) / 3.6;

    xweight = 1./( 2. * f_air * vmax * vmax * vmax - p_standby );
    timecost0 = 1./vmax + xweight*(f_roll + f_air*vmax*vmax + p_standby/vmax );
  
    wayIdxMaxspeed = ctxWay.getOutputVariableIndex( "maxspeed", false );
    wayIdxMinspeed = ctxWay.getOutputVariableIndex( "minspeed", false );

    nodeIdxMaxspeed = ctxNode.getOutputVariableIndex( "maxspeed", false );
  }
  
  public float getWayMaxspeed()
  {
    return ctxWay.getBuildInVariable( wayIdxMaxspeed ) / 3.6f;
  }

  public float getWayMinspeed()
  {
    return ctxWay.getBuildInVariable( wayIdxMinspeed ) / 3.6f;
  }

  public float getNodeMaxspeed()
  {
    return ctxNode.getBuildInVariable( nodeIdxMaxspeed ) / 3.6f;
  }

  public double getMaxKineticEnergy()
  {
    // determine maximum possible speed and kinetic energy
    double mspeed = Math.min( getWayMaxspeed(), Math.max( getWayMinspeed(), vmax ) );
    return 0.5*totalweight*mspeed*mspeed;
  }
}
