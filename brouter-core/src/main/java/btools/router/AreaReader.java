package btools.router;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import btools.codec.DataBuffers;
import btools.codec.MicroCache;
import btools.expressions.BExpressionContextWay;
import btools.mapaccess.MatchedWaypoint;
import btools.mapaccess.NodesCache;
import btools.mapaccess.OsmFile;
import btools.mapaccess.OsmLink;
import btools.mapaccess.OsmNode;
import btools.mapaccess.OsmNodesMap;
import btools.mapaccess.PhysicalFile;

public class AreaReader {

  File segmentFolder;

  public void getDirectAllData(File folder, RoutingContext rc, OsmNodeNamed wp, int maxscale, BExpressionContextWay expctxWay, OsmNogoPolygon searchRect, List<AreaInfo> ais) {
    this.segmentFolder = folder;

    int div = 32;
    int cellsize = 1000000 / div;
    int scale = maxscale;
    int count = 0;
    int used = 0;
    boolean checkBorder = maxscale > 7;

    Map<Long, String> tiles = new TreeMap<>();
    for (int idxLat = -scale; idxLat <= scale; idxLat++) {
      for (int idxLon = -scale; idxLon <= scale; idxLon++) {
        if (ignoreCenter(maxscale, idxLon, idxLat)) continue;
        int tmplon = wp.ilon + cellsize * idxLon;
        int tmplat = wp.ilat + cellsize * idxLat;
        int lonDegree = tmplon / 1000000;
        int latDegree = tmplat / 1000000;
        int lonMod5 = (int) lonDegree % 5;
        int latMod5 = (int) latDegree % 5;

        int lon = (int) lonDegree - 180 - lonMod5;
        String slon = lon < 0 ? "W" + (-lon) : "E" + lon;
        int lat = (int) latDegree - 90 - latMod5;
        String slat = lat < 0 ? "S" + (-lat) : "N" + lat;
        String filenameBase = slon + "_" + slat;

        int lonIdx = tmplon / cellsize;
        int latIdx = tmplat / cellsize;
        int subIdx = (latIdx - div * latDegree) * div + (lonIdx - div * lonDegree);

        int subLonIdx = (lonIdx - div * lonDegree);
        int subLatIdx = (latIdx - div * latDegree);

        OsmNogoPolygon dataRect = new OsmNogoPolygon(true);
        lon = lonDegree * 1000000;
        lat = latDegree * 1000000;
        int tmplon2 = lon + cellsize * (subLonIdx);
        int tmplat2 = lat + cellsize * (subLatIdx);
        dataRect.addVertex(tmplon2, tmplat2);

        tmplon2 = lon + cellsize * (subLonIdx + 1);
        tmplat2 = lat + cellsize * (subLatIdx);
        dataRect.addVertex(tmplon2, tmplat2);

        tmplon2 = lon + cellsize * (subLonIdx + 1);
        tmplat2 = lat + cellsize * (subLatIdx + 1);
        dataRect.addVertex(tmplon2, tmplat2);

        tmplon2 = lon + cellsize * (subLonIdx);
        tmplat2 = lat + cellsize * (subLatIdx + 1);
        dataRect.addVertex(tmplon2, tmplat2);

        boolean intersects = checkBorder && dataRect.intersects(searchRect.points.get(0).x, searchRect.points.get(0).y, searchRect.points.get(2).x, searchRect.points.get(2).y);
        if (!intersects && checkBorder)
          intersects = dataRect.intersects(searchRect.points.get(1).x, searchRect.points.get(1).y, searchRect.points.get(2).x, searchRect.points.get(3).y);
        if (intersects) {
          continue;
        }

        intersects = searchRect.intersects(dataRect.points.get(0).x, dataRect.points.get(0).y, dataRect.points.get(2).x, dataRect.points.get(2).y);
        if (!intersects)
          intersects = searchRect.intersects(dataRect.points.get(1).x, dataRect.points.get(1).y, dataRect.points.get(3).x, dataRect.points.get(3).y);
        if (!intersects)
          intersects = containsRect(searchRect, dataRect.points.get(0).x, dataRect.points.get(0).y, dataRect.points.get(2).x, dataRect.points.get(2).y);

        if (!intersects) {
          continue;
        }

        tiles.put(((long) tmplon) << 32 | tmplat, filenameBase);
        count++;
      }
    }

    List<Map.Entry<Long, String>> list = new ArrayList<>(tiles.entrySet());
    Collections.sort(list, new Comparator<>() {
      @Override
      public int compare(Map.Entry<Long, String> e1, Map.Entry<Long, String> e2) {
        return e1.getValue().compareTo(e2.getValue());
      }
    });

    long maxmem = rc.memoryclass * 1024L * 1024L; // in MB
    NodesCache nodesCache = new NodesCache(segmentFolder, expctxWay, rc.forceSecondaryData, maxmem, null, false);
    PhysicalFile pf = null;
    String lastFilenameBase = "";
    DataBuffers dataBuffers = null;
    try {
      for (Map.Entry<Long, String> entry : list) {
        OsmNode n = new OsmNode(entry.getKey());
        // System.out.println("areareader set " + n.getILon() + "_" + n.getILat() + " " + entry.getValue());
        String filenameBase = entry.getValue();
        if (!filenameBase.equals(lastFilenameBase)) {
          if (pf != null) pf.close();
          lastFilenameBase = filenameBase;
          File file = new File(segmentFolder, filenameBase + ".rd5");
          dataBuffers = new DataBuffers();

          pf = new PhysicalFile(file, dataBuffers, -1, -1);
        }
        if (getDirectData(pf, dataBuffers, n.getILon(), n.getILat(), rc, expctxWay, ais))
          used++;
      }
    } catch (Exception e) {
      System.err.println("AreaReader: after " + used + "/" + count + " " + e.getMessage());
      ais.clear();
    } finally {
      if (pf != null)
        try {
          pf.close();
        } catch (Exception ee) {
        }
      nodesCache.close();
    }
  }

  public boolean getDirectData(PhysicalFile pf, DataBuffers dataBuffers, int inlon, int inlat, RoutingContext rc, BExpressionContextWay expctxWay, List<AreaInfo> ais) {

    int lonDegree = inlon / 1000000;
    int latDegree = inlat / 1000000;

    OsmNodesMap nodesMap = new OsmNodesMap();

    try {
      int div = pf.divisor;

      OsmFile osmf = new OsmFile(pf, lonDegree, latDegree, dataBuffers);
      if (osmf.hasData()) {
        int cellsize = 1000000 / div;
        int tmplon = inlon;
        int tmplat = inlat;
        int lonIdx = tmplon / cellsize;
        int latIdx = tmplat / cellsize;

        MicroCache segment = osmf.createMicroCache(lonIdx, latIdx, dataBuffers, expctxWay, null, true, null);

        if (segment != null) {
          int size = segment.getSize();
          for (int i = 0; i < size; i++) {
            long id = segment.getIdForIndex(i);
            OsmNode node = new OsmNode(id);
            if (segment.getAndClear(id)) {
              node.parseNodeBody(segment, nodesMap, expctxWay);
              if (node.firstlink instanceof OsmLink) {
                for (OsmLink link = node.firstlink; link != null; link = link.getNext(node)) {
                  OsmNode nextNode = link.getTarget(node);
                  if (nextNode.firstlink == null)
                    continue; // don't care about dead ends
                  if (nextNode.firstlink.descriptionBitmap == null)
                    continue;

                  for (AreaInfo ai : ais) {
                    if (ai.polygon.isWithin(node.ilon, node.ilat)) {
                      ai.checkAreaInfo(expctxWay, node.getElev(), nextNode.firstlink.descriptionBitmap);
                      break;
                    }
                  }
                  break;
                }
              }
            }
          }
        }
        return true;
      }
    } catch (Exception e) {
      System.err.println("AreaReader: " + e.getMessage());
    }
    return false;
  }

  boolean ignoreCenter(int maxscale, int idxLon, int idxLat) {
    int centerScale = (int) Math.round(maxscale * .2) - 1;
    if (centerScale < 0) return false;
    return idxLon >= -centerScale && idxLon <= centerScale &&
      idxLat >= -centerScale && idxLat <= centerScale;
  }

  /*
    in this case the polygon is 'only' a rectangle
  */
  boolean containsRect(OsmNogoPolygon searchRect, int p1x, int p1y, int p2x, int p2y) {
    return searchRect.isWithin((long) p1x, (long) p1y) &&
      searchRect.isWithin(p2x, p2y);
  }

  public void writeAreaInfo(String filename, MatchedWaypoint wp, List<AreaInfo> ais) throws Exception {
    DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(filename)));

    wp.writeToStream(dos);
    for (AreaInfo ai : ais) {
      dos.writeInt(ai.direction);
      dos.writeDouble(ai.elevStart);
      dos.writeInt(ai.ways);
      dos.writeInt(ai.greenWays);
      dos.writeInt(ai.riverWays);
      dos.writeInt(ai.elev50);
    }
    dos.close();
  }

  public void readAreaInfo(File fai, MatchedWaypoint wp, List<AreaInfo> ais) {
    DataInputStream dis = null;
    MatchedWaypoint ep = null;
    try {
      dis = new DataInputStream(new BufferedInputStream(new FileInputStream(fai)));
      ep = MatchedWaypoint.readFromStream(dis);
      if (Math.abs(ep.waypoint.ilon - wp.waypoint.ilon) > 500 &&
        Math.abs(ep.waypoint.ilat - wp.waypoint.ilat) > 500) {
        return;
      }
      if (Math.abs(ep.radius - wp.radius) > 500) {
        return;
      }
      for (int i = 0; i < 4; i++) {
        int direction = dis.readInt();
        AreaInfo ai = new AreaInfo(direction);
        ai.elevStart = dis.readDouble();
        ai.ways = dis.readInt();
        ai.greenWays = dis.readInt();
        ai.riverWays = dis.readInt();
        ai.elev50 = dis.readInt();
        ais.add(ai);
      }
    } catch (IOException e) {
      ais.clear();
    } finally {
      if (dis != null) {
        try {
          dis.close();
        } catch (IOException e) {
        }
      }
    }
  }

}
