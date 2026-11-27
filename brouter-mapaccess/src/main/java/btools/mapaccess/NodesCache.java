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
  private final String fileSuffix;

  private final Map<Long, GraphNode> hollowNodes = new HashMap<>(4096);

  private final TagValueValidator wayValidator;

  private String currentFileName;

  private final Map<String, GraphFile> fileCache;

  /**
   * @param oldCache optional previous cache (may be null): its open files (with parsed headers and
   *                 tile indexes) are taken over, but cleaned of all decoded tile data
   */
  public NodesCache(File segmentDir, String fileSuffix, TagValueValidator wayValidator, NodesCache oldCache) {
    this.segmentDir = segmentDir;
    this.fileSuffix = fileSuffix;
    this.wayValidator = wayValidator;

    if (!this.segmentDir.isDirectory()) {
      throw new RuntimeException("segment directory " + segmentDir.getAbsolutePath() + " does not exist");
    }
    if (oldCache != null) {
      fileCache = oldCache.fileCache;
      clean();
    } else {
      fileCache = new HashMap<>(4);
    }
  }

  public NodesCache(NodesCache oldCache) {
    this( oldCache.segmentDir, oldCache.fileSuffix, oldCache.wayValidator, oldCache);
  }

  public void clean() {
    for (GraphFile osmf : fileCache.values()) {
      if ( osmf != null ) {
        osmf.clean();
      }
    }
  }

  public boolean decodeSegmentFor(GraphNode template, boolean failOnNoFile, WaypointMatcher waypointMatcher  ) {
    try {
      GraphFile osmf = fileForSegment(template, failOnNoFile);
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
  public boolean obtainNonHollowNode(GraphNode node) {
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
  public void expandHollowLinkTargets(GraphNode n) {
    for (GraphLink link = n.firstLink; link != null; link = link.getNext(n)) {
      obtainNonHollowNode(link.getTarget(n));
    }
  }


  public GraphNode getGraphNode(GraphNode template) {
    GraphNode existing = hollowNodes.get(GraphNode.idFromPos(template.iLon, template.iLat));
    if (existing != null) {
      return existing;
    }
    GraphNode graphNode = new GraphNode(template.iLon, template.iLat);
    graphNode.setBits(GraphNode.HOLLOW_BIT);
    hollowNodes.put(GraphNode.idFromPos(graphNode.iLon, graphNode.iLat), graphNode);
    return graphNode;
  }

  public void matchWaypointsToNodes(List<MatchedWaypoint> unmatchedWaypoints, double maxDistance, NodePairSet islandNodePairs) {
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

  private void preloadPosition(GraphNode n, WaypointMatcher waypointMatcher, int d) {
    decodeSegmentFor(n, true, waypointMatcher);
    for (int idxLat = -1; idxLat <= 1; idxLat++)
      for (int idxLon = -1; idxLon <= 1; idxLon++) {
        if (idxLon != 0 || idxLat != 0) {
          decodeSegmentFor(new GraphNode(n.iLon + d * idxLon, n.iLat + d * idxLat), false, waypointMatcher );
        }
      }
  }

  private GraphFile fileForSegment(GraphNode template, boolean failOnNoFile ) throws Exception {
    int iLonBase = (template.iLon / 5000000)*5000000;
    int iLatBase = (template.iLat / 5000000)*5000000;
    int lonBase5 = iLonBase / 1000000 - 180;
    int latBase5 = iLatBase / 1000000 -  90;
    String filenameBase = GraphFile.getBaseName(lonBase5, latBase5);

    currentFileName = filenameBase + "." + fileSuffix;
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
    GraphFile  osmf = new GraphFile(f, iLonBase, iLatBase);
    osmf.openForReading();
    fileCache.put(filenameBase, osmf);
    return osmf;
  }

  public void close() {
    for (GraphFile f : fileCache.values()) {
      try {
        if (f != null)
          f.close();
      } catch (IOException ioe) {
        // ignore
      }
    }
  }
}
