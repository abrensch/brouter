package btools.router;

import btools.mapaccess.OsmNode;
import btools.mapaccess.OsmTransferNode;

/**
 * Changes the cost of a link from its geometry.
 *
 * Selected by a negative turncost of the way, the turn angle penalty is switched
 * off then (see StdPath). Called once per link, before its sections are processed.
 * An instance can be shared by several routing threads, so it must be thread safe.
 */
public interface LinkCostModifier {

  /**
   * @param lon0          longitude of the point before the link (the last but one point
   *                      of the previous link), -1 at the start of the route
   * @param lat0          latitude of that point, -1 at the start of the route
   * @param sourceNode    start of the link
   * @param transferNodes intermediate points in the direction of travel, null if none
   * @param targetNode    end of the link
   * @return factor for the section costs of the link, >= 0; 1 = no change
   */
  double linkCostFactor(int lon0, int lat0, OsmNode sourceNode, OsmTransferNode transferNodes, OsmNode targetNode);
}
