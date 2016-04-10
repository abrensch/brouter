/**
 * Container for a voice hint
 * (both input- and result data for voice hint processing)
 *
 * @author ab
 */
package btools.router;

import java.util.ArrayList;
import java.util.List;

public class VoiceHint
{
  int ilon;
  int ilat;
  String message;
  String symbol;
  int locusAction;
  MessageData goodWay;
  List<MessageData> badWays;
  double distanceToNext;
  int locusRouteType;
  int turnInstructionMode;
  
  public void addBadWay( MessageData badWay )
  {
    if ( badWay == null )
    {
      return;
    }
    if ( badWays == null )
    {
      badWays = new ArrayList<MessageData>();
    }
    badWays.add( badWay );
  }
  
  public boolean setTurnAngle( float angle )
  {
    if ( angle < -165. || angle > 165. )
    {
      symbol = "TU";
      message = "u-turn";
      locusAction = 12;
    }
    else if ( angle < -115. )
    {
      symbol = "TSHL";
      message = "sharp left";
      locusAction = 5;
    }
    else if ( angle < -65. )
    {
      symbol = "Left";
      message = "left";
      locusAction = 4;
    }
    else if ( angle < -15. )
    {
      symbol = "TSLL";
      message = "slight left";
      locusAction = 3;
    }
    else if ( angle < 15. )
    {
      symbol = "Straight";
      message = "straight";
      locusAction = 1;
      return false;
    }
    else if ( angle < 65. )
    {
      symbol = "TSLR";
      message = "slight right";
      locusAction = 6;
    }
    else if ( angle < 115. )
    {
      symbol = "Right";
      message = "right";
      locusAction = 7;
    }
    else
    {
      symbol = "TSHR";
      message = "sharp right";
      locusAction = 8;
    }
    return true;
  }
}
