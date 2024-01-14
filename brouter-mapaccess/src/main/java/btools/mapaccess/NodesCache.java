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

import btools.codec.DataBuffers;
import btools.codec.WaypointMatcher;
import btools.expressions.BExpressionContextWay;

public final class NodesCache {
  private final File segmentDir;
  private final File secondarySegmentsDir;

  public OsmNodesMap nodesMap;
  private final BExpressionContextWay expCtxWay;

  private final boolean forceSecondaryData;
  private String currentFileName;

  private final Map<String, OsmFile> fileCache;
  private final DataBuffers dataBuffers;

  public WaypointMatcher waypointMatcher;

  public boolean first_file_access_failed;
  public String first_file_access_name;

  public NodesCache(File segmentDir, BExpressionContextWay ctxWay, boolean forceSecondaryData, long maxMem, boolean detailed) {
    this.segmentDir = segmentDir;
    this.nodesMap = new OsmNodesMap();
    this.nodesMap.maxmem = (2L * maxMem) / 3L;
    this.expCtxWay = ctxWay;
    this.forceSecondaryData = forceSecondaryData;

    ctxWay.setDecodeForbidden(detailed);

    if (!this.segmentDir.isDirectory())
      throw new RuntimeException("segment directory " + segmentDir.getAbsolutePath() + " does not exist");

    fileCache = new HashMap<>(4);
    dataBuffers = new DataBuffers();
    secondarySegmentsDir = StorageConfigHelper.getSecondarySegmentDir(segmentDir);
  }

  public void clean() {
    for (OsmFile osmf : fileCache.values()) {
      osmf.clean();
    }
  }

  public boolean decodeSegmentFor(int ilon, int ilat) {
    try {
      OsmFile osmf = fileForSegment(ilon, ilat);
      if (osmf == null) {
        return false;
      }
      osmf.checkDecodeMicroTile(ilon, ilat, dataBuffers, expCtxWay, waypointMatcher, nodesMap);
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
      if ( !decodeSegmentFor(node.iLon, node.iLat) ) {
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

  /**
   * make sure all link targets of the given node are non-hollow
   */
  public boolean hasHollowLinkTargets(OsmNode n) {
    for (OsmLink link = n.firstLink; link != null; link = link.getNext(n)) {
      if (link.getTarget(n).isHollow()) {
        return true;
      }
    }
    return false;
  }

  /**
   * get a node for the given id with all link-targets also non-hollow
   * <p>
   * It is required that an instance of the start-node does not yet
   * exist, not even a hollow instance, so getStartNode should only
   * be called once right after resetting the cache
   *
   * @param id the id of the node to load
   * @return the fully expanded node for id, or null if it was not found
   */
  public OsmNode getStartNode(long id) {
    // initialize the start-node
    OsmNode n = new OsmNode(id);
    n.setHollow();
    nodesMap.put(n);
    if (!obtainNonHollowNode(n)) {
      return null;
    }
    expandHollowLinkTargets(n);
    return n;
  }

  public OsmNode getGraphNode(OsmNode template) {
    OsmNode graphNode = new OsmNode(template.iLon, template.iLat);
    graphNode.setHollow();
    OsmNode existing = nodesMap.put(graphNode);
    if (existing == null) {
      return graphNode;
    }
    nodesMap.put(existing);
    return existing;
  }

  public void matchWaypointsToNodes(List<MatchedWaypoint> unmatchedWaypoints, double maxDistance, OsmNodePairSet islandNodePairs) {
    waypointMatcher = new WaypointMatcherImpl(unmatchedWaypoints, maxDistance, islandNodePairs);
    for (MatchedWaypoint mwp : unmatchedWaypoints) {
      int cellsize = 12500;
      preloadPosition(mwp.waypoint, cellsize);
      // get a second chance
      if (mwp.crosspoint == null) {
        cellsize = 1000000 / 32;
        preloadPosition(mwp.waypoint, cellsize);
      }
    }

    if (first_file_access_failed) {
      throw new IllegalArgumentException("datafile " + first_file_access_name + " not found");
    }
    int len = unmatchedWaypoints.size();
    for (int i = 0; i < len; i++) {
      MatchedWaypoint mwp = unmatchedWaypoints.get(i);
      if (mwp.crosspoint == null) {
        if (unmatchedWaypoints.size() > 1 && i == unmatchedWaypoints.size() - 1 && unmatchedWaypoints.get(i - 1).direct) {
          mwp.crosspoint = new OsmNode(mwp.waypoint.iLon, mwp.waypoint.iLat);
          mwp.direct = true;
        } else {
          throw new IllegalArgumentException(mwp.name + "-position not mapped in existing datafile");
        }
      }
      if (unmatchedWaypoints.size() > 1 && i == unmatchedWaypoints.size() - 1 && unmatchedWaypoints.get(i - 1).direct) {
        mwp.crosspoint = new OsmNode(mwp.waypoint.iLon, mwp.waypoint.iLat);
        mwp.direct = true;
      }
    }
  }

  private void preloadPosition(OsmNode n, int d) {
    first_file_access_failed = false;
    first_file_access_name = null;
    decodeSegmentFor(n.iLon, n.iLat);
    if (first_file_access_failed) {
      throw new IllegalArgumentException("datafile " + first_file_access_name + " not found");
    }
    for (int idxLat = -1; idxLat <= 1; idxLat++)
      for (int idxLon = -1; idxLon <= 1; idxLon++) {
        if (idxLon != 0 || idxLat != 0) {
          decodeSegmentFor(n.iLon + d * idxLon, n.iLat + d * idxLat);
        }
      }
  }

  private OsmFile fileForSegment(int iLon, int iLat) throws Exception {
    int iLonBase = (iLon / 5000000)*5000000;
    int iLatBase = (iLat / 5000000)*5000000;
    int lonBase5 = iLonBase / 1000000 - 180;
    int latBase5 = iLatBase / 1000000 -  90;
    String slon = lonBase5 < 0 ? "W" + (-lonBase5) : "E" + lonBase5;
    String slat = latBase5 < 0 ? "S" + (-latBase5) : "N" + latBase5;
    String filenameBase = slon + "_" + slat;

    currentFileName = filenameBase + ".rd5";
    if (fileCache.containsKey(filenameBase)) {
      return fileCache.get(filenameBase);
    }
    File f = null;
    if (!forceSecondaryData) {
      File primary = new File(segmentDir, currentFileName);
      if (primary.exists()) {
        f = primary;
      }
    }
    if (f == null) {
      File secondary = new File(secondarySegmentsDir, currentFileName);
      if (secondary.exists()) {
        f = secondary;
      }
    }
    OsmFile osmf = null;
    if (f != null) {
      osmf = new OsmFile(f, iLonBase, iLatBase, dataBuffers);
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
