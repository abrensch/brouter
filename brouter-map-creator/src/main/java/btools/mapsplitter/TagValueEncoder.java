package btools.mapsplitter;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Encoder for the tag-value statistics
 *
 * @author ab
 */
public class TagValueEncoder
{
  HashMap<String,Tag> tags = new HashMap<String,Tag>();
  ArrayList<TagGroup> groups = new ArrayList<TagGroup>();
  
  ByteArrayOutputStream baos ;
  DataOutputStream dos;

  ArrayList<String> stringList;
  HashMap<String,Integer> stringMap;
  ArrayList<Tag> taglist;
  
  private int setId = 0;
  private int nextIdx = 0;

  private int pass;

  private static String[][] taggroups = new String[][] {
      { "highway", "name", "maxspeed", "lanes", "service", "tracktype", "surface" }
    , { "access", "foot", "bicycle", "motorcar", "motor_vehicle", "motorcycle", "vehicle" }
    , { "building", "addr:street", "addr:housenumber", "addr:city", "addr:postcode", "addr:housename" }
  };

  private void encodeString( BitWriteBuffer bc, String s )
  {
    Integer ii = stringMap.get( s );
    bc.encodeBit( ii == null );
    if ( ii == null )
    {
      try
      {
        byte[] textBytes = s.getBytes( "UTF8" );
        bc.encodeInt( textBytes.length );
        dos.write( textBytes );
      }
      catch( Exception e )
      {
        throw new RuntimeException( e );
      }
      ii = Integer.valueOf( stringList.size() );
      stringList.add( s );
      stringMap.put( s, ii );
      return;
    }
    bc.encodeBounded( stringList.size()-1, ii.intValue() );
  }

  private class TagGroup implements Comparable<TagGroup>
  {
    String[] names;
    int count;
    
    int lastSetId = 0;
    
    void incCount()
    {
      if ( setId != lastSetId ) count++;
      lastSetId = setId;
    }

    TagGroup( String[] names )
    {
      this.names = names;
      for( String name : names )
      {
        tags.put( name, new Tag( name, this ) );
      }
      groups.add( this );
    }
    
    void indexTags()
    {
      for( String name : names )
      {
        Tag t = tags.get( name );
        if ( t.count > 0 ) t.idx = nextIdx++;
      }
    }    
    
    @Override
    public int compareTo( TagGroup g )
    {
      return g.count - count;
    }
  }
  
  public TagValueEncoder()
  {
    for( String[] names : taggroups )
    {
      new TagGroup( names );
    }
  }
  
  public class Tag implements Comparable<Tag>
  {
    Tag( String name, TagGroup group )
    {
      this.name = name;
      this.group = group;
    }
    String name;
    int count;
    int idx;

    private Object tree;

    HashMap<String,Value> values = new HashMap<String,Value>();
    
    List<Value> valueList;
    TagGroup group;
    
    void addValue( String value )
    {
      Value v = values.get( value );
      if ( v == null )
      {
        v = new Value( value );
        values.put( value, v );
      }
      v.frequency++;
      count++;
    }
    
    public void encodeDictionary( BitWriteBuffer bc ) throws IOException
    {
      encodeString( bc, name );

      PriorityQueue<Value> queue = new PriorityQueue<Value>( values.size() );
      queue.addAll( values.values() );
      while (queue.size() > 1)
      {
        queue.add( new Value( queue.poll(), queue.poll() ) );
      }
      queue.poll().encodeTree( bc, 1, 0 );
    }

    @Override
    public int compareTo( Tag t )
    {
      return idx - t.idx;
    }
  }

  private class Value implements Comparable<Value>
  {
    Value( String value )
    {
      this.value = value;
    }  
    Value( Value c1, Value c2 )
    {
      child1 = c1;
      child2 = c2;
      frequency = c1.frequency + c2.frequency;
    }  
    String value;
    int code;
    int range;
    Value child1;
    Value child2;
    int frequency;

    void encodeTree( BitWriteBuffer bc, int range, int code ) throws IOException
    {
      this.range = range;
      this.code = code;
      boolean isNode = child1 != null;
      bc.encodeBit( isNode );
      if ( isNode )
      {
        child1.encodeTree( bc, range << 1, code );
        child2.encodeTree( bc, range << 1, code + range );
        return;
      }
      bc.encodeBit( false ); // no inline item here
      encodeString( bc, value );
    }

    void encode( BitWriteBuffer bc )
    {
      bc.encodeBounded( range - 1, code );
    }
    
    @Override
    public int compareTo( Value v )
    {
      return frequency - v.frequency;
    }
  }
  
  public byte[] encodeDictionary( BitWriteBuffer bc ) throws IOException
  {
    if ( ++pass == 1 )
    {
      return null;
    }
    else if ( pass == 2 )
    {
      nextIdx = 0;
      Collections.sort( groups );
      for( TagGroup g : groups )
      {
        g.indexTags();
      }

      taglist = new ArrayList<Tag>();
      for( Tag t : tags.values() )
      {
        if ( t.count > 0 )
        {
          taglist.add( t );
        }
      }
      Collections.sort( taglist );
      return null;
    }
  
    stringList = new ArrayList<String>();
    stringMap = new HashMap<String,Integer>();

    baos = new ByteArrayOutputStream();
    dos = new DataOutputStream( baos );

    bc.encodeInt( taglist.size() );
    for( Tag t : taglist )
    {
      t.encodeDictionary( bc );
    }
    
    dos.close();
    byte[] textData = baos.toByteArray();
    dos = null;
    baos = null;
    return textData;
  }
  
  public int getTagIndex( String name )
  {
    return tags.get( name ).idx;
  }

  public List<String> sortTagNames( Collection<String> col )
  {
    ArrayList<Tag> taglist = new ArrayList<Tag>( col.size() );
    for( String name : col )
    {
      taglist.add( tags.get( name ) );
    }
    Collections.sort( taglist );
    ArrayList<String> res = new ArrayList<String>( taglist.size() );
    for( Tag t : taglist )
    {
      res.add( t.name );
    }
    return res;    
  }

  public void startTagSet()
  {
    if ( pass == 1 )
    {
      setId++;
    }
  }

  public void encodeValue( BitWriteBuffer bc, String name, String value )
  {
    if ( pass == 1 )
    {
      Tag t = tags.get( name );
      if ( t == null )
      {
        String[] names = new String[1];
        names[0] = name;
        new TagGroup( names );
        t = tags.get( name );
      }
      t.addValue( value );
    }
    else // pass 2+3
    {  
      tags.get( name ).values.get( value ).encode( bc );
    }
  }
}
