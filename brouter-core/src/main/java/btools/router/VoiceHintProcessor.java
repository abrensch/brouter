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
  private static float sumNonConsumedWithin40( List<VoiceHint> inputs, int offset )
  {
    double distance = 0.;
    float angle = 0.f;
    while( offset >= 0 && distance < 40. )
    {
      VoiceHint input = inputs.get( offset-- );
      if ( input.turnAngleConsumed )
      {
        break;
      }
      angle += input.goodWay.turnangle;
      distance += input.goodWay.linkdist;
      input.turnAngleConsumed = true;
    }
    return angle;
  }


  public static List<VoiceHint> process( List<VoiceHint> inputs )
  {
    List<VoiceHint> results = new ArrayList<VoiceHint>();
    double distance = 0.;
    float roundAboutTurnAngle = 0.f; // sums up angles in roundabout

    int roundaboutExit = 0;

    for ( int hintIdx = 0; hintIdx < inputs.size(); hintIdx++ )
    {
      VoiceHint input = inputs.get( hintIdx );

      float turnAngle = input.goodWay.turnangle;
      distance += input.goodWay.linkdist;
      int currentPrio = input.goodWay.getPrio();
      int oldPrio = input.oldWay == null ? currentPrio : input.oldWay.getPrio();
      int minPrio = Math.min( oldPrio, currentPrio );

      // odd priorities are link-types
      boolean isLink2Highway = ( ( oldPrio & 1 ) == 1 ) && ( ( currentPrio & 1 ) == 0 );

      boolean inRoundabout = input.oldWay != null && input.oldWay.roundaboutdirection != 0;
      if ( inRoundabout )
      {
        roundAboutTurnAngle += sumNonConsumedWithin40( inputs, hintIdx );
        boolean isExit = roundaboutExit == 0; // exit point is always exit
        if ( input.badWays != null )
        {
          for ( MessageData badWay : input.badWays )
          {
            if ( badWay.onwaydirection >= 0 && badWay.isGoodForCars() && Math.abs( badWay.turnangle ) < 120. )
            {
              isExit = true;
            }
          }
        }
        if ( isExit )
        {
          roundaboutExit++;
        }
        continue;
      }
      if ( roundaboutExit > 0 )
      {
        roundAboutTurnAngle += sumNonConsumedWithin40( inputs, hintIdx );
        input.angle = roundAboutTurnAngle;
        input.distanceToNext = distance;
        input.roundaboutExit = turnAngle < 0  ? -roundaboutExit : roundaboutExit;
        distance = 0.;
        results.add( input );
        roundAboutTurnAngle = 0.f;
        roundaboutExit = 0;
        continue;
      }
      int maxPrioAll = -1; // max prio of all detours
      int maxPrioCandidates = -1; // max prio of real candidates

      float maxAngle = -180.f;
      float minAngle = 180.f;
      if ( input.badWays != null )
      {
        for ( MessageData badWay : input.badWays )
        {
          int badPrio = badWay.getPrio();
          boolean badOneway = badWay.onwaydirection < 0;

          boolean isHighway2Link = ( ( badPrio & 1 ) == 1 ) && ( ( currentPrio & 1 ) == 0 );

          if ( badPrio > maxPrioAll && !isHighway2Link )
          {
            maxPrioAll = badPrio;
          }

          if ( badPrio < minPrio )
          {
            continue; // ignore low prio ways
          }

          if ( badOneway )
          {
            continue; // ignore wrong oneways
          }

          float badTurn = badWay.turnangle;
          if ( Math.abs( badTurn ) - Math.abs( turnAngle ) > 80.f )
          {
            continue; // ways from the back should not trigger a slight turn
          }

          if ( badPrio > maxPrioCandidates )
          {
            maxPrioCandidates = badPrio;
          }
          if ( badTurn > maxAngle )
          {
            maxAngle = badTurn;
          }
          if ( badTurn < minAngle )
          {
            minAngle = badTurn;
          }
        }
      }

      // unconditional triggers are all junctions with
      // - higher detour prios than the minimum route prio (except link->highway junctions)
      // - or candidate detours with higher prio then the route exit leg
      boolean unconditionalTrigger = ( maxPrioAll > minPrio && !isLink2Highway ) || ( maxPrioCandidates > currentPrio );

      // conditional triggers (=real turning angle required) are junctions
      // with candidate detours equal in priority than the route exit leg
      boolean conditionalTrigger = maxPrioCandidates >= minPrio;

      if ( unconditionalTrigger || conditionalTrigger )
      {
        input.angle = turnAngle;
        input.calcCommand();
        boolean isStraight = input.cmd == VoiceHint.C;
        input.needsRealTurn = (!unconditionalTrigger) && isStraight;

        // check for KR/KL
        if ( maxAngle < turnAngle && maxAngle > turnAngle - 45.f - (turnAngle > 0.f ? turnAngle : 0.f ) )
        {
          input.cmd = VoiceHint.KR;
        }
        if ( minAngle > turnAngle && minAngle < turnAngle + 45.f - (turnAngle < 0.f ? turnAngle : 0.f ) )
        {
          input.cmd = VoiceHint.KL;
        }

        input.angle = sumNonConsumedWithin40( inputs, hintIdx );
        input.distanceToNext = distance;
        distance = 0.;
        results.add( input );
      }
      if ( results.size() > 0 && distance < 40. )
      {
        results.get( results.size()-1 ).angle += sumNonConsumedWithin40( inputs, hintIdx );
      }
    }

    // go through the hint list again in reverse order (=travel direction)
    // and filter out non-signficant hints and hints too close to it's predecessor

    List<VoiceHint> results2 = new ArrayList<VoiceHint>();
    int i = results.size();
    while( i > 0 )
    {
      VoiceHint hint = results.get(--i);
      if ( hint.cmd == 0 )
      {
        hint.calcCommand();
      }
      if ( ! ( hint.needsRealTurn && hint.cmd == VoiceHint.C ) )
      {
        double dist = hint.distanceToNext;
        // sum up other hints within 40m
        while( dist < 40. && i > 0 )
        {
          VoiceHint h2 = results.get(i-1);
          dist = h2.distanceToNext;
          hint.distanceToNext+= dist;
          hint.angle += h2.angle;
          i--;
          if ( h2.isRoundabout() ) // if we hit a roundabout, use that as the trigger
          {
            h2.angle = hint.angle;
            hint = h2;
            break;
          }
        }
        hint.calcCommand();
        results2.add( hint );
      }
    }
    return results2;
  }

}
