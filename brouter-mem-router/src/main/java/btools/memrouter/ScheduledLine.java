/**
 * A train line as a common set of stations with many departures
 *
 * @author ab
 */
package btools.memrouter;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class ScheduledLine
{
  String name;
  List<Integer> offsetMinutes = new ArrayList<Integer>();
  TrainSchedule schedule;

  /**
   * get a list of departures relative to the start-time plus the individual
   * offsets according to the offset mask
   * 
   * departures with the same wait-time are aggregated in one result element
   * with multiple 1-bits in the offset mask
   * 
   * departures with different wait-times are returned as separate items
   * 
   * @param id
   *          the value to add to this set.
   * @return true if "id" already contained in this set.
   */
  public List<ScheduledDeparture> getScheduledDepartures( int idx, long timeFrom, OffsetSet offsets )
  {
    List<ScheduledDeparture> result = new ArrayList<ScheduledDeparture>();

    long minutesFrom = ( timeFrom + 59999L ) / 60000L;
    long timeFromCorrection = minutesFrom * 60000L - timeFrom;

    if ( idx < 0 || idx >= offsetMinutes.size() - 1 )
      return result;

    int offsetStart = offsetMinutes.get( idx ).intValue();
    int offsetEnd = offsetMinutes.get( idx + 1 ).intValue();

    Map<Integer, List<Integer>> waitOffsets = getDepartures( offsetStart, timeFrom + timeFromCorrection, offsets );

    for ( Map.Entry<Integer, List<Integer>> e : waitOffsets.entrySet() )
    {
      ScheduledDeparture depart = new ScheduledDeparture();
      depart.waitTime = e.getKey().intValue() * 60000L + timeFromCorrection;
      depart.offsets = offsets.create( e.getValue() );
      depart.rideTime = ( offsetEnd - offsetStart ) * 60000L;
      result.add( depart );
    }
    return result;
  }

  private Map<Integer, List<Integer>> getDepartures( int offsetStart, long timeFrom, OffsetSet offsets )
  {
    Map<Integer, List<Integer>> waitOffsets = new HashMap<Integer, List<Integer>>();
    int size = offsets.size();

    for ( int offset = 0;; )
    {
      // skip to next offset bit
      while (offset < size && !offsets.contains( offset ))
      {
        offset++;
      }
      if ( offset >= size )
        return waitOffsets;

      int toNext = schedule.getMinutesToNext( timeFrom + 60000L * ( offset - offsetStart ) );

      if ( toNext < 0 )
        return waitOffsets;
      int departOffset = offset + toNext;

      // whats the closest offset within the next toNext minutes
      int lastOffset = offset;
      while (toNext-- >= 0 && offset < size)
      {
        if ( offsets.contains( offset ) )
        {
          lastOffset = offset;
        }
        offset++;
      }

  //    if ( lastOffset == size - 1 )
  //      return waitOffsets; // todo?

      int waitTime = departOffset - lastOffset;

      // if we have that wait time in the list, just add the offset bit
      List<Integer> offsetList = waitOffsets.get( Integer.valueOf( waitTime ) );
      if ( offsetList == null )
      {
        offsetList = new ArrayList<Integer>();
        waitOffsets.put( Integer.valueOf( waitTime ), offsetList );
      }
      offsetList.add( Integer.valueOf( lastOffset ) );
    }
  }
}
