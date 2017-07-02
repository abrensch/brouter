package btools.mapdecoder;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.zip.Inflater;

/**
 * TileEncoder decodes a compressed osm tile
 */
public class TileDecoder
{
  private TagSetDecoder tagSetDecoder;
  private TagValueDecoder tagValueDecoder;

  public static void main( String[] args ) throws Exception
  {
    OsmTile t = new TileDecoder().process( new File( args[0] ), null, Integer.parseInt( args[1] ), Integer.parseInt( args[2] ), Integer.parseInt( args[3] ) );
    while( t != null )
    {
      System.out.println( "decoded: " + t );
      t = t.parent;
    }
  }

  public OsmTile process( File tileDir, OsmTile template, int zoom, int x, int y ) throws Exception
  {
    long sourceId = tileDir.getAbsolutePath().hashCode();
  
    // look for a match in the template
    for( OsmTile tile = template; tile != null; tile = tile.parent )
    {
      if ( tile.zoom == zoom && tile.x == x && tile.y == y && tile.sourceId == sourceId )
      {
        return tile;
      }
    }
  
    OsmTile td = new OsmTile();
    td.sourceId = sourceId;
    td.zoom = zoom;
    td.x = x;
    td.y = y;
    if ( zoom > 0 )
    {
      td.parent = new TileDecoder().process( tileDir, template, zoom-1, x >> 1, y >> 1 );
    }

    File file = new File( new File( tileDir, "" + zoom ), x + "_" + y + ".osb" );
    if ( !file.exists() )
    {
      return td;
    }

    DataInputStream dis = new DataInputStream( new FileInputStream( file  ) );
    int textHeaderLen = dis.readInt();
    int textHeaderCompressedLen = dis.readInt();
    byte[] textHeaderCompressed = new byte[textHeaderCompressedLen];
    dis.readFully( textHeaderCompressed );
    byte[] textHeader = new byte[textHeaderLen];

    Inflater decompresser = new Inflater();
    decompresser.setInput( textHeaderCompressed );
    int rawlen = decompresser.inflate( textHeader );

    int bufferLen = dis.readInt();
    byte[] buffer = new byte[bufferLen];
    dis.readFully( buffer );
    BitReadBuffer brb = new BitReadBuffer( buffer );
    dis.close();
    
    tagSetDecoder = new TagSetDecoder( brb );
    tagValueDecoder = new TagValueDecoder( brb, textHeader );

    // decode the node positions
    td.nodePositions = brb.decodeSortedArray();
    int nodecount = td.nodePositions.length;
    td.nodes = new ArrayList<OsmNode>(nodecount);
    
    int shift = 56-2*zoom;
    long offset = (encodeMorton( x ) << shift) +  (encodeMorton( y ) << (shift+1) );
    
    for ( int nidx = 0; nidx < nodecount; nidx++ )
    {
      OsmNode n = new OsmNode();
      long z = offset + td.nodePositions[nidx];
      n.id = nidx;
      n.ilon = decodeMorton( z );
      n.ilat = decodeMorton( z >> 1 );
      td.nodes.add( n );
    }
    
    LocaleIndexMapping indexMapping = new LocaleIndexMapping( td, brb );

    // decode tagged nodes
    long[] taggedIndexes = brb.decodeSortedArray();
    int ntaggedNodes = taggedIndexes.length;
    for( int tnidx=0; tnidx<ntaggedNodes; tnidx++ )
    {
      int idx = (int)taggedIndexes[tnidx];
      td.nodes.get( idx ).tags = decodeTagValues();
    }

    // decode ways
    long[] startIndexes = brb.decodeSortedArray();
    int nways = startIndexes.length;

    td.ways = new ArrayList<OsmWay>( nways );
    for( int widx=0; widx<nways; widx++ )
    {
      OsmWay w = new OsmWay();
      w.tags = decodeTagValues();
      int[] nodeIndexes = decodeWayNodes( (int)startIndexes[widx], brb );
      w.nodes = new ArrayList<OsmNode>( nodeIndexes.length );
      for( int i=0; i<nodeIndexes.length; i++ )
      {
        w.nodes.add( indexMapping.nodeForLocaleIndex( nodeIndexes[i] ) );
      }
      w.calcBBox();
      td.ways.add( w );
    }

    // decode relations
    int nrels = brb.decodeInt();
    td.relations = new ArrayList<OsmRelation>( nrels );
    for( int ridx=0; ridx<nrels; ridx++ )
    {
      OsmRelation r = new OsmRelation();
      r.tags = decodeTagValues();
      
      int nmembers = brb.decodeInt();
      r.members = new ArrayList<OsmRelationMember>(nmembers);      
      for( int midx = 0; midx<nmembers; midx++ )
      {
        OsmRelationMember m = new OsmRelationMember();
        int zoomDelta = brb.decodeInt();
        int nativeIndex = brb.decodeInt();
        m.role = tagValueDecoder.decodeRole();
        m.way = indexMapping.getWay( zoomDelta, nativeIndex );
        r.members.add( m );
      }
      r.calcBBox();
      td.relations.add( r );
    }


    return td;
  }

  private int[] decodeWayNodes( int startIdx, BitReadBuffer brb )
  {
    boolean closedPoly = brb.decodeBit();
    int nnodes = brb.decodeInt() + 2;
      
    int[] ids = new int[ closedPoly ? nnodes+1 : nnodes ];
    int lastIdx = startIdx;
    ids[0] = startIdx;
    for( int i=1; i<nnodes; i++ )
    {
      boolean negative = brb.decodeBit();
      int delta = (int)brb.decodeLong() + 1;
      ids[i] = lastIdx = lastIdx + (negative ? -delta : delta );
    }
    if ( closedPoly )
    {
      ids[nnodes] = startIdx;
    }
    return ids;
  }  

  private HashMap<String,String> decodeTagValues()
  {
    HashMap<String,String> map = new HashMap<String,String>();
    int[] tagSet = tagSetDecoder.decode();
    for( int i=0; i<tagSet.length; i++ )
    {
      int tagIdx = tagSet[i];
      String key = tagValueDecoder.getTagName( tagIdx );
      String value = tagValueDecoder.decodeValue( tagIdx );
      map.put( key, value );
    }
    return map;
  }


  public static int decodeMorton( long z )
  {
    long x = z         & 0x5555555555555555L;
    x = (x | (x >> 1)) & 0x3333333333333333L;
    x = (x | (x >> 2)) & 0x0F0F0F0F0F0F0F0FL;
    x = (x | (x >> 4)) & 0x00FF00FF00FF00FFL;
    x = (x | (x >> 8)) & 0x0000FFFF0000FFFFL;
    return (int)(x | (x >> 16));
  }
  public static long encodeMorton( int x )
  {
   long z = x & 0xFFFFFFFFL;
    z = (z | (z << 16)) & 0x0000FFFF0000FFFFL;
    z = (z | (z <<  8)) & 0x00FF00FF00FF00FFL;
    z = (z | (z <<  4)) & 0x0F0F0F0F0F0F0F0FL;
    z = (z | (z <<  2)) & 0x3333333333333333L;
    return (z|(z << 1)) & 0x5555555555555555L;
  }
 
}
