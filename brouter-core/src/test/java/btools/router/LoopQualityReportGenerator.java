package btools.router;

import java.util.List;
import java.util.Locale;

/**
 * Generates a self-contained Leaflet HTML report from loop quality test results.
 * Embeds simplified route polylines directly in the HTML for interactive viewing.
 */
final class LoopQualityReportGenerator {

  private LoopQualityReportGenerator() {
  }

  static String generateHtml(List<LoopQualityTest.LoopQualityResult> results) {
    StringBuilder sb = new StringBuilder(1024 * 1024);

    sb.append("<!DOCTYPE html>\n<html>\n<head>\n");
    sb.append("<meta charset=\"utf-8\">\n");
    sb.append("<title>BRouter Loop Quality Report</title>\n");
    sb.append("<link rel=\"stylesheet\" href=\"https://unpkg.com/leaflet@1.9.4/dist/leaflet.css\"/>\n");
    sb.append("<script src=\"https://unpkg.com/leaflet@1.9.4/dist/leaflet.js\"></script>\n");
    sb.append("<style>\n");
    sb.append("* { margin: 0; padding: 0; box-sizing: border-box; }\n");
    sb.append("body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; display: flex; height: 100vh; }\n");
    sb.append("#sidebar { width: 440px; overflow-y: auto; background: #f8f9fa; border-right: 1px solid #dee2e6; padding: 12px; font-size: 13px; }\n");
    sb.append("#map { flex: 1; }\n");
    sb.append(".filters { margin-bottom: 12px; display: flex; flex-wrap: wrap; gap: 4px; }\n");
    sb.append(".filters select { padding: 4px; font-size: 12px; }\n");
    sb.append(".summary { margin-bottom: 12px; padding: 8px; background: #fff; border-radius: 4px; border: 1px solid #dee2e6; }\n");
    sb.append(".summary strong { font-size: 14px; }\n");
    sb.append("table { width: 100%; border-collapse: collapse; font-size: 12px; }\n");
    sb.append("th { background: #e9ecef; padding: 6px 4px; text-align: left; position: sticky; top: 0; cursor: pointer; }\n");
    sb.append("td { padding: 4px; border-bottom: 1px solid #dee2e6; }\n");
    sb.append("tr.pass { background: #d4edda; }\n");
    sb.append("tr.fail { background: #f8d7da; }\n");
    sb.append("tr.error { background: #fff3cd; }\n");
    sb.append("tr:hover { opacity: 0.8; cursor: pointer; }\n");
    sb.append("tr.selected { outline: 2px solid #007bff; }\n");
    sb.append(".metric-bad { color: #dc3545; font-weight: bold; }\n");
    sb.append(".legend { margin-bottom: 12px; font-size: 12px; }\n");
    sb.append(".legend span { display: inline-block; width: 14px; height: 14px; border-radius: 2px; vertical-align: middle; margin-right: 4px; }\n");
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
    sb.append(String.format("<strong>BRouter Loop Quality Report</strong><br>%d passed, %d failed, %d errors / %d total\n",
      passed, failed, errors, results.size()));
    sb.append("</div>\n");

    // Legend
    sb.append("<div class=\"legend\">\n");
    sb.append("<span style=\"background:#28a745\"></span> Pass &nbsp; ");
    sb.append("<span style=\"background:#dc3545\"></span> Fail &nbsp; ");
    sb.append("<span style=\"background:#ffc107\"></span> Error\n");
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

    // Embedded route data as JS — coordinates simplified to every Nth point for performance
    sb.append("<script>\n");
    sb.append("var routes = [\n");
    for (int i = 0; i < results.size(); i++) {
      LoopQualityTest.LoopQualityResult r = results.get(i);
      sb.append("  {label:\"").append(r.label).append(" [").append(r.variant).append("]\",");
      sb.append("variant:\"").append(r.variant).append("\",");
      sb.append("region:\"").append(r.region.name()).append("\",");
      sb.append("profile:\"").append(r.profileName).append("\",");
      sb.append("dist:").append(r.distanceMeters / 1000).append(",");
      sb.append(String.format(Locale.US, "center:[%.4f,%.4f],", r.region.lat, r.region.lon));
      String status = r.metrics == null ? "error" : (r.passed() ? "pass" : "fail");
      sb.append("status:\"").append(status).append("\",");
      if (r.metrics != null) {
        sb.append(String.format(Locale.US, "reuse:%.1f,distR:%.2f,dirD:%.0f,actualDist:%d,",
          r.metrics.getRoadReusePercent(), r.metrics.getDistanceRatio(),
          r.metrics.getDirectionDeltaDegrees(), r.metrics.getActualDistanceMeters()));
      }
      // Embed simplified coordinates (every Nth point to keep HTML manageable)
      sb.append("coords:[");
      if (r.coordinates != null && r.coordinates.length > 0) {
        int step = Math.max(1, r.coordinates.length / 200); // ~200 points per route max
        for (int j = 0; j < r.coordinates.length; j += step) {
          if (j > 0) sb.append(",");
          sb.append(String.format(Locale.US, "[%.5f,%.5f]", r.coordinates[j][1], r.coordinates[j][0])); // Leaflet: [lat, lon]
        }
        // Always include last point to close the loop
        if ((r.coordinates.length - 1) % step != 0) {
          sb.append(String.format(Locale.US, ",[%.5f,%.5f]",
            r.coordinates[r.coordinates.length - 1][1], r.coordinates[r.coordinates.length - 1][0]));
        }
      }
      sb.append("]},\n");
    }
    sb.append("];\n\n");

    // Map initialization
    sb.append("var map = L.map('map').setView([48.5, 8.5], 5);\n");
    sb.append("L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {\n");
    sb.append("  attribution: '&copy; OpenStreetMap contributors', maxZoom: 18\n");
    sb.append("}).addTo(map);\n\n");

    sb.append("var layers = [];\n");
    sb.append("var variantColors = {probe:'#0066cc', isochrone:'#e67300', greedy:'#22aa44'};\n");
    sb.append("var selectedIdx = -1;\n\n");

    sb.append("routes.forEach(function(r, i) {\n");
    sb.append("  var color = variantColors[r.variant] || '#888';\n");
    sb.append("  var layer = null;\n");
    sb.append("  if (r.coords.length > 1) {\n");
    sb.append("    layer = L.polyline(r.coords, {color: color, weight: 3, opacity: 0.6});\n");
    sb.append("    var popup = '<b>' + r.label + '</b><br>';\n");
    sb.append("    popup += '<hr style=\"margin:4px 0\">';\n");
    sb.append("    popup += '<b>Parameters:</b><br>';\n");
    sb.append("    popup += 'Region: ' + r.region + '<br>';\n");
    sb.append("    popup += 'Profile: ' + r.profile + '<br>';\n");
    sb.append("    popup += 'Requested: ' + r.dist + 'km<br>';\n");
    sb.append("    if (r.reuse !== undefined) {\n");
    sb.append("      popup += '<hr style=\"margin:4px 0\">';\n");
    sb.append("      popup += '<b>Results:</b><br>';\n");
    sb.append("      popup += 'Actual: ' + r.actualDist + 'm (' + r.distR + 'x)<br>';\n");
    sb.append("      popup += 'Road reuse: ' + r.reuse + '%<br>';\n");
    sb.append("      popup += 'Dir delta: ' + r.dirD + '&deg;<br>';\n");
    sb.append("      popup += 'Status: <b>' + r.status + '</b>';\n");
    sb.append("    }\n");
    sb.append("    layer.bindPopup(popup);\n");
    sb.append("    layer.on('click', function() { highlightRow(i); });\n");
    sb.append("  }\n");
    sb.append("  layers.push(layer);\n");
    sb.append("});\n\n");

    // Initially show nothing — user selects via filters or clicks
    sb.append("// Start with all routes hidden; filters reveal them\n");
    sb.append("applyFilters();\n\n");

    sb.append("function focusRoute(idx) {\n");
    sb.append("  highlightRow(idx);\n");
    sb.append("  var r = routes[idx];\n");
    sb.append("  if (layers[idx]) {\n");
    sb.append("    layers[idx].setStyle({weight: 5, opacity: 1.0});\n");
    sb.append("    map.fitBounds(layers[idx].getBounds().pad(0.1));\n");
    sb.append("    layers[idx].openPopup();\n");
    sb.append("  } else {\n");
    sb.append("    map.setView(r.center, 11);\n");
    sb.append("  }\n");
    sb.append("}\n\n");

    sb.append("function highlightRow(idx) {\n");
    sb.append("  // Reset previous\n");
    sb.append("  if (selectedIdx >= 0 && layers[selectedIdx]) layers[selectedIdx].setStyle({weight: 3, opacity: 0.6});\n");
    sb.append("  document.querySelectorAll('tr.selected').forEach(function(el) { el.classList.remove('selected'); });\n");
    sb.append("  selectedIdx = idx;\n");
    sb.append("  var row = document.querySelector('tr[data-idx=\"'+idx+'\"]');\n");
    sb.append("  if (row) { row.classList.add('selected'); row.scrollIntoView({block:'nearest'}); }\n");
    sb.append("}\n\n");

    sb.append("function applyFilters() {\n");
    sb.append("  var fR = document.getElementById('fRegion').value;\n");
    sb.append("  var fP = document.getElementById('fProfile').value;\n");
    sb.append("  var fD = document.getElementById('fDist').value;\n");
    sb.append("  var fS = document.getElementById('fStatus').value;\n");
    sb.append("  var rows = document.querySelectorAll('#results tbody tr');\n");
    sb.append("  var bounds = L.latLngBounds();\n");
    sb.append("  var anyVisible = false;\n");
    sb.append("  rows.forEach(function(row) {\n");
    sb.append("    var show = true;\n");
    sb.append("    if (fR && row.dataset.region !== fR) show = false;\n");
    sb.append("    if (fP && row.dataset.profile !== fP) show = false;\n");
    sb.append("    if (fD && row.dataset.dist !== fD) show = false;\n");
    sb.append("    if (fS && row.dataset.status !== fS) show = false;\n");
    sb.append("    row.style.display = show ? '' : 'none';\n");
    sb.append("    var idx = parseInt(row.dataset.idx);\n");
    sb.append("    if (layers[idx]) {\n");
    sb.append("      if (show) { layers[idx].addTo(map); bounds.extend(layers[idx].getBounds()); anyVisible = true; }\n");
    sb.append("      else { layers[idx].remove(); }\n");
    sb.append("    }\n");
    sb.append("  });\n");
    sb.append("  if (anyVisible) map.fitBounds(bounds.pad(0.1));\n");
    sb.append("}\n");

    sb.append("</script>\n</body>\n</html>\n");
    return sb.toString();
  }

  /**
   * Generate a per-region HTML report with full route geometry and variant comparison.
   * Probe routes shown in blue, isochrone routes in orange.
   */
  static String generateRegionHtml(LoopTestRegion region, List<LoopQualityTest.LoopQualityResult> results) {
    StringBuilder sb = new StringBuilder(512 * 1024);

    sb.append("<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n");
    sb.append("<title>BRouter Loops: ").append(region.name()).append("</title>\n");
    sb.append("<link rel=\"stylesheet\" href=\"https://unpkg.com/leaflet@1.9.4/dist/leaflet.css\"/>\n");
    sb.append("<script src=\"https://unpkg.com/leaflet@1.9.4/dist/leaflet.js\"></script>\n");
    sb.append("<style>\n");
    sb.append("* { margin: 0; padding: 0; box-sizing: border-box; }\n");
    sb.append("body { font-family: -apple-system, sans-serif; display: flex; height: 100vh; }\n");
    sb.append("#sidebar { width: 480px; overflow-y: auto; background: #f8f9fa; border-right: 1px solid #dee2e6; padding: 12px; font-size: 12px; }\n");
    sb.append("#map { flex: 1; }\n");
    sb.append(".filters { margin-bottom: 8px; display: flex; flex-wrap: wrap; gap: 4px; }\n");
    sb.append(".filters select, .filters label { padding: 3px; font-size: 12px; }\n");
    sb.append("table { width: 100%; border-collapse: collapse; }\n");
    sb.append("th { background: #e9ecef; padding: 4px; text-align: left; position: sticky; top: 0; }\n");
    sb.append("td { padding: 3px; border-bottom: 1px solid #dee2e6; }\n");
    sb.append("tr:hover { background: #e2e6ea; cursor: pointer; }\n");
    sb.append("tr.selected { outline: 2px solid #007bff; }\n");
    sb.append(".probe { color: #0066cc; } .isochrone { color: #e67300; } .greedy { color: #22aa44; }\n");
    sb.append(".metric-bad { color: #dc3545; font-weight: bold; }\n");
    sb.append("</style>\n</head>\n<body>\n");

    // Sidebar
    sb.append("<div id=\"sidebar\">\n");
    sb.append("<h3>").append(region.name()).append("</h3>\n");
    sb.append("<p>").append(String.format("%.3f, %.3f", region.lon, region.lat)).append("</p>\n");
    sb.append("<p style=\"margin:4px 0\"><span class=\"probe\">&#9632; Probe (blue)</span> &nbsp; ");
    sb.append("<span class=\"isochrone\">&#9632; Isochrone (orange)</span> &nbsp; ");
    sb.append("<span class=\"greedy\">&#9632; Greedy (green)</span></p>\n");

    // Filters
    sb.append("<div class=\"filters\">\n");
    sb.append("<select id=\"fProfile\" onchange=\"applyFilters()\"><option value=\"\">All profiles</option>");
    sb.append("<option>fastbike</option><option>gravel</option><option>mtb-zossebart</option></select>\n");
    sb.append("<select id=\"fDist\" onchange=\"applyFilters()\"><option value=\"\">All distances</option>");
    sb.append("<option>30</option><option>50</option><option>80</option><option>100</option></select>\n");
    sb.append("<select id=\"fVariant\" onchange=\"applyFilters()\"><option value=\"\">All variants</option>");
    sb.append("<option>probe</option><option>isochrone</option><option>greedy</option></select>\n");
    sb.append("</div>\n");

    // Table
    sb.append("<table id=\"results\"><thead><tr><th>Test</th><th>Var</th><th>Reuse</th><th>DistR</th><th>Dir</th><th>Dist</th></tr></thead><tbody>\n");
    for (int i = 0; i < results.size(); i++) {
      LoopQualityTest.LoopQualityResult r = results.get(i);
      String vc = r.variant != null ? r.variant : "probe";
      sb.append(String.format("<tr class=\"%s\" data-idx=\"%d\" data-profile=\"%s\" data-dist=\"%d\" data-variant=\"%s\" onclick=\"focusRoute(%d)\">",
        vc, i, r.profileName, r.distanceMeters / 1000, r.variant, i));
      sb.append(String.format("<td title=\"%s\">%s</td>", r.label, abbreviateLabel(r.label)));
      sb.append(String.format("<td class=\"%s\">%s</td>", vc, r.variant.substring(0, 1).toUpperCase()));
      if (r.metrics != null) {
        sb.append(String.format(Locale.US, "<td>%.1f</td>", r.metrics.getRoadReusePercent()));
        sb.append(String.format(Locale.US, "<td>%.2f</td>", r.metrics.getDistanceRatio()));
        sb.append(String.format(Locale.US, "<td>%.0f</td>", r.metrics.getDirectionDeltaDegrees()));
        sb.append(String.format(Locale.US, "<td>%d</td>", r.metrics.getActualDistanceMeters()));
      } else {
        sb.append("<td colspan=\"4\">").append(r.error != null ? "err" : "skip").append("</td>");
      }
      sb.append("</tr>\n");
    }
    sb.append("</tbody></table></div>\n");

    // Map
    sb.append("<div id=\"map\"></div>\n<script>\n");

    // Embed routes with FULL coordinates
    sb.append("var routes = [\n");
    for (int i = 0; i < results.size(); i++) {
      LoopQualityTest.LoopQualityResult r = results.get(i);
      sb.append("{label:\"").append(r.label).append(" [").append(r.variant).append("]\",");
      sb.append("variant:\"").append(r.variant).append("\",");
      sb.append("profile:\"").append(r.profileName).append("\",");
      sb.append("dist:").append(r.distanceMeters / 1000).append(",");
      if (r.metrics != null) {
        sb.append(String.format(Locale.US, "reuse:%.1f,distR:%.2f,dirD:%.0f,actualDist:%d,",
          r.metrics.getRoadReusePercent(), r.metrics.getDistanceRatio(),
          r.metrics.getDirectionDeltaDegrees(), r.metrics.getActualDistanceMeters()));
      }
      sb.append("coords:[");
      if (r.coordinates != null) {
        for (int j = 0; j < r.coordinates.length; j++) {
          if (j > 0) sb.append(",");
          sb.append(String.format(Locale.US, "[%.5f,%.5f]", r.coordinates[j][1], r.coordinates[j][0]));
        }
      }
      sb.append("]},\n");
    }
    sb.append("];\n\n");

    sb.append(String.format(Locale.US, "var map = L.map('map').setView([%.4f, %.4f], 11);\n", region.lat, region.lon));
    sb.append("L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{attribution:'&copy; OSM',maxZoom:18}).addTo(map);\n");
    sb.append("var layers=[], selectedIdx=-1;\n");
    sb.append("var variantColors = {probe:'#0066cc', isochrone:'#e67300', greedy:'#22aa44'};\n\n");

    sb.append("routes.forEach(function(r,i){\n");
    sb.append("  var layer=null;\n");
    sb.append("  if(r.coords.length>1){\n");
    sb.append("    layer=L.polyline(r.coords,{color:variantColors[r.variant],weight:3,opacity:0.7});\n");
    sb.append("    var p='<b>'+r.label+'</b><hr>';\n");
    sb.append("    p+='Variant: <b>'+r.variant+'</b><br>';\n");
    sb.append("    p+='Profile: '+r.profile+'<br>';\n");
    sb.append("    p+='Requested: '+r.dist+'km<br>';\n");
    sb.append("    if(r.reuse!==undefined){p+='<hr>Actual: '+r.actualDist+'m ('+r.distR+'x)<br>Reuse: '+r.reuse+'%<br>Dir delta: '+r.dirD+'&deg;';}\n");
    sb.append("    layer.bindPopup(p);\n");
    sb.append("    layer.on('click',function(){highlightRow(i);});\n");
    sb.append("  }\n  layers.push(layer);\n});\n\n");

    sb.append("function focusRoute(i){highlightRow(i);if(layers[i]){layers[i].setStyle({weight:5,opacity:1});map.fitBounds(layers[i].getBounds().pad(0.1));layers[i].openPopup();}}\n");
    sb.append("function highlightRow(i){if(selectedIdx>=0&&layers[selectedIdx])layers[selectedIdx].setStyle({weight:3,opacity:0.7});document.querySelectorAll('tr.selected').forEach(function(e){e.classList.remove('selected');});selectedIdx=i;var row=document.querySelector('tr[data-idx=\"'+i+'\"]');if(row){row.classList.add('selected');row.scrollIntoView({block:'nearest'});}}\n\n");

    sb.append("function applyFilters(){\n");
    sb.append("  var fP=document.getElementById('fProfile').value,fD=document.getElementById('fDist').value,fV=document.getElementById('fVariant').value;\n");
    sb.append("  var bounds=L.latLngBounds(),any=false;\n");
    sb.append("  document.querySelectorAll('#results tbody tr').forEach(function(row){\n");
    sb.append("    var show=true;\n");
    sb.append("    if(fP&&row.dataset.profile!==fP)show=false;\n");
    sb.append("    if(fD&&row.dataset.dist!==fD)show=false;\n");
    sb.append("    if(fV&&row.dataset.variant!==fV)show=false;\n");
    sb.append("    row.style.display=show?'':'none';\n");
    sb.append("    var idx=parseInt(row.dataset.idx);\n");
    sb.append("    if(layers[idx]){if(show){layers[idx].addTo(map);bounds.extend(layers[idx].getBounds());any=true;}else{layers[idx].remove();}}\n");
    sb.append("  });\n  if(any)map.fitBounds(bounds.pad(0.1));\n}\n");
    sb.append("applyFilters();\n");

    sb.append("</script>\n</body>\n</html>\n");
    return sb.toString();
  }

  private static String abbreviateLabel(String label) {
    if (label.length() <= 22) return label;
    return label.substring(0, 20) + "..";
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
