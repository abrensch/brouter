/**
 * Information on matched way point
 *
 * @author ab
 */
package btools.memrouter;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.StringTokenizer;



final class TrainSchedule
{
	private static class TrainScheduleCron
	{
	  long minutes;
	  long hours;
	  long dows;
	
	  boolean isNegative;
	  
	  String cronSource;

      TrainScheduleCron( String value )
      {
		StringTokenizer tk = new StringTokenizer( value, "_" );
		minutes = parseElement( tk.nextToken() );
		hours   = parseElement( tk.nextToken() );
		dows     = parseElement( tk.nextToken() );
		
		cronSource = value;
      }
      
  	private long parseElement( String s )
  	{
  	  if ( "*".equals( s ) ) return Long.MAX_VALUE;
  	  StringTokenizer tk = new StringTokenizer( s, "," );
  	  long res = 0;
  	  while( tk.hasMoreTokens() )
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
  		  end = Integer.parseInt( sub.substring( idx + 1) );
  		}
  	    for( int i=start; i <= end ; i++ )
  	    {
  	      res |= (1L<<i);
  	    }
  	  }
  	  return res;
  	}

  	boolean matches( int minute, int hour, int dow )
  	{
	  return ( (1L << minute) & minutes ) != 0
	      && ( (1L << hour) & hours ) != 0
	      && ( (1L << dow) & dows ) != 0;
	}
	}
	
	private List<TrainScheduleCron> cronsPositive;
	private List<TrainScheduleCron> cronsNegative;
	
	public TrainSchedule( String cronstring )
	{
		StringTokenizer tk = new StringTokenizer( cronstring, " " );
		
		cronsPositive = new ArrayList<TrainScheduleCron>();
		cronsNegative = new ArrayList<TrainScheduleCron>();
		
		while ( tk.hasMoreTokens() )
		{
			String sign = tk.nextToken();
			String value = tk.nextToken();
            TrainScheduleCron cron = new TrainScheduleCron( value );
            if ( "+".equals( sign ) )
            {
              cronsPositive.add( cron );    
            }
            else if ( "-".equals( sign ) )
            {
              cronsNegative.add( cron );    
            }
            else throw new IllegalArgumentException( "invalid cron sign: " + sign );
		}
	}
		


	public int getMinutesToNext( long timeFrom )
	{
		Calendar cal = Calendar.getInstance();
		cal.setTime( new Date( timeFrom ) );
		int minute = cal.get( Calendar.MINUTE );
		int hour = cal.get( Calendar.HOUR_OF_DAY );
		int dow = cal.get( Calendar.DAY_OF_WEEK );
		dow = dow > 1 ? dow -1 : dow+6; 
		
		for( int cnt=0; cnt < 10080; cnt++ )
		{
	      boolean veto = false;
		  for( TrainScheduleCron cron : cronsNegative )
		  {
		    if ( cron.matches( minute,  hour,  dow ) )
		    {
		    	veto = true;
		    	break;
		    }
		  }
		  if ( !veto )
		  {
		    for( TrainScheduleCron cron : cronsPositive )
		    {
		      if ( cron.matches( minute,  hour,  dow ) ) return cnt;
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
}
