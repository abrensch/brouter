/**
 * Container for a track
 *
 * @author ab
 */
package btools.router;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import btools.mapaccess.MatchedWaypoint;
import btools.mapaccess.OsmPos;
import btools.util.CompactLongMap;
import btools.util.FrozenLongMap;
import btools.util.StringUtils;

public final class OsmTrack {
  final public static String version = "1.7.3";
  final public static String versionDate = "19082023";

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

  private VoiceHintList voiceHints;

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

  private List<String> aggregateMessages() {
    ArrayList<String> res = new ArrayList<>();
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

  private List<String> aggregateSpeedProfile() {
    ArrayList<String> res = new ArrayList<>();
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
          throw new RuntimeException("Exception reading rawTrack: " + e);
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

  /**
   * writes the track in gpx-format to a file
   *
   * @param filename the filename to write to
   */
  public void writeGpx(String filename) throws Exception {
    BufferedWriter bw = new BufferedWriter(new FileWriter(filename));
    formatAsGpx(bw);
    bw.close();
  }

  public String formatAsGpx() {
    try {
      StringWriter sw = new StringWriter(8192);
      BufferedWriter bw = new BufferedWriter(sw);
      formatAsGpx(bw);
      bw.close();
      return sw.toString();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public String formatAsGpx(BufferedWriter sb) throws IOException {
    int turnInstructionMode = voiceHints != null ? voiceHints.turnInstructionMode : 0;

    sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    if (turnInstructionMode != 9) {
      for (int i = messageList.size() - 1; i >= 0; i--) {
        String message = messageList.get(i);
        if (i < messageList.size() - 1)
          message = "(alt-index " + i + ": " + message + " )";
        if (message != null)
          sb.append("<!-- ").append(message).append(" -->\n");
      }
    }

    if (turnInstructionMode == 4) { // comment style
      sb.append("<!-- $transport-mode$").append(voiceHints.getTransportMode()).append("$ -->\n");
      sb.append("<!--          cmd    idx        lon        lat d2next  geometry -->\n");
      sb.append("<!-- $turn-instruction-start$\n");
      for (VoiceHint hint : voiceHints.list) {
        sb.append(String.format("     $turn$%6s;%6d;%10s;%10s;%6d;%s$\n", hint.getCommandString(), hint.indexInTrack,
          formatILon(hint.ilon), formatILat(hint.ilat), (int) (hint.distanceToNext), hint.formatGeometry()));
      }
      sb.append("    $turn-instruction-end$ -->\n");
    }
    sb.append("<gpx \n");
    sb.append(" xmlns=\"http://www.topografix.com/GPX/1/1\" \n");
    sb.append(" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" \n");
    if (turnInstructionMode == 9) { // BRouter style
      sb.append(" xmlns:brouter=\"Not yet documented\" \n");
    }
    if (turnInstructionMode == 7) { // old locus style
      sb.append(" xmlns:locus=\"http://www.locusmap.eu\" \n");
    }
    sb.append(" xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\" \n");

    if (turnInstructionMode == 3) {
      sb.append(" creator=\"OsmAndRouter\" version=\"1.1\">\n");
    } else {
      sb.append(" creator=\"BRouter-" + version + "\" version=\"1.1\">\n");
    }
    if (turnInstructionMode == 9) {
      sb.append(" <metadata>\n");
      sb.append("  <name>").append(name).append("</name>\n");
      sb.append("  <extensions>\n");
      sb.append("   <brouter:info>").append(messageList.get(0)).append("</brouter:info>\n");
      if (params != null && params.size() > 0) {
        sb.append("   <brouter:params><![CDATA[");
        int i = 0;
        for (Map.Entry<String, String> e : params.entrySet()) {
          if (i++ != 0) sb.append("&");
          sb.append(e.getKey()).append("=").append(e.getValue());
        }
        sb.append("]]></brouter:params>\n");
      }
      sb.append("  </extensions>\n");
      sb.append(" </metadata>\n");
    }
    if (turnInstructionMode == 3 || turnInstructionMode == 8) { // osmand style, cruiser
      float lastRteTime = 0;

      sb.append(" <rte>\n");

      float rteTime = getVoiceHintTime(0);
      StringBuffer first = new StringBuffer();
      // define start point
      {
        first.append("  <rtept lat=\"").append(formatILat(nodes.get(0).getILat())).append("\" lon=\"")
          .append(formatILon(nodes.get(0).getILon())).append("\">\n")
          .append("   <desc>start</desc>\n   <extensions>\n");
        if (rteTime != lastRteTime) { // add timing only if available
          double t = rteTime - lastRteTime;
          first.append("    <time>").append("" + (int) (t + 0.5)).append("</time>\n");
          lastRteTime = rteTime;
        }
        first.append("    <offset>0</offset>\n  </extensions>\n </rtept>\n");
      }
      if (turnInstructionMode == 8) {
        if (matchedWaypoints.get(0).direct && voiceHints.list.get(0).indexInTrack == 0) {
          // has a voice hint do nothing, voice hint will do
        } else {
          sb.append(first.toString());
        }
      } else {
        sb.append(first.toString());
      }

      for (int i = 0; i < voiceHints.list.size(); i++) {
        VoiceHint hint = voiceHints.list.get(i);
        sb.append("  <rtept lat=\"").append(formatILat(hint.ilat)).append("\" lon=\"")
          .append(formatILon(hint.ilon)).append("\">\n")
          .append("   <desc>")
          .append(turnInstructionMode == 3 ? hint.getMessageString() : hint.getCruiserMessageString())
          .append("</desc>\n   <extensions>\n");

        rteTime = getVoiceHintTime(i + 1);

        if (rteTime != lastRteTime) { // add timing only if available
          double t = rteTime - lastRteTime;
          sb.append("    <time>").append("" + (int) (t + 0.5)).append("</time>\n");
          lastRteTime = rteTime;
        }
        sb.append("    <turn>")
          .append(turnInstructionMode == 3 ? hint.getCommandString() : hint.getCruiserCommandString())
          .append("</turn>\n    <turn-angle>").append("" + (int) hint.angle)
          .append("</turn-angle>\n    <offset>").append("" + hint.indexInTrack).append("</offset>\n  </extensions>\n </rtept>\n");
      }
      sb.append("  <rtept lat=\"").append(formatILat(nodes.get(nodes.size() - 1).getILat())).append("\" lon=\"")
        .append(formatILon(nodes.get(nodes.size() - 1).getILon())).append("\">\n")
        .append("   <desc>destination</desc>\n   <extensions>\n");
      sb.append("    <time>0</time>\n");
      sb.append("    <offset>").append("" + (nodes.size() - 1)).append("</offset>\n  </extensions>\n </rtept>\n");

      sb.append("</rte>\n");
    }

    if (turnInstructionMode == 7) { // old locus style
      float lastRteTime = getVoiceHintTime(0);

      for (int i = 0; i < voiceHints.list.size(); i++) {
        VoiceHint hint = voiceHints.list.get(i);
        sb.append(" <wpt lon=\"").append(formatILon(hint.ilon)).append("\" lat=\"")
          .append(formatILat(hint.ilat)).append("\">")
          .append(hint.selev == Short.MIN_VALUE ? "" : "<ele>" + (hint.selev / 4.) + "</ele>")
          .append("<name>").append(hint.getMessageString()).append("</name>")
          .append("<extensions><locus:rteDistance>").append("" + hint.distanceToNext).append("</locus:rteDistance>");
        float rteTime = getVoiceHintTime(i + 1);
        if (rteTime != lastRteTime) { // add timing only if available
          double t = rteTime - lastRteTime;
          double speed = hint.distanceToNext / t;
          sb.append("<locus:rteTime>").append("" + t).append("</locus:rteTime>")
            .append("<locus:rteSpeed>").append("" + speed).append("</locus:rteSpeed>");
          lastRteTime = rteTime;
        }
        sb.append("<locus:rtePointAction>").append("" + hint.getLocusAction()).append("</locus:rtePointAction></extensions>")
          .append("</wpt>\n");
      }
    }
    if (turnInstructionMode == 5) { // gpsies style
      for (VoiceHint hint : voiceHints.list) {
        sb.append(" <wpt lon=\"").append(formatILon(hint.ilon)).append("\" lat=\"")
          .append(formatILat(hint.ilat)).append("\">")
          .append("<name>").append(hint.getMessageString()).append("</name>")
          .append("<sym>").append(hint.getSymbolString().toLowerCase()).append("</sym>")
          .append("<type>").append(hint.getSymbolString()).append("</type>")
          .append("</wpt>\n");
      }
    }

    if (turnInstructionMode == 6) { // orux style
      for (VoiceHint hint : voiceHints.list) {
        sb.append(" <wpt lat=\"").append(formatILat(hint.ilat)).append("\" lon=\"")
          .append(formatILon(hint.ilon)).append("\">")
          .append(hint.selev == Short.MIN_VALUE ? "" : "<ele>" + (hint.selev / 4.) + "</ele>")
          .append("<extensions>\n" +
            "  <om:oruxmapsextensions xmlns:om=\"http://www.oruxmaps.com/oruxmapsextensions/1/0\">\n" +
            "   <om:ext type=\"ICON\" subtype=\"0\">").append("" + hint.getOruxAction())
          .append("</om:ext>\n" +
            "  </om:oruxmapsextensions>\n" +
            "  </extensions>\n" +
            " </wpt>\n");
      }
    }

    for (int i = 0; i <= pois.size() - 1; i++) {
      OsmNodeNamed poi = pois.get(i);
      sb.append(" <wpt lon=\"").append(formatILon(poi.ilon)).append("\" lat=\"")
        .append(formatILat(poi.ilat)).append("\">\n")
        .append("  <name>").append(StringUtils.escapeXml10(poi.name)).append("</name>\n")
        .append(" </wpt>\n");
    }

    if (exportWaypoints) {
      for (int i = 0; i <= matchedWaypoints.size() - 1; i++) {
        MatchedWaypoint wt = matchedWaypoints.get(i);
        sb.append(" <wpt lon=\"").append(formatILon(wt.waypoint.ilon)).append("\" lat=\"")
          .append(formatILat(wt.waypoint.ilat)).append("\">\n")
          .append("  <name>").append(StringUtils.escapeXml10(wt.name)).append("</name>\n");
        if (i == 0) {
          sb.append("  <type>from</type>\n");
        } else if (i == matchedWaypoints.size() - 1) {
          sb.append("  <type>to</type>\n");
        } else {
          sb.append("  <type>via</type>\n");
        }
        sb.append(" </wpt>\n");
      }
    }
    sb.append(" <trk>\n");
    if (turnInstructionMode == 9
      || turnInstructionMode == 2
      || turnInstructionMode == 8
      || turnInstructionMode == 4) { // Locus, comment, cruise, brouter style
      sb.append("  <src>").append(name).append("</src>\n");
      sb.append("  <type>").append(voiceHints.getTransportMode()).append("</type>\n");
    } else {
      sb.append("  <name>").append(name).append("</name>\n");
    }

    if (turnInstructionMode == 7) {
      sb.append("  <extensions>\n");
      sb.append("   <locus:rteComputeType>").append("" + voiceHints.getLocusRouteType()).append("</locus:rteComputeType>\n");
      sb.append("   <locus:rteSimpleRoundabouts>1</locus:rteSimpleRoundabouts>\n");
      sb.append("  </extensions>\n");
    }


    // all points
    sb.append("  <trkseg>\n");
    String lastway = "";
    boolean bNextDirect = false;
    OsmPathElement nn = null;
    String aSpeed;

    for (int idx = 0; idx < nodes.size(); idx++) {
      OsmPathElement n = nodes.get(idx);
      String sele = n.getSElev() == Short.MIN_VALUE ? "" : "<ele>" + n.getElev() + "</ele>";
      VoiceHint hint = getVoiceHint(idx);
      MatchedWaypoint mwpt = getMatchedWaypoint(idx);

      if (showTime) {
        sele += "<time>" + getFormattedTime3(n.getTime()) + "</time>";
      }
      if (turnInstructionMode == 8) {
        if (mwpt != null &&
          !mwpt.name.startsWith("via") && !mwpt.name.startsWith("from") && !mwpt.name.startsWith("to")) {
          sele += "<name>" + mwpt.name + "</name>";
        }
      }
      boolean bNeedHeader = false;
      if (turnInstructionMode == 9) { // trkpt/sym style

        if (hint != null) {

          if (mwpt != null &&
            !mwpt.name.startsWith("via") && !mwpt.name.startsWith("from") && !mwpt.name.startsWith("to")) {
            sele += "<name>" + mwpt.name + "</name>";
          }
          sele += "<desc>" + hint.getCruiserMessageString() + "</desc>";
          sele += "<sym>" + hint.getCommandString(hint.cmd) + "</sym>";
          if (mwpt != null) {
            sele += "<type>Via</type>";
          }
          sele += "<extensions>";
          if (showspeed) {
            double speed = 0;
            if (nn != null) {
              int dist = n.calcDistance(nn);
              float dt = n.getTime() - nn.getTime();
              if (dt != 0.f) {
                speed = ((3.6f * dist) / dt + 0.5);
              }
            }
            sele += "<brouter:speed>" + (((int) (speed * 10)) / 10.f) + "</brouter:speed>";
          }

          sele += "<brouter:voicehint>" + hint.getCommandString() + ";" + (int) (hint.distanceToNext) + "," + hint.formatGeometry() + "</brouter:voicehint>";
          if (n.message != null && n.message.wayKeyValues != null && !n.message.wayKeyValues.equals(lastway)) {
            sele += "<brouter:way>" + n.message.wayKeyValues + "</brouter:way>";
            lastway = n.message.wayKeyValues;
          }
          if (n.message != null && n.message.nodeKeyValues != null) {
            sele += "<brouter:node>" + n.message.nodeKeyValues + "</brouter:node>";
          }
          sele += "</extensions>";

        }
        if (idx == 0 && hint == null) {
          if (mwpt != null && mwpt.direct) {
            sele += "<desc>beeline</desc>";
          } else {
            sele += "<desc>start</desc>";
          }
          sele += "<type>Via</type>";

        } else if (idx == nodes.size() - 1 && hint == null) {

          sele += "<desc>end</desc>";
          sele += "<type>Via</type>";

        } else {
          if (mwpt != null && hint == null) {
            if (mwpt.direct) {
              // bNextDirect = true;
              sele += "<desc>beeline</desc>";
            } else {
              sele += "<desc>" + mwpt.name + "</desc>";
            }
            sele += "<type>Via</type>";
            bNextDirect = false;
          }
        }


        if (hint == null) {
          bNeedHeader = (showspeed || (n.message != null && n.message.wayKeyValues != null && !n.message.wayKeyValues.equals(lastway))) ||
            (n.message != null && n.message.nodeKeyValues != null);
          if (bNeedHeader) {
            sele += "<extensions>";
            if (showspeed) {
              double speed = 0;
              if (nn != null) {
                int dist = n.calcDistance(nn);
                float dt = n.getTime() - nn.getTime();
                if (dt != 0.f) {
                  speed = ((3.6f * dist) / dt + 0.5);
                }
              }
              sele += "<brouter:speed>" + (((int) (speed * 10)) / 10.f) + "</brouter:speed>";
            }
            if (n.message != null && n.message.wayKeyValues != null && !n.message.wayKeyValues.equals(lastway)) {
              sele += "<brouter:way>" + n.message.wayKeyValues + "</brouter:way>";
              lastway = n.message.wayKeyValues;
            }
            if (n.message != null && n.message.nodeKeyValues != null) {
              sele += "<brouter:node>" + n.message.nodeKeyValues + "</brouter:node>";
            }
            sele += "</extensions>";
          }
        }
      }

      if (turnInstructionMode == 2) { // locus style new
        if (hint != null) {
          if (mwpt != null) {
            if (!mwpt.name.startsWith("via") && !mwpt.name.startsWith("from") && !mwpt.name.startsWith("to")) {
              sele += "<name>" + mwpt.name + "</name>";
            }
            if (mwpt.direct && bNextDirect) {
              sele += "<src>" + hint.getLocusSymbolString() + "</src><sym>pass_place</sym><type>Shaping</type>";
              // bNextDirect = false;
            } else if (mwpt.direct) {
              if (idx == 0)
                sele += "<sym>pass_place</sym><type>Via</type>";
              else
                sele += "<sym>pass_place</sym><type>Shaping</type>";
              bNextDirect = true;
            } else if (bNextDirect) {
              sele += "<src>beeline</src><sym>" + hint.getLocusSymbolString() + "</sym><type>Shaping</type>";
              bNextDirect = false;
            } else {
              sele += "<sym>" + hint.getLocusSymbolString() + "</sym><type>Via</type>";
            }
          } else {
            sele += "<sym>" + hint.getLocusSymbolString() + "</sym>";
          }
        } else {
          if (idx == 0 && hint == null) {

            int pos = sele.indexOf("<sym");
            if (pos != -1) {
              sele = sele.substring(0, pos);
            }
            if (mwpt != null && !mwpt.name.startsWith("from"))
              sele += "<name>" + mwpt.name + "</name>";
            if (mwpt != null && mwpt.direct) {
              bNextDirect = true;
            }
            sele += "<sym>pass_place</sym>";
            sele += "<type>Via</type>";

          } else if (idx == nodes.size() - 1 && hint == null) {

            int pos = sele.indexOf("<sym");
            if (pos != -1) {
              sele = sele.substring(0, pos);
            }
            if (mwpt != null && mwpt.name != null && !mwpt.name.startsWith("to"))
              sele += "<name>" + mwpt.name + "</name>";
            if (bNextDirect) {
              sele += "<src>beeline</src>";
            }
            sele += "<sym>pass_place</sym>";
            sele += "<type>Via</type>";

          } else {
            if (mwpt != null) {
              if (!mwpt.name.startsWith("via") && !mwpt.name.startsWith("from") && !mwpt.name.startsWith("to")) {
                sele += "<name>" + mwpt.name + "</name>";
              }
              if (mwpt.direct && bNextDirect) {
                sele += "<src>beeline</src><sym>pass_place</sym><type>Shaping</type>";
              } else if (mwpt.direct) {
                if (idx == 0)
                  sele += "<sym>pass_place</sym><type>Via</type>";
                else
                  sele += "<sym>pass_place</sym><type>Shaping</type>";
                bNextDirect = true;
              } else if (bNextDirect) {
                sele += "<src>beeline</src><sym>pass_place</sym><type>Shaping</type>";
                bNextDirect = false;
              } else if (mwpt.name.startsWith("via") ||
                mwpt.name.startsWith("from") ||
                mwpt.name.startsWith("to")) {
                if (bNextDirect) {
                  sele += "<src>beeline</src><sym>pass_place</sym><type>Shaping</type>";
                } else {
                  sele += "<sym>pass_place</sym><type>Via</type>";
                }
                bNextDirect = false;
              } else {
                sele += "<name>" + mwpt.name + "</name>";
                sele += "<sym>pass_place</sym><type>Via</type>";
              }
            }
          }
        }
      }
      sb.append("   <trkpt lon=\"").append(formatILon(n.getILon())).append("\" lat=\"")
        .append(formatILat(n.getILat())).append("\">").append(sele).append("</trkpt>\n");

      nn = n;
    }

    sb.append("  </trkseg>\n");
    sb.append(" </trk>\n");
    sb.append("</gpx>\n");

    return sb.toString();
  }

  static public String formatAsGpxWaypoint(OsmNodeNamed n) {
    try {
      StringWriter sw = new StringWriter(8192);
      BufferedWriter bw = new BufferedWriter(sw);
      formatGpxHeader(bw);
      formatWaypointGpx(bw, n);
      formatGpxFooter(bw);
      bw.close();
      sw.close();
      return sw.toString();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  static public void formatGpxHeader(BufferedWriter sb) throws IOException {
    sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    sb.append("<gpx \n");
    sb.append(" xmlns=\"http://www.topografix.com/GPX/1/1\" \n");
    sb.append(" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" \n");
    sb.append(" xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\" \n");
    sb.append(" creator=\"BRouter-" + version + "\" version=\"1.1\">\n");
  }

  static public void formatGpxFooter(BufferedWriter sb) throws IOException {
    sb.append("</gpx>\n");
  }

  static public void formatWaypointGpx(BufferedWriter sb, OsmNodeNamed n) throws IOException {
    sb.append(" <wpt lon=\"").append(formatILon(n.ilon)).append("\" lat=\"")
      .append(formatILat(n.ilat)).append("\">");
    if (n.getSElev() != Short.MIN_VALUE) {
      sb.append("<ele>").append("" + n.getElev()).append("</ele>");
    }
    if (n.name != null) {
      sb.append("<name>").append(StringUtils.escapeXml10(n.name)).append("</name>");
    }
    if (n.nodeDescription != null) {
      sb.append("<desc>").append("hat desc").append("</desc>");
    }
    sb.append("</wpt>\n");
  }

  public void writeKml(String filename) throws Exception {
    BufferedWriter bw = new BufferedWriter(new FileWriter(filename));

    bw.write(formatAsKml());
    bw.close();
  }

  public String formatAsKml() {
    StringBuilder sb = new StringBuilder(8192);

    sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");

    sb.append("<kml xmlns=\"http://earth.google.com/kml/2.0\">\n");
    sb.append("  <Document>\n");
    sb.append("    <name>KML Samples</name>\n");
    sb.append("    <open>1</open>\n");
    sb.append("    <distance>3.497064</distance>\n");
    sb.append("    <traveltime>872</traveltime>\n");
    sb.append("    <description>To enable simple instructions add: 'instructions=1' as parameter to the URL</description>\n");
    sb.append("    <Folder>\n");
    sb.append("      <name>Paths</name>\n");
    sb.append("      <visibility>0</visibility>\n");
    sb.append("      <description>Examples of paths.</description>\n");
    sb.append("      <Placemark>\n");
    sb.append("        <name>Tessellated</name>\n");
    sb.append("        <visibility>0</visibility>\n");
    sb.append("        <description><![CDATA[If the <tessellate> tag has a value of 1, the line will contour to the underlying terrain]]></description>\n");
    sb.append("        <LineString>\n");
    sb.append("          <tessellate>1</tessellate>\n");
    sb.append("         <coordinates>");

    for (OsmPathElement n : nodes) {
      sb.append(formatILon(n.getILon())).append(",").append(formatILat(n.getILat())).append("\n");
    }

    sb.append("          </coordinates>\n");
    sb.append("        </LineString>\n");
    sb.append("      </Placemark>\n");
    sb.append("    </Folder>\n");
    if (exportWaypoints || !pois.isEmpty()) {
      if (!pois.isEmpty()) {
        sb.append("    <Folder>\n");
        sb.append("      <name>poi</name>\n");
        for (int i = 0; i < pois.size(); i++) {
          OsmNodeNamed poi = pois.get(i);
          createPlaceMark(sb, poi.name, poi.ilat, poi.ilon);
        }
        sb.append("    </Folder>\n");
      }

      if (exportWaypoints) {
        int size = matchedWaypoints.size();
        createFolder(sb, "start", matchedWaypoints.subList(0, 1));
        if (matchedWaypoints.size() > 2) {
          createFolder(sb, "via", matchedWaypoints.subList(1, size - 1));
        }
        createFolder(sb, "end", matchedWaypoints.subList(size - 1, size));
      }
    }
    sb.append("  </Document>\n");
    sb.append("</kml>\n");

    return sb.toString();
  }

  private void createFolder(StringBuilder sb, String type, List<MatchedWaypoint> waypoints) {
    sb.append("    <Folder>\n");
    sb.append("      <name>" + type + "</name>\n");
    for (int i = 0; i < waypoints.size(); i++) {
      MatchedWaypoint wp = waypoints.get(i);
      createPlaceMark(sb, wp.name, wp.waypoint.ilat, wp.waypoint.ilon);
    }
    sb.append("    </Folder>\n");
  }

  private void createPlaceMark(StringBuilder sb, String name, int ilat, int ilon) {
    sb.append("      <Placemark>\n");
    sb.append("        <name>" + StringUtils.escapeXml10(name) + "</name>\n");
    sb.append("        <Point>\n");
    sb.append("         <coordinates>" + formatILon(ilon) + "," + formatILat(ilat) + "</coordinates>\n");
    sb.append("        </Point>\n");
    sb.append("      </Placemark>\n");
  }

  public List<String> iternity;

  public void writeJson(String filename) throws Exception {
    BufferedWriter bw = new BufferedWriter(new FileWriter(filename));

    bw.write(formatAsGeoJson());
    bw.close();
  }


  public String formatAsGeoJson() {
    int turnInstructionMode = voiceHints != null ? voiceHints.turnInstructionMode : 0;

    StringBuilder sb = new StringBuilder(8192);

    sb.append("{\n");
    sb.append("  \"type\": \"FeatureCollection\",\n");
    sb.append("  \"features\": [\n");
    sb.append("    {\n");
    sb.append("      \"type\": \"Feature\",\n");
    sb.append("      \"properties\": {\n");
    sb.append("        \"creator\": \"BRouter-" + version + "\",\n");
    sb.append("        \"name\": \"").append(name).append("\",\n");
    sb.append("        \"track-length\": \"").append(distance).append("\",\n");
    sb.append("        \"filtered ascend\": \"").append(ascend).append("\",\n");
    sb.append("        \"plain-ascend\": \"").append(plainAscend).append("\",\n");
    sb.append("        \"total-time\": \"").append(getTotalSeconds()).append("\",\n");
    sb.append("        \"total-energy\": \"").append(energy).append("\",\n");
    sb.append("        \"cost\": \"").append(cost).append("\",\n");
    if (voiceHints != null && !voiceHints.list.isEmpty()) {
      sb.append("        \"voicehints\": [\n");
      for (VoiceHint hint : voiceHints.list) {
        sb.append("          [");
        sb.append(hint.indexInTrack);
        sb.append(',').append(hint.getJsonCommandIndex());
        sb.append(',').append(hint.getExitNumber());
        sb.append(',').append(hint.distanceToNext);
        sb.append(',').append((int) hint.angle);

        // not always include geometry because longer and only needed for comment style
        if (turnInstructionMode == 4) { // comment style
          sb.append(",\"").append(hint.formatGeometry()).append("\"");
        }

        sb.append("],\n");
      }
      sb.deleteCharAt(sb.lastIndexOf(","));
      sb.append("        ],\n");
    }
    if (showSpeedProfile) { // set in profile
      List<String> sp = aggregateSpeedProfile();
      if (sp.size() > 0) {
        sb.append("        \"speedprofile\": [\n");
        for (int i = sp.size() - 1; i >= 0; i--) {
          sb.append("          [").append(sp.get(i)).append(i > 0 ? "],\n" : "]\n");
        }
        sb.append("        ],\n");
      }
    }
    //  ... traditional message list
    {
      sb.append("        \"messages\": [\n");
      sb.append("          [\"").append(MESSAGES_HEADER.replaceAll("\t", "\", \"")).append("\"],\n");
      for (String m : aggregateMessages()) {
        sb.append("          [\"").append(m.replaceAll("\t", "\", \"")).append("\"],\n");
      }
      sb.deleteCharAt(sb.lastIndexOf(","));
      sb.append("        ],\n");
    }

    if (getTotalSeconds() > 0) {
      sb.append("        \"times\": [");
      DecimalFormat decimalFormat = (DecimalFormat) NumberFormat.getInstance(Locale.ENGLISH);
      decimalFormat.applyPattern("0.###");
      for (OsmPathElement n : nodes) {
        sb.append(decimalFormat.format(n.getTime())).append(",");
      }
      sb.deleteCharAt(sb.lastIndexOf(","));
      sb.append("]\n");
    } else {
      sb.deleteCharAt(sb.lastIndexOf(","));
    }

    sb.append("      },\n");

    if (iternity != null) {
      sb.append("      \"iternity\": [\n");
      for (String s : iternity) {
        sb.append("        \"").append(s).append("\",\n");
      }
      sb.deleteCharAt(sb.lastIndexOf(","));
      sb.append("        ],\n");
    }
    sb.append("      \"geometry\": {\n");
    sb.append("        \"type\": \"LineString\",\n");
    sb.append("        \"coordinates\": [\n");

    OsmPathElement nn = null;
    for (OsmPathElement n : nodes) {
      String sele = n.getSElev() == Short.MIN_VALUE ? "" : ", " + n.getElev();
      if (showspeed) { // hack: show speed instead of elevation
        double speed = 0;
        if (nn != null) {
          int dist = n.calcDistance(nn);
          float dt = n.getTime() - nn.getTime();
          if (dt != 0.f) {
            speed = ((3.6f * dist) / dt + 0.5);
          }
        }
        sele = ", " + (((int) (speed * 10)) / 10.f);
      }
      sb.append("          [").append(formatILon(n.getILon())).append(", ").append(formatILat(n.getILat()))
        .append(sele).append("],\n");
      nn = n;
    }
    sb.deleteCharAt(sb.lastIndexOf(","));

    sb.append("        ]\n");
    sb.append("      }\n");
    if (exportWaypoints || !pois.isEmpty()) {
      sb.append("    },\n");
      for (int i = 0; i <= pois.size() - 1; i++) {
        OsmNodeNamed poi = pois.get(i);
        addFeature(sb, "poi", poi.name, poi.ilat, poi.ilon);
        if (i < matchedWaypoints.size() - 1) {
          sb.append(",");
        }
        sb.append("    \n");
      }
      if (exportWaypoints) {
        for (int i = 0; i <= matchedWaypoints.size() - 1; i++) {
          String type;
          if (i == 0) {
            type = "from";
          } else if (i == matchedWaypoints.size() - 1) {
            type = "to";
          } else {
            type = "via";
          }

          MatchedWaypoint wp = matchedWaypoints.get(i);
          addFeature(sb, type, wp.name, wp.waypoint.ilat, wp.waypoint.ilon);
          if (i < matchedWaypoints.size() - 1) {
            sb.append(",");
          }
          sb.append("    \n");
        }
      }
    } else {
      sb.append("    }\n");
    }
    sb.append("  ]\n");
    sb.append("}\n");

    return sb.toString();
  }

  private void addFeature(StringBuilder sb, String type, String name, int ilat, int ilon) {
    sb.append("    {\n");
    sb.append("      \"type\": \"Feature\",\n");
    sb.append("      \"properties\": {\n");
    sb.append("        \"name\": \"" + StringUtils.escapeJson(name) + "\",\n");
    sb.append("        \"type\": \"" + type + "\"\n");
    sb.append("      },\n");
    sb.append("      \"geometry\": {\n");
    sb.append("        \"type\": \"Point\",\n");
    sb.append("        \"coordinates\": [\n");
    sb.append("          " + formatILon(ilon) + ",\n");
    sb.append("          " + formatILat(ilat) + "\n");
    sb.append("        ]\n");
    sb.append("      }\n");
    sb.append("    }");
  }

  private VoiceHint getVoiceHint(int i) {
    if (voiceHints == null) return null;
    for (VoiceHint hint : voiceHints.list) {
      if (hint.indexInTrack == i) {
        return hint;
      }
    }
    return null;
  }

  private MatchedWaypoint getMatchedWaypoint(int idx) {
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

  private int getTotalSeconds() {
    float s = nodes.size() < 2 ? 0 : nodes.get(nodes.size() - 1).getTime() - nodes.get(0).getTime();
    return (int) (s + 0.5);
  }

  public String getFormattedTime() {
    return format1(getTotalSeconds() / 60.) + "m";
  }

  public String getFormattedTime2() {
    int seconds = (int) (getTotalSeconds() + 0.5);
    int hours = seconds / 3600;
    int minutes = (seconds - hours * 3600) / 60;
    seconds = seconds - hours * 3600 - minutes * 60;
    String time = "";
    if (hours != 0)
      time = "" + hours + "h ";
    if (minutes != 0)
      time = time + minutes + "m ";
    if (seconds != 0)
      time = time + seconds + "s";
    return time;
  }

  SimpleDateFormat TIMESTAMP_FORMAT;

  public String getFormattedTime3(float time) {
    if (TIMESTAMP_FORMAT == null) {
      TIMESTAMP_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
      TIMESTAMP_FORMAT.setTimeZone(TimeZone.getTimeZone("UTC"));
    }
    // yyyy-mm-ddThh:mm:ss.SSSZ
    Date d = new Date((long) (time * 1000f));
    return TIMESTAMP_FORMAT.format(d);
  }

  public String getFormattedEnergy() {
    return format1(energy / 3600000.) + "kwh";
  }

  private static String formatILon(int ilon) {
    return formatPos(ilon - 180000000);
  }

  private static String formatILat(int ilat) {
    return formatPos(ilat - 90000000);
  }

  private static String formatPos(int p) {
    boolean negative = p < 0;
    if (negative)
      p = -p;
    char[] ac = new char[12];
    int i = 11;
    while (p != 0 || i > 3) {
      ac[i--] = (char) ('0' + (p % 10));
      p /= 10;
      if (i == 5)
        ac[i--] = '.';
    }
    if (negative)
      ac[i--] = '-';
    return new String(ac, i + 1, 11 - i);
  }

  private String format1(double n) {
    String s = "" + (long) (n * 10 + 0.5);
    int len = s.length();
    return s.substring(0, len - 1) + "." + s.charAt(len - 1);
  }

  public void dumpMessages(String filename, RoutingContext rc) throws Exception {
    BufferedWriter bw = filename == null ? null : new BufferedWriter(new FileWriter(filename));
    writeMessages(bw, rc);
  }

  public void writeMessages(BufferedWriter bw, RoutingContext rc) throws Exception {
    dumpLine(bw, MESSAGES_HEADER);
    for (String m : aggregateMessages()) {
      dumpLine(bw, m);
    }
    if (bw != null)
      bw.close();
  }

  private void dumpLine(BufferedWriter bw, String s) throws Exception {
    if (bw == null) {
      System.out.println(s);
    } else {
      bw.write(s);
      bw.write("\n");
    }
  }

  public void readGpx(String filename) throws Exception {
    File f = new File(filename);
    if (!f.exists())
      return;
    BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f)));

    for (; ; ) {
      String line = br.readLine();
      if (line == null)
        break;

      int idx0 = line.indexOf("<trkpt lon=\"");
      if (idx0 >= 0) {
        idx0 += 12;
        int idx1 = line.indexOf('"', idx0);
        int ilon = (int) ((Double.parseDouble(line.substring(idx0, idx1)) + 180.) * 1000000. + 0.5);
        int idx2 = line.indexOf(" lat=\"");
        if (idx2 < 0)
          continue;
        idx2 += 6;
        int idx3 = line.indexOf('"', idx2);
        int ilat = (int) ((Double.parseDouble(line.substring(idx2, idx3)) + 90.) * 1000000. + 0.5);
        nodes.add(OsmPathElement.create(ilon, ilat, (short) 0, null, false));
      }
    }
    br.close();
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

    VoiceHintProcessor vproc = new VoiceHintProcessor(rc.turnInstructionCatchingRange, rc.turnInstructionRoundabouts);
    List<VoiceHint> results = vproc.process(inputs);

    double minDistance = getMinDistance();
    List<VoiceHint> resultsLast = vproc.postProcess(results, rc.turnInstructionCatchingRange, minDistance);
    for (VoiceHint hint : resultsLast) {
      voiceHints.list.add(hint);
    }

  }

  int getMinDistance() {
    if (voiceHints != null) {
      switch (voiceHints.getTransportMode()) {
        case "car":
          return 20;
        case "bike":
          return 5;
        case "foot":
          return 3;
        default:
          return 5;
      }
    }
    return 2;
  }

  private float getVoiceHintTime(int i) {
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
