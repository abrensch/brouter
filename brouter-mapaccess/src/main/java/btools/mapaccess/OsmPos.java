/**
 * Interface for a position (OsmNode or OsmPath)
 *
 * @author ab
 */
package btools.mapaccess;


public interface OsmPos
{
  public int getILat();

  public int getILon();

  public short getSElev();

  public double getElev();

  public int calcDistance( OsmPos p );

  public long getIdFromPos();

}
