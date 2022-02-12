package btools.router;

import java.util.Map;

public class SuspectInfo
{
  public static final int TRIGGER_DEAD_END    = 1;
  public static final int TRIGGER_DEAD_START  = 2;
  public static final int TRIGGER_NODE_BLOCK  = 4;
  public static final int TRIGGER_BAD_ACCESS  = 8;
  public static final int TRIGGER_UNK_ACCESS  = 16;
  public static final int TRIGGER_SHARP_EXIT  = 32;
  public static final int TRIGGER_SHARP_ENTRY = 64;
  public static final int TRIGGER_SHARP_LINK  = 128;
  public static final int TRIGGER_BAD_TR      = 256;

  public int prio;
  public int triggers;
  
  public static void addSuspect( Map<Long,SuspectInfo> map, long id, int prio, int trigger )
  {
    Long iD = Long.valueOf( id );
    SuspectInfo info = map.get( iD );
    if ( info == null )
    {
      info = new SuspectInfo();
      map.put( iD, info );
    }
    info.prio = Math.max( info.prio, prio );
    info.triggers |= trigger;
  }
  
  public static SuspectInfo addTrigger( SuspectInfo old, int prio, int trigger )
  {
    if ( old == null )
    {
      old = new SuspectInfo();
    }
    old.prio = Math.max( old.prio, prio );
    old.triggers |= trigger;
    return old;
  }
  
}
