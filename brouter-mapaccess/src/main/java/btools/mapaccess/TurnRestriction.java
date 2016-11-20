/**
 * Container for a turn restriction
 *
 * @author ab
 */
package btools.mapaccess;

public final class TurnRestriction
{
  public boolean isPositive;

  public int fromLon;
  public int fromLat;

  public int toLon;
  public int toLat;

  public TurnRestriction next;
  
  @Override
  public String toString()
  {
    return "pos=" + isPositive + " fromLon=" + fromLon + " fromLat=" + fromLat + " toLon=" + toLon + " toLat=" + toLat;
  }
    
}
