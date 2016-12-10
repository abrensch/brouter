package btools.mapcreator;

import java.io.DataInputStream;
import java.io.DataOutputStream;

import btools.util.LongList;

/**
 * Container for a turn restriction
 *
 * @author ab
 */
public class RestrictionData extends MapCreatorBase
{
  public boolean isPositive;
  public short exceptions;
  public long fromWid;
  public long toWid;
  public long viaNid;
  public RestrictionData next;

  public int fromLon;
  public int fromLat;
  
  public int toLon;
  public int toLat;

  public RestrictionData()
  {
  }

  public RestrictionData( DataInputStream di ) throws Exception
  {
    isPositive = di.readBoolean();
    exceptions = di.readShort();
    fromWid = readId( di );
    toWid = readId( di );
    viaNid = readId( di );
  }

  public void writeTo( DataOutputStream dos ) throws Exception  
  {
    dos.writeBoolean( isPositive );
    dos.writeShort( exceptions );
    writeId( dos, fromWid );
    writeId( dos, toWid );
    writeId( dos, viaNid );
  }
}
