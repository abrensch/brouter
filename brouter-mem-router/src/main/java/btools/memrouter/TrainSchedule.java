/**
 * Information on matched way point
 *
 * @author ab
 */
package btools.memrouter;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;

final class TrainSchedule
{
  private static class TrainScheduleCron
  {
    long minutes;
    long hours;
    long dows;

    TrainScheduleCron( String value )
    {
      StringTokenizer tk = new StringTokenizer( value, "_" );
      minutes = parseElement( tk.nextToken() );
      hours = parseElement( tk.nextToken() );
      dows = parseElement( tk.nextToken() );
    }

    private long parseElement( String s )
    {
      if ( "*".equals( s ) )
      {
        return Long.MAX_VALUE;
      }
      StringTokenizer tk = new StringTokenizer( s, "," );
      long res = 0;
      while (tk.hasMoreTokens())
      {
        String sub = tk.nextToken();
        int start, end;
        int idx = sub.indexOf( '-' );
        if ( idx < 0 )
        {
          start = Integer.parseInt( sub );
          end = start;
        }
        else
        {
          start = Integer.parseInt( sub.substring( 0, idx ) );
          end = Integer.parseInt( sub.substring( idx + 1 ) );
        }
        for ( int i = start; i <= end; i++ )
        {
          res |= ( 1L << i );
        }
      }
      return res;
    }

    boolean matches( int minute, int hour, int dow )
    {
      return ( ( 1L << minute ) & minutes ) != 0 && ( ( 1L << hour ) & hours ) != 0 && ( ( 1L << dow ) & dows ) != 0;
    }
  }

  private List<TrainScheduleCron> cronsPositive;
  private List<TrainScheduleCron> cronsNegative;
  private Map<String,List<Integer>> timeTable = new HashMap<String,List<Integer>>();

  public TrainSchedule( String cronstring )
  {
    StringTokenizer tk = new StringTokenizer( cronstring, " " );

    cronsPositive = new ArrayList<TrainScheduleCron>();
    cronsNegative = new ArrayList<TrainScheduleCron>();

    while (tk.hasMoreTokens())
    {
      String prefix = tk.nextToken();
      String value = tk.nextToken();
      if ( "+".equals( prefix ) )
      {
        cronsPositive.add( new TrainScheduleCron( value ) );
      }
      else if ( "-".equals( prefix ) )
      {
        cronsNegative.add( new TrainScheduleCron( value ) );
      }
      else
      {
        // prefix is a calendar id, value a slash-separated time list
        List<Integer> times = new ArrayList<Integer>();
        StringTokenizer tk3 = new StringTokenizer( value, "/" );
        while( tk3.hasMoreTokens() )
        {
          times.add( Integer.valueOf( time2Minute( tk3.nextToken() ) ) );
        }
        Collections.sort( times );
        timeTable.put( prefix, times );
      }
    }
  }
  
  private static int time2Minute( String s )
  {
    StringTokenizer tk = new StringTokenizer( s, ":" );
    int mins = 60 * Integer.parseInt( tk.nextToken() );
    mins += Integer.parseInt( tk.nextToken() );
    return mins;
  }

  public int getMinutesToNext( long timeFrom )
  {
    if ( timeTable.isEmpty() )
    {
      return getMinutesToNextCron( timeFrom );
    }
    Calendar cal = Calendar.getInstance();
    cal.setTime( new Date( timeFrom ) );
    int minute = cal.get( Calendar.MINUTE );
    int hour = cal.get( Calendar.HOUR_OF_DAY );
    
    int year  = cal.get( Calendar.YEAR );
    int month = cal.get( Calendar.MONTH ) + 1;
    int day   = cal.get( Calendar.DAY_OF_MONTH );

    String sday = "" + ( year*10000 + month * 100 + day );
    
    List<Integer> alltimes = null;
    boolean listIsCopy = false;
    for( String calId : timeTable.keySet() )
    {
      if ( calendarHasDate( calId, sday ) )
      {      
        List<Integer> times = timeTable.get( calId );
        if ( alltimes == null )
        {
          alltimes = times;
        }
        else
        {
          if ( !listIsCopy )
          {
            alltimes = new ArrayList<Integer>( alltimes );
            listIsCopy = true;
          }
          alltimes.addAll( times );
        }
      }
    }
    if ( alltimes == null ) return -1;
    if ( listIsCopy )
    {
      Collections.sort( alltimes );
    }
    
    int mins = 60*hour + minute;
    for( Integer t : alltimes )
    {
      if ( t.intValue() >= mins )
      {
        return t.intValue() - mins;
      }
    }
    return -1;
  }

  private int getMinutesToNextCron( long timeFrom )
  {
  
    Calendar cal = Calendar.getInstance();
    cal.setTime( new Date( timeFrom ) );
    int minute = cal.get( Calendar.MINUTE );
    int hour = cal.get( Calendar.HOUR_OF_DAY );
    int dow = cal.get( Calendar.DAY_OF_WEEK );
    dow = dow > 1 ? dow - 1 : dow + 6;

    for ( int cnt = 0; cnt < 180; cnt++ )
    {
      boolean veto = false;
      for ( TrainScheduleCron cron : cronsNegative )
      {
        if ( cron.matches( minute, hour, dow ) )
        {
          veto = true;
          break;
        }
      }
      if ( !veto )
      {
        for ( TrainScheduleCron cron : cronsPositive )
        {
          if ( cron.matches( minute, hour, dow ) )
            return cnt;
        }
      }

      if ( ++minute == 60 )
      {
        minute = 0;
        if ( ++hour == 24 )
        {
          hour = 0;
          if ( ++dow == 8 )
          {
            dow = 1;
          }
        }
      }
    }
    return -1;
  }
  
  private static Map<String,Set<String>> calendarMap;

  private static boolean calendarHasDate( String calId, String date )
  {
    if ( calendarMap == null )
    {
      try
      {
        readCalendar();
      }
      catch (Exception e)
      {
        throw new RuntimeException( "cannot readcalendar: " + e );
      }
    }
    Set<String> idSet = calendarMap.get( calId );
    if ( idSet != null )
    {
      return idSet.contains( date );
    }
    return true;
  }
  
  private static void readCalendar() throws Exception
  {
System.out.println( "reading calendar..." );
    calendarMap = new HashMap<String,Set<String>>();
  
    Map<String,String> uniqueMap = new HashMap<String,String>();
  
    BufferedReader br = new BufferedReader( new InputStreamReader( new FileInputStream( new File( "../calendar_dates.txt" ) ), "UTF-8" ) );
    br.readLine(); // skip header

    for ( ;; )
    {
      String line = br.readLine();
      if ( line == null ) break;
      StringTokenizer tk = new StringTokenizer( line, "," );
      String calId = tk.nextToken();
      String calDate = tk.nextToken();
      
      Set<String> idSet = calendarMap.get( calId );
      if ( idSet == null )
      {
        idSet = new HashSet<String>();
        calendarMap.put( calId, idSet );
      }
      String uniqueDate = uniqueMap.get( calDate );
      if ( uniqueDate == null )
      {
        uniqueDate = calDate;
        uniqueMap.put( uniqueDate, uniqueDate );
      }
      idSet.add( uniqueDate );
    }
    br.close();
System.out.println( "read calendar with " + calendarMap.size() + " ids" );
  }
}
