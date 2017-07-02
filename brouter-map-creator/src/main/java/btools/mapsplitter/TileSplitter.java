package btools.mapsplitter;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import btools.util.DenseLongMap;
import btools.util.TinyDenseLongMap;

/**
 * TileSplitter splits a tile into pieces
 */
public class TileSplitter extends MapCreatorBase
{
  private NodeData templateNode = new NodeData( 0, 0, 0 );

  private DenseLongMap nodeIndexMap;
  private DenseLongMap bigWayMemberMap;

  private DenseLongMap wayIndexMap;
  private DenseLongMap bigRelMemberMap;

  private DenseLongMap relIndexMap;

  private Map<NodeData,NodeData> nodeMap;

  private List<NodeData> thisLevelNodes;
  private Map<Long,Integer> thisLevelNodesIndexes;

  private List<WayData> thisLevelWays;
  private Map<Long,Integer> thisLevelWaysIndexes;

  private int level;
  private int baseLon;
  private int baseLat;

  private int nodeCount = 0;
  private String typeSuffix;
  private boolean inPassLoop;
  private int pass; // 1 == build tileIndexMap, 2 == collect this-level-nodes, 3 == output nodes
  private File inTileDir; 

  public static void main(String[] args) throws Exception
  {
    System.out.println("*** TileSplitter: cut tiles into 16 pieces");
    if (args.length != 1)
    {
      System.out.println("usage: java TileSplitter <tile-dir>" );

      return;
    }
    new TileSplitter().process( new File( args[0] ) );
  }

  public void process( File tileDir) throws Exception
  {
    for( int level = 0; level < 12; level += 2 )
    {
      process( tileDir, level );
    }
  }

  public void process( File tileDir, int level ) throws Exception
  {
    System.out.println("processing level: " + level );

    inTileDir = new File( tileDir, "" + (level) ); 
    outTileDir = new File( tileDir, "" + (level+2) );
    outTileDir.mkdirs();
    this.level = level;

    // *** initialize 3-pass processing of nodes, ways and relations
    inPassLoop = false;
    new NodeIterator( this ).processDir( inTileDir, ".ntl" );
  }

  @Override
  public void nodeFileStart( File nodeFile ) throws Exception
  {
    if ( !inPassLoop )
    {
      inPassLoop = true;
      pass = 1;
      new NodeIterator( this ).processFile( nodeFile );
      pass = 2;
      new NodeIterator( this ).processFile( nodeFile );
      pass = 3;
      new NodeIterator( this ).processFile( nodeFile );
      pass = 4;
      inPassLoop = false;
    }  

System.out.println( "nodeFileStart pass=" + pass );
  
    if ( pass == 1 )
    {
      getBaseTileFromName( nodeFile.getName() );
      nodeIndexMap = Boolean.getBoolean( "useDenseMaps" ) ? new DenseLongMap() : new TinyDenseLongMap();
    }
    else if ( pass == 2 )
    {
    }
    else if ( pass == 3 )
    {
      nodeMap = new HashMap<NodeData,NodeData>();
      thisLevelNodes = new ArrayList<NodeData>();
    }
    else // nodePass = 4
    {
      NodeData.sortByGeoId( thisLevelNodes );

      thisLevelNodesIndexes = new HashMap<Long,Integer>();
      int idx = 0;
      for( NodeData n : thisLevelNodes )
      {
        thisLevelNodesIndexes.put( Long.valueOf( n.nid ), Integer.valueOf( idx++ ) );
      }
      thisLevelNodes = null;
    }
    typeSuffix = "ntl";
  }
  

  private void getBaseTileFromName( String name )
  {
System.out.println( "getBaseTileFromName: " + name );
    int idx1 = name.indexOf( '_' );
    int idx2 = name.indexOf( '.' );
    baseLon = Integer.parseInt( name.substring( 0, idx1 ) );
    baseLat = Integer.parseInt( name.substring( idx1+1, idx2 ) );
  }

  @Override
  public void nextNode( NodeData n ) throws Exception
  {
    int tidx = getTileIndex( n );

    if ( pass == 1 )
    {
      nodeCount++;
      nodeIndexMap.put( n.nid, tidx );
    }
    else if ( pass == 2 )
    {
    }
    else
    {
      boolean usedHere = bigWayMemberMap.getInt( n.nid ) == 0;
    
      if ( usedHere ) // if used on this level...
      {
        // if no level yet, this is it
        if ( n.zoom == -1 )
        {
          n.zoom = level;
        }
      }
      
      if ( pass == 3 )
      {
        if ( n.zoom != -1 )
        {
          n.calcGeoId();
          nodeMap.put( n,n );
          if ( n.zoom == level )
          {
            thisLevelNodes.add( n );
          }
        }
      }
      else // pass == 4
      {
        // add the index
        if ( n.zoom == level )
        {
          n.nativeIndex = thisLevelNodesIndexes.get( Long.valueOf( n.nid ) );
        }
      
        if ( usedHere )
        {
          n.writeTo( getOutStreamForTile( 16 ) );
        }
        n.writeTo( getOutStreamForTile( tidx ) ); // write to subtile
      }
    }
  }

  @Override
  public void nodeFileEnd( File nodeFile ) throws Exception
  {
System.out.println( "nodeFileEnd pass=" + pass );

    closeTileOutStreams();
    File parentNodes = new File( outTileDir, getNameForTile( 16 ) );

    // read corresponding way-file
    if ( pass == 2 )
    {
      bigWayMemberMap = Boolean.getBoolean( "useDenseMaps" ) ? new DenseLongMap() : new TinyDenseLongMap();
    }
    String name = nodeFile.getName();
    String wayfilename = name.substring( 0, name.length()-3 ) + "wtl";
    File wayfile = new File( inTileDir, wayfilename );
    if ( wayfile.exists() )
    {
      new WayIterator( this ).processFile( wayfile );
    }

    // read corresponding relation-file
    if ( pass == 1 )
    {
      bigRelMemberMap = Boolean.getBoolean( "useDenseMaps" ) ? new DenseLongMap() : new TinyDenseLongMap();
    }
    String relfilename = name.substring( 0, name.length()-3 ) + "rtl";
    File relfile = new File( inTileDir, relfilename );
    if ( relfile.exists() )
    {
      new RelationIterator( this ).processFile( relfile );
    }

    if ( pass == 4 )
    {    
      nodeFile.delete();    
      if ( parentNodes.exists() )
      {
        parentNodes.renameTo( nodeFile );
      }
      else if ( nodeCount > 0 )
      {
        nodeFile.createNewFile(); // create even empty to signal existence of childs
      }
    }
  }


  @Override
  public void wayFileStart( File wayFile ) throws Exception
  {
System.out.println( "wayFileStart pass=" + pass );

    if ( pass == 1 )
    {
      wayIndexMap = Boolean.getBoolean( "useDenseMaps" ) ? new DenseLongMap() : new TinyDenseLongMap();
    }
    else if ( pass == 3 )
    {
      thisLevelWays = new ArrayList<WayData>();
    }
    else if ( pass == 4 )
    {
      WayData.sortByStartNode( thisLevelWays );

      thisLevelWaysIndexes = new HashMap<Long,Integer>();
      int idx = 0;
      for( WayData w : thisLevelWays )
      {
        thisLevelWaysIndexes.put( Long.valueOf( w.wid ), Integer.valueOf( idx++ ) );
      }
      thisLevelWays = null;
    }
    typeSuffix = "wtl";
  }


  @Override
  public void nextWay( WayData w ) throws Exception
  {
    int widx = getTileIndex( w );
    if ( widx == -1 )
    {
System.out.println( "************ invalid way: " + w.wid );
      return;
    }
    
    if ( pass == 1 )
    {
      wayIndexMap.put( w.wid, widx );
    }
    else // pass >= 2
    {
      boolean usedHere = bigRelMemberMap.getInt( w.wid ) == 0;
      if ( usedHere || widx == 16 )
      {
        // if no level yet, this is it
        if ( w.zoom == -1 )
        {
          w.zoom = level;
        }

        if ( pass == 2 )
        {
          int nnodes = w.nodes.size();
          for (int i=0; i<nnodes; i++ )
          {
            bigWayMemberMap.put( w.nodes.get(i), 0 );
          }
        }
      }

      if ( pass == 3 )
      {
        if ( w.zoom == level )
        {
          w.startNodeIdx = getLocaleIndexForNid( w.nodes.get(0) );
          thisLevelWays.add( w );
        }
      }
      if ( pass == 4 )
      {
        if ( w.zoom == level )
        {
          w.nativeIndex = thisLevelWaysIndexes.get( Long.valueOf( w.wid ) );
        }

        if ( usedHere && widx != 16 )
        {
          w.writeTo( getOutStreamForTile( 16 ) );
        }
        w.writeTo( getOutStreamForTile( widx ) );
      }
    }
  }

  @Override
  public void wayFileEnd( File wayFile ) throws Exception
  {
System.out.println( "wayFileEnd pass=" + pass );

    closeTileOutStreams();

    if ( pass == 4 )
    {
      wayFile.delete();
      File parentWays = new File( outTileDir, getNameForTile( 16 ) );
      if ( parentWays.exists() )
      {
        parentWays.renameTo( wayFile );
      }
    }
  }

  @Override
  public void relationFileStart( File relFile ) throws Exception
  {
System.out.println( "relFileStart pass=" + pass );

    if ( pass == 1 )
    {
      relIndexMap = Boolean.getBoolean( "useDenseMaps" ) ? new DenseLongMap() : new TinyDenseLongMap();
    }
    else if ( pass == 2 )
    {
    }
    else // nodePass = 3
    {
    }
    typeSuffix = "rtl";
  }


  @Override
  public void nextRelation( RelationData r ) throws Exception
  {
    int ridx = getTileIndex( r );
    if ( ridx == -1 )
    {
System.out.println( "************ invalid relation: " + r.rid );
      return;
    }
    
    if ( pass == 1 )
    {
      relIndexMap.put( r.rid, ridx );
    }

    if ( pass == 1 && ridx == 16 )
    {
      int nways = r.ways.size();
      for (int i=0; i<nways; i++ )
      {
        bigRelMemberMap.put( r.ways.get(i), 0 );
      }
    }

    if ( pass == 4 )
    {
      r.writeTo( getOutStreamForTile( ridx ) );
    }
  }

  @Override
  public void relationFileEnd( File relFile ) throws Exception
  {
System.out.println( "relFileEnd pass=" + pass );

    closeTileOutStreams();

    if ( pass == 4 )
    {
      relFile.delete();
      File parentRels = new File( outTileDir, getNameForTile( 16 ) );
      if ( parentRels.exists() )
      {
        parentRels.renameTo( relFile );
      }
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

  private int getTileIndex( NodeData n )
  {
     int idxLon = ( n.ilon >> ( 26 - level ) ) & 3;
     int idxLat = ( n.ilat >> ( 26 - level ) ) & 3;
     return 4 * idxLon + idxLat;
  }

  private int getTileIndex( WayData w )
  {
    int nnodes = w.nodes.size();

    int wayTileIndex = 16;

    // determine the tile-index for each node
    for (int i=0; i<nnodes; i++ )
    {
      int tileIndex = nodeIndexMap.getInt( w.nodes.get(i) );
      if ( tileIndex == -1 )
      {
        return -1;
      }
      if ( wayTileIndex == 16 )
      {
        wayTileIndex = tileIndex;
      }
      else if ( tileIndex != wayTileIndex )
      {
        return 16;
      }
    }
    return wayTileIndex;
  }

  private int getTileIndex( RelationData r )
  {
    int nways = r.ways.size();

    int relTileIndex = 16;
    boolean hasAny = false;

    // determine the tile-index for each way
    for (int i=0; i<nways; i++ )
    {
      int tileIndex = wayIndexMap.getInt( r.ways.get(i) );
      if ( tileIndex == -1 )
      {
        continue;
      }
      hasAny = true;
      if ( relTileIndex == 16 )
      {
        relTileIndex = tileIndex;
      }
      else if ( tileIndex != relTileIndex )
      {
        return 16;
      }
    }
    return hasAny ? relTileIndex : -1;
  }

  protected String getNameForTile( int tileIndex )
  {
    if ( tileIndex == 16 )
    {
      return "parent." + typeSuffix;
    }
    int idxLon = baseLon * 4 + (tileIndex >> 2);
    int idxLat = baseLat * 4 + (tileIndex & 3);
    return idxLon + "_" + idxLat + "." + typeSuffix;
  }

}
