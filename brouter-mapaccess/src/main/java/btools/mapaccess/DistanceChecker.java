/**
 * Container for routig configs
 *
 * @author ab
 */
package btools.mapaccess;

public interface DistanceChecker
{
  /**
   * Checks whether the given path is within a maximum distance
   * known to the distance checker
   * @return true if close enough
   */
  boolean isWithinRadius( int ilon0, int ilat0, OsmTransferNode firstTransfer, int ilon1, int ilat1 );
}
