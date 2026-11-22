package btools.router;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import btools.mapaccess.MatchedWaypoint;
import btools.mapaccess.NodesCache;
import btools.mapaccess.GraphLink;
import btools.mapaccess.GraphNode;
import btools.mapaccess.NodePairSet;
import btools.util.SortedHeap;

public class RoutingEngine {

  private NodesCache nodesCache;

  protected List<GraphNodeNamed> waypoints;
  protected List<MatchedWaypoint> matchedWaypoints;

  private int nodeLimit; // used for target island search
  private int MAXNODES_ISLAND_CHECK = 500;
  private NodePairSet islandNodePairs = new NodePairSet(MAXNODES_ISLAND_CHECK);


  protected OsmTrack foundTrack = new OsmTrack();

  protected String errorMessage = null;

  private volatile boolean terminated;

  protected File segmentDir;
  protected RoutingContext routingContext;

  public double airDistanceCostFactor;

  private OsmTrack guideTrack;

  private long startTime;
  private long maxRunningTime;

  public RoutingEngine(File segmentDir, File profileDir,
                       List<GraphNodeNamed> waypoints, RoutingContext rc) {
    this.segmentDir = segmentDir;
    this.waypoints = waypoints;
    this.routingContext = rc;

    ProfileCache.parseProfile(profileDir, rc);
  }

  public void doRun(long maxRunningTime) {
    if (waypoints.size() < 2) {
      throw new IllegalArgumentException("we need two lat/lon points at least!");
    }
    doRouting(maxRunningTime);
  }


  public void doRouting(long maxRunningTime) {
    try {
      startTime = System.currentTimeMillis();
      this.maxRunningTime = maxRunningTime;
      int nsections = waypoints.size() - 1;
      OsmTrack[] refTracks = new OsmTrack[nsections]; // used ways for alternatives
      OsmTrack[] lastTracks = new OsmTrack[nsections];
      OsmTrack track = null;
      ArrayList<String> messageList = new ArrayList<>();
      for (int i = 0; ; i++) {
        track = findTrack(refTracks, lastTracks);
        track.message = "track-length = " + track.distance + " filtered ascend = " + track.ascend
          + " plain-ascend = " + track.plainAscend + " cost=" + track.cost;
        if (track.energy != 0) {
          track.message += " energy=" + Formatter.getFormattedEnergy(track.energy) + " time=" + Formatter.getFormattedTime2(track.getTotalSeconds());
        }
        track.name = "brouter" + "_" + i;

        messageList.add(track.message);
        track.messageList = messageList;
        if (i == routingContext.getAlternativeIdx(0, 3)) {
          foundTrack = track;
        } else {
          continue;
        }
        break;
      }
    } catch (IllegalArgumentException e) {
      setError(e);
    } catch (Exception e) {
      setError(e);
    } catch (Error e) {
      cleanOnOOM();
      setError(e);
    } finally {

      ProfileCache.releaseProfile(routingContext);

      if (nodesCache != null) {
        nodesCache.close();
        nodesCache = null;
      }
    }
  }

  private void postElevationCheck(OsmTrack track) {
    OsmPathElement lastPt = null;
    OsmPathElement startPt = null;
    short lastElev = Short.MIN_VALUE;
    short startElev = Short.MIN_VALUE;
    short endElev = Short.MIN_VALUE;
    int startIdx = 0;
    int endIdx = -1;
    int dist = 0;
    int ourSize = track.nodes.size();
    for (int idx = 0; idx < ourSize; idx++) {
      OsmPathElement n = track.nodes.get(idx);
      if (n.getSElev() == Short.MIN_VALUE && lastElev != Short.MIN_VALUE && idx < ourSize - 1) {
        // start one point before entry point to get better elevation results
        if (idx > 1)
          startElev = track.nodes.get(idx - 2).getSElev();
        if (startElev == Short.MIN_VALUE)
          startElev = lastElev;
        startIdx = idx;
        startPt = lastPt;
        dist = 0;
        if (lastPt != null)
          dist += n.calcDistance(lastPt);
      } else if (n.getSElev() != Short.MIN_VALUE && lastElev == Short.MIN_VALUE && startElev != Short.MIN_VALUE) {
        // end one point behind exit point to get better elevation results
        if (idx + 1 < track.nodes.size())
          endElev = track.nodes.get(idx + 1).getSElev();
        if (endElev == Short.MIN_VALUE)
          endElev = n.getSElev();
        endIdx = idx;
        OsmPathElement tmpPt = track.nodes.get(startIdx > 1 ? startIdx - 2 : startIdx - 1);
        int diffElev = endElev - startElev;
        dist += tmpPt.calcDistance(startPt);
        dist += n.calcDistance(lastPt);
        int distRest = dist;
        double incline = diffElev / (dist / 100.);
        String lastMsg = "";
        double tmpincline = 0;
        double startincline = 0;
        double selev = track.nodes.get(startIdx - 2).getSElev();
        boolean hasInclineTags = false;
        for (int i = startIdx - 1; i < endIdx + 1; i++) {
          OsmPathElement tmp = track.nodes.get(i);
          if (tmp.message != null) {
            MessageData md = tmp.message.copy();
            String msg = md.wayKeyValues;
            if (!msg.equals(lastMsg)) {
              boolean revers = msg.contains("reversedirection=yes");
              int pos = msg.indexOf("incline=");
              if (pos != -1) {
                hasInclineTags = true;
                String s = msg.substring(pos + 8);
                pos = s.indexOf(" ");
                if (pos != -1)
                  s = s.substring(0, pos);

                if (s.length() > 0) {
                  try {
                    int ind = s.indexOf("%");
                    if (ind != -1)
                      s = s.substring(0, ind);
                    ind = s.indexOf("°");
                    if (ind != -1)
                      s = s.substring(0, ind);
                    tmpincline = Double.parseDouble(s.trim());
                    if (revers)
                      tmpincline *= -1;
                  } catch (NumberFormatException e) {
                    tmpincline = 0;
                  }
                }
              } else {
                tmpincline = 0;
              }
              if (startincline == 0) {
                startincline = tmpincline;
              } else if (startincline < 0 && tmpincline > 0) {
                // for the way up find the exit point
                double diff = endElev - selev;
                tmpincline = diff / (distRest / 100.);
              }
            }
            lastMsg = msg;
          }
          int tmpdist = tmp.calcDistance(tmpPt);
          distRest -= tmpdist;
          if (hasInclineTags)
            incline = tmpincline;
          selev = (selev + (tmpdist / 100. * incline));
          tmp.setSElev((short) selev);
          tmp.message.ele = (short) selev;
          tmpPt = tmp;
        }
        dist = 0;
      } else if (n.getSElev() != Short.MIN_VALUE && lastElev == Short.MIN_VALUE && startIdx == 0) {
        // fill at start
        for (int i = 0; i < idx; i++) {
          track.nodes.get(i).setSElev(n.getSElev());
        }
      } else if (n.getSElev() == Short.MIN_VALUE && idx == track.nodes.size() - 1) {
        // fill at end
        startIdx = idx;
        for (int i = startIdx; i < track.nodes.size(); i++) {
          track.nodes.get(i).setSElev(lastElev);
        }
      } else if (n.getSElev() == Short.MIN_VALUE) {
        if (lastPt != null)
          dist += n.calcDistance(lastPt);
      }
      lastElev = n.getSElev();
      lastPt = n;
    }

  }

  private void setError(Throwable t) {
    errorMessage = t instanceof RuntimeException ? t.getMessage() : t.toString();
  }

  public void cleanOnOOM() {
    terminate();
  }

  private OsmTrack findTrack(OsmTrack[] refTracks, OsmTrack[] lastTracks) {
    for (; ; ) {
      try {
        return tryFindTrack(refTracks, lastTracks);
      } catch (RoutingIslandException rie) {
        islandNodePairs.freezeTempPairs();
        nodesCache.clean();
        matchedWaypoints = null;
      }
    }
  }

  private OsmTrack tryFindTrack(OsmTrack[] refTracks, OsmTrack[] lastTracks) {
    OsmTrack totaltrack = new OsmTrack();

    if (matchedWaypoints == null) { // could exist from the previous alternative level
      matchedWaypoints = new ArrayList<>();
      for (int i = 0; i < waypoints.size(); i++) {
        MatchedWaypoint mwp = new MatchedWaypoint();
        mwp.waypoint = waypoints.get(i);
        mwp.name = waypoints.get(i).name;
        matchedWaypoints.add(mwp);
      }
      matchWaypointsToNodes(matchedWaypoints);

      routingContext.checkMatchedWaypointAgainstNogos(matchedWaypoints);

      // detect target islands: restricted search in inverse direction
      routingContext.inverseDirection = !routingContext.inverseRouting;
      airDistanceCostFactor = 0.;
      for (int i = 0; i < matchedWaypoints.size() - 1; i++) {
        nodeLimit = MAXNODES_ISLAND_CHECK;

        if (routingContext.inverseRouting) {
          OsmTrack seg = findTrack("start-island-check", matchedWaypoints.get(i), matchedWaypoints.get(i + 1), null, null);
          if (seg == null && nodeLimit > 0) {
            throw new IllegalArgumentException("start island detected for section " + i);
          }
        } else {
          OsmTrack seg = findTrack("target-island-check", matchedWaypoints.get(i + 1), matchedWaypoints.get(i), null, null);
          if (seg == null && nodeLimit > 0) {
            throw new IllegalArgumentException("target island detected for section " + i);
          }
        }
      }
      routingContext.inverseDirection = false;
      nodeLimit = 0;
    }

    OsmPath.seg = 1; // set segment counter
    for (int i = 0; i < matchedWaypoints.size() - 1; i++) {
      if (lastTracks[i] != null) {
        if (refTracks[i] == null) refTracks[i] = new OsmTrack();
        refTracks[i].addNodes(lastTracks[i]);
      }

      OsmTrack seg;
      if (routingContext.inverseRouting) {
        routingContext.inverseDirection = true;
        seg = searchTrack(matchedWaypoints.get(i + 1), matchedWaypoints.get(i), refTracks[i]);
        routingContext.inverseDirection = false;
      } else {
        seg = searchTrack(matchedWaypoints.get(i), matchedWaypoints.get(i + 1), refTracks[i]);
      }
      if (seg == null)
        return null;

      totaltrack.appendTrack(seg);
      lastTracks[i] = seg;
    }

    postElevationCheck(totaltrack);

    recalcTrack(totaltrack);

    totaltrack.processVoiceHints(routingContext);
    totaltrack.prepareSpeedProfile(routingContext);

    totaltrack.showTime = routingContext.showTime;
    totaltrack.params = routingContext.keyValues;

    return totaltrack;
  }

  private void recalcTrack(OsmTrack t) {
    int totaldist = 0;
    float lasttime = 0;
    float speed_min = 9999;
    Map<Integer, Integer> directMap = new HashMap<>();
    float tmptime = 1;
    float speed = 1;
    int dist;
    double angle;

    double ascend = 0;
    double ehb = 0.;
    int ourSize = t.nodes.size();

    short ele_start = Short.MIN_VALUE;
    short ele_end = Short.MIN_VALUE;
    double eleFactor = routingContext.inverseRouting ? 0.25 : -0.25;

    for (int i = 0; i < ourSize; i++) {
      OsmPathElement n = t.nodes.get(i);
      if (n.message == null) n.message = new MessageData();
      OsmPathElement nLast = null;
      if (i == 0) {
        angle = 0;
        dist = 0;
      } else if (i == 1) {
        angle = 0;
        nLast = t.nodes.get(0);
        dist = nLast.calcDistance(n);
      } else {
        int lon0 = t.nodes.get(i - 2).getILon();
        int lat0 = t.nodes.get(i - 2).getILat();
        int lon1 = t.nodes.get(i - 1).getILon();
        int lat1 = t.nodes.get(i - 1).getILat();
        int lon2 = t.nodes.get(i).getILon();
        int lat2 = t.nodes.get(i).getILat();
        angle = routingContext.anglemeter.calcAngle(lon0, lat0, lon1, lat1, lon2, lat2);
        nLast = t.nodes.get(i - 1);
        dist = nLast.calcDistance(n);
      }
      n.message.linkdist = dist;
      n.message.turnangle = (float) angle;
      totaldist += dist;
      tmptime = (n.getTime() - lasttime);
      if (dist > 0) {
        speed = dist / tmptime * 3.6f;
        speed_min = Math.min(speed_min, speed);
      }
      if (tmptime == 1.f) { // no time used here
        directMap.put(i, dist);
      }

      lasttime = n.getTime();

      short ele = n.getSElev();
      if (ele != Short.MIN_VALUE)
        ele_end = ele;
      if (ele_start == Short.MIN_VALUE)
        ele_start = ele;

      if (nLast != null) {
        short ele_last = nLast.getSElev();
        if (ele_last != Short.MIN_VALUE) {
          ehb = ehb + (ele_last - ele) * eleFactor;
        }
        if (ehb > 0) {
          ascend += ehb;
          ehb = 0;
        } else if (ehb < -10) {
          ehb = -10;
        }
      }

    }

    t.ascend = (int) ascend;
    t.plainAscend = (int) ((ele_start - ele_end) * eleFactor + 0.5);

    t.distance = totaldist;

    SortedSet<Integer> keys = new TreeSet<>(directMap.keySet());
    for (Integer key : keys) {
      int value = directMap.get(key);
      float addTime = (value / (speed_min / 3.6f));

      double addEnergy = 0;
      if (key > 0) {
        double GRAVITY = 9.81;  // in meters per second^(-2)
        double incline = (t.nodes.get(key - 1).getSElev() == Short.MIN_VALUE || t.nodes.get(key).getSElev() == Short.MIN_VALUE ? 0 : (t.nodes.get(key - 1).getElev() - t.nodes.get(key).getElev()) / value);
        double f_roll = routingContext.totalMass * GRAVITY * (routingContext.defaultC_r + incline);
        double spd = speed_min / 3.6;
        addEnergy = value * (routingContext.S_C_x * spd * spd + f_roll);
      }
      for (int j = key; j < ourSize; j++) {
        OsmPathElement n = t.nodes.get(j);
        n.setTime(n.getTime() + addTime);
        n.setEnergy(n.getEnergy() + (float) addEnergy);
      }
    }
    t.energy = (int) t.nodes.get(t.nodes.size() - 1).getEnergy();

  }

  // geometric position matching finding the nearest routable way-section
  private void matchWaypointsToNodes(List<MatchedWaypoint> unmatchedWaypoints) {
    resetCache(false);
    nodesCache.matchWaypointsToNodes(unmatchedWaypoints, routingContext.waypointCatchingRange, islandNodePairs);
  }

  private OsmTrack searchTrack(MatchedWaypoint startWp, MatchedWaypoint endWp, OsmTrack refTrack) {
    // remove nogos with waypoints inside
    try {
      return searchRoutedTrack(startWp, endWp, refTrack);
    } finally {
      routingContext.restoreNogoList();
    }
  }

  private OsmTrack searchRoutedTrack(MatchedWaypoint startWp, MatchedWaypoint endWp, OsmTrack refTrack) {
    OsmTrack track = null;
    double[] airDistanceCostFactors = new double[]{
      routingContext.pass1coefficient,
      routingContext.pass2coefficient
    };

    for (int cfi = 0; cfi < airDistanceCostFactors.length; cfi++) {
      airDistanceCostFactor = airDistanceCostFactors[cfi];

      if (airDistanceCostFactor < 0.) {
        continue;
      }

      OsmTrack t = findTrack(cfi == 0 ? "pass0" : "pass1", startWp, endWp, track, refTrack);

      if (t != null) {
        track = t;
      } else {
        throw new IllegalArgumentException("no track found at pass=" + cfi);
      }
    }
    if (track == null) throw new IllegalArgumentException("no track found");

    // final run for verbose log info and detail nodes
    airDistanceCostFactor = 0.;
    guideTrack = track;
    startTime = System.currentTimeMillis(); // reset timeout...
    try {
      OsmTrack tt = findTrack("re-tracking", startWp, endWp, null, refTrack);
      if (tt == null) throw new IllegalArgumentException("error re-tracking track");
      return tt;
    } finally {
      guideTrack = null;
    }
  }


  private void resetCache(boolean detailed) {
    routingContext.expCtxWay.setDecodeForbidden(detailed);
    nodesCache = new NodesCache(segmentDir, "rd5", routingContext.expCtxWay, nodesCache);
    islandNodePairs.clearTempPairs();
  }

  private OsmPath getStartPath(GraphNode n1, GraphNode n2, MatchedWaypoint mwp, GraphNodeNamed endPos, boolean sameSegmentSearch) {
    endPos.radius = 1.5;
    OsmPath p = getStartPath(n1, n2, new GraphNodeNamed(mwp.crossPoint), endPos, sameSegmentSearch);

    // special case: start+end on same segment
    if (p.cost >= 0 && sameSegmentSearch && endPos.radius < 1.5) {
      p.treeDepth = 0; // hack: mark for the final-check
    }
    return p;
  }


  private OsmPath getStartPath(GraphNode n1, GraphNode n2, GraphNodeNamed wp, GraphNodeNamed endPos, boolean sameSegmentSearch) {
    try {
      routingContext.setWaypoint(wp, sameSegmentSearch ? endPos : null, false);
      OsmPath bestPath = null;
      GraphLink startLink = new GraphLink(null, n1);
      OsmPath startPath = routingContext.createPath(startLink);
      double minradius = 1e10;
      for (GraphLink link = n1.firstLink; link != null; link = link.getNext(n1)) {
        GraphNode nextNode = link.getTarget(n1);
        if (nextNode.isHollow())
          continue; // border node?
        if (nextNode.firstLink == null)
          continue; // don't care about dead ends
        if (nextNode == n1)
          continue; // ?
        if (nextNode != n2)
          continue; // just that link

        wp.radius = 1.5;
        OsmPath testPath = routingContext.createPath(startPath, link, null, guideTrack != null);
        testPath.airDistance = nextNode.calcDistance(endPos);
        if (wp.radius < minradius) {
          bestPath = testPath;
          minradius = wp.radius;
        }
      }
      bestPath.treeDepth = 1;

      return bestPath;
    } finally {
      routingContext.unsetWaypoint();
    }
  }

  private OsmTrack findTrack(String operationName, MatchedWaypoint startWp, MatchedWaypoint endWp, OsmTrack costCuttingTrack, OsmTrack refTrack) {
    try {
      List<GraphNode> wpts2 = new ArrayList<>();
      wpts2.add(startWp.waypoint);
      wpts2.add(endWp.waypoint);
      routingContext.cleanNogoList(wpts2);

      boolean detailed = guideTrack != null;
      resetCache(detailed);

      return _findTrack(operationName, startWp, endWp, costCuttingTrack, refTrack);
    } finally {
      routingContext.restoreNogoList();
      nodesCache.clean();
    }
  }


  private OsmTrack _findTrack(String operationName, MatchedWaypoint startWp, MatchedWaypoint endWp, OsmTrack costCuttingTrack, OsmTrack refTrack) {
    boolean verbose = guideTrack != null;

    int maxTotalCost = guideTrack != null ? guideTrack.cost + 5000 : 1000000000;


    int nodesVisited = 0;

    long startNodeId1 = startWp.node1.getIdFromPos();
    long startNodeId2 = startWp.node2.getIdFromPos();
    long endNodeId1 = endWp.node1.getIdFromPos();
    long endNodeId2 = endWp.node2.getIdFromPos();

    GraphNode start1 = nodesCache.getGraphNode(startWp.node1);
    GraphNode start2 = nodesCache.getGraphNode(startWp.node2);
    GraphNode end1 = nodesCache.getGraphNode(endWp.node1);
    GraphNode end2 = nodesCache.getGraphNode(endWp.node2);
    GraphNodeNamed endPos = new GraphNodeNamed(endWp.crossPoint);
    boolean sameSegmentSearch = (start1 == end1 && start2 == end2) || (start1 == end2 && start2 == end1);

    if (!nodesCache.obtainNonHollowNode(start1)) {
      return null;
    }
    nodesCache.expandHollowLinkTargets(start1);
    if (!nodesCache.obtainNonHollowNode(start2)) {
      return null;
    }
    nodesCache.expandHollowLinkTargets(start2);



    OsmPath startPath1 = getStartPath(start1, start2, startWp, endPos, sameSegmentSearch);
    OsmPath startPath2 = getStartPath(start2, start1, startWp, endPos, sameSegmentSearch);

    SortedHeap<OsmPath> openSet = new SortedHeap<>();
    addToOpenset(openSet, startPath1);
    addToOpenset(openSet, startPath2);

    for (; ; ) {
      if (terminated) {
        throw new IllegalArgumentException("operation killed by thread-priority-watchdog after " + (System.currentTimeMillis() - startTime) / 1000 + " seconds");
      }

      if (maxRunningTime > 0) {
        long timeout = maxRunningTime;
        if (System.currentTimeMillis() - startTime > timeout) {
          throw new IllegalArgumentException(operationName + " timeout after " + (timeout / 1000) + " seconds");
        }
      }


      OsmPath path = openSet.popLowestKeyValue();
      if (path == null) {
        break;
      }

      if (nodeLimit > 0) { // check node-limit for target island search
        if (--nodeLimit == 0) {
          return null;
        }
      }

      nodesVisited++;

      GraphLink currentLink = path.getLink();
      GraphNode sourceNode = path.getSourceNode();
      GraphNode currentNode = path.getTargetNode();

      if (!sourceNode.linkStillValid(currentLink)) {
        continue; // probably processed by another path
      }

      long currentNodeId = currentNode.getIdFromPos();
      long sourceNodeId = sourceNode.getIdFromPos();

      if (!path.didEnterDestinationArea()) {
        islandNodePairs.addTempPair(sourceNodeId, currentNodeId);
      }

      if (path.treeDepth != 1) {
        if (path.treeDepth == 0) { // hack: sameSegment Paths marked treedepth=0 to pass above check
          path.treeDepth = 1;
        }

        if ((sourceNodeId == endNodeId1 && currentNodeId == endNodeId2)
          || (sourceNodeId == endNodeId2 && currentNodeId == endNodeId1)) {
          // track found, compile
          OsmTrack t = compileTrack(path, verbose);
          t.showspeed = routingContext.showspeed;
          t.showSpeedProfile = routingContext.showSpeedProfile;
          return t;
        }

        // check for a match with the cost-cutting-track
        if (costCuttingTrack != null) {
          OsmPathElement pe = costCuttingTrack.getLink(sourceNodeId, currentNodeId);
          if (pe != null) {
            int costEstimate = path.cost
              + path.elevationCorrection()
              + (costCuttingTrack.cost - pe.cost);
            if (costEstimate < maxTotalCost) {
              maxTotalCost = costEstimate;
            }
          }
        }
      }

      if (path.treeDepth > 1) {
        boolean isBidir = currentLink.isBidirectional();
        sourceNode.unlinkLink(currentLink);

        // if the counterlink is alive and does not yet have a path, remove it
        if (isBidir && !routingContext.considerTurnRestrictions) {
          currentNode.unlinkLink(currentLink);
        }
      }

      // recheck cutoff before doing expensive stuff
      int addDiff = 100;
      if (path.cost + path.airDistance > maxTotalCost + addDiff) {
        continue;
      }


      routingContext.firstPrePath = null;

      for (GraphLink link = currentNode.firstLink; link != null; link = link.getNext(currentNode)) {
        GraphNode nextNode = link.getTarget(currentNode);

        if (!nodesCache.obtainNonHollowNode(nextNode)) {
          continue; // border node?
        }
        if (nextNode.firstLink == null) {
          continue; // don't care about dead ends
        }
        if (nextNode == sourceNode) {
          continue; // border node?
        }

        OsmPrePath prePath = routingContext.createPrePath(path, link);
        if (prePath != null) {
          prePath.next = routingContext.firstPrePath;
          routingContext.firstPrePath = prePath;
        }
      }

      for (GraphLink link = currentNode.firstLink; link != null; link = link.getNext(currentNode)) {
        GraphNode nextNode = link.getTarget(currentNode);

        if (!nodesCache.obtainNonHollowNode(nextNode)) {
          continue; // border node?
        }
        if (nextNode.firstLink == null) {
          continue; // don't care about dead ends
        }
        if (nextNode == sourceNode) {
          continue; // border node?
        }

        if (guideTrack != null) {
          int gidx = path.treeDepth + 1;
          if (gidx >= guideTrack.nodes.size()) {
            continue;
          }
          OsmPathElement guideNode = guideTrack.nodes.get(routingContext.inverseRouting ? guideTrack.nodes.size() - 1 - gidx : gidx);
          long nextId = nextNode.getIdFromPos();
          if (nextId != guideNode.getIdFromPos()) {
            // not along the guide-track, discard, but register for voice-hint processing
            if (routingContext.turnInstructionMode > 0) {
              OsmPath detour = routingContext.createPath(path, link, refTrack, true);
              if (detour.cost >= 0. && nextId != startNodeId1 && nextId != startNodeId2) {
                guideTrack.registerDetourForId(currentNode.getIdFromPos(), OsmPathElement.create(detour));
              }
            }
            continue;
          }
        }

        boolean isFinalLink = false;
        long targetNodeId = nextNode.getIdFromPos();
        if (currentNodeId == endNodeId1 || currentNodeId == endNodeId2) {
          if (targetNodeId == endNodeId1 || targetNodeId == endNodeId2) {
            isFinalLink = true;
          }
        }

        OsmPath bestPath = null;
        {
          try {
            if (isFinalLink) {
              endPos.radius = 1.5; // 1.5 meters is the upper limit that will not change the unit-test result..
              routingContext.setWaypoint(endPos, true);
            }
            OsmPath testPath = routingContext.createPath(path, link, refTrack, guideTrack != null);
            if (testPath.cost >= 0 &&
              (testPath.sourceNode.getIdFromPos() != testPath.targetNode.getIdFromPos())) {
              bestPath = testPath;
            }
          } finally {
            if (isFinalLink) {
              routingContext.unsetWaypoint();
            }
          }
        }
        if (bestPath != null) {
          bestPath.airDistance = isFinalLink ? 0 : nextNode.calcDistance(endPos);

          if (isFinalLink || bestPath.cost + bestPath.airDistance <= maxTotalCost + addDiff) {
            bestPath.treeDepth = path.treeDepth + 1;
            addToOpenset(openSet, bestPath);
          }
        }
      }
    }

    if (nodesVisited < MAXNODES_ISLAND_CHECK && islandNodePairs.getFreezeCount() < 5) {
      throw new RoutingIslandException();
    }

    return null;
  }

  private void addToOpenset(SortedHeap<OsmPath> openSet, OsmPath path) {
    if (path.cost >= 0) {
      openSet.add(path.cost + (int) (path.airDistance * airDistanceCostFactor), path);
    }
  }

  private OsmTrack compileTrack(OsmPath path, boolean verbose) {
    OsmPathElement element = OsmPathElement.create(path);

    // for final track, cut endnode
    if (guideTrack != null && element.origin != null) {
      element = element.origin;
    }

    float totalTime = element.getTime();
    float totalEnergy = element.getEnergy();

    OsmTrack track = new OsmTrack();
    track.cost = path.cost;
    track.energy = (int) path.getTotalEnergy();

    int distance = 0;

    while (element != null) {
      if (guideTrack != null && element.message == null) {
        element.message = new MessageData();
      }
      OsmPathElement nextElement = element.origin;
      // ignore double element
      if (nextElement != null && nextElement.positionEquals(element)) {
        element = nextElement;
        continue;
      }
      if (routingContext.inverseRouting) {
        element.setTime(totalTime - element.getTime());
        element.setEnergy(totalEnergy - element.getEnergy());
        track.nodes.add(element);
      } else {
        track.nodes.add(0, element);
      }

      if (nextElement != null) {
        distance += element.calcDistance(nextElement);
      }
      element = nextElement;
    }
    track.distance = distance;
    track.buildMap();

    // for final track..
    if (guideTrack != null) {
      track.copyDetours(guideTrack);
    }
    return track;
  }

  public OsmTrack getFoundTrack() {
    return foundTrack;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void terminate() {
    terminated = true;
  }
}
