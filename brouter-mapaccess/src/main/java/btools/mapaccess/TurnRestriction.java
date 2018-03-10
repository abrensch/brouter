/**
 * Container for a turn restriction
 *
 * @author ab
 */
package btools.mapaccess;

public final class TurnRestriction
{
  public boolean isPositive;
  public short exceptions;

  public int fromLon;
  public int fromLat;

  public int toLon;
  public int toLat;

  public TurnRestriction next;
  
  public boolean exceptBikes()
  {
    return ( exceptions & 1 ) != 0;
  }

  public boolean exceptMotorcars()
  {
    return ( exceptions & 2 ) != 0;
  }

  @Override
  public String toString()
  {
    return "pos=" + isPositive + " fromLon=" + fromLon + " fromLat=" + fromLat + " toLon=" + toLon + " toLat=" + toLat;
  }
    
}
