package btools.router;

import btools.mapaccess.OsmNode;
import btools.mapaccess.OsmTransferNode;

/**
 * LinkCostModifier with the same factor for every link.
 */
final class ConstantLinkCostModifier implements LinkCostModifier {
  private final double factor;

  ConstantLinkCostModifier(double factor) {
    this.factor = factor;
  }

  @Override
  public double linkCostFactor(int lon0, int lat0, OsmNode sourceNode, OsmTransferNode transferNodes, OsmNode targetNode) {
    return factor;
  }
}
