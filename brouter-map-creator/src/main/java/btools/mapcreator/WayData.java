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
 * Container for waydata on the preprocessor level
 *
 * @author ab
 */
public class WayData extends MapCreatorBase
{
  public long wid;
  public long description;
  public LongList nodes;

  public WayData( long id  )
  {
    wid = id;
    nodes = new LongList( 16 );
  }

  public WayData( long id, LongList nodes  )
  {
    wid = id;
    this.nodes = nodes;
  }

  public WayData( DataInputStream di ) throws Exception
  {
    nodes = new LongList( 16 );
    wid = readId( di) ;
    description = di.readLong();
    for (;;)
    {
      long nid = readId( di );
      if ( nid == -1 ) break;
      nodes.add( nid );
    }
  }    

  public void writeTo( DataOutputStream dos ) throws Exception  
  {
    writeId( dos, wid );
    dos.writeLong( description );
    int size = nodes.size();
    for( int i=0; i < size; i++ )
    {
      writeId( dos, nodes.get( i ) );
    }
    writeId( dos, -1 ); // stopbyte
  }
}
