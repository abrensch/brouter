package btools.mapcreator;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashMap;

import btools.util.*;

/**
 * Container for node data on the preprocessor level
 *
 * @author ab
 */
public class NodeData extends MapCreatorBase
{
  public long nid;
  public int ilon;
  public int ilat;
  public long description;
  public short selev = Short.MIN_VALUE;

  public NodeData( long id, double lon, double lat )
  {
    nid = id;
    ilat = (int)( ( lat + 90. )*1000000. + 0.5);
    ilon = (int)( ( lon + 180. )*1000000. + 0.5);
  }

  public NodeData( DataInputStream dis ) throws Exception
  {
    nid = readId( dis );
    ilon = dis.readInt();
    ilat = dis.readInt();
    int mode = dis.readByte();
    if ( ( mode & 1 ) != 0 ) description = dis.readLong();
    if ( ( mode & 2 ) != 0 ) selev = dis.readShort();
  }    

  public void writeTo( DataOutputStream dos ) throws Exception  
  {
    writeId( dos, nid );
    dos.writeInt( ilon );
    dos.writeInt( ilat );
    int mode = ( description == 0L ? 0 : 1 ) | ( selev == Short.MIN_VALUE ? 0 : 2 );
    dos.writeByte( (byte)mode );
    if ( ( mode & 1 ) != 0 ) dos.writeLong( description );
    if ( ( mode & 2 ) != 0 ) dos.writeShort( selev );
  }
}
