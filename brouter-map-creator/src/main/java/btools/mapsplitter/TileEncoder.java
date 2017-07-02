package btools.mapsplitter;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;

/**
 * TileEncoder encodes a given node/way file pair
 *
 * @author ab
 */
public class TileEncoder extends MapCreatorBase
{
  private Map<NodeData,NodeData> nodeMap;
  private Map<WayData,WayData> wayMap;
  private List<NodeData> used = new ArrayList<NodeData>();

  private NodeData templateNode = new NodeData( 0, 0, 0 );
  private WayData templateWay = new WayData( 0, null );

  private TileData tile;

  private BitWriteBuffer bwb;
  
  private byte[] buffer;

  private List<WayData> wayList;

  private List<RelationData> relationList;
  
  // statistics only
  private int nTagSets;

  private int nTaggedNodes;
  private long totalNodes;
  private long totalTaggedNodes;
  private long totalWays;
  private long totalTextBytes;
  private long totalTiles;

  private int pass;
  private boolean dostats;
  private TagValueEncoder tagValueEncoder;
  private TagSetEncoder tagSetEnoder;

  public static void main( String[] args ) throws Exception
  {
    System.out.println( "*** TileEncoder: encodes a given node/way file pair" );
    if ( args.length != 1 )
    {
      System.out.println( "usage: java TileEncoder <node-file>" );
      return;
    }
    new TileEncoder().process( new File( args[0] ) );
  }

  public void process( File nodeFile) throws Exception
  {
    TileData t0 = new TileData(); // zoom 0 dummy
    process( nodeFile, t0 );

    System.out.println( "**** total statistics ****" );
    System.out.println( "tiles=" + totalTiles + " nodes=" + totalNodes + " taggedNodes=" + totalTaggedNodes + " ways=" + totalWays + " textBytes= " + totalTextBytes );
    System.out.println( bwb.getBitReport() );
  }

  public void process( File nodeFile, TileData tile ) throws Exception
  {
    this.tile = tile;
    
    if ( !nodeFile.exists() )
    {
      return;
    }

    System.out.println( "******* processing: " + nodeFile );

    new NodeIterator( this ).processFile( nodeFile );
    
    // process childs
    
    int zoomStep = 2;
    int xyStep = 1 << zoomStep;
    
    int nextZoom = tile.zoom + zoomStep;
    int x0 = tile.x << zoomStep;
    int y0 = tile.y << zoomStep;

    File childDir = new File( nodeFile.getParentFile().getParentFile(), "" + nextZoom );

    for( int dx = 0; dx < xyStep; dx++ )
    {
      for( int dy = 0; dy < xyStep; dy++ )
      {
        TileData nextTile = new TileData();
        nextTile.zoom = nextZoom;
        nextTile.x = x0 + dx;
        nextTile.y = y0 + dy;
        nextTile.parent = tile;
        File nextFile = new File( childDir, nextTile.x + "_" + nextTile.y + ".ntl" );
        process( nextFile, nextTile );
      }
    }
  }

  @Override
  public void nodeFileStart( File nodeFile ) throws Exception
  {
    tile.nodeList = new ArrayList<NodeData>();
    nodeMap = new HashMap<NodeData,NodeData>();
    wayMap = new HashMap<WayData,WayData>();
  }

  @Override
  public void nextNode( NodeData n ) throws Exception
  {
    // if no level yet, it's ours
    if ( n.zoom == -1 || n.zoom == tile.zoom )
    {
      n.zoom = tile.zoom;
      n.used = true;
      tile.nodeList.add( n );
    }
    n.localeIndex = nodeMap.size();
    nodeMap.put( n,n );
    n.calcGeoId();
  }

  @Override
  public void nodeFileEnd( File nodeFile ) throws Exception
  {
    NodeData.sortByGeoId( tile.nodeList );
    int idx = 0;
    for( NodeData n : tile.nodeList )
    {
      n.nativeIndex = idx++;
    }

    // read corresponding way-file into wayList
    wayList = new ArrayList<WayData>();
    String name = nodeFile.getName();
    String wayfilename = name.substring( 0, name.length()-3 ) + "wtl";
    File wayfile = new File( nodeFile.getParent(), wayfilename );
    if ( wayfile.exists() )
    {
      new WayIterator( this ).processFile( wayfile );
    }

    // read corresponding relation-file
    relationList = new ArrayList<RelationData>();
    String relfilename = name.substring( 0, name.length()-3 ) + "rtl";
    File relfile = new File( nodeFile.getParent(), relfilename );
    if ( relfile.exists() )
    {
      new RelationIterator( this ).processFile( relfile );
    }


    int nnodes = tile.nodeList.size();

    tagValueEncoder = new TagValueEncoder();
    tagSetEnoder = new TagSetEncoder();
    
    long[] nodePositions = new long[nnodes];
    for( int i=0; i<nnodes; i++ )
    {
      nodePositions[i] = tile.nodeList.get(i).gid;
    }

    for( pass=1;pass<=3; pass++) // 3 passes: counters, stat-collection, encoding
    {
      nTagSets = 0;
    
      dostats = pass == 3;

      buffer = new byte[10000000];
      bwb = new BitWriteBuffer( buffer );

      tagSetEnoder.encodeDictionary( bwb );
      if ( dostats ) bwb.assignBits( "tagset-dictionary" );

      // encode the dictionary
      byte[] textData = tagValueEncoder.encodeDictionary( bwb );
      if ( dostats ) bwb.assignBits( "value-dictionary" );
    
      // encode the node positions    
      bwb.encodeSortedArray( nodePositions );
      if ( dostats ) bwb.assignBits( "node-positions" );
      
      if ( pass == 3 )
      {
        writeDownzoomRefs( bwb );
      }

      // encode the tagged nodes
      writeTaggedNodes();

      writeWays( bwb );
      
      writeRelations( bwb );

      if ( pass == 1 && nTagSets == 0 ) // stop it if nothing tagged
      {
        break;
      }
      
      if ( pass == 1 )
      {
        assignLocalIndexes();
      }

      if ( pass == 3 )
      {
        // Compress the text-bytes
        Deflater compresser = new Deflater();
        compresser.setInput(textData);
        compresser.finish();
        byte[] textHeader = new byte[textData.length + 1024];
        int textHeaderLen = compresser.deflate(textHeader);

        totalTiles++;
        totalNodes += tile.nodeList.size();
        totalTaggedNodes += nTaggedNodes;
        totalTextBytes += textHeaderLen;
        System.out.println( "nodes=" + tile.nodeList.size() + " taggedNodes=" + nTaggedNodes + " ways=" + wayList.size() + " textBytes= " + textHeaderLen );

        // write result to file
        String datafilename = name.substring( 0, name.length()-3 ) + "osb";
        File datafile = new File( nodeFile.getParent(), datafilename );

        DataOutputStream dos = new DataOutputStream( new FileOutputStream( datafile ) );
        dos.writeInt( textData.length );
        dos.writeInt( textHeaderLen );
        dos.write( textHeader, 0, textHeaderLen );
        int size = bwb.getEncodedLength();
        dos.writeInt( size );
        dos.write( buffer, 0, size );
        dos.close();
      }
    }
      
    if ( relfile.exists() )
    {
      relfile.delete();
    }
    if ( wayfile.exists() )
    {
      wayfile.delete();
    }
    if ( nodeFile.exists() )
    {
      nodeFile.delete();
    }
  }

  @Override
  public void nextWay( WayData way ) throws Exception
  {
    // if no level yet, it's ours
    if ( way.zoom == -1 || way.zoom == tile.zoom )
    {
      way.zoom = tile.zoom;
      way.startNodeIdx = -1;
      wayList.add( way );
    }
    wayMap.put( way,way );
  }

  @Override
  public void nextRelation( RelationData r ) throws Exception
  {
    relationList.add( r );
  }

  private void assignLocalIndexes()
  {
    used = new ArrayList<NodeData>();
    for( NodeData n : nodeMap.values() )
    {
      if ( n.used )
      {
        used.add( n );
      }
    }
    NodeData.sortByGeoId( used );
    int idx = 0;
    for( NodeData n : used )
    {
      n.localeIndex = idx++;
    }
  }

  private void writeDownzoomRefs( BitWriteBuffer bwb )
  {
    // total locale nodes
    bwb.encodeInt( used.size() );
      
    for( int zoom=0; zoom<tile.zoom; zoom++ )
    {
      // count
      int cnt = 0;
      for( NodeData n : used )
      {
        if ( n.zoom == zoom )
        {
          cnt++;
        }
      }
      long[] localeIndexes = new long[cnt];
      long[] nativeIndexes = new long[cnt];
      int idx = 0;
      for( NodeData n : used )
      {
        if ( n.zoom == zoom )
        {
// System.out.println( " ---> locale=" + n.localeIndex + " native=" + n.nativeIndex );
          localeIndexes[idx] = n.localeIndex;
          nativeIndexes[idx] = n.nativeIndex;
          idx++;
        }
      }
      bwb.encodeSortedArray( localeIndexes );
      if ( dostats ) bwb.assignBits( "localindexes" );
      bwb.encodeSortedArray( nativeIndexes );
      if ( dostats ) bwb.assignBits( "nativeindexes" );
    }
  }
  
  private int getLocaleIndexForNid( long nid )
  {
    templateNode.nid = nid;
    NodeData n = nodeMap.get( templateNode );
    if ( n == null ) throw new IllegalArgumentException( "nid=" + nid + " not found" );
    n.used = true;
    return n.localeIndex;
  }

  private void encodeWay( BitWriteBuffer bwb, WayData way ) throws Exception
  {
    int nnodes = way.nodes.size();
    boolean closedPoly = way.nodes.get(0) == way.nodes.get(nnodes-1);
    if ( closedPoly )
    {
      nnodes--;
    }
    if ( nnodes < 2 )
    {
      return;
    }
    
    writeTags( way.getTagsOrNull() );

    bwb.encodeBit( closedPoly );
    bwb.encodeInt( nnodes-2 );

    if ( dostats ) bwb.assignBits( "way-node-count" );

    // determine the tile-index for each node
    int lastIdx = 0;
    for (int i=0; i<nnodes; i++ )
    {
      long nid = way.nodes.get(i);      
      int idx = getLocaleIndexForNid( nid );
      if ( i == 0 )
      {
        way.startNodeIdx = idx;
      }
      else
      {
        int delta = idx-lastIdx;

        if ( delta == 0 )
        {
          System.out.println( "double node in way, ignoring" );
          way.startNodeIdx = -1;
          return;
        }
        boolean negative = delta < 0;
        bwb.encodeBit( negative );
        bwb.encodeLong( (negative ? -delta : delta) -1 );

        if ( dostats ) bwb.assignBits( "way-node-idx-delta" );              
      }
      lastIdx = idx;
    }
  }


  private void writeWays( BitWriteBuffer bwb ) throws Exception
  {
    // in pass 3, sort ways according startNodeIdx and encode start-indexes
    if ( pass == 3 )
    {
      if ( wayList.size() > 0 )
      {
        ArrayList<WayData> goodWays = new ArrayList<WayData>();
        for( WayData w : wayList )
        {
          if ( w.startNodeIdx >= 0 )
          {
            goodWays.add( w );
          }
        }
        WayData.sortByStartNode( goodWays );
        wayList = goodWays;
      }

      // encode start-node-indexes
      int waycount = wayList.size();
      long[] startIndexes = new long[waycount];
      int i = 0;
      for( WayData w : wayList )
      {
        w.nativeIndex = i;
        startIndexes[i++] = w.startNodeIdx;
      }
      bwb.encodeSortedArray( startIndexes );
      if ( dostats ) bwb.assignBits( "way-start-idx" );
    }
    for( WayData way : wayList )
    {
      encodeWay( bwb, way );
    }
  }

  private void writeRelations( BitWriteBuffer bwb ) throws Exception
  {
    bwb.encodeInt( relationList.size() );
    if ( dostats ) bwb.assignBits( "relation-count" );
    for( RelationData rel : relationList )
    {
      encodeRelation( bwb, rel );
    }
  }

  private void encodeRelation( BitWriteBuffer bwb, RelationData rel ) throws Exception
  {
    writeTags( rel.getTagsOrNull() );

    int size = rel.ways.size();
    if ( dostats ) bwb.assignBits( "way-node-count" );

    // count valid members
    int validMembers = 0;
    for( int i=0; i < size; i++ )
    {
      long wid = rel.ways.get( i );
      String role = rel.roles.get(i);
      templateWay.wid = wid;
      WayData w = wayMap.get( templateWay );
      if ( w == null ) continue;
      validMembers++;
    }
    bwb.encodeInt( validMembers );

    for( int i=0; i < size; i++ )
    {
      long wid = rel.ways.get( i );
      String role = rel.roles.get(i);
      templateWay.wid = wid;
      WayData w = wayMap.get( templateWay );
      if ( w == null ) continue;
      
      int zoomDelta = tile.zoom - w.zoom;
      
      bwb.encodeInt( zoomDelta );
      bwb.encodeInt( w.nativeIndex );
      tagValueEncoder.encodeValue( bwb, "role", role );
    }
  }

  private void writeTaggedNodes() throws Exception
  {
    // count tagged nodes
    int cnt = 0;
    for( int idx=0; idx<tile.nodeList.size(); idx++ )
    {
      NodeData n = tile.nodeList.get( idx );
      if ( n.zoom == tile.zoom && n.getTagsOrNull() != null )
      {
        cnt++;
      }
    }
    // build index array
    long[] taggedIndexes = new long[cnt];
    int i = 0;
    for( int idx=0; idx<tile.nodeList.size(); idx++ )
    {
      if ( tile.nodeList.get( idx ).getTagsOrNull() != null )
      {
        taggedIndexes[i++] = idx;
      }
    }

    nTaggedNodes = cnt;

    bwb.encodeSortedArray( taggedIndexes );
    if ( dostats ) bwb.assignBits( "tagged-node-idx" );
    
    for( int idx=0; idx<tile.nodeList.size(); idx++ )
    {
      NodeData n = tile.nodeList.get( idx );
      if ( n.getTagsOrNull() != null )
      {
        writeTags( n.getTagsOrNull() );
      }
    }
  }
  
  private void writeTags( HashMap<String, String> tags ) throws Exception
  {
    List<String> names;

    if ( tags == null )
    {
      tags = new HashMap<String, String>();
    }

    if ( pass > 1 )
    {
      // create tagset as sorted int-array
      names = tagValueEncoder.sortTagNames( tags.keySet() );
      int ntags = names.size();
      int[] tagset = new int[ ntags ];
      for( int i=0; i<ntags; i++ )
      {
        tagset[i] = tagValueEncoder.getTagIndex( names.get(i) );
      }
      // ... and encode it
      tagSetEnoder.encodeTagSet( tagset );
      if ( dostats ) bwb.assignBits( "tag-set" );
    }
    else
    {
      nTagSets++;
    
      names = new ArrayList<String>( tags.keySet() ); // unsorted is o.k. in pass 1
    }

    // then encode the values
    tagValueEncoder.startTagSet();
    for( String name : names )
    {
      String value = tags.get( name );
      tagValueEncoder.encodeValue( bwb, name, value );
      if ( dostats ) bwb.assignBits( "value-index" );
    }
  }
  
}
