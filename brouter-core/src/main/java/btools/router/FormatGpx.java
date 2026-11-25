package btools.router;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.util.Map;

import btools.util.StringUtils;

public class FormatGpx extends Formatter {
  public FormatGpx(RoutingContext rc) {
    super(rc);
  }

  @Override
  public String format(OsmTrack t) {
    try {
      StringWriter sw = new StringWriter(8192);
      BufferedWriter bw = new BufferedWriter(sw);
      formatAsGpx(bw, t);
      bw.close();
      return sw.toString();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public String formatAsGpx(BufferedWriter sb, OsmTrack t) throws IOException {
    int turnInstructionMode = t.voiceHints != null ? t.voiceHints.turnInstructionMode : 0;

    sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    if (turnInstructionMode != 9) {
      for (int i = t.messageList.size() - 1; i >= 0; i--) {
        String message = t.messageList.get(i);
        if (i < t.messageList.size() - 1)
          message = "(alt-index " + i + ": " + message + " )";
        if (message != null)
          sb.append("<!-- ").append(message).append(" -->\n");
      }
    }

    if (turnInstructionMode == 4) { // comment style
      sb.append("<!-- $transport-mode$").append(t.voiceHints.getTransportMode()).append("$ -->\n");
      sb.append("<!--          cmd    idx        lon        lat d2next  geometry -->\n");
      sb.append("<!-- $turn-instruction-start$\n");
      for (VoiceHint hint : t.voiceHints.list) {
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
      sb.append(" creator=\"BRouter-" + t.version + "\" version=\"1.1\">\n");
    }
    if (turnInstructionMode == 9) {
      sb.append(" <metadata>\n");
      sb.append("  <name>").append(t.name).append("</name>\n");
      sb.append("  <extensions>\n");
      sb.append("   <brouter:info>").append(t.messageList.get(0)).append("</brouter:info>\n");
      if (t.params != null && t.params.size() > 0) {
        sb.append("   <brouter:params><![CDATA[");
        int i = 0;
        for (Map.Entry<String, String> e : t.params.entrySet()) {
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

      float rteTime = t.getVoiceHintTime(0);
      StringBuffer first = new StringBuffer();
      // define start point
      {
        first.append("  <rtept lat=\"").append(formatILat(t.nodes.get(0).getILat())).append("\" lon=\"")
          .append(formatILon(t.nodes.get(0).getILon())).append("\">\n")
          .append("   <desc>start</desc>\n   <extensions>\n");
        if (rteTime != lastRteTime) { // add timing only if available
          double ti = rteTime - lastRteTime;
          first.append("    <time>").append("" + (int) (ti + 0.5)).append("</time>\n");
          lastRteTime = rteTime;
        }
        first.append("    <offset>0</offset>\n  </extensions>\n </rtept>\n");
      }
      sb.append(first.toString());

      for (int i = 0; i < t.voiceHints.list.size(); i++) {
        VoiceHint hint = t.voiceHints.list.get(i);
        sb.append("  <rtept lat=\"").append(formatILat(hint.ilat)).append("\" lon=\"")
          .append(formatILon(hint.ilon)).append("\">\n")
          .append("   <desc>")
          .append(turnInstructionMode == 3 ? hint.getMessageString() : hint.getCruiserMessageString())
          .append("</desc>\n   <extensions>\n");

        rteTime = t.getVoiceHintTime(i + 1);

        if (rteTime != lastRteTime) { // add timing only if available
          double ti = rteTime - lastRteTime;
          sb.append("    <time>").append("" + (int) (ti + 0.5)).append("</time>\n");
          lastRteTime = rteTime;
        }
        sb.append("    <turn>")
          .append(turnInstructionMode == 3 ? hint.getCommandString() : hint.getCruiserCommandString())
          .append("</turn>\n    <turn-angle>").append("" + (int) hint.angle)
          .append("</turn-angle>\n    <offset>").append("" + hint.indexInTrack).append("</offset>\n  </extensions>\n </rtept>\n");
      }
      sb.append("  <rtept lat=\"").append(formatILat(t.nodes.get(t.nodes.size() - 1).getILat())).append("\" lon=\"")
        .append(formatILon(t.nodes.get(t.nodes.size() - 1).getILon())).append("\">\n")
        .append("   <desc>destination</desc>\n   <extensions>\n");
      sb.append("    <time>0</time>\n");
      sb.append("    <offset>").append("" + (t.nodes.size() - 1)).append("</offset>\n  </extensions>\n </rtept>\n");

      sb.append("</rte>\n");
    }

    if (turnInstructionMode == 7) { // old locus style
      float lastRteTime = t.getVoiceHintTime(0);

      for (int i = 0; i < t.voiceHints.list.size(); i++) {
        VoiceHint hint = t.voiceHints.list.get(i);
        sb.append(" <wpt lon=\"").append(formatILon(hint.ilon)).append("\" lat=\"")
          .append(formatILat(hint.ilat)).append("\">")
          .append(hint.selev == Short.MIN_VALUE ? "" : "<ele>" + (hint.selev / 4.) + "</ele>")
          .append("<name>").append(hint.getMessageString()).append("</name>")
          .append("<extensions><locus:rteDistance>").append("" + hint.distanceToNext).append("</locus:rteDistance>");
        float rteTime = t.getVoiceHintTime(i + 1);
        if (rteTime != lastRteTime) { // add timing only if available
          double ti = rteTime - lastRteTime;
          double speed = hint.distanceToNext / ti;
          sb.append("<locus:rteTime>").append("" + ti).append("</locus:rteTime>")
            .append("<locus:rteSpeed>").append("" + speed).append("</locus:rteSpeed>");
          lastRteTime = rteTime;
        }
        sb.append("<locus:rtePointAction>").append("" + hint.getLocusAction()).append("</locus:rtePointAction></extensions>")
          .append("</wpt>\n");
      }
    }
    if (turnInstructionMode == 5) { // gpsies style
      for (VoiceHint hint : t.voiceHints.list) {
        sb.append(" <wpt lon=\"").append(formatILon(hint.ilon)).append("\" lat=\"")
          .append(formatILat(hint.ilat)).append("\">")
          .append("<name>").append(hint.getMessageString()).append("</name>")
          .append("<sym>").append(hint.getSymbolString().toLowerCase()).append("</sym>")
          .append("<type>").append(hint.getSymbolString()).append("</type>")
          .append("</wpt>\n");
      }
    }

    if (turnInstructionMode == 6) { // orux style
      for (VoiceHint hint : t.voiceHints.list) {
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

    for (int i = 0; i <= t.pois.size() - 1; i++) {
      GraphNodeNamed poi = t.pois.get(i);
      sb.append(" <wpt lon=\"").append(formatILon(poi.iLon)).append("\" lat=\"")
        .append(formatILat(poi.iLat)).append("\">\n")
        .append("  <name>").append(StringUtils.escapeXml10(poi.name)).append("</name>\n")
        .append(" </wpt>\n");
    }

    sb.append(" <trk>\n");
    if (turnInstructionMode == 9
      || turnInstructionMode == 2
      || turnInstructionMode == 8
      || turnInstructionMode == 4) { // Locus, comment, cruise, brouter style
      sb.append("  <src>").append(t.name).append("</src>\n");
      sb.append("  <type>").append(t.voiceHints.getTransportMode()).append("</type>\n");
    } else {
      sb.append("  <name>").append(t.name).append("</name>\n");
    }

    if (turnInstructionMode == 7) {
      sb.append("  <extensions>\n");
      sb.append("   <locus:rteComputeType>").append("" + t.voiceHints.getLocusRouteType()).append("</locus:rteComputeType>\n");
      sb.append("   <locus:rteSimpleRoundabouts>1</locus:rteSimpleRoundabouts>\n");
      sb.append("  </extensions>\n");
    }


    // all points
    sb.append("  <trkseg>\n");
    String lastway = "";
    OsmPathElement nn = null;
    String aSpeed;

    for (int idx = 0; idx < t.nodes.size(); idx++) {
      OsmPathElement n = t.nodes.get(idx);
      String sele = n.getSElev() == Short.MIN_VALUE ? "" : "<ele>" + n.getElev() + "</ele>";
      VoiceHint hint = t.getVoiceHint(idx);

      if (t.showTime) {
        sele += "<time>" + getFormattedTime3(n.getTime()) + "</time>";
      }
      boolean bNeedHeader = false;
      if (turnInstructionMode == 9) { // trkpt/sym style

        if (hint != null) {

          sele += "<desc>" + hint.getCruiserMessageString() + "</desc>";
          sele += "<sym>" + hint.getCommandString(hint.cmd) + "</sym>";
          sele += "<extensions>";
          if (t.showspeed) {
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
          sele += "<desc>start</desc>";
          sele += "<type>Via</type>";

        } else if (idx == t.nodes.size() - 1 && hint == null) {

          sele += "<desc>end</desc>";
          sele += "<type>Via</type>";

        }


        if (hint == null) {
          bNeedHeader = (t.showspeed || (n.message != null && n.message.wayKeyValues != null && !n.message.wayKeyValues.equals(lastway))) ||
            (n.message != null && n.message.nodeKeyValues != null);
          if (bNeedHeader) {
            sele += "<extensions>";
            if (t.showspeed) {
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
          sele += "<sym>" + hint.getLocusSymbolString() + "</sym>";
        } else {
          if (idx == 0 || idx == t.nodes.size() - 1) {
            int pos = sele.indexOf("<sym");
            if (pos != -1) {
              sele = sele.substring(0, pos);
            }
            sele += "<sym>pass_place</sym>";
            sele += "<type>Via</type>";
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
}
