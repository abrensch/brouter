package btools.router;

import java.io.BufferedWriter;
import java.io.StringWriter;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

import btools.mapaccess.MatchedWaypoint;
import btools.util.StringUtils;

public class FormatJson extends Formatter {

  public FormatJson(RoutingContext rc) {
    super(rc);
  }

  @Override
  public String format(OsmTrack t) {
    int turnInstructionMode = t.voiceHints != null ? t.voiceHints.turnInstructionMode : 0;

    StringBuilder sb = new StringBuilder(8192);

    sb.append("{\n");
    sb.append("  \"type\": \"FeatureCollection\",\n");
    sb.append("  \"features\": [\n");
    sb.append("    {\n");
    sb.append("      \"type\": \"Feature\",\n");
    sb.append("      \"properties\": {\n");
    sb.append("        \"creator\": \"BRouter-" + t.version + "\",\n");
    sb.append("        \"name\": \"").append(t.name).append("\",\n");
    sb.append("        \"track-length\": \"").append(t.distance).append("\",\n");
    sb.append("        \"filtered ascend\": \"").append(t.ascend).append("\",\n");
    sb.append("        \"plain-ascend\": \"").append(t.plainAscend).append("\",\n");
    sb.append("        \"total-time\": \"").append(t.getTotalSeconds()).append("\",\n");
    sb.append("        \"total-energy\": \"").append(t.energy).append("\",\n");
    sb.append("        \"cost\": \"").append(t.cost).append("\",\n");
    if (t.voiceHints != null && !t.voiceHints.list.isEmpty()) {
      sb.append("        \"voicehints\": [\n");
      for (VoiceHint hint : t.voiceHints.list) {
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
    if (t.showSpeedProfile) { // set in profile
      List<String> sp = t.aggregateSpeedProfile();
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
      for (String m : t.aggregateMessages()) {
        sb.append("          [\"").append(m.replaceAll("\t", "\", \"")).append("\"],\n");
      }
      sb.deleteCharAt(sb.lastIndexOf(","));
      sb.append("        ],\n");
    }

    if (t.getTotalSeconds() > 0) {
      sb.append("        \"times\": [");
      DecimalFormat decimalFormat = (DecimalFormat) NumberFormat.getInstance(Locale.ENGLISH);
      decimalFormat.applyPattern("0.###");
      for (OsmPathElement n : t.nodes) {
        sb.append(decimalFormat.format(n.getTime())).append(",");
      }
      sb.deleteCharAt(sb.lastIndexOf(","));
      sb.append("]\n");
    } else {
      sb.deleteCharAt(sb.lastIndexOf(","));
    }

    sb.append("      },\n");

    if (t.iternity != null) {
      sb.append("      \"iternity\": [\n");
      for (String s : t.iternity) {
        sb.append("        \"").append(s).append("\",\n");
      }
      sb.deleteCharAt(sb.lastIndexOf(","));
      sb.append("        ],\n");
    }
    sb.append("      \"geometry\": {\n");
    sb.append("        \"type\": \"LineString\",\n");
    sb.append("        \"coordinates\": [\n");

    OsmPathElement nn = null;
    for (OsmPathElement n : t.nodes) {
      String sele = n.getSElev() == Short.MIN_VALUE ? "" : ", " + n.getElev();
      if (t.showspeed) { // hack: show speed instead of elevation
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
    if (t.exportWaypoints || !t.pois.isEmpty()) {
      sb.append("    },\n");
      for (int i = 0; i <= t.pois.size() - 1; i++) {
        OsmNodeNamed poi = t.pois.get(i);
        addFeature(sb, "poi", poi.name, poi.ilat, poi.ilon);
        if (i < t.matchedWaypoints.size() - 1) {
          sb.append(",");
        }
        sb.append("    \n");
      }
      if (t.exportWaypoints) {
        for (int i = 0; i <= t.matchedWaypoints.size() - 1; i++) {
          String type;
          if (i == 0) {
            type = "from";
          } else if (i == t.matchedWaypoints.size() - 1) {
            type = "to";
          } else {
            type = "via";
          }

          MatchedWaypoint wp = t.matchedWaypoints.get(i);
          addFeature(sb, type, wp.name, wp.waypoint.ilat, wp.waypoint.ilon);
          if (i < t.matchedWaypoints.size() - 1) {
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

  public String formatAsWaypoint(OsmNodeNamed n) {
    try {
      StringWriter sw = new StringWriter(8192);
      BufferedWriter bw = new BufferedWriter(sw);
      addJsonHeader(bw);
      addJsonFeature(bw, "info", "wpinfo", n.ilon, n.ilat, n.getElev(), (n.nodeDescription != null ? rc.expctxWay.getKeyValueDescription(false, n.nodeDescription) : null));
      addJsonFooter(bw);
      bw.close();
      sw.close();
      return sw.toString();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private void addJsonFeature(BufferedWriter sb, String type, String name, int ilon, int ilat, double elev, String desc) {
    try {
      sb.append("    {\n");
      sb.append("      \"type\": \"Feature\",\n");
      sb.append("      \"properties\": {\n");
      sb.append("        \"creator\": \"BRouter-" + OsmTrack.version + "\",\n");
      sb.append("        \"name\": \"" + StringUtils.escapeJson(name) + "\",\n");
      sb.append("        \"type\": \"" + type + "\"");
      if (desc != null) {
        sb.append(",\n        \"message\": \"" + desc + "\"\n");
      } else {
        sb.append("\n");
      }
      sb.append("      },\n");
      sb.append("      \"geometry\": {\n");
      sb.append("        \"type\": \"Point\",\n");
      sb.append("        \"coordinates\": [\n");
      sb.append("          " + formatILon(ilon) + ",\n");
      sb.append("          " + formatILat(ilat) + ",\n");
      sb.append("          " + elev + "\n");
      sb.append("        ]\n");
      sb.append("      }\n");
      sb.append("    }\n");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static void addJsonHeader(BufferedWriter sb) {
    try {
      sb.append("{\n");
      sb.append("  \"type\": \"FeatureCollection\",\n");
      sb.append("  \"features\": [\n");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static void addJsonFooter(BufferedWriter sb) {
    try {
      sb.append("  ]\n");
      sb.append("}\n");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

}
