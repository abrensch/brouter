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

  private final OsmNodesMap hollowNodes;

  private final TagValueValidator wayValidator;

  private String currentFileName;

  private final Map<String, OsmFile> fileCache;

  public WaypointMatcher waypointMatcher;

  public boolean first_file_access_failed;
  public String first_file_access_name;

  public NodesCache(File segmentDir, TagValueValidator wayValidator, long maxMem) {
    this.segmentDir = segmentDir;
    this.hollowNodes = new OsmNodesMap();
    this.hollowNodes.maxmem = (2L * maxMem) / 3L;
    this.wayValidator = wayValidator;

    if (!this.segmentDir.isDirectory())
      throw new RuntimeException("segment directory " + segmentDir.getAbsolutePath() + " does not exist");

    fileCache = new HashMap<>(4);
  }

  public boolean isInMemoryBounds(int npaths, boolean extend) {
    return hollowNodes.isInMemoryBounds(npaths, extend);
  }

  public void clean() {
    for (OsmFile osmf : fileCache.values()) {
      if ( osmf != null ) {
        osmf.clean();
      }
    }
  }

  public boolean decodeSegmentFor(OsmNode template) {
    try {
      OsmFile osmf = fileForSegment(template);
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
      if ( !decodeSegmentFor(node) ) {
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
    OsmNode existing = hollowNodes.get(template.iLon, template.iLat);
    if (existing != null) {
      return existing;
    }
    OsmNode graphNode = new OsmNode(template.iLon, template.iLat);
    graphNode.setBits(OsmNode.HOLLOW_BIT);
    hollowNodes.put(graphNode);
    return graphNode;
  }

  public void matchWaypointsToNodes(List<MatchedWaypoint> unmatchedWaypoints, double maxDistance, OsmNodePairSet islandNodePairs) {
    waypointMatcher = new WaypointMatcher(unmatchedWaypoints, maxDistance, islandNodePairs);
    for (MatchedWaypoint mwp : unmatchedWaypoints) {
      int cellsize = 1000000 / 32;
      preloadPosition(mwp.waypoint, cellsize);
    }

    if (first_file_access_failed) {
      throw new IllegalArgumentException("datafile " + first_file_access_name + " not found");
    }
    for (MatchedWaypoint mwp : unmatchedWaypoints) {
      if (mwp.crossPoint == null) {
        throw new IllegalArgumentException(mwp.name + "-position not mapped in existing datafile");
      }
    }
  }

  private void preloadPosition(OsmNode n, int d) {
    first_file_access_failed = false;
    first_file_access_name = null;
    decodeSegmentFor(n);
    if (first_file_access_failed) {
      throw new IllegalArgumentException("datafile " + first_file_access_name + " not found");
    }
    for (int idxLat = -1; idxLat <= 1; idxLat++)
      for (int idxLon = -1; idxLon <= 1; idxLon++) {
        if (idxLon != 0 || idxLat != 0) {
          decodeSegmentFor(new OsmNode(n.iLon + d * idxLon, n.iLat + d * idxLat));
        }
      }
  }

  private OsmFile fileForSegment(OsmNode template) throws Exception {
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
      f = null;
    }
    OsmFile osmf = null;
    if (f != null) {
      osmf = new OsmFile(f, iLonBase, iLatBase);
      osmf.openForReading();
    }
    fileCache.put(filenameBase, osmf);

    if (first_file_access_name == null) {
      first_file_access_name = currentFileName;
      first_file_access_failed = osmf == null;
    }

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
