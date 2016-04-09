/**
 * Processor for Voice Hints
 *
 * @author ab
 */
package btools.router;

import java.util.ArrayList;
import java.util.List;

public final class VoiceHintProcessor
{

  public static List<VoiceHint> process( List<VoiceHint> inputs )
  {
    List<VoiceHint> results = new ArrayList<VoiceHint>();
    for ( VoiceHint input : inputs )
    {
//      System.out.println( "***** processing: " + input.ilat + " " + input.ilon + " goodWay=" + input.goodWay );
      if ( input.badWays != null )
      {
        float maxprio = 0.f;
        for ( MessageData badWay : input.badWays )
        {
//          System.out.println( " --> badWay: " + badWay );
          if ( badWay.priorityclassifier > maxprio )
          {
            maxprio = badWay.priorityclassifier;
          }
        }
        if ( maxprio > 0. && maxprio >= input.goodWay.priorityclassifier )
        {
          boolean isTurn = input.setTurnAngle( input.goodWay.turnangle );
          if ( isTurn || input.goodWay.priorityclassifier < maxprio )
          {
            results.add( input );
          }
        }
      }
    }
    return results;
  }

}
