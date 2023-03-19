package btools.routingapp;

import android.graphics.Point;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import btools.router.OsmNodeNamed;
import btools.router.OsmNogoPolygon;

/**
 * Read coordinates from a gpx-file
 */
public class CoordinateReader {
  protected static String[] posnames
    = new String[]{"from", "via1", "via2", "via3", "via4", "via5", "via6", "via7", "via8", "via9", "to"};
  private final String internalDir;
  public List<OsmNodeNamed> waypoints;
  public List<OsmNodeNamed> nogopoints;
  public String basedir;
  public String rootdir;
  public String tracksdir;
  public List<OsmNodeNamed> allpoints;
  private boolean nogosOnly;
  private Map<String, Map<String, OsmNodeNamed>> allpointsMap;
  private HashMap<String, OsmNodeNamed> pointmap;

  public CoordinateReader(String basedir) {
    this.basedir = basedir;
    internalDir = basedir + "/brouter/import";
    tracksdir = "/brouter/import/tracks";
    rootdir = "/brouter/import";
  }

  public static CoordinateReader obtainValidReader(String basedir) throws IOException {
    return obtainValidReader(basedir, false);
  }

  public static CoordinateReader obtainValidReader(String basedir, boolean nogosOnly) throws IOException {
    CoordinateReader cor = new CoordinateReader(basedir);
    cor.nogosOnly = nogosOnly;
    cor.readFromTo();
    return cor;
  }

  public int getTurnInstructionMode() {
    return 4; // comment style
  }

  /*
   * read the from and to position from a gpx-file
   * (with hardcoded name for now)
   */
  public void readPointmap() throws IOException {
    _readPointmap(internalDir + "/favourites.gpx");

    _readNogoLines(basedir + tracksdir);
  }

  private boolean _readPointmap(String filename) throws IOException {
    BufferedReader br;
    try {
      br = new BufferedReader(
        new InputStreamReader(
          new FileInputStream(filename)));
    } catch (FileNotFoundException e) {
      // ignore until it's reading error
      return false;
    }
    OsmNodeNamed n = null;

    for (; ; ) {
      String line = br.readLine();
      if (line == null) break;

      int idx0 = line.indexOf(" lat=\"");
      int idx10 = line.indexOf("<name>");
      if (idx0 >= 0) {
        n = new OsmNodeNamed();
        idx0 += 6;
        int idx1 = line.indexOf('"', idx0);
        n.ilat = (int) ((Double.parseDouble(line.substring(idx0, idx1)) + 90.) * 1000000. + 0.5);
        int idx2 = line.indexOf(" lon=\"");
        if (idx2 < 0) continue;
        idx2 += 6;
        int idx3 = line.indexOf('"', idx2);
        n.ilon = (int) ((Double.parseDouble(line.substring(idx2, idx3)) + 180.) * 1000000. + 0.5);
        if (idx3 < 0) continue;
      }
      if (n != null && idx10 >= 0) {
        idx10 += 6;
        int idx11 = line.indexOf("</name>", idx10);
        if (idx11 >= 0) {
          n.name = line.substring(idx10, idx11).trim();
          checkAddPoint("(one-for-all)", n);
        }
      }
    }
    br.close();
    return true;
  }

  private void _readNogoLines(String dirname) {
    File dir = new File(dirname);

    if (dir.exists() && dir.isDirectory()) {
      for (final File file : dir.listFiles()) {
        final String name = file.getName();
        if (name.startsWith("nogo") && name.endsWith(".gpx")) {
          try {
            _readNogoLine(file);
          } catch (Exception ignored) {
          }
        }
      }
    }
  }

  private void _readNogoLine(File file) throws Exception {
    XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
    factory.setNamespaceAware(false);
    XmlPullParser xpp = factory.newPullParser();

    xpp.setInput(new FileReader(file));

    List<Point> tmpPts = new ArrayList<>();
    int eventType = xpp.getEventType();
    int numSeg = 0;
    boolean bIsNogoPoint = false;
    String sNogoName = null;
    while (eventType != XmlPullParser.END_DOCUMENT) {
      switch (eventType) {
        case XmlPullParser.START_TAG: {
          if (xpp.getName().equals("trkpt") || xpp.getName().equals("rtept") || xpp.getName().equals("wpt")) {
            final String lon = xpp.getAttributeValue(null, "lon");
            final String lat = xpp.getAttributeValue(null, "lat");
            if (lon != null && lat != null) {
              tmpPts.add(new Point(
                (int) ((Double.parseDouble(lon) + 180.) * 1000000. + 0.5),
                (int) ((Double.parseDouble(lat) + 90.) * 1000000. + 0.5)));
              if (xpp.getName().equals("wpt")) bIsNogoPoint = true;
            }
          } else if (bIsNogoPoint && xpp.getName().equals("name")) {
            sNogoName = xpp.nextText();
          }
          break;
        }
        case XmlPullParser.END_TAG: {
          if (xpp.getName().equals("trkseg") || xpp.getName().equals("rte")) { // rte has no segment
            OsmNogoPolygon nogo;
            if (tmpPts.size() > 0) {
              if (tmpPts.get(0).x == tmpPts.get(tmpPts.size() - 1).x &&
                tmpPts.get(0).y == tmpPts.get(tmpPts.size() - 1).y) {
                nogo = new OsmNogoPolygon(true);
              } else {
                nogo = new OsmNogoPolygon(false);
              }
              for (Point p : tmpPts) {
                nogo.addVertex(p.x, p.y);
              }
              nogo.calcBoundingCircle();
              final String name = file.getName();
              nogo.name = name.substring(0, name.length() - 4);
              if (numSeg > 0) {
                nogo.name += Integer.toString(numSeg + 1);
              }
              numSeg++;
              checkAddPoint("(one-for-all)", nogo);
            }
            tmpPts.clear();
          } else if (xpp.getName().equals("wpt")) {
            Point p = tmpPts.get(tmpPts.size() - 1);
            if (p != null) {
              OsmNodeNamed nogo = new OsmNodeNamed();
              nogo.ilon = p.x;
              nogo.ilat = p.y;
              nogo.name = (sNogoName != null ? sNogoName : "nogo1000 x");
              nogo.isNogo = true;
              nogo.radius = 20;
              try {
                nogo.radius = (sNogoName != null ? Integer.parseInt(sNogoName.substring(4, sNogoName.indexOf(" "))) : 20);
              } catch (Exception e) {
                try {
                  nogo.radius = (sNogoName != null ? Integer.parseInt(sNogoName.substring(4)) : 20);
                } catch (Exception e1) {
                  nogo.radius = 20;
                }
              }
              bIsNogoPoint = false;
              sNogoName = null;
              checkAddPoint("(one-for-all)", nogo);
              tmpPts.clear();
            }
          }
          break;
        }
      }
      eventType = xpp.next();
    }
  }

  public void readAllPoints() throws Exception {
    allpointsMap = new TreeMap<>();
    readFromTo();
    allpoints = new ArrayList<>();
    Set<String> names = new HashSet<>();
    for (String category : allpointsMap.keySet()) {
      Map<String, OsmNodeNamed> cat = allpointsMap.get(category);
      if (cat != null && cat.size() < 101) {
        for (OsmNodeNamed wp : cat.values()) {
          if (names.add(wp.name)) {
            allpoints.add(wp);
          }
        }
      } else {
        OsmNodeNamed nocatHint = new OsmNodeNamed();
        nocatHint.name = "<big category " + category + " supressed>";
        allpoints.add(nocatHint);
      }
    }
  }

  /*
   * read the from, to and via-positions from a gpx-file
   */
  public void readFromTo() throws IOException {
    pointmap = new HashMap<>();
    waypoints = new ArrayList<>();
    nogopoints = new ArrayList<>();
    readPointmap();
    boolean fromToMissing = false;
    for (String name : posnames) {
      OsmNodeNamed n = pointmap.get(name);
      if (n != null) {
        waypoints.add(n);
      } else {
        if ("from".equals(name)) fromToMissing = true;
        if ("to".equals(name)) fromToMissing = true;
      }
    }
    if (fromToMissing) waypoints.clear();
  }

  protected void checkAddPoint(String category, OsmNodeNamed n) {
    if (allpointsMap != null) {
      if (category == null) category = "";
      Map<String, OsmNodeNamed> cat = allpointsMap.get(category);
      if (cat == null) {
        cat = new TreeMap<>();
        allpointsMap.put(category, cat);
      }
      if (cat.size() < 101) {
        cat.put(n.name, n);
      }
      return;
    }

    boolean isKnown = false;
    for (String posname : posnames) {
      if (posname.equals(n.name)) {
        isKnown = true;
        break;
      }
    }

    if (isKnown) {
      if (pointmap.put(n.name, n) != null) {
        if (!nogosOnly) {
          throw new IllegalArgumentException("multiple " + n.name + "-positions!");
        }
      }
    } else if (n.name != null && n.name.startsWith("nogo")) {
      n.isNogo = true;
      n.nogoWeight = Double.NaN;
      nogopoints.add(n);
    }

  }
}
