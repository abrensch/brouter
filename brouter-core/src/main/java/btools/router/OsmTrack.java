/**
 * Container for a track
 *
 * @author ab
 */
package btools.router;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import btools.mapaccess.MatchedWaypoint;
import btools.mapaccess.OsmPos;
import btools.util.CompactLongMap;
import btools.util.FrozenLongMap;

public final class OsmTrack {
  final public static String version = "1.7.4";
  final public static String versionDate = "09042024";

  // csv-header-line
  private static final String MESSAGES_HEADER = "Longitude\tLatitude\tElevation\tDistance\tCostPerKm\tElevCost\tTurnCost\tNodeCost\tInitialCost\tWayTags\tNodeTags\tTime\tEnergy";

  public MatchedWaypoint endPoint;
  public long[] nogoChecksums;
  public long profileTimestamp;
  public boolean isDirty;

  public boolean showspeed;
  public boolean showSpeedProfile;
  public boolean showTime;

  public Map<String, String> params;

  public List<OsmNodeNamed> pois = new ArrayList<>();

  public static class OsmPathElementHolder {
    public OsmPathElement node;
    public OsmPathElementHolder nextHolder;
  }

  public List<OsmPathElement> nodes = new ArrayList<>();

  private CompactLongMap<OsmPathElementHolder> nodesMap;

  private CompactLongMap<OsmPathElementHolder> detourMap;

  public VoiceHintList voiceHints;

  public String message = null;
  public List<String> messageList = null;

  public String name = "unset";

  protected List<MatchedWaypoint> matchedWaypoints;
  public boolean exportWaypoints = false;

  public void addNode(OsmPathElement node) {
    nodes.add(0, node);
  }

  public void registerDetourForId(long id, OsmPathElement detour) {
    if (detourMap == null) {
      detourMap = new CompactLongMap<>();
    }
    OsmPathElementHolder nh = new OsmPathElementHolder();
    nh.node = detour;
    OsmPathElementHolder h = detourMap.get(id);
    if (h != null) {
      while (h.nextHolder != null) {
        h = h.nextHolder;
      }
      h.nextHolder = nh;
    } else {
      detourMap.fastPut(id, nh);
    }
  }

  public void copyDetours(OsmTrack source) {
    detourMap = source.detourMap == null ? null : new FrozenLongMap<>(source.detourMap);
  }

  public void addDetours(OsmTrack source) {
    if (detourMap != null) {
      CompactLongMap<OsmPathElementHolder> tmpDetourMap = new CompactLongMap<>();

      List oldlist = ((FrozenLongMap) detourMap).getValueList();
      long[] oldidlist = ((FrozenLongMap) detourMap).getKeyArray();
      for (int i = 0; i < oldidlist.length; i++) {
        long id = oldidlist[i];
        OsmPathElementHolder v = detourMap.get(id);

        tmpDetourMap.put(id, v);
      }

      if (source.detourMap != null) {
        long[] idlist = ((FrozenLongMap) source.detourMap).getKeyArray();
        for (int i = 0; i < idlist.length; i++) {
          long id = idlist[i];
          OsmPathElementHolder v = source.detourMap.get(id);
          if (!tmpDetourMap.contains(id) && source.nodesMap.contains(id)) {
            tmpDetourMap.put(id, v);
          }
        }
      }
      detourMap = new FrozenLongMap<>(tmpDetourMap);
    }
  }

  OsmPathElement lastorigin = null;

  public void appendDetours(OsmTrack source) {
    if (detourMap == null) {
      detourMap = source.detourMap == null ? null : new CompactLongMap<>();
    }
    if (source.detourMap != null) {
      int pos = nodes.size() - source.nodes.size() + 1;
      OsmPathElement origin = null;
      if (pos > 0)
        origin = nodes.get(pos);
      for (OsmPathElement node : source.nodes) {
        long id = node.getIdFromPos();
        OsmPathElementHolder nh = new OsmPathElementHolder();
        if (node.origin == null && lastorigin != null)
          node.origin = lastorigin;
        nh.node = node;
        lastorigin = node;
        OsmPathElementHolder h = detourMap.get(id);
        if (h != null) {
          while (h.nextHolder != null) {
            h = h.nextHolder;
          }
          h.nextHolder = nh;
        } else {
          detourMap.fastPut(id, nh);
        }
      }
    }
  }

  public void buildMap() {
    nodesMap = new CompactLongMap<>();
    for (OsmPathElement node : nodes) {
      long id = node.getIdFromPos();
      OsmPathElementHolder nh = new OsmPathElementHolder();
      nh.node = node;
      OsmPathElementHolder h = nodesMap.get(id);
      if (h != null) {
        while (h.nextHolder != null) {
          h = h.nextHolder;
        }
        h.nextHolder = nh;
      } else {
        nodesMap.fastPut(id, nh);
      }
    }
    nodesMap = new FrozenLongMap<>(nodesMap);
  }

  public List<String> aggregateMessages() {
    List<String> res = new ArrayList<>();
    MessageData current = null;
    for (OsmPathElement n : nodes) {
      if (n.message != null && n.message.wayKeyValues != null) {
        MessageData md = n.message.copy();
        if (current != null) {
          if (current.nodeKeyValues != null || !current.wayKeyValues.equals(md.wayKeyValues)) {
            res.add(current.toMessage());
          } else {
            md.add(current);
          }
        }
        current = md;
      }
    }
    if (current != null) {
      res.add(current.toMessage());
    }
    return res;
  }

  public List<String> aggregateSpeedProfile() {
    List<String> res = new ArrayList<>();
    int vmax = -1;
    int vmaxe = -1;
    int vmin = -1;
    int extraTime = 0;
    for (int i = nodes.size() - 1; i > 0; i--) {
      OsmPathElement n = nodes.get(i);
      MessageData m = n.message;
      int vnode = getVNode(i);
      if (m != null && (vmax != m.vmax || vmin != m.vmin || vmaxe != m.vmaxExplicit || vnode < m.vmax || extraTime != m.extraTime)) {
        vmax = m.vmax;
        vmin = m.vmin;
        vmaxe = m.vmaxExplicit;
        extraTime = m.extraTime;
        res.add(i + "," + vmaxe + "," + vmax + "," + vmin + "," + vnode + "," + extraTime);
      }
    }
    return res;
  }


  /**
   * writes the track in binary-format to a file
   *
   * @param filename the filename to write to
   */
  public void writeBinary(String filename) throws Exception {
    DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(filename)));

    endPoint.writeToStream(dos);
    dos.writeInt(nodes.size());
    for (OsmPathElement node : nodes) {
      node.writeToStream(dos);
    }
    dos.writeLong(nogoChecksums[0]);
    dos.writeLong(nogoChecksums[1]);
    dos.writeLong(nogoChecksums[2]);
    dos.writeBoolean(isDirty);
    dos.writeLong(profileTimestamp);
    dos.close();
  }

  public static OsmTrack readBinary(String filename, OsmNodeNamed newEp, long[] nogoChecksums, long profileChecksum, StringBuilder debugInfo) {
    OsmTrack t = null;
    if (filename != null) {
      File f = new File(filename);
      if (f.exists()) {
        try {
          DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(f)));
          MatchedWaypoint ep = MatchedWaypoint.readFromStream(dis);
          int dlon = ep.waypoint.ilon - newEp.ilon;
          int dlat = ep.waypoint.ilat - newEp.ilat;
          boolean targetMatch = dlon < 20 && dlon > -20 && dlat < 20 && dlat > -20;
          if (debugInfo != null) {
            debugInfo.append("target-delta = " + dlon + "/" + dlat + " targetMatch=" + targetMatch);
          }
          if (targetMatch) {
            t = new OsmTrack();
            t.endPoint = ep;
            int n = dis.readInt();
            OsmPathElement last_pe = null;
            for (int i = 0; i < n; i++) {
              OsmPathElement pe = OsmPathElement.readFromStream(dis);
              pe.origin = last_pe;
              last_pe = pe;
              t.nodes.add(pe);
            }
            t.cost = last_pe.cost;
            t.buildMap();

            // check cheecksums, too
            long[] al = new long[3];
            long pchecksum = 0;
            try {
              al[0] = dis.readLong();
              al[1] = dis.readLong();
              al[2] = dis.readLong();
            } catch (EOFException eof) { /* kind of expected */ }
            try {
              t.isDirty = dis.readBoolean();
            } catch (EOFException eof) { /* kind of expected */ }
            try {
              pchecksum = dis.readLong();
            } catch (EOFException eof) { /* kind of expected */ }
            boolean nogoCheckOk = Math.abs(al[0] - nogoChecksums[0]) <= 20
              && Math.abs(al[1] - nogoChecksums[1]) <= 20
              && Math.abs(al[2] - nogoChecksums[2]) <= 20;
            boolean profileCheckOk = pchecksum == profileChecksum;

            if (debugInfo != null) {
              debugInfo.append(" nogoCheckOk=" + nogoCheckOk + " profileCheckOk=" + profileCheckOk);
              debugInfo.append(" al=" + formatLongs(al) + " nogoChecksums=" + formatLongs(nogoChecksums));
            }
            if (!(nogoCheckOk && profileCheckOk)) return null;
          }
          dis.close();
        } catch (Exception e) {
          if (debugInfo != null) {
            debugInfo.append("Error reading rawTrack: " + e);
          }
        }
      }
    }
    return t;
  }

  private static String formatLongs(long[] al) {
    StringBuilder sb = new StringBuilder();
    sb.append('{');
    for (long l : al) {
      sb.append(l);
      sb.append(' ');
    }
    sb.append('}');
    return sb.toString();
  }


  public void addNodes(OsmTrack t) {
    for (OsmPathElement n : t.nodes)
      addNode(n);
    buildMap();
  }

  public boolean containsNode(OsmPos node) {
    return nodesMap.contains(node.getIdFromPos());
  }

  public OsmPathElement getLink(long n1, long n2) {
    OsmPathElementHolder h = nodesMap.get(n2);
    while (h != null) {
      OsmPathElement e1 = h.node.origin;
      if (e1 != null && e1.getIdFromPos() == n1) {
        return h.node;
      }
      h = h.nextHolder;
    }
    return null;
  }

  public void appendTrack(OsmTrack t) {
    int i = 0;

    int ourSize = nodes.size();
    if (ourSize > 0 && t.nodes.size() > 1) {
      OsmPathElement olde = nodes.get(ourSize - 1);
      t.nodes.get(1).origin = olde;
    }
    float t0 = ourSize > 0 ? nodes.get(ourSize - 1).getTime() : 0;
    float e0 = ourSize > 0 ? nodes.get(ourSize - 1).getEnergy() : 0;
    for (i = 0; i < t.nodes.size(); i++) {
      OsmPathElement e = t.nodes.get(i);
      if (i == 0 && ourSize > 0 && nodes.get(ourSize - 1).getSElev() == Short.MIN_VALUE)
        nodes.get(ourSize - 1).setSElev(e.getSElev());
      if (i > 0 || ourSize == 0) {
        e.setTime(e.getTime() + t0);
        e.setEnergy(e.getEnergy() + e0);
        if (e.message != null){
          if (!(e.message.lon == e.getILon() && e.message.lat == e.getILat())) {
            e.message.lon = e.getILon();
            e.message.lat = e.getILat();
          }
        }
        nodes.add(e);
      }
    }

    if (t.voiceHints != null) {
      if (ourSize > 0) {
        for (VoiceHint hint : t.voiceHints.list) {
          hint.indexInTrack = hint.indexInTrack + ourSize - 1;
        }
      }
      if (voiceHints == null) {
        voiceHints = t.voiceHints;
      } else {
        voiceHints.list.addAll(t.voiceHints.list);
      }
    } else {
      if (detourMap == null) {
        //copyDetours( t );
        detourMap = t.detourMap;
      } else {
        addDetours(t);
      }
    }

    distance += t.distance;
    ascend += t.ascend;
    plainAscend += t.plainAscend;
    cost += t.cost;
    energy = (int) nodes.get(nodes.size() - 1).getEnergy();

    showspeed |= t.showspeed;
    showSpeedProfile |= t.showSpeedProfile;
  }

  public int distance;
  public int ascend;
  public int plainAscend;
  public int cost;
  public int energy;
  public List<String> iternity;

  public VoiceHint getVoiceHint(int i) {
    if (voiceHints == null) return null;
    for (VoiceHint hint : voiceHints.list) {
      if (hint.indexInTrack == i) {
        return hint;
      }
    }
    return null;
  }

  public MatchedWaypoint getMatchedWaypoint(int idx) {
    if (matchedWaypoints == null) return null;
    for (MatchedWaypoint wp : matchedWaypoints) {
      if (idx == wp.indexInTrack) {
        return wp;
      }
    }
    return null;
  }

  private int getVNode(int i) {
    MessageData m1 = i + 1 < nodes.size() ? nodes.get(i + 1).message : null;
    MessageData m0 = i < nodes.size() ? nodes.get(i).message : null;
    int vnode0 = m1 == null ? 999 : m1.vnode0;
    int vnode1 = m0 == null ? 999 : m0.vnode1;
    return vnode0 < vnode1 ? vnode0 : vnode1;
  }

  public int getTotalSeconds() {
    float s = nodes.size() < 2 ? 0 : nodes.get(nodes.size() - 1).getTime() - nodes.get(0).getTime();
    return (int) (s + 0.5);
  }

  public boolean equalsTrack(OsmTrack t) {
    if (nodes.size() != t.nodes.size())
      return false;
    for (int i = 0; i < nodes.size(); i++) {
      OsmPathElement e1 = nodes.get(i);
      OsmPathElement e2 = t.nodes.get(i);
      if (e1.getILon() != e2.getILon() || e1.getILat() != e2.getILat())
        return false;
    }
    return true;
  }

  public OsmPathElementHolder getFromDetourMap(long id) {
    if (detourMap == null)
      return null;
    return detourMap.get(id);
  }

  public void prepareSpeedProfile(RoutingContext rc) {
    // sendSpeedProfile = rc.keyValues != null && rc.keyValues.containsKey( "vmax" );
  }

  public void processVoiceHints(RoutingContext rc) {
    voiceHints = new VoiceHintList();
    voiceHints.setTransportMode(rc.carMode, rc.bikeMode);
    voiceHints.turnInstructionMode = rc.turnInstructionMode;

    if (detourMap == null) {
      return;
    }
    int nodeNr = nodes.size() - 1;
    int i = nodeNr;
    OsmPathElement node = nodes.get(nodeNr);
    while (node != null) {
      if (node.origin != null) {
      }
      node = node.origin;
    }

    i = 0;

    node = nodes.get(nodeNr);
    List<VoiceHint> inputs = new ArrayList<>();
    while (node != null) {
      if (node.origin != null) {
        VoiceHint input = new VoiceHint();
        inputs.add(input);
        input.ilat = node.origin.getILat();
        input.ilon = node.origin.getILon();
        input.selev = node.origin.getSElev();
        input.indexInTrack = --nodeNr;
        input.goodWay = node.message;
        input.oldWay = node.origin.message == null ? node.message : node.origin.message;
        if (rc.turnInstructionMode == 8 ||
          rc.turnInstructionMode == 4 ||
          rc.turnInstructionMode == 2 ||
          rc.turnInstructionMode == 9) {
          MatchedWaypoint mwpt = getMatchedWaypoint(nodeNr);
          if (mwpt != null && mwpt.direct) {
            input.cmd = VoiceHint.BL;
            input.angle = (float) (nodeNr == 0 ? node.origin.message.turnangle : node.message.turnangle);
            input.distanceToNext = node.calcDistance(node.origin);
          }
        }
        OsmPathElementHolder detours = detourMap.get(node.origin.getIdFromPos());
        if (nodeNr >= 0 && detours != null) {
          OsmPathElementHolder h = detours;
          while (h != null) {
            OsmPathElement e = h.node;
            input.addBadWay(startSection(e, node.origin));
            h = h.nextHolder;
          }
        } else if (nodeNr == 0 && detours != null) {
          OsmPathElementHolder h = detours;
          OsmPathElement e = h.node;
          input.addBadWay(startSection(e, e));
        }
      }
      node = node.origin;
    }

    int transportMode = voiceHints.transportMode();
    VoiceHintProcessor vproc = new VoiceHintProcessor(rc.turnInstructionCatchingRange, rc.turnInstructionRoundabouts, transportMode);
    List<VoiceHint> results = vproc.process(inputs);

    double minDistance = getMinDistance();
    List<VoiceHint> resultsLast = vproc.postProcess(results, rc.turnInstructionCatchingRange, minDistance);
    for (VoiceHint hint : resultsLast) {
      voiceHints.list.add(hint);
    }

  }

  int getMinDistance() {
    if (voiceHints != null) {
      switch (voiceHints.transportMode()) {
        case VoiceHintList.TRANS_MODE_CAR:
          return 20;
        case VoiceHintList.TRANS_MODE_FOOT:
          return 3;
        case VoiceHintList.TRANS_MODE_BIKE:
        default:
          return 5;
      }
    }
    return 2;
  }

  public float getVoiceHintTime(int i) {
    if (voiceHints.list.isEmpty()) {
      return 0f;
    }
    if (i < voiceHints.list.size()) {
      return voiceHints.list.get(i).getTime();
    }
    if (nodes.isEmpty()) {
      return 0f;
    }
    return nodes.get(nodes.size() - 1).getTime();
  }

  public void removeVoiceHint(int i) {
    if (voiceHints != null) {
      VoiceHint remove = null;
      for (VoiceHint vh : voiceHints.list) {
        if (vh.indexInTrack == i)
          remove = vh;
      }
      if (remove != null)
        voiceHints.list.remove(remove);
    }
  }

  private MessageData startSection(OsmPathElement element, OsmPathElement root) {
    OsmPathElement e = element;
    int cnt = 0;
    while (e != null && e.origin != null) {
      if (e.origin.getILat() == root.getILat() && e.origin.getILon() == root.getILon()) {
        return e.message;
      }
      e = e.origin;
      if (cnt++ == 1000000) {
        throw new IllegalArgumentException("ups: " + root + "->" + element);
      }
    }
    return null;
  }
}
