package btools.mapsplitter;

import java.io.DataInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import btools.util.LongList;

/**
 * Container for relation data on the preprocessor level
 *
 * @author ab
 */
public class RelationData extends MapCreatorBase
{
  public long rid;
  public LongList ways;
  public List<String> roles;

  public RelationData( long id, LongList ways, List<String> roles )
  {
    rid = id;
    this.ways = ways;
    this.roles = roles;
  }

  public RelationData( DataInputStream di ) throws Exception
  {
    ways = new LongList( 16 );
    roles = new ArrayList<String>();
    rid = readId( di) ;
    for (;;)
    {
      String key = di.readUTF();
      if ( key.length() == 0 ) break;
      String value = di.readUTF();
      putTag( key, value );
    }
    for (;;)
    {
      long wid = readId( di );
      if ( wid == -1 ) break;
      ways.add( wid );
      roles.add( di.readUTF() );
    }
  }    

  public void writeTo( java.io.DataOutputStream dos ) throws Exception  
  {
    writeId( dos, rid );
    if ( getTagsOrNull() != null )
    {
      for( Map.Entry<String,String> me : getTagsOrNull().entrySet() )
      {
        if ( me.getKey().length() > 0 )
        {
          dos.writeUTF( me.getKey() );
          dos.writeUTF( me.getValue() );
        }
      }
    }
    dos.writeUTF( "" );
    
    int size = ways.size();
    for( int i=0; i < size; i++ )
    {
      writeId( dos, ways.get( i ) );
      dos.writeUTF( roles.get(i) );
    }
    writeId( dos, -1 ); // stopbyte
  }

}
