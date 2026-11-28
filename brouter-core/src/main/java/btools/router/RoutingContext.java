/**
 * Container for routig configs
 *
 * @author ab
 */
package btools.router;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import btools.expressions.BExpressionContext;
import btools.expressions.BExpressionContextNode;
import btools.expressions.BExpressionContextWay;
import btools.mapaccess.GraphLink;
import btools.mapaccess.GraphNode;
import btools.mapaccess.GraphNodeNamed;
import btools.mapaccess.WaypointMatcher;
import btools.util.CheapAngleMeter;

public final class RoutingContext {
  public void setAlternativeIdx(int idx) {
    alternativeIdx = idx;
  }

  public int getAlternativeIdx(int min, int max) {
    return alternativeIdx < min ? min : (alternativeIdx > max ? max : alternativeIdx);
  }

  public int alternativeIdx = 0;
  public String profileName;
  public long profileTimestamp;

  public Map<String, String> keyValues;

  public BExpressionContextWay expCtxWay;
  public BExpressionContextNode expCtxNode;

  public int memoryclass = 64;

  public boolean carMode;
  public boolean bikeMode;
  public boolean footMode;
  public boolean considerTurnRestrictions;
  public boolean processUnusedTags;
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

  public double waypointCatchingRange;

  private void setModel(String className) {
    if (className == null) {
      pm = new StdModel();
    } else {
      try {
        Class<?> clazz = Class.forName(className);
        pm = (OsmPathModel) clazz.getDeclaredConstructor().newInstance();
      } catch (Exception e) {
        throw new RuntimeException("Cannot create path-model: " + e);
      }
    }
    initModel();
  }

  public void initModel() {
    pm.init(expCtxWay, expCtxNode, keyValues);
  }

  public long getKeyValueChecksum() {
    long s = 0L;
    if (keyValues != null) {
      for (Map.Entry<String, String> e : keyValues.entrySet()) {
        s += e.getKey().hashCode() + e.getValue().hashCode();
      }
    }
    return s;
  }

  public void readGlobalConfig() {
    BExpressionContext expctxGlobal = expCtxWay; // just one of them...
    setModel(expctxGlobal._modelClass);

    carMode = 0.f != expctxGlobal.getVariableValue("validForCars", 0.f);
    bikeMode = 0.f != expctxGlobal.getVariableValue("validForBikes", 0.f);
    footMode = 0.f != expctxGlobal.getVariableValue("validForFoot", 0.f);

    waypointCatchingRange = expctxGlobal.getVariableValue("waypointCatchingRange", 250.f);

    // turn-restrictions not used per default for foot profiles
    considerTurnRestrictions = 0.f != expctxGlobal.getVariableValue("considerTurnRestrictions", footMode ? 0.f : 1.f);


    // process tags not used in the profile (to have them in the data-tab)
    processUnusedTags = 0.f != expctxGlobal.getVariableValue("processUnusedTags", 0.f);


    pass1coefficient = expctxGlobal.getVariableValue("pass1coefficient", 1.5f);
    pass2coefficient = expctxGlobal.getVariableValue("pass2coefficient", 0.f);
    elevationpenaltybuffer = (int) (expctxGlobal.getVariableValue("elevationpenaltybuffer", 5.f) * 1000000);
    elevationmaxbuffer = (int) (expctxGlobal.getVariableValue("elevationmaxbuffer", 10.f) * 1000000);
    elevationbufferreduce = (int) (expctxGlobal.getVariableValue("elevationbufferreduce", 0.f) * 10000);

    cost1speed = expctxGlobal.getVariableValue("cost1speed", 22.f);
    additionalcostfactor = expctxGlobal.getVariableValue("additionalcostfactor", 1.5f);
    changetime = expctxGlobal.getVariableValue("changetime", 180.f);
    buffertime = expctxGlobal.getVariableValue("buffertime", 120.f);
    waittimeadjustment = expctxGlobal.getVariableValue("waittimeadjustment", 0.9f);
    inittimeadjustment = expctxGlobal.getVariableValue("inittimeadjustment", 0.2f);
    starttimeoffset = expctxGlobal.getVariableValue("starttimeoffset", 0.f);
    transitonly = expctxGlobal.getVariableValue("transitonly", 0.f) != 0.f;

    showspeed = 0.f != expctxGlobal.getVariableValue("showspeed", 0.f);
    showSpeedProfile = 0.f != expctxGlobal.getVariableValue("showSpeedProfile", 0.f);
    inverseRouting = 0.f != expctxGlobal.getVariableValue("inverseRouting", 0.f);
    showTime = 0.f != expctxGlobal.getVariableValue("showtime", 0.f);

    int tiMode = (int) expctxGlobal.getVariableValue("turnInstructionMode", 0.f);
    if (tiMode != 1) { // automatic selection from coordinate source
      turnInstructionMode = tiMode;
    }
    turnInstructionCatchingRange = expctxGlobal.getVariableValue("turnInstructionCatchingRange", 40.f);
    turnInstructionRoundabouts = expctxGlobal.getVariableValue("turnInstructionRoundabouts", 1.f) != 0.f;

    // Speed computation model (for bikes)
    // Total mass (biker + bike + luggages or hiker), in kg
    totalMass = expctxGlobal.getVariableValue("totalMass", 90.f);
    // Max speed (before braking), in km/h in profile and m/s in code
    if (footMode) {
      maxSpeed = expctxGlobal.getVariableValue("maxSpeed", 6.f) / 3.6;
    } else {
      maxSpeed = expctxGlobal.getVariableValue("maxSpeed", 45.f) / 3.6;
    }
    // Equivalent surface for wind, S * C_x, F = -1/2 * S * C_x * v^2 = - S_C_x * v^2
    S_C_x = expctxGlobal.getVariableValue("S_C_x", 0.5f * 0.45f);
    // Default resistance of the road, F = - m * g * C_r (for good quality road)
    defaultC_r = expctxGlobal.getVariableValue("C_r", 0.01f);
    // Constant power of the biker (in W)
    bikerPower = expctxGlobal.getVariableValue("bikerPower", 100.f);
  }

  public List<GraphNodeNamed> nogopoints = null;
  public WaypointMatcher nogoMatcher;

  public CheapAngleMeter anglemeter = new CheapAngleMeter();

  public boolean inverseDirection;

  public boolean showspeed;
  public boolean showSpeedProfile;
  public boolean inverseRouting;
  public boolean showTime;

  public String outputFormat = "gpx";

  public OsmPrePath firstPrePath;

  public int turnInstructionMode; // 0=none, 1=auto, 2=locus, 3=osmand, 4=comment-style, 5=gpsies-style
  public double turnInstructionCatchingRange;
  public boolean turnInstructionRoundabouts;

  // Speed computation model (for bikes)
  public double totalMass;
  public double maxSpeed;
  public double S_C_x;
  public double defaultC_r;
  public double bikerPower;

  public static void prepareNogoPoints(List<GraphNodeNamed> nogos) {
    for (GraphNodeNamed nogo : nogos) {
      String s = nogo.name;
      int idx = s.indexOf(' ');
      if (idx > 0) s = s.substring(0, idx);
      int ir = 20; // default radius
      if (s.length() > 4) {
        try {
          ir = Integer.parseInt(s.substring(4));
        } catch (Exception e) { /* ignore */ }
      }
      // Radius of the nogo point in meters
      nogo.radius = ir;
    }
  }

  /**
   * clean the nogolist (previoulsy saved by saveFullNogolist())
   * by removing nogos with waypoints within
   *
   * @return true if all wayoints are all in the same (full-weigth) nogo area (triggering bee-line-mode)
   */
  public void createNogoMatcher( List<GraphNode> waypoints ) {

    List<GraphNodeNamed> nogoWaypoints = new ArrayList<>();
    if (nogopoints != null) {
      for (GraphNodeNamed nogo : nogopoints) {
        boolean goodGuy = true;
        for (GraphNode wp : waypoints) {
          if (wp.calcDistance(nogo) < nogo.radius) {
            goodGuy = false;
          }
        }
        if (goodGuy) {
          nogoWaypoints.add(nogo);
        }
      }
    }
    nogoMatcher = new WaypointMatcher(nogoWaypoints);
  }


  public OsmPathModel pm;

  public OsmPrePath createPrePath(OsmPath origin, GraphLink link) {
    OsmPrePath p = pm.createPrePath();
    if (p != null) {
      p.init(origin, link, this);
    }
    return p;
  }

  public OsmPath createPath(GraphLink link) {
    OsmPath p = pm.createPath();
    p.init(link);
    return p;
  }

  public OsmPath createPath(OsmPath origin, GraphLink link, OsmTrack refTrack, boolean detailMode) {
    OsmPath p = pm.createPath();
    p.init(origin, link, refTrack, detailMode, this);
    return p;
  }

}
