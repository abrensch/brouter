package btools.router;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import btools.util.CheapRuler;

/**
 * Round-trip-only model of the dense (town/city) areas of a region, used by GREEDY via-steering to
 * keep planned waypoints out of built-up cores. Holds the dense-area polygons and answers point-in-area
 * queries; built from the coarse desirability grid by {@link #fromDesirabilityGrid}. This deliberately
 * lives in the round-trip code path and is never referenced by the general per-segment cost engine.
 */
public final class DenseAreaMap {

  private final List<OsmNogoPolygon> boxes;

  DenseAreaMap(List<OsmNogoPolygon> boxes) {
    this.boxes = boxes;
  }

  /** True if (ilon,ilat) falls inside any dense box (bounding-circle pre-filter, then exact point-in-polygon). */
  public boolean contains(int ilon, int ilat) {
    for (OsmNogoPolygon b : boxes) {
      if (CheapRuler.distance(ilon, ilat, b.ilon, b.ilat) < b.radius && b.isWithin(ilon, ilat)) {
        return true;
      }
    }
    return false;
  }

  /** Number of dense boxes (for logging). */
  public int size() {
    return boxes.size();
  }

  /**
   * Build a dense-area map from the coarse desirability grid (cell {@code -> [nodeCount, ...]}). Cells
   * whose node-count clears the percentile/absolute-floor threshold are clustered into 4-connected
   * components, oversized blobs are split into town-sized tiles (no city-wide mega-box), and each
   * component becomes a bounding polygon. Returns {@code null} if no dense area emerges, so callers can
   * leave via-steering off with a single null check.
   */
  public static DenseAreaMap fromDesirabilityGrid(Map<Long, double[]> grid, int cellSize,
                                                  double densePercentile, int minDenseNodes,
                                                  int minCells, int maxCells, int tileCells) {
    if (grid == null || grid.isEmpty()) {
      return null;
    }
    Set<Long> dense = CapsuleNogoBuilder.classifyDense(grid, densePercentile, minDenseNodes);
    List<Set<Long>> comps =
      CapsuleNogoBuilder.splitOversized(CapsuleNogoBuilder.components(dense, minCells), maxCells, tileCells);
    List<OsmNogoPolygon> polys = new ArrayList<>();
    for (Set<Long> comp : comps) {
      for (OsmNodeNamed n : CapsuleNogoBuilder.polygonsFromCells(comp, cellSize, 1.0, minCells)) {
        if (n instanceof OsmNogoPolygon) {
          polys.add((OsmNogoPolygon) n);
        }
      }
    }
    return polys.isEmpty() ? null : new DenseAreaMap(polys);
  }
}

