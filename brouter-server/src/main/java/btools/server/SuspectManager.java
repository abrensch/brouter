package btools.server;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.TreeSet;

import btools.mapaccess.OsmNode;
import btools.router.OsmNodeNamed;
import btools.router.SuspectInfo;

public class SuspectManager extends Thread {
  private static SimpleDateFormat dfTimestampZ = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");

  static NearRecentWps nearRecentWps = new NearRecentWps();
  static NearRecentWps hiddenWps = new NearRecentWps();

  private static String formatZ(Date date) {
    synchronized (dfTimestampZ) {
      return dfTimestampZ.format(date);
    }
  }

  private static String formatAge(File f) {
    return formatAge(System.currentTimeMillis() - f.lastModified());
  }

  private static String formatAge(long age) {
    long minutes = age / 60000;
    if (minutes < 60) {
      return minutes + " minutes";
    }
    long hours = minutes / 60;
    if (hours < 24) {
      return hours + " hours";
    }
    long days = hours / 24;
    return days + " days";
  }

  private static String getLevelDecsription(int level) {
    switch (level) {
      case 30:
        return "motorway";
      case 28:
        return "trunk";
      case 26:
        return "primary";
      case 24:
        return "secondary";
      case 22:
        return "tertiary";
      default:
        return "none";
    }
  }


  private static void markFalsePositive(SuspectList suspects, long id) throws IOException {
    new File("falsepositives/" + id).createNewFile();
    for (int isuspect = 0; isuspect < suspects.cnt; isuspect++) {
      if (id == suspects.ids[isuspect]) {
        suspects.falsePositive[isuspect] = true;
      }
    }

  }

  public static void newAndConfirmedJson(SuspectList suspects, BufferedWriter bw, String filter, int level, Area polygon) throws IOException {
    bw.write("{\n");
    bw.write("\"type\": \"FeatureCollection\",\n");
    bw.write("\"features\": [");

    int n = 0;

    for (int isuspect = 0; isuspect < suspects.cnt; isuspect++) {
      long id = suspects.ids[isuspect];
      int prio = suspects.prios[isuspect];
      int nprio = ((prio + 1) / 2) * 2; // normalize (no link prios)

      if (nprio < level) {
        continue; // not the given level
      }
      if (("new".equals(filter) || "deferred".equals(filter)) && !suspects.newOrConfirmed[isuspect]) {
        continue; // known archived
      }
      if ("fp".equals(filter) ^ suspects.falsePositive[isuspect]) {
        continue; // wrong false-positive state
      }

      if (polygon != null && !polygon.isInBoundingBox(id)) {
        continue; // not in selected polygon (pre-check)
      }

      String dueTime = null;
      if (!"deferred".equals(filter)) {
        if (isFixed(id, suspects.timestamp)) {
          continue; // known fixed
        }
      } else {
        File fixedEntry = new File("fixedsuspects/" + id);
        if (!fixedEntry.exists()) {
          continue;
        }
        long fixedTs = fixedEntry.lastModified();
        if (fixedTs < suspects.timestamp) {
          continue; // that would be under current suspects
        }
        long hideTime = fixedTs - System.currentTimeMillis();
        if (hideTime < 0) {
          File confirmedEntry = new File("confirmednegatives/" + id);
          if (confirmedEntry.exists() && confirmedEntry.lastModified() > suspects.timestamp) {
            continue; // that would be under current suspects
          }
        }
        dueTime = hideTime < 0 ? "(asap)" : formatAge(hideTime + 43200000);
      }

      if (polygon != null && !polygon.isInArea(id)) {
        continue; // not in selected polygon
      }

      File confirmedEntry = new File("confirmednegatives/" + id);
      String status = suspects.newOrConfirmed[isuspect] ? "new" : "archived";
      if (confirmedEntry.exists()) {
        status = "confirmed " + formatAge(confirmedEntry) + " ago";
      }

      if (dueTime != null) {
        status = "deferred";
      }
      if (n > 0) {
        bw.write(",");
      }

      int ilon = (int) (id >> 32);
      int ilat = (int) (id & 0xffffffff);
      double dlon = (ilon - 180000000) / 1000000.;
      double dlat = (ilat - 90000000) / 1000000.;

      String slevel = getLevelDecsription(nprio);

      bw.write("\n{\n");
      bw.write("  \"id\": " + n + ",\n");
      bw.write("  \"type\": \"Feature\",\n");
      bw.write("  \"properties\": {\n");
      bw.write("    \"issue_id\": \"" + id + "\",\n");
      bw.write("    \"Status\": \"" + status + "\",\n");
      if (dueTime != null) {
        bw.write("    \"DueTime\": \"" + dueTime + "\",\n");
      }
      bw.write("    \"Level\": \"" + slevel + "\"\n");
      bw.write("  },\n");
      bw.write("  \"geometry\": {\n");
      bw.write("    \"type\": \"Point\",\n");
      bw.write("    \"coordinates\": [\n");
      bw.write("      " + dlon + ",\n");
      bw.write("      " + dlat + "\n");
      bw.write("    ]\n");
      bw.write("  }\n");
      bw.write("}");

      n++;
    }

    bw.write("\n  ]\n");
    bw.write("}\n");
    bw.flush();
  }

  public static void process(String url, BufferedWriter bw) throws IOException {
    try {
      _process(url, bw);
    } catch (IllegalArgumentException iae) {
      try {
        bw.write("<br><br>ERROR: " + iae.getMessage() + "<br><br>\n\n");
        bw.write("(press Browser-Back to continue)\n");
        bw.flush();
      } catch (IOException _ignore) {
      }
    }
  }

  private static void _process(String url, BufferedWriter bw) throws IOException {
    StringTokenizer tk = new StringTokenizer(url, "/?");
    tk.nextToken();
    tk.nextToken();
    long id = 0L;
    String country = "";
    String challenge = "";
    String suspectFilename = "worldsuspects.txt";
    String filter = null;

    while (tk.hasMoreTokens()) {
      String c = tk.nextToken();
      if ("all".equals(c) || "new".equals(c) || "confirmed".equals(c) || "fp".equals(c) || "deferred".equals(c)) {
        filter = c;
        break;
      }
      if (country.length() == 0 && !"world".equals(c)) {
        if (new File(c + "suspects.txt").exists()) {
          suspectFilename = c + "suspects.txt";
          challenge = "/" + c;
          continue;
        }
      }

      country += "/" + c;
    }

    SuspectList suspects = getAllSuspects(suspectFilename);

    Area polygon = null;
    if (!("/world".equals(country) || "".equals(country))) {
      File polyFile = new File("worldpolys" + country + ".poly");
      if (!polyFile.exists()) {
        bw.write("polygon file for country '" + country + "' not found\n");
        bw.write("</body></html>\n");
        bw.flush();
        return;
      }
      polygon = new Area(polyFile);
    }

    if (url.endsWith(".json")) {
      StringTokenizer tk2 = new StringTokenizer(tk.nextToken(), ".");
      int level = Integer.parseInt(tk2.nextToken());

      newAndConfirmedJson(suspects, bw, filter, level, polygon);
      return;
    }

    bw.write("<html><body>\n");
    bw.write("BRouter suspect manager. <a href=\"http://brouter.de/brouter/suspect_manager_help.html\">Help</a><br><br>\n");

    if (filter == null) { // generate country list
      bw.write("<table>\n");
      File countryParent = new File("worldpolys" + country);
      File[] files = countryParent.listFiles();
      Set<String> names = new TreeSet<>();
      for (File f : files) {
        String name = f.getName();
        if (name.endsWith(".poly")) {
          names.add(name.substring(0, name.length() - 5));
        }
      }
      for (String c : names) {
        String url2 = "/brouter/suspects" + challenge + country + "/" + c;
        String linkNew = "<td>&nbsp;<a href=\"" + url2 + "/new\">new</a>&nbsp;</td>";
        String linkCnf = "<td>&nbsp;<a href=\"" + url2 + "/confirmed\">confirmed</a>&nbsp;</td>";
        String linkAll = "<td>&nbsp;<a href=\"" + url2 + "/all\">all</a>&nbsp;</td>";

        String linkSub = "";
        if (new File(countryParent, c).exists()) {
          linkSub = "<td>&nbsp;<a href=\"" + url2 + "\">sub-regions</a>&nbsp;</td>";
        }
        bw.write("<tr><td>" + c + "</td>" + linkNew + linkCnf + linkAll + linkSub + "\n");
      }
      bw.write("</table>\n");
      bw.write("</body></html>\n");
      bw.flush();
      return;
    }

    File suspectFile = new File("worldsuspects.txt");
    if (!suspectFile.exists()) {
      bw.write("suspect file worldsuspects.txt not found\n");
      bw.write("</body></html>\n");
      bw.flush();
      return;
    }

    boolean showWatchList = false;
    if (tk.hasMoreTokens()) {
      String t = tk.nextToken();
      if ("watchlist".equals(t)) {
        showWatchList = true;
      } else {
        id = Long.parseLong(t);
      }
    }

    if (showWatchList) {
      bw.write("watchlist for " + country + "\n");
      bw.write("<br><a href=\"/brouter/suspects" + challenge + "\">back to country list</a><br><br>\n");

      long timeNow = System.currentTimeMillis();

      for (int isuspect = 0; isuspect < suspects.cnt; isuspect++) {
        id = suspects.ids[isuspect];

        if (polygon != null && !polygon.isInBoundingBox(id)) {
          continue; // not in selected polygon (pre-check)
        }
        if (new File("falsepositives/" + id).exists()) {
          continue; // known false positive
        }
        File fixedEntry = new File("fixedsuspects/" + id);
        if (!fixedEntry.exists()) {
          continue;
        }
        long fixedTs = fixedEntry.lastModified();
        if (fixedTs < suspects.timestamp) {
          continue; // that would be under current suspects
        }
        long hideTime = fixedTs - timeNow;
        if (hideTime < 0) {
          File confirmedEntry = new File("confirmednegatives/" + id);
          if (confirmedEntry.exists() && confirmedEntry.lastModified() > suspects.timestamp) {
            continue; // that would be under current suspects
          }
        }
        if (polygon != null && !polygon.isInArea(id)) {
          continue; // not in selected polygon
        }

        String countryId = challenge + country + "/" + filter + "/" + id;
        String dueTime = hideTime < 0 ? "(asap)" : formatAge(hideTime + 43200000);
        String hint = "&nbsp;&nbsp;&nbsp;due in " + dueTime;
        int ilon = (int) (id >> 32);
        int ilat = (int) (id & 0xffffffff);
        double dlon = (ilon - 180000000) / 1000000.;
        double dlat = (ilat - 90000000) / 1000000.;
        String url2 = "/brouter/suspects" + countryId;
        bw.write("<a href=\"" + url2 + "\">" + dlon + "," + dlat + "</a>" + hint + "<br>\n");
      }
      bw.write("</body></html>\n");
      bw.flush();
      return;
    }

    String message = null;
    if (tk.hasMoreTokens()) {
      String command = tk.nextToken();
      if ("falsepositive".equals(command)) {
        int wps = nearRecentWps.count(id);
        if (wps < 8) {
          message = "marking false-positive requires at least 8 recent nearby waypoints from BRouter-Web, found: " + wps
            + "<br><br>****** DO SOME MORE TEST-ROUTINGS IN BROUTER-WEB ******* before marking false positive";
        } else {
          markFalsePositive(suspects, id);
          message = "Marked issue " + id + " as false-positive";
          id = 0L;
        }
      }
      if ("confirm".equals(command)) {
        int wps = nearRecentWps.count(id);
        if (wps < 2) {
          message = "marking confirmed requires at least 2 recent nearby waypoints from BRouter-Web, found: " + wps
            + "<br><br>****** DO AT LEAST ONE TEST-ROUTING IN BROUTER-WEB ******* before marking confirmed";
        } else {
          new File("confirmednegatives/" + id).createNewFile();
        }
      }
      if ("fixed".equals(command)) {
        File fixedMarker = new File("fixedsuspects/" + id);
        if (!fixedMarker.exists()) {
          fixedMarker.createNewFile();
        }

        int hideDays = 0;
        if (tk.hasMoreTokens()) {
          String param = tk.nextToken();
          if (param.startsWith("ndays=")) {
            param = param.substring("ndays=".length());
          }
          try {
            hideDays = Integer.parseInt(param); // hiding, not fixing
          } catch (NumberFormatException nfe) {
            throw new IllegalArgumentException("not a number: " + param);
          }
          if (hideDays < 1 || hideDays > 999) {
            throw new IllegalArgumentException("hideDays must be within 1..999");
          }
          message = "Hide issue " + id + " for " + hideDays + " days";
        } else {
          message = "Marked issue " + id + " as fixed";
        }
        if (hideDays > 0) {
          OsmNodeNamed nn = new OsmNodeNamed(new OsmNode(id));
          nn.name = "" + hideDays;
          hiddenWps.add(nn);
        }
        id = 0L;
        fixedMarker.setLastModified(System.currentTimeMillis() + hideDays * 86400000L);
      }
    }
    if (id != 0L) {
      String countryId = challenge + country + "/" + filter + "/" + id;

      int ilon = (int) (id >> 32);
      int ilat = (int) (id & 0xffffffff);
      double dlon = (ilon - 180000000) / 1000000.;
      double dlat = (ilat - 90000000) / 1000000.;

      String profile = "car-eco";
      File configFile = new File("configs/profile.cfg");
      if (configFile.exists()) {
        BufferedReader br = new BufferedReader(new FileReader(configFile));
        profile = br.readLine();
        br.close();
      }

      // get triggers
      int triggers = suspects.trigger4Id(id);
      SuspectList daily = getDailySuspectsIfLoaded();
      if (daily != null && daily != suspects) {
        triggers |= daily.trigger4Id(id); // hack, because osmoscope does not echo type of analysis
      }
      String triggerText = SuspectInfo.getTriggerText(triggers);


      String url1 = "http://brouter.de/brouter-web/#map=18/" + dlat + "/" + dlon
        + "/OpenStreetMap&lonlats=" + dlon + "," + dlat + "&profile=" + profile;

      // String url1 = "http://localhost:8080/brouter-web/#map=18/" + dlat + "/"
      // + dlon + "/Mapsforge Tile Server&lonlats=" + dlon + "," + dlat;

      String url2 = "https://www.openstreetmap.org/?mlat=" + dlat + "&mlon=" + dlon + "#map=19/" + dlat + "/" + dlon + "&layers=N";

      double slon = 0.00156;
      double slat = 0.001;
      String url3 = "http://127.0.0.1:8111/load_and_zoom?left=" + (dlon - slon)
        + "&bottom=" + (dlat - slat) + "&right=" + (dlon + slon) + "&top=" + (dlat + slat);

      Date weekAgo = new Date(System.currentTimeMillis() - 604800000L);
      String url4a = "https://overpass-turbo.eu/?Q=[date:&quot;" + formatZ(weekAgo) + "Z&quot;];way[highway]({{bbox}});out meta geom;&C="
        + dlat + ";" + dlon + ";18&R";

      String url4b = "https://overpass-turbo.eu/?Q=(node(around%3A1%2C%7B%7Bcenter%7D%7D)-%3E.n%3Bway(bn.n)%5Bhighway%5D%3Brel(bn.n%3A%22via%22)%5Btype%3Drestriction%5D%3B)%3Bout%20meta%3B%3E%3Bout%20skel%20qt%3B&C="
        + dlat + ";" + dlon + ";18&R";

      String url5 = "https://tyrasd.github.io/latest-changes/#16/" + dlat + "/" + dlon;

      String url6 = "https://apps.sentinel-hub.com/sentinel-playground/?source=S2L2A&lat=" + dlat + "&lng=" + dlon + "&zoom=15";

      if (message != null) {
        bw.write("<strong>" + message + "</strong><br><br>\n");
      }
      bw.write("Trigger: " + triggerText + "<br><br>\n");
      bw.write("<a href=\"" + url1 + "\">Open in BRouter-Web</a><br><br>\n");
      bw.write("<a href=\"" + url2 + "\">Open in OpenStreetmap</a><br><br>\n");
      bw.write("<a href=\"" + url3 + "\">Open in JOSM (via remote control)</a><br><br>\n");
      bw.write("Overpass: <a href=\"" + url4a + "\">minus one week</a> &nbsp;&nbsp; <a href=\"" + url4b + "\">node context</a><br><br>\n");
      bw.write("<a href=\"" + url5 + "\">Open in Latest-Changes / last week</a><br><br>\n");
      bw.write("<a href=\"" + url6 + "\">Current Sentinel-2 imagary</a><br><br>\n");
      bw.write("<br>\n");
      if (isFixed(id, suspects.timestamp)) {
        bw.write("<br><br><a href=\"/brouter/suspects/" + challenge + country + "/" + filter + "/watchlist\">back to watchlist</a><br><br>\n");
      } else {
        bw.write("<a href=\"/brouter/suspects" + countryId + "/falsepositive\">mark false positive (=not an issue)</a><br><br>\n");
        File confirmedEntry = new File("confirmednegatives/" + id);
        if (confirmedEntry.exists()) {
          String prefix = "<a href=\"/brouter/suspects" + countryId + "/fixed";
          String prefix2 = " &nbsp;&nbsp;" + prefix;

          OsmNodeNamed nc = hiddenWps.closest(id);
          String proposal = nc == null ? "" : nc.name;
          String prefix2d = "<form action=\"/brouter/suspects" + countryId + "/fixed\" method=\"get\">hide for days: &nbsp;&nbsp;<input type=\"text\" name=\"ndays\" value=\"" + proposal + "\" autofocus><button type=\"submit\">OK</button></form>";

          bw.write(prefix + "\">mark as fixed</a><br><br>\n");
          bw.write("hide for:  weeks:");
          bw.write(prefix2 + "/7\">1w</a>");
          bw.write(prefix2 + "/14\">2w</a>");
          bw.write(prefix2 + "/21\">3w</a>");
          bw.write(" &nbsp;&nbsp;&nbsp; months:");
          bw.write(prefix2 + "/30\">1m</a>");
          bw.write(prefix2 + "/61\">2m</a>");
          bw.write(prefix2 + "/91\">3m</a>");
          bw.write(prefix2 + "/122\">4m</a>");
          bw.write(prefix2 + "/152\">5m</a>");
          bw.write(prefix2 + "/183\">6m</a><br><br>\n");
          bw.write(prefix2d + "<br><br>\n");
        } else {
          bw.write("<a href=\"/brouter/suspects" + countryId + "/confirm\">mark as a confirmed issue</a><br><br>\n");
        }
        if (polygon != null) {
          bw.write("<br><br><a href=\"/brouter/suspects" + challenge + country + "/" + filter + "\">back to issue list</a><br><br>\n");
        }
      }
    } else if (polygon == null) {
      bw.write(message + "<br>\n");
    } else {
      bw.write(filter + " suspect list for " + country + "\n");
      bw.write("<br><a href=\"/brouter/suspects" + challenge + country + "/" + filter + "/watchlist\">see watchlist</a>\n");
      bw.write("<br><a href=\"/brouter/suspects" + challenge + "\">back to country list</a><br><br>\n");
      int maxprio = 0;
      {
        for (int isuspect = 0; isuspect < suspects.cnt; isuspect++) {
          id = suspects.ids[isuspect];
          int prio = suspects.prios[isuspect];
          int nprio = ((prio + 1) / 2) * 2; // normalize (no link prios)
          if (nprio < maxprio) {
            if (maxprio == 0) {
              bw.write("current level: " + getLevelDecsription(maxprio) + "<br><br>\n");
            }
            break;
          }
          if (polygon != null && !polygon.isInBoundingBox(id)) {
            continue; // not in selected polygon (pre-check)
          }
          if (new File("falsepositives/" + id).exists()) {
            continue; // known false positive
          }
          if (isFixed(id, suspects.timestamp)) {
            continue; // known fixed
          }
          if ("new".equals(filter) && new File("suspectarchive/" + id).exists()) {
            continue; // known archived
          }
          if ("confirmed".equals(filter) && !new File("confirmednegatives/" + id).exists()) {
            continue; // showing confirmed suspects only
          }
          if (polygon != null && !polygon.isInArea(id)) {
            continue; // not in selected polygon
          }
          if (maxprio == 0) {
            maxprio = nprio;
            bw.write("current level: " + getLevelDecsription(maxprio) + "<br><br>\n");
          }
          String countryId = challenge + country + "/" + filter + "/" + id;
          File confirmedEntry = new File("confirmednegatives/" + id);
          String hint = "";
          if (confirmedEntry.exists()) {
            hint = "&nbsp;&nbsp;&nbsp;confirmed " + formatAge(confirmedEntry) + " ago";
          }
          int ilon = (int) (id >> 32);
          int ilat = (int) (id & 0xffffffff);
          double dlon = (ilon - 180000000) / 1000000.;
          double dlat = (ilat - 90000000) / 1000000.;
          String url2 = "/brouter/suspects" + countryId;
          bw.write("<a href=\"" + url2 + "\">" + dlon + "," + dlat + "</a>" + hint + "<br>\n");
        }
      }
    }
    bw.write("</body></html>\n");
    bw.flush();
  }


  private static boolean isFixed(long id, long timestamp) {
    File fixedEntry = new File("fixedsuspects/" + id);
    return fixedEntry.exists() && fixedEntry.lastModified() > timestamp;
  }


  private static final class SuspectList {
    int cnt;
    long[] ids;
    int[] prios;
    int[] triggers;
    boolean[] newOrConfirmed;
    boolean[] falsePositive;
    long timestamp;

    SuspectList(int count, long time) {
      cnt = count;
      ids = new long[cnt];
      prios = new int[cnt];
      triggers = new int[cnt];
      newOrConfirmed = new boolean[cnt];
      falsePositive = new boolean[cnt];
      timestamp = time;
    }

    int trigger4Id(long id) {
      for (int i = 0; i < cnt; i++) {
        if (id == ids[i]) {
          return triggers[i];
        }
      }
      return 0;
    }
  }

  private static Map<String, SuspectList> allSuspectsMap = new HashMap<>();

  private static SuspectList getDailySuspectsIfLoaded() throws IOException {
    synchronized (allSuspectsMap) {
      return allSuspectsMap.get("dailysuspects.txt");
    }
  }

  private static SuspectList getAllSuspects(String suspectFileName) throws IOException {
    synchronized (allSuspectsMap) {
      SuspectList allSuspects = allSuspectsMap.get(suspectFileName);
      File suspectFile = new File(suspectFileName);
      if (allSuspects != null && suspectFile.lastModified() == allSuspects.timestamp) {
        return allSuspects;
      }

      // count prios
      int[] prioCount = new int[100];
      BufferedReader r = new BufferedReader(new FileReader(suspectFile));
      for (; ; ) {
        String line = r.readLine();
        if (line == null) break;
        StringTokenizer tk2 = new StringTokenizer(line);
        tk2.nextToken();
        int prio = Integer.parseInt(tk2.nextToken());
        int nprio = ((prio + 1) / 2) * 2; // normalize (no link prios)
        prioCount[nprio]++;
      }
      r.close();

      // sum up
      int pointer = 0;
      for (int i = 99; i >= 0; i--) {
        int cnt = prioCount[i];
        prioCount[i] = pointer;
        pointer += cnt;
      }


      // sort into suspect list
      allSuspects = new SuspectList(pointer, suspectFile.lastModified());
      r = new BufferedReader(new FileReader(suspectFile));
      for (; ; ) {
        String line = r.readLine();
        if (line == null) break;
        StringTokenizer tk2 = new StringTokenizer(line);
        long id = Long.parseLong(tk2.nextToken());
        int prio = Integer.parseInt(tk2.nextToken());
        int nprio = ((prio + 1) / 2) * 2; // normalize (no link prios)
        pointer = prioCount[nprio]++;
        allSuspects.ids[pointer] = id;
        allSuspects.prios[pointer] = prio;
        allSuspects.triggers[pointer] = tk2.hasMoreTokens() ? Integer.parseInt(tk2.nextToken()) : 0;
        allSuspects.newOrConfirmed[pointer] = new File("confirmednegatives/" + id).exists() || !(new File("suspectarchive/" + id).exists());
        allSuspects.falsePositive[pointer] = new File("falsepositives/" + id).exists();
      }
      r.close();
      allSuspectsMap.put(suspectFileName, allSuspects);
      return allSuspects;
    }
  }
}
