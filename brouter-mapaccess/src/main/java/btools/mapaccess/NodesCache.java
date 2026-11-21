/**
 * Efficient cache or osmnodes
 *
 * @author ab
 */
package btools.mapaccess;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import btools.util.TagValueValidator;

public final class NodesCache {
  private final File segmentDir;

  private final Map<Long, OsmNode> hollowNodes = new HashMap<>(4096);

  private final TagValueValidator wayValidator;

  private String currentFileName;

  private final Map<String, OsmFile> fileCache;

  public NodesCache(File segmentDir, TagValueValidator wayValidator, long maxMem) {
    this.segmentDir = segmentDir;
    this.wayValidator = wayValidator;

    if (!this.segmentDir.isDirectory()) {
      throw new RuntimeException("segment directory " + segmentDir.getAbsolutePath() + " does not exist");
    }
    fileCache = new HashMap<>(4);
  }

  public void clean() {
    for (OsmFile osmf : fileCache.values()) {
      if ( osmf != null ) {
        osmf.clean();
      }
    }
  }

  public boolean decodeSegmentFor(OsmNode template, boolean failOnNoFile, WaypointMatcher waypointMatcher  ) {
    try {
      OsmFile osmf = fileForSegment(template, failOnNoFile);
      if (osmf == null) {
        return false;
      }
      osmf.checkDecodeTile(template, wayValidator, waypointMatcher, hollowNodes);
      return true;
    } catch (IOException re) {
      throw new RuntimeException(re.getMessage());
    } catch (RuntimeException re) {
      throw re;
    } catch (Exception e) {
      throw new RuntimeException("error reading datafile " + currentFileName + ": " + e, e);
    }
  }

  /**
   * make sure the given node is non-hollow,
   * which means it contains not just the id,
   * but also the actual data
   *
   * @return true if successfull, false if node is still hollow
   */
  public boolean obtainNonHollowNode(OsmNode node) {
    if (node.isHollow()) {
      if ( !decodeSegmentFor(node, false, null) ) {
        return false;
      }
      if (node.isHollow()) {
        throw new RuntimeException("node must not be hollow after decoding available micro-tile");
      }
    }
    return true;
  }


  /**
   * make sure all link targets of the given node are non-hollow
   */
  public void expandHollowLinkTargets(OsmNode n) {
    for (OsmLink link = n.firstLink; link != null; link = link.getNext(n)) {
      obtainNonHollowNode(link.getTarget(n));
    }
  }


  public OsmNode getGraphNode(OsmNode template) {
    OsmNode existing = hollowNodes.get(OsmNode.idFromPos(template.iLon, template.iLat));
    if (existing != null) {
      return existing;
    }
    OsmNode graphNode = new OsmNode(template.iLon, template.iLat);
    graphNode.setBits(OsmNode.HOLLOW_BIT);
    hollowNodes.put(OsmNode.idFromPos(graphNode.iLon, graphNode.iLat), graphNode);
    return graphNode;
  }

  public void matchWaypointsToNodes(List<MatchedWaypoint> unmatchedWaypoints, double maxDistance, OsmNodePairSet islandNodePairs) {
    WaypointMatcher waypointMatcher = new WaypointMatcher(unmatchedWaypoints, maxDistance, islandNodePairs);
    for (MatchedWaypoint mwp : unmatchedWaypoints) {
      int cellsize = 1000000 / 32;
      preloadPosition(mwp.waypoint, waypointMatcher, cellsize);
    }
    for (MatchedWaypoint mwp : unmatchedWaypoints) {
      if (mwp.crossPoint == null) {
        throw new IllegalArgumentException(mwp.name + "-position not mapped in existing datafile");
      }
    }
  }

  private void preloadPosition(OsmNode n, WaypointMatcher waypointMatcher, int d) {
    decodeSegmentFor(n, true, waypointMatcher);
    for (int idxLat = -1; idxLat <= 1; idxLat++)
      for (int idxLon = -1; idxLon <= 1; idxLon++) {
        if (idxLon != 0 || idxLat != 0) {
          decodeSegmentFor(new OsmNode(n.iLon + d * idxLon, n.iLat + d * idxLat), false, waypointMatcher );
        }
      }
  }

  private OsmFile fileForSegment(OsmNode template, boolean failOnNoFile ) throws Exception {
    int iLonBase = (template.iLon / 5000000)*5000000;
    int iLatBase = (template.iLat / 5000000)*5000000;
    int lonBase5 = iLonBase / 1000000 - 180;
    int latBase5 = iLatBase / 1000000 -  90;
    String filenameBase = OsmFile.getBaseName(lonBase5, latBase5);

    currentFileName = filenameBase + ".rd5";
    if (fileCache.containsKey(filenameBase)) {
      return fileCache.get(filenameBase);
    }
    File f = new File(segmentDir, currentFileName);
    if (!f.exists()) {
      if (failOnNoFile) {
        throw new IllegalArgumentException("datafile " + currentFileName + " not found");
      }
      fileCache.put(filenameBase, null);
      return null;
    }
    OsmFile  osmf = new OsmFile(f, iLonBase, iLatBase);
    osmf.openForReading();
    fileCache.put(filenameBase, osmf);
    return osmf;
  }

  public void close() {
    for (OsmFile f : fileCache.values()) {
      try {
        if (f != null)
          f.close();
      } catch (IOException ioe) {
        // ignore
      }
    }
  }
}
