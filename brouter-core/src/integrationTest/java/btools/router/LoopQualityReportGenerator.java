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

  static String generateHtml(List<LoopQualityResult> results) {
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
    sb.append(".legend { margin-bottom: 8px; font-size: 12px; }\n");
    sb.append(".legend span { display: inline-block; width: 14px; height: 14px; border-radius: 2px; vertical-align: middle; margin-right: 4px; }\n");
    sb.append(".variant-badge { display: inline-block; width: 16px; height: 16px; border-radius: 3px; color: #fff; text-align: center; font-weight: bold; font-size: 10px; line-height: 16px; }\n");
    sb.append(".v-probe { background: #0066cc; } .v-isochrone { background: #e67300; } .v-greedy { background: #22aa44; } .v-iso_greedy { background: #aa22cc; } .v-auto { background: #000; }\n");
    sb.append(".filters label { display: inline-flex; align-items: center; gap: 3px; padding: 2px 6px; font-size: 12px; background: #fff; border: 1px solid #ced4da; border-radius: 3px; cursor: pointer; }\n");
    sb.append("</style>\n");
    sb.append("</head>\n<body>\n");

    // Sidebar
    sb.append("<div id=\"sidebar\">\n");
    sb.append("<div class=\"summary\">\n");
    int passed = 0, failed = 0, errors = 0;
    for (LoopQualityResult r : results) {
      if (r.metrics == null) errors++;
      else if (r.passed()) passed++;
      else failed++;
    }
    sb.append(String.format("<strong>BRouter Loop Quality Report</strong><br>%d passed, %d failed, %d errors / %d total\n",
      passed, failed, errors, results.size()));
    sb.append("</div>\n");

    // Legend (status + variant colors)
    sb.append("<div class=\"legend\">\n");
    sb.append("<span style=\"background:#28a745\"></span> Pass &nbsp; ");
    sb.append("<span style=\"background:#dc3545\"></span> Fail &nbsp; ");
    sb.append("<span style=\"background:#ffc107\"></span> Error\n");
    sb.append("</div>\n");
    sb.append("<div class=\"legend\">\n");
    sb.append("<span style=\"background:#0066cc\"></span> Probe &nbsp; ");
    sb.append("<span style=\"background:#e67300\"></span> Isochrone &nbsp; ");
    sb.append("<span style=\"background:#22aa44\"></span> Greedy &nbsp; ");
    sb.append("<span style=\"background:#aa22cc\"></span> Iso-Greedy &nbsp; ");
    sb.append("<span style=\"background:#000\"></span> <b>AUTO (ships)</b>\n");
    sb.append("</div>\n");

    // Filters
    sb.append("<div class=\"filters\">\n");
    sb.append("<select id=\"fRegion\" onchange=\"applyFilters()\"><option value=\"\">All regions</option>");
    for (LoopTestRegion r : LoopTestRegion.values()) {
      sb.append("<option>").append(r.name()).append("</option>");
    }
    sb.append("</select>\n");
    sb.append("<select id=\"fProfile\" onchange=\"applyFilters()\"><option value=\"\">All profiles</option>");
    sb.append("<option>fastbike</option><option>gravel</option><option>mtb</option>");
    sb.append("</select>\n");
    sb.append("<select id=\"fDist\" onchange=\"applyFilters()\"><option value=\"\">All distances</option>");
    sb.append("<option>30</option><option>50</option><option>80</option><option>100</option>");
    sb.append("</select>\n");
    sb.append("<select id=\"fStatus\" onchange=\"applyFilters()\"><option value=\"\">All status</option>");
    sb.append("<option>pass</option><option>fail</option><option>error</option>");
    sb.append("</select>\n");
    sb.append("<select id=\"fVariant\" onchange=\"applyFilters()\"><option value=\"\">All algorithms</option>");
    sb.append("<option>auto</option><option>probe</option><option>isochrone</option><option>greedy</option><option>iso_greedy</option>");
    sb.append("</select>\n");
    sb.append("<select id=\"fQuality\" onchange=\"applyFilters()\"><option value=\"\">Any quality</option>");
    sb.append("<option value=\"spur\">&#9888; has spurs</option>");
    sb.append("<option value=\"cross\">&#9888; has crossings</option>");
    sb.append("<option value=\"loopcross\">&#9888; detour-loop crossings</option>");
    sb.append("<option value=\"badshape\">&#9888; spurs OR crossings</option>");
    sb.append("<option value=\"lowcompact\">&#9888; low compactness (&lt;0.35)</option>");
    sb.append("</select>\n");
    sb.append("<label title=\"Click a route to overlay every algorithm for the SAME request\"><input type=\"checkbox\" id=\"fCompare\" onchange=\"applyFilters()\"> Compare algorithms</label>\n");
    sb.append("<label><input type=\"checkbox\" id=\"fShowAll\" onchange=\"applyFilters()\"> Show all on map</label>\n");
    sb.append("</div>\n");

    // Results table
    sb.append("<table id=\"results\">\n");
    sb.append("<thead><tr><th>Alg</th><th>Test</th><th>Reuse%</th><th>DistR</th><th>DirD</th><th>Cont</th><th>Comp</th><th>Score</th><th>Dist(m)</th></tr></thead>\n");
    sb.append("<tbody>\n");
    for (int i = 0; i < results.size(); i++) {
      LoopQualityResult r = results.get(i);
      String status = r.metrics == null ? "error" : (r.passed() ? "pass" : "fail");
      String variant = r.variant != null ? r.variant : "probe";
      int spur = r.metrics != null ? r.metrics.getSpurCount() : 0;
      int crossX = r.metrics != null ? r.metrics.getSelfIntersections() : 0;
      int loopX = r.metrics != null ? r.metrics.getSmallLoopCrossings() : 0;
      double compact = r.metrics != null ? r.metrics.getCompactnessScore() : 1.0;
      sb.append(String.format(Locale.US, "<tr class=\"%s\" data-idx=\"%d\" data-region=\"%s\" data-profile=\"%s\" data-dist=\"%d\" data-status=\"%s\" data-variant=\"%s\" data-case=\"%s\" data-spur=\"%d\" data-crossx=\"%d\" data-loopx=\"%d\" data-compact=\"%.2f\" onclick=\"focusRoute(%d)\">",
        status, i, r.region.name(), r.profileName, r.distanceMeters / 1000, status, variant, r.label, spur, crossX, loopX, compact, i));
      sb.append(String.format("<td><span class=\"variant-badge v-%s\" title=\"%s\">%s</span></td>",
        variant, variant, variant.substring(0, 1).toUpperCase()));
      sb.append(String.format("<td title=\"%s\">%s</td>", r.label, abbreviateLabel(r.label)));
      if (r.metrics != null) {
        sb.append(formatMetricCell(r.metrics.getRoadReusePercent(), r.region.maxReusePercent, "%.1f"));
        sb.append(formatRatioCell(r.metrics.getDistanceRatio(), r.region.minDistanceRatio, r.region.maxDistanceRatio));
        sb.append(formatMetricCell(r.metrics.getDirectionDeltaDegrees(), r.region.maxDirectionDelta, "%.0f"));
        sb.append(String.format(Locale.US, "<td>%.2f</td>", r.metrics.getContinuityScore()));
        sb.append(String.format(Locale.US, "<td>%.2f</td>", r.metrics.getCompactnessScore()));
        sb.append(String.format(Locale.US, "<td>%.2f</td>", r.metrics.compositeScore()));
        sb.append(String.format(Locale.US, "<td>%d</td>", r.metrics.getActualDistanceMeters()));
      } else {
        sb.append("<td colspan=\"7\" title=\"").append(escapeHtml(r.error != null ? r.error : "")).append("\">error</td>");
      }
      sb.append("</tr>\n");
    }
    sb.append("</tbody></table>\n</div>\n");

    // Map
    sb.append("<div id=\"map\"></div>\n");

    // Embedded route data as JS. LoopQualityTest already caps routes with
    // Douglas-Peucker simplification; avoid a second stride simplification here
    // because it can draw misleading straight cuts across curved roads.
    sb.append("<script>\n");
    sb.append("var routes = [\n");
    for (int i = 0; i < results.size(); i++) {
      LoopQualityResult r = results.get(i);
      sb.append("  {label:\"").append(r.label).append(" [").append(r.variant).append("]\",");
      sb.append("variant:\"").append(r.variant).append("\",");
      sb.append("caseKey:\"").append(r.label).append("\",");
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
        sb.append(String.format(Locale.US, "cont:%.2f,compact:%.2f,costM:%.1f,maxGap:%d,closure:%d,composite:%.2f,",
          r.metrics.getContinuityScore(), r.metrics.getCompactnessScore(),
          r.metrics.getAverageCostPerMeter(), r.metrics.getMaxGapMeters(),
          r.metrics.getClosureDistanceMeters(), r.metrics.compositeScore()));
        sb.append(String.format(Locale.US, "spur:%d,worstSpur:%d,crossX:%d,loopX:%d,",
          r.metrics.getSpurCount(), r.metrics.getWorstSpurMeters(),
          r.metrics.getSelfIntersections(), r.metrics.getSmallLoopCrossings()));
      }
      sb.append("coords:[");
      if (r.coordinates != null && r.coordinates.length > 0) {
        for (int j = 0; j < r.coordinates.length; j++) {
          if (j > 0) sb.append(",");
          sb.append(String.format(Locale.US, "[%.5f,%.5f]", r.coordinates[j][1], r.coordinates[j][0])); // Leaflet: [lat, lon]
        }
      }
      sb.append("],");
      appendDefectOverlays(sb, r);
      sb.append("},\n");
    }
    sb.append("];\n\n");

    // Map initialization
    sb.append("var map = L.map('map').setView([48.5, 8.5], 5);\n");
    sb.append("L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {\n");
    sb.append("  attribution: '&copy; OpenStreetMap contributors', maxZoom: 18\n");
    sb.append("}).addTo(map);\n\n");

    sb.append("var layers = [];\n");
    sb.append("var variantColors = {auto:'#000000', probe:'#0066cc', isochrone:'#e67300', greedy:'#22aa44', iso_greedy:'#aa22cc'};\n");
    sb.append("var selectedIdx = -1;\n\n");
    appendDefectJs(sb);

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
    sb.append("      popup += 'Continuity: ' + r.cont + '<br>';\n");
    sb.append("      popup += 'Compactness: ' + r.compact + '<br>';\n");
    sb.append("      popup += 'Cost/m: ' + r.costM + '<br>';\n");
    sb.append("      popup += 'Max gap: ' + r.maxGap + 'm<br>';\n");
    sb.append("      popup += 'Closure: ' + r.closure + 'm<br>';\n");
    sb.append("      if (r.spur !== undefined && r.spur > 0) { popup += '<b style=\"color:#c0392b\">Spurs: ' + r.spur + ' (worst ' + r.worstSpur + 'm)</b><br>'; }\n");
    sb.append("      if (r.crossX !== undefined && r.crossX > 0) { popup += '<b style=\"color:#c0392b\">Crossings: ' + r.crossX + (r.loopX > 0 ? ' (' + r.loopX + ' from detour loops)' : '') + '</b><br>'; }\n");
    sb.append("      popup += '<b>Composite: ' + r.composite + '</b><br>';\n");
    sb.append("      popup += 'Status: <b>' + r.status + '</b>';\n");
    sb.append("    }\n");
    sb.append("    layer.bindPopup(popup);\n");
    sb.append("    layer.on('click', function() { highlightRow(i); });\n");
    sb.append("  }\n");
    sb.append("  layers.push(layer);\n");
    sb.append("});\n\n");

    // Default view: single-route focus. "Show all on map" toggle overlays
    // every filtered loop so several algorithms can be compared side-by-side.
    sb.append("applyFilters();\n\n");

    sb.append("function isVisibleRow(idx) {\n");
    sb.append("  var row = document.querySelector('tr[data-idx=\"'+idx+'\"]');\n");
    sb.append("  return row && row.style.display !== 'none';\n");
    sb.append("}\n\n");

    sb.append("function focusRoute(idx) {\n");
    sb.append("  var compare = document.getElementById('fCompare') && document.getElementById('fCompare').checked;\n");
    sb.append("  if (compare) { focusCompare(idx); return; }\n");
    sb.append("  var showAll = document.getElementById('fShowAll').checked;\n");
    sb.append("  if (!showAll && selectedIdx >= 0 && selectedIdx !== idx && layers[selectedIdx]) layers[selectedIdx].remove();\n");
    sb.append("  highlightRow(idx);\n");
    sb.append("  var r = routes[idx];\n");
    sb.append("  drawDefects(r);\n");
    sb.append("  if (layers[idx]) {\n");
    sb.append("    if (!map.hasLayer(layers[idx])) layers[idx].addTo(map);\n");
    sb.append("    layers[idx].setStyle({weight: 5, opacity: 1.0});\n");
    sb.append("    layers[idx].bringToFront();\n");
    sb.append("    map.fitBounds(layers[idx].getBounds().pad(0.1));\n");
    sb.append("    layers[idx].openPopup();\n");
    sb.append("  } else {\n");
    sb.append("    map.setView(r.center, 11);\n");
    sb.append("  }\n");
    sb.append("}\n\n");

    sb.append("function focusCompare(idx) {\n");
    sb.append("  // Overlay EVERY algorithm variant for the same route request (region/profile/dist/dir),\n");
    sb.append("  // each in its variant colour, so they can be compared side-by-side on one map.\n");
    sb.append("  var key = routes[idx].caseKey;\n");
    sb.append("  var bounds = L.latLngBounds([]);\n");
    sb.append("  layers.forEach(function(layer, j) {\n");
    sb.append("    if (!layer) return;\n");
    sb.append("    if (routes[j].caseKey === key) {\n");
    sb.append("      if (!map.hasLayer(layer)) layer.addTo(map);\n");
    sb.append("      layer.setStyle({weight: (j === idx ? 5 : 3), opacity: (j === idx ? 1.0 : 0.7)});\n");
    sb.append("      bounds.extend(layer.getBounds());\n");
    sb.append("    } else if (map.hasLayer(layer)) { layer.remove(); }\n");
    sb.append("  });\n");
    sb.append("  highlightRow(idx);\n");
    sb.append("  drawDefects(routes[idx]);\n");
    sb.append("  if (layers[idx]) { layers[idx].bringToFront(); layers[idx].openPopup(); }\n");
    sb.append("  if (bounds.isValid()) map.fitBounds(bounds.pad(0.1));\n");
    sb.append("}\n\n");

    sb.append("function highlightRow(idx) {\n");
    sb.append("  if (selectedIdx >= 0 && selectedIdx !== idx && layers[selectedIdx]) layers[selectedIdx].setStyle({weight: 3, opacity: 0.6});\n");
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
    sb.append("  var fV = document.getElementById('fVariant').value;\n");
    sb.append("  var fQ = document.getElementById('fQuality').value;\n");
    sb.append("  var compare = document.getElementById('fCompare').checked;\n");
    sb.append("  var showAll = document.getElementById('fShowAll').checked;\n");
    sb.append("  var visibleIdx = [];\n");
    sb.append("  document.querySelectorAll('#results tbody tr').forEach(function(row) {\n");
    sb.append("    var show = true;\n");
    sb.append("    if (fR && row.dataset.region !== fR) show = false;\n");
    sb.append("    if (fP && row.dataset.profile !== fP) show = false;\n");
    sb.append("    if (fD && row.dataset.dist !== fD) show = false;\n");
    sb.append("    if (fS && row.dataset.status !== fS) show = false;\n");
    sb.append("    if (fV && row.dataset.variant !== fV) show = false;\n");
    sb.append("    if (fQ) {\n");
    sb.append("      var sp = +row.dataset.spur, cx = +row.dataset.crossx, lx = +row.dataset.loopx, cm = +row.dataset.compact;\n");
    sb.append("      if (fQ === 'spur' && !(sp > 0)) show = false;\n");
    sb.append("      else if (fQ === 'cross' && !(cx > 0)) show = false;\n");
    sb.append("      else if (fQ === 'loopcross' && !(lx > 0)) show = false;\n");
    sb.append("      else if (fQ === 'badshape' && !(sp > 0 || cx > 0)) show = false;\n");
    sb.append("      else if (fQ === 'lowcompact' && !(cm < 0.35)) show = false;\n");
    sb.append("    }\n");
    sb.append("    row.style.display = show ? '' : 'none';\n");
    sb.append("    var idx = parseInt(row.dataset.idx);\n");
    sb.append("    if (show) visibleIdx.push(idx);\n");
    sb.append("    if (!show && idx === selectedIdx) { row.classList.remove('selected'); selectedIdx = -1; }\n");
    sb.append("  });\n");
    sb.append("  // Compare mode draws the per-case variant overlay in focusRoute; leave the map alone here.\n");
    sb.append("  if (compare) return;\n");
    sb.append("  // Map sync: in show-all mode every visible route is drawn; otherwise only the selected one.\n");
    sb.append("  layers.forEach(function(layer, idx) {\n");
    sb.append("    if (!layer) return;\n");
    sb.append("    var shouldShow = showAll ? isVisibleRow(idx) : (idx === selectedIdx);\n");
    sb.append("    if (shouldShow && !map.hasLayer(layer)) layer.addTo(map);\n");
    sb.append("    else if (!shouldShow && map.hasLayer(layer)) layer.remove();\n");
    sb.append("  });\n");
    sb.append("  if (showAll && visibleIdx.length > 0) {\n");
    sb.append("    var bounds = L.latLngBounds([]);\n");
    sb.append("    visibleIdx.forEach(function(idx) { if (layers[idx]) bounds.extend(layers[idx].getBounds()); });\n");
    sb.append("    if (bounds.isValid()) map.fitBounds(bounds.pad(0.05));\n");
    sb.append("  }\n");
    sb.append("}\n");

    sb.append("</script>\n</body>\n</html>\n");
    return sb.toString();
  }

  /**
   * Generate a per-region HTML report with full route geometry and variant comparison.
   * Probe routes shown in blue, isochrone routes in orange.
   */
  static String generateRegionHtml(LoopTestRegion region, List<LoopQualityResult> results) {
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
    sb.append(".probe { color: #0066cc; } .isochrone { color: #e67300; } .greedy { color: #22aa44; } .iso_greedy { color: #aa22cc; } .auto { color: #000; }\n");
    sb.append(".metric-bad { color: #dc3545; font-weight: bold; }\n");
    sb.append("</style>\n</head>\n<body>\n");

    // Sidebar
    sb.append("<div id=\"sidebar\">\n");
    sb.append("<h3>").append(region.name()).append("</h3>\n");
    sb.append("<p>").append(String.format("%.3f, %.3f", region.lon, region.lat)).append("</p>\n");
    sb.append("<p style=\"margin:4px 0\"><span class=\"probe\">&#9632; Probe (blue)</span> &nbsp; ");
    sb.append("<span class=\"isochrone\">&#9632; Isochrone (orange)</span> &nbsp; ");
    sb.append("<span class=\"greedy\">&#9632; Greedy (green)</span> &nbsp; ");
    sb.append("<span class=\"iso_greedy\">&#9632; Iso-Greedy (purple)</span> &nbsp; ");
    sb.append("<span class=\"auto\">&#9632; <b>AUTO ships (black)</b></span></p>\n");

    // Filters
    sb.append("<div class=\"filters\">\n");
    sb.append("<select id=\"fProfile\" onchange=\"applyFilters()\"><option value=\"\">All profiles</option>");
    sb.append("<option>fastbike</option><option>gravel</option><option>mtb</option></select>\n");
    sb.append("<select id=\"fDist\" onchange=\"applyFilters()\"><option value=\"\">All distances</option>");
    sb.append("<option>30</option><option>50</option><option>80</option><option>100</option></select>\n");
    sb.append("<select id=\"fVariant\" onchange=\"applyFilters()\"><option value=\"\">All variants</option>");
    sb.append("<option>auto</option><option>probe</option><option>isochrone</option><option>greedy</option><option>iso_greedy</option></select>\n");
    sb.append("</div>\n");

    // Table
    sb.append("<table id=\"results\"><thead><tr><th>Test</th><th>Var</th><th>Reuse</th><th>DistR</th><th>Dir</th><th>Cont</th><th>Comp</th><th>Score</th><th>Dist</th></tr></thead><tbody>\n");
    for (int i = 0; i < results.size(); i++) {
      LoopQualityResult r = results.get(i);
      String vc = r.variant != null ? r.variant : "probe";
      sb.append(String.format("<tr class=\"%s\" data-idx=\"%d\" data-profile=\"%s\" data-dist=\"%d\" data-variant=\"%s\" onclick=\"focusRoute(%d)\">",
        vc, i, r.profileName, r.distanceMeters / 1000, r.variant, i));
      sb.append(String.format("<td title=\"%s\">%s</td>", r.label, abbreviateLabel(r.label)));
      sb.append(String.format("<td class=\"%s\">%s</td>", vc, r.variant.substring(0, 1).toUpperCase()));
      if (r.metrics != null) {
        sb.append(String.format(Locale.US, "<td>%.1f</td>", r.metrics.getRoadReusePercent()));
        sb.append(String.format(Locale.US, "<td>%.2f</td>", r.metrics.getDistanceRatio()));
        sb.append(String.format(Locale.US, "<td>%.0f</td>", r.metrics.getDirectionDeltaDegrees()));
        sb.append(String.format(Locale.US, "<td>%.2f</td>", r.metrics.getContinuityScore()));
        sb.append(String.format(Locale.US, "<td>%.2f</td>", r.metrics.getCompactnessScore()));
        sb.append(String.format(Locale.US, "<td>%.2f</td>", r.metrics.compositeScore()));
        sb.append(String.format(Locale.US, "<td>%d</td>", r.metrics.getActualDistanceMeters()));
      } else {
        sb.append("<td colspan=\"7\">").append(r.error != null ? "err" : "skip").append("</td>");
      }
      sb.append("</tr>\n");
    }
    sb.append("</tbody></table></div>\n");

    // Map
    sb.append("<div id=\"map\"></div>\n<script>\n");

    // Embed routes with FULL coordinates
    sb.append("var routes = [\n");
    for (int i = 0; i < results.size(); i++) {
      LoopQualityResult r = results.get(i);
      sb.append("{label:\"").append(r.label).append(" [").append(r.variant).append("]\",");
      sb.append("variant:\"").append(r.variant).append("\",");
      sb.append("profile:\"").append(r.profileName).append("\",");
      sb.append("dist:").append(r.distanceMeters / 1000).append(",");
      if (r.metrics != null) {
        sb.append(String.format(Locale.US, "reuse:%.1f,distR:%.2f,dirD:%.0f,actualDist:%d,",
          r.metrics.getRoadReusePercent(), r.metrics.getDistanceRatio(),
          r.metrics.getDirectionDeltaDegrees(), r.metrics.getActualDistanceMeters()));
        sb.append(String.format(Locale.US, "cont:%.2f,compact:%.2f,costM:%.1f,maxGap:%d,closure:%d,composite:%.2f,",
          r.metrics.getContinuityScore(), r.metrics.getCompactnessScore(),
          r.metrics.getAverageCostPerMeter(), r.metrics.getMaxGapMeters(),
          r.metrics.getClosureDistanceMeters(), r.metrics.compositeScore()));
        sb.append(String.format(Locale.US, "spur:%d,worstSpur:%d,crossX:%d,loopX:%d,",
          r.metrics.getSpurCount(), r.metrics.getWorstSpurMeters(),
          r.metrics.getSelfIntersections(), r.metrics.getSmallLoopCrossings()));
      }
      sb.append("coords:[");
      if (r.coordinates != null) {
        for (int j = 0; j < r.coordinates.length; j++) {
          if (j > 0) sb.append(",");
          sb.append(String.format(Locale.US, "[%.5f,%.5f]", r.coordinates[j][1], r.coordinates[j][0]));
        }
      }
      sb.append("],");
      appendDefectOverlays(sb, r);
      sb.append("},\n");
    }
    sb.append("];\n\n");

    sb.append(String.format(Locale.US, "var map = L.map('map').setView([%.4f, %.4f], 11);\n", region.lat, region.lon));
    sb.append("L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{attribution:'&copy; OSM',maxZoom:18}).addTo(map);\n");
    sb.append("var layers=[], selectedIdx=-1;\n");
    sb.append("var variantColors = {auto:'#000000', probe:'#0066cc', isochrone:'#e67300', greedy:'#22aa44', iso_greedy:'#aa22cc'};\n\n");
    appendDefectJs(sb);

    sb.append("routes.forEach(function(r,i){\n");
    sb.append("  var layer=null;\n");
    sb.append("  if(r.coords.length>1){\n");
    sb.append("    layer=L.polyline(r.coords,{color:variantColors[r.variant],weight:3,opacity:0.7});\n");
    sb.append("    var p='<b>'+r.label+'</b><hr>';\n");
    sb.append("    p+='Variant: <b>'+r.variant+'</b><br>';\n");
    sb.append("    p+='Profile: '+r.profile+'<br>';\n");
    sb.append("    p+='Requested: '+r.dist+'km<br>';\n");
    sb.append("    if(r.reuse!==undefined){p+='<hr>Actual: '+r.actualDist+'m ('+r.distR+'x)<br>Reuse: '+r.reuse+'%<br>Dir delta: '+r.dirD+'&deg;<br>Continuity: '+r.cont+'<br>Compactness: '+r.compact+'<br>Cost/m: '+r.costM+'<br>Closure: '+r.closure+'m<br>'+((r.spur>0)?'<b style=\"color:#c0392b\">Spurs: '+r.spur+' (worst '+r.worstSpur+'m)</b><br>':'')+((r.crossX>0)?'<b style=\"color:#c0392b\">Crossings: '+r.crossX+((r.loopX>0)?' ('+r.loopX+' from detour loops)':'')+'</b><br>':'')+'<b>Composite: '+r.composite+'</b>';}\n");
    sb.append("    layer.bindPopup(p);\n");
    sb.append("    layer.on('click',function(){highlightRow(i);});\n");
    sb.append("  }\n  layers.push(layer);\n});\n\n");

    sb.append("function focusRoute(i){if(selectedIdx>=0&&selectedIdx!==i&&layers[selectedIdx])layers[selectedIdx].remove();highlightRow(i);drawDefects(routes[i]);if(layers[i]){layers[i].addTo(map);layers[i].setStyle({weight:5,opacity:1});map.fitBounds(layers[i].getBounds().pad(0.1));layers[i].openPopup();}}\n");
    sb.append("function highlightRow(i){if(selectedIdx>=0&&layers[selectedIdx])layers[selectedIdx].setStyle({weight:3,opacity:0.7});document.querySelectorAll('tr.selected').forEach(function(e){e.classList.remove('selected');});selectedIdx=i;var row=document.querySelector('tr[data-idx=\"'+i+'\"]');if(row){row.classList.add('selected');row.scrollIntoView({block:'nearest'});}}\n\n");

    sb.append("function applyFilters(){\n");
    sb.append("  // filters only narrow the table list; the map keeps showing the single selected route\n");
    sb.append("  var fP=document.getElementById('fProfile').value,fD=document.getElementById('fDist').value,fV=document.getElementById('fVariant').value;\n");
    sb.append("  document.querySelectorAll('#results tbody tr').forEach(function(row){\n");
    sb.append("    var show=true;\n");
    sb.append("    if(fP&&row.dataset.profile!==fP)show=false;\n");
    sb.append("    if(fD&&row.dataset.dist!==fD)show=false;\n");
    sb.append("    if(fV&&row.dataset.variant!==fV)show=false;\n");
    sb.append("    row.style.display=show?'':'none';\n");
    sb.append("    if(!show&&parseInt(row.dataset.idx)===selectedIdx&&layers[selectedIdx]){layers[selectedIdx].remove();row.classList.remove('selected');selectedIdx=-1;}\n");
    sb.append("  });\n}\n");
    sb.append("applyFilters();\n");

    sb.append("</script>\n</body>\n</html>\n");
    return sb.toString();
  }

  /**
   * Emit per-route defect overlays for the map: crossing locations
   * ({@code xpts}: [lat, lon, enclosedArcM] per crossing) and near-revisit
   * span index ranges ({@code spans}: [i0, i1] into {@code coords}).
   * Computed at render time from the persisted Douglas-Peucker-simplified
   * geometry — DP removes points but never moves them, so positions survive
   * simplification. The authoritative COUNTS in the table still come from
   * the full-resolution test-time metrics; that is also why the O(n²)
   * crossing scan only runs on routes the metrics already flagged.
   */
  private static void appendDefectOverlays(StringBuilder sb, LoopQualityResult r) {
    List<OsmPathElement> els = null;
    if (r.coordinates != null && r.coordinates.length >= 4) {
      els = new java.util.ArrayList<>(r.coordinates.length);
      for (double[] c : r.coordinates) {
        els.add(OsmPathElement.create(
          (int) Math.round((c[0] + 180.0) * 1e6),
          (int) Math.round((c[1] + 90.0) * 1e6), (short) 0, null));
      }
    }
    sb.append("xpts:[");
    if (els != null && r.metrics != null && r.metrics.getSelfIntersections() > 0) {
      boolean first = true;
      for (double[] x : LoopQualityMetrics.crossingPoints(els)) {
        if (!first) sb.append(",");
        first = false;
        sb.append(String.format(Locale.US, "[%.5f,%.5f,%.0f]", x[1], x[0], x[2]));
      }
    }
    sb.append("],spans:[");
    if (els != null) {
      double[] cum = new double[els.size()];
      for (int k = 1; k < els.size(); k++) {
        cum[k] = cum[k - 1] + els.get(k - 1).calcDistance(els.get(k));
      }
      double perim = cum[els.size() - 1];
      boolean first = true;
      for (int[] s : LoopQualityMetrics.nearRevisitSpans(els, 60.0, 600.0, 10000.0)) {
        double arc = cum[s[1]] - cum[s[0]];
        if (perim > 0 && arc > 0.85 * perim) continue; // the loop's own closure
        if (!first) sb.append(",");
        first = false;
        sb.append("[").append(s[0]).append(",").append(s[1]).append("]");
      }
    }
    sb.append("]");
  }

  /** Shared map-side JS: defect overlay layer + renderer, used by both pages. */
  private static void appendDefectJs(StringBuilder sb) {
    sb.append("var defectLayer = L.layerGroup().addTo(map);\n");
    sb.append("function drawDefects(r) {\n");
    sb.append("  defectLayer.clearLayers();\n");
    sb.append("  if (!r) return;\n");
    sb.append("  (r.xpts||[]).forEach(function(x) {\n");
    sb.append("    L.circleMarker([x[0],x[1]], {radius:9, color:'#c0392b', weight:3, fillColor:'#ff2222', fillOpacity:0.45})\n");
    sb.append("      .bindPopup('<b>&#9888; Route crossing</b><br>enclosed loop ~' + (x[2]/1000).toFixed(1) + 'km'\n");
    sb.append("        + (x[2] <= 4000 ? ' (detour loop / lasso)' : ' (structural outbound-vs-return)'))\n");
    sb.append("      .addTo(defectLayer);\n");
    sb.append("  });\n");
    sb.append("  (r.spans||[]).forEach(function(s) {\n");
    sb.append("    L.polyline(r.coords.slice(s[0], s[1]+1), {color:'#e67e22', weight:8, opacity:0.5, dashArray:'8 8'})\n");
    sb.append("      .bindPopup('<b>&#9888; Near-revisit span</b><br>out-and-back / teardrop section')\n");
    sb.append("      .addTo(defectLayer);\n");
    sb.append("  });\n");
    sb.append("}\n\n");
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
