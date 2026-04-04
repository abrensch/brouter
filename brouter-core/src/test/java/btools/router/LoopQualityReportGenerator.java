package btools.router;

import java.util.List;
import java.util.Locale;

/**
 * Generates a self-contained Leaflet HTML report from loop quality test results.
 */
final class LoopQualityReportGenerator {

  private LoopQualityReportGenerator() {
  }

  static String generateHtml(List<LoopQualityTest.LoopQualityResult> results) {
    StringBuilder sb = new StringBuilder(32768);

    sb.append("<!DOCTYPE html>\n<html>\n<head>\n");
    sb.append("<meta charset=\"utf-8\">\n");
    sb.append("<title>BRouter Loop Quality Report</title>\n");
    sb.append("<link rel=\"stylesheet\" href=\"https://unpkg.com/leaflet@1.9.4/dist/leaflet.css\"/>\n");
    sb.append("<script src=\"https://unpkg.com/leaflet@1.9.4/dist/leaflet.js\"></script>\n");
    sb.append("<style>\n");
    sb.append("* { margin: 0; padding: 0; box-sizing: border-box; }\n");
    sb.append("body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; display: flex; height: 100vh; }\n");
    sb.append("#sidebar { width: 420px; overflow-y: auto; background: #f8f9fa; border-right: 1px solid #dee2e6; padding: 12px; font-size: 13px; }\n");
    sb.append("#map { flex: 1; }\n");
    sb.append(".filters { margin-bottom: 12px; }\n");
    sb.append(".filters select { margin-right: 8px; padding: 4px; font-size: 12px; }\n");
    sb.append(".summary { margin-bottom: 12px; padding: 8px; background: #fff; border-radius: 4px; border: 1px solid #dee2e6; }\n");
    sb.append(".summary strong { font-size: 14px; }\n");
    sb.append("table { width: 100%; border-collapse: collapse; font-size: 12px; }\n");
    sb.append("th { background: #e9ecef; padding: 6px 4px; text-align: left; position: sticky; top: 0; }\n");
    sb.append("td { padding: 4px; border-bottom: 1px solid #dee2e6; }\n");
    sb.append("tr.pass { background: #d4edda; }\n");
    sb.append("tr.fail { background: #f8d7da; }\n");
    sb.append("tr.error { background: #fff3cd; }\n");
    sb.append("tr:hover { opacity: 0.8; cursor: pointer; }\n");
    sb.append(".metric-bad { color: #dc3545; font-weight: bold; }\n");
    sb.append("</style>\n");
    sb.append("</head>\n<body>\n");

    // Sidebar
    sb.append("<div id=\"sidebar\">\n");
    sb.append("<div class=\"summary\">\n");
    int passed = 0, failed = 0, errors = 0;
    for (LoopQualityTest.LoopQualityResult r : results) {
      if (r.metrics == null) errors++;
      else if (r.passed()) passed++;
      else failed++;
    }
    sb.append(String.format("<strong>Loop Quality Report</strong><br>%d passed, %d failed, %d errors / %d total\n",
      passed, failed, errors, results.size()));
    sb.append("</div>\n");

    // Filters
    sb.append("<div class=\"filters\">\n");
    sb.append("<select id=\"fRegion\" onchange=\"applyFilters()\"><option value=\"\">All regions</option>");
    for (LoopTestRegion r : LoopTestRegion.values()) {
      sb.append("<option>").append(r.name()).append("</option>");
    }
    sb.append("</select>\n");
    sb.append("<select id=\"fProfile\" onchange=\"applyFilters()\"><option value=\"\">All profiles</option>");
    sb.append("<option>fastbike</option><option>gravel</option><option>mtb-zossebart</option>");
    sb.append("</select>\n");
    sb.append("<select id=\"fDist\" onchange=\"applyFilters()\"><option value=\"\">All distances</option>");
    sb.append("<option>30</option><option>50</option><option>80</option><option>100</option>");
    sb.append("</select>\n");
    sb.append("<select id=\"fStatus\" onchange=\"applyFilters()\"><option value=\"\">All status</option>");
    sb.append("<option>pass</option><option>fail</option><option>error</option>");
    sb.append("</select>\n");
    sb.append("</div>\n");

    // Results table
    sb.append("<table id=\"results\">\n");
    sb.append("<thead><tr><th>Test</th><th>Reuse%</th><th>DistR</th><th>DirD</th><th>Dist(m)</th></tr></thead>\n");
    sb.append("<tbody>\n");
    for (int i = 0; i < results.size(); i++) {
      LoopQualityTest.LoopQualityResult r = results.get(i);
      String status = r.metrics == null ? "error" : (r.passed() ? "pass" : "fail");
      sb.append(String.format("<tr class=\"%s\" data-idx=\"%d\" data-region=\"%s\" data-profile=\"%s\" data-dist=\"%d\" data-status=\"%s\" onclick=\"focusRoute(%d)\">",
        status, i, r.region.name(), r.profileName, r.distanceMeters / 1000, status, i));
      sb.append(String.format("<td title=\"%s\">%s</td>", r.label, abbreviateLabel(r.label)));
      if (r.metrics != null) {
        sb.append(formatMetricCell(r.metrics.getRoadReusePercent(), r.region.maxReusePercent, "%.1f"));
        sb.append(formatRatioCell(r.metrics.getDistanceRatio(), r.region.minDistanceRatio, r.region.maxDistanceRatio));
        sb.append(formatMetricCell(r.metrics.getDirectionDeltaDegrees(), r.region.maxDirectionDelta, "%.0f"));
        sb.append(String.format(Locale.US, "<td>%d</td>", r.metrics.getActualDistanceMeters()));
      } else {
        sb.append("<td colspan=\"4\" title=\"").append(escapeHtml(r.error != null ? r.error : "")).append("\">error</td>");
      }
      sb.append("</tr>\n");
    }
    sb.append("</tbody></table>\n</div>\n");

    // Map
    sb.append("<div id=\"map\"></div>\n");

    // Embedded route data as JS
    sb.append("<script>\n");
    sb.append("var routes = [\n");
    for (int i = 0; i < results.size(); i++) {
      LoopQualityTest.LoopQualityResult r = results.get(i);
      sb.append("  {label:\"").append(r.label).append("\",");
      sb.append("region:\"").append(r.region.name()).append("\",");
      sb.append(String.format(Locale.US, "center:[%.4f,%.4f],", r.region.lat, r.region.lon));
      String status = r.metrics == null ? "error" : (r.passed() ? "pass" : "fail");
      sb.append("status:\"").append(status).append("\",");
      if (r.metrics != null) {
        sb.append(String.format(Locale.US, "reuse:%.1f,distR:%.2f,dirD:%.0f,dist:%d,",
          r.metrics.getRoadReusePercent(), r.metrics.getDistanceRatio(),
          r.metrics.getDirectionDeltaDegrees(), r.metrics.getActualDistanceMeters()));
      }
      // Coordinates from the track (if we have them embedded in the result)
      // For now, coordinates come from the golden GeoJSON files — the map shows markers only
      sb.append("coords:[]},\n");
    }
    sb.append("];\n\n");

    // Map initialization
    sb.append("var map = L.map('map').setView([48.5, 8.5], 5);\n");
    sb.append("L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {\n");
    sb.append("  attribution: '&copy; OpenStreetMap contributors', maxZoom: 18\n");
    sb.append("}).addTo(map);\n\n");

    sb.append("var layers = [];\n");
    sb.append("var markers = [];\n");
    sb.append("var colors = {pass:'#28a745', fail:'#dc3545', error:'#ffc107'};\n\n");

    sb.append("routes.forEach(function(r, i) {\n");
    sb.append("  var color = colors[r.status];\n");
    sb.append("  var m = L.circleMarker(r.center, {radius: 6, color: color, fillColor: color, fillOpacity: 0.7});\n");
    sb.append("  var popup = '<b>' + r.label + '</b><br>';\n");
    sb.append("  if (r.reuse !== undefined) popup += 'Reuse: ' + r.reuse + '%<br>Dist ratio: ' + r.distR + '<br>Dir delta: ' + r.dirD + '&deg;<br>Distance: ' + r.dist + 'm';\n");
    sb.append("  else popup += 'Error';\n");
    sb.append("  m.bindPopup(popup);\n");
    sb.append("  m.addTo(map);\n");
    sb.append("  markers.push(m);\n");
    sb.append("  if (r.coords.length > 0) {\n");
    sb.append("    var line = L.polyline(r.coords, {color: color, weight: 3, opacity: 0.8});\n");
    sb.append("    line.addTo(map);\n");
    sb.append("    layers.push(line);\n");
    sb.append("  } else {\n");
    sb.append("    layers.push(null);\n");
    sb.append("  }\n");
    sb.append("});\n\n");

    sb.append("function focusRoute(idx) {\n");
    sb.append("  var r = routes[idx];\n");
    sb.append("  if (layers[idx]) map.fitBounds(layers[idx].getBounds().pad(0.1));\n");
    sb.append("  else map.setView(r.center, 11);\n");
    sb.append("  markers[idx].openPopup();\n");
    sb.append("}\n\n");

    sb.append("function applyFilters() {\n");
    sb.append("  var fR = document.getElementById('fRegion').value;\n");
    sb.append("  var fP = document.getElementById('fProfile').value;\n");
    sb.append("  var fD = document.getElementById('fDist').value;\n");
    sb.append("  var fS = document.getElementById('fStatus').value;\n");
    sb.append("  var rows = document.querySelectorAll('#results tbody tr');\n");
    sb.append("  rows.forEach(function(row) {\n");
    sb.append("    var show = true;\n");
    sb.append("    if (fR && row.dataset.region !== fR) show = false;\n");
    sb.append("    if (fP && row.dataset.profile !== fP) show = false;\n");
    sb.append("    if (fD && row.dataset.dist !== fD) show = false;\n");
    sb.append("    if (fS && row.dataset.status !== fS) show = false;\n");
    sb.append("    row.style.display = show ? '' : 'none';\n");
    sb.append("    var idx = parseInt(row.dataset.idx);\n");
    sb.append("    if (markers[idx]) { show ? markers[idx].addTo(map) : markers[idx].remove(); }\n");
    sb.append("    if (layers[idx]) { show ? layers[idx].addTo(map) : layers[idx].remove(); }\n");
    sb.append("  });\n");
    sb.append("}\n");

    sb.append("</script>\n</body>\n</html>\n");
    return sb.toString();
  }

  private static String abbreviateLabel(String label) {
    // dreieich_30km_fastbike_N -> drei..30k_fb_N
    if (label.length() <= 20) return label;
    return label.substring(0, 18) + "..";
  }

  private static String formatMetricCell(double value, double threshold, String fmt) {
    boolean bad = value > threshold;
    return String.format(Locale.US, "<td%s>" + fmt + "</td>",
      bad ? " class=\"metric-bad\"" : "", value);
  }

  private static String formatRatioCell(double value, double min, double max) {
    boolean bad = value < min || value > max;
    return String.format(Locale.US, "<td%s>%.2f</td>",
      bad ? " class=\"metric-bad\"" : "", value);
  }

  private static String escapeHtml(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
  }
}
