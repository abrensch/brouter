package btools.mapdecoder;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;

/**
 * Decoder for the list of tags and their value-trees
 */
public class TagValueDecoder
{
  private int nextStringStart = 0;
  private byte[] textHeader;
  private ArrayList<String> stringList;
  private ArrayList<Tag> taglist;
  private int roleIdx;

  private String decodeString( BitReadBuffer brb )
  {
    boolean newIdx = brb.decodeBit();
    if ( newIdx )
    {
      int slen = brb.decodeInt();
      try
      {
        String s = new String( textHeader, nextStringStart, slen, "UTF8" );
        nextStringStart += slen;
        stringList.add( s );
        return s;
      }
      catch( UnsupportedEncodingException uee )
      {
        throw new RuntimeException( uee );
      }
    }
    int idx = (int)brb.decodeBounded( stringList.size()-1 );
    return stringList.get( idx );
  }
  
  private class Tag extends HuffmannTreeDecoder<String>
  {
    String name;
    
    Tag( BitReadBuffer brb, String tagName )
    {
      super( brb );
      name = tagName;
    }
    
    protected String decodeItem()
    {
      return decodeString( brb );
    }
  }

  public TagValueDecoder( BitReadBuffer brb, byte[] textHeader )
  {
    this.textHeader = textHeader;
    stringList = new ArrayList<String>();

    int ntags = brb.decodeInt();
    taglist = new ArrayList<Tag>();
    for( int i=0; i<ntags; i++ )
    {
      String tagName = decodeString( brb );
      taglist.add( new Tag( brb, tagName ) );
      if ( "role".equals( tagName ) )
      {
        roleIdx = i;
      }
    }
  }
  
  public String getTagName( int idx )
  {
    return taglist.get(idx).name;
  }

  public String decodeValue( int tagIdx )
  {
    return taglist.get( tagIdx ).decode();
  }

  public String decodeRole()
  {
    return taglist.get( roleIdx ).decode();
  }

}
