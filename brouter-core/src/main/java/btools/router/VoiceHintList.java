/**
 * Container for a voice hint
 * (both input- and result data for voice hint processing)
 *
 * @author ab
 */
package btools.router;

import java.util.ArrayList;
import java.util.List;

public class VoiceHintList
{
  private String transportMode;
  int turnInstructionMode;
  ArrayList<VoiceHint> list = new ArrayList<VoiceHint>();

  public void setTransportMode( boolean isCar )
  {
    transportMode = isCar ? "car" : "bike";
  }

  public String getTransportMode()
  {
    return transportMode;
  }

  public int getLocusRouteType()
  {
    if ( "car".equals( transportMode ) )
    {
      return 4;
    }
    if ( "bike".equals( transportMode ) )
    {
      return 5;
    }
    return 5; // ??
  }
}
