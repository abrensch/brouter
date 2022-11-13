/**
 * Interface for a position (OsmNode or OsmPath)
 *
 * @author ab
 */
package btools.mapaccess;


public interface OsmPos {
  int getILat();

  int getILon();

  short getSElev();

  double getElev();

  int calcDistance(OsmPos p);

  long getIdFromPos();

}
