package btools.server;

import java.util.HashMap;
import java.util.Map;

public class IpAccessMonitor 
{
  private static Object sync = new Object();
  private static HashMap<String,Long> ipAccess = new HashMap<String,Long>();
  private static long MAX_IDLE = 900000; // 15 minutes
  
  public static boolean touchIpAccess( String ip )
  {
    long t = System.currentTimeMillis();
    synchronized( sync )
    {
      Long lastTime = ipAccess.get( ip );
      if ( lastTime == null || t - lastTime.longValue() > MAX_IDLE )
      {
        ipAccess.put( ip, Long.valueOf( t ) );
        cleanup(t);
        return true; // new session detected
      }
      ipAccess.put( ip, Long.valueOf( t ) ); // touch existing session
      return false;
    }
  }
  
  private static void cleanup( long t )
  {
    HashMap<String,Long> newMap = new HashMap<String,Long>(ipAccess.size());
    for( Map.Entry<String,Long> e : ipAccess.entrySet() )
    {
      if ( t - e.getValue().longValue() <= MAX_IDLE )
      {
        newMap.put( e.getKey(), e.getValue() );
      }
    }
    ipAccess = newMap;
  }
    
}
