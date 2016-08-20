package btools.memrouter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import btools.expressions.BExpressionContextWay;
import btools.expressions.BExpressionMetaData;
import btools.mapaccess.OsmPos;
import btools.mapcreator.MapCreatorBase;
import btools.mapcreator.NodeData;
import btools.mapcreator.NodeIterator;
import btools.mapcreator.WayData;
import btools.mapcreator.WayIterator;
import btools.util.ByteArrayUnifier;
import btools.util.CompactLongMap;
import btools.util.FrozenLongMap;
import btools.util.LazyArrayOfLists;

/**
 * GraphLoader loads the routing graph from
 * the nodes+way files (much like mapcreator.WayLinker)
 *
 * @author ab
 */
public class GraphLoader extends MapCreatorBase
{
  private CompactLongMap<OsmNodeP> nodesMap;

  private Map<String, StationNode> stationMap;
  
  private BExpressionContextWay expctxWay;

  private ByteArrayUnifier abUnifier;
  
  private int currentTile;
  
  private long linksLoaded = 0L;
  private long nodesLoaded = 0L;

  private static final int MAXTILES = 2592;
  private List<LazyArrayOfLists<OsmNodeP>> seglistsArray = new ArrayList<LazyArrayOfLists<OsmNodeP>>(2592);

  public static void main(String[] args) throws Exception
  {
    System.out.println("*** GraphLoader: load a routing graph in memory");
    if (args.length != 5)
    {
      System.out.println("usage: java GraphLoader <node-tiles-in> <way-tiles-in> <lookup-file> <profile-file> <fahtplan-file>");
      return;
    }

    BExpressionMetaData meta = new BExpressionMetaData();
    
    // read lookup + profile for lookup-version + access-filter
    BExpressionContextWay expctxWay = new BExpressionContextWay(meta);
    File lookupFile = new File( args[2] );
    File profileFile = new File( args[3] );
    meta.readMetaData( lookupFile );
    expctxWay.parseFile( profileFile, "global" );
    
    GraphLoader graph = new GraphLoader();
    File[] fahrplanFiles = new File[2];
    fahrplanFiles[0] = new File( args[4] );
    fahrplanFiles[1] = new File( args[5] );
    graph.process( new File( args[0] ), new File( args[1] ), fahrplanFiles, expctxWay );
  }

  public void process( File nodeTilesIn, File wayTilesIn, File[] fahrplanFiles, BExpressionContextWay expctxWay ) throws Exception
  {
	this.expctxWay = expctxWay;
	  
    seglistsArray = new ArrayList<LazyArrayOfLists<OsmNodeP>>(MAXTILES);
    for( int i=0; i < MAXTILES; i++ )
    {
    	seglistsArray.add( null );
    }    

    abUnifier = new ByteArrayUnifier( 16384, false );

    nodesMap = new CompactLongMap<OsmNodeP>();

    // read all nodes
    new NodeIterator( this, false ).processDir( nodeTilesIn, ".u5d" );
    
    // freeze the nodes-map
    nodesMap = new FrozenLongMap<OsmNodeP>( nodesMap );
    
    // trim the list array
    for( int i=0; i<MAXTILES; i++ )
    {
    	if ( seglistsArray.get(i) != null )
    	{
        seglistsArray.get(i).trimAll();
      }
    }

    // then read the ways
    new WayIterator( this, false ).processDir( wayTilesIn, ".wt5" );

    nodesMap = null; // don't need that anymore
    
    System.out.println( "nodesLoaded=" + nodesLoaded + " linksLoaded=" + linksLoaded );
    
    // now load the train-schedules
    stationMap = ScheduleParser.parseTrainTable( fahrplanFiles, this, expctxWay );
    
    System.gc();
    long mem = Runtime.getRuntime().totalMemory() -  Runtime.getRuntime().freeMemory();
    System.out.println( "memory after graph loading: " + mem / 1024 / 1024 + " MB" );
  }


  public OsmNodeP matchNodeForPosition( OsmPos pos, BExpressionContextWay wayCtx, boolean transitonly )
  {
    if ( transitonly )
    {
      return matchStationForPosition( pos );
    }
  
	  int ilon = pos.getILon();
	  int ilat = pos.getILat();
	  
    List<OsmNodeP> nodes = new ArrayList<OsmNodeP>();
    nodes.addAll( subListForPos( ilon-6125, ilat-6125 ) );
    nodes.addAll( subListForPos( ilon-6125, ilat+6125 ) );
    nodes.addAll( subListForPos( ilon+6125, ilat-6125 ) );
    nodes.addAll( subListForPos( ilon+6125, ilat+6125 ) );

    int mindist = Integer.MAX_VALUE;
    OsmNodeP bestmatch = null;

    for( OsmNodeP node : nodes )
    {
      if ( transitonly )
      {
        StationNode sn = getStationNode( node );
        if ( sn != null )
        {
          int dist = pos.calcDistance( sn );
          if ( dist < mindist )
          {
            mindist = dist;
            bestmatch = sn;
          }
        }
        continue;
      }
    
    
      int dist = pos.calcDistance( node );
      if ( dist < mindist )
      {
    	if ( wayCtx == null || hasRoutableLinks(node, wayCtx) )
    	{
          mindist = dist;
          bestmatch = node;
    	}
      }
    }
    return bestmatch;
  }

  private StationNode getStationNode( OsmNodeP node )
  {
    for( OsmLinkP link = node.getFirstLink(); link != null; link = link.getNext( node ) )
    {
      OsmNodeP tn = link.getTarget( node );
      if ( tn instanceof StationNode )
      {
        return (StationNode)tn;
      }
    }
    return null;
  }

  public OsmNodeP matchStationForPosition( OsmPos pos )
  {
    int mindist = Integer.MAX_VALUE;
    OsmNodeP bestmatch = null;

    for( OsmNodeP node : stationMap.values() )
    {
      int dist = pos.calcDistance( node );
      if ( dist < mindist )
      {
        mindist = dist;
        bestmatch = node;
      }
    }
    return bestmatch;
  }

  private boolean hasRoutableLinks( OsmNodeP node, BExpressionContextWay wayCtx )
  {
    for( OsmLinkP link = node.getFirstLink(); link != null; link = link.getNext( node ) )
	{
        if ( link.isWayLink() )
        {
          wayCtx.evaluate( false, link.descriptionBitmap );
          if ( wayCtx.getCostfactor() < 10000.f )
          {
            return true;
          }
        }
	}
    return false;
  }
  
  @Override
  public void nodeFileStart( File nodefile ) throws Exception
  {
  	currentTile = tileForFilename( nodefile.getName() );
  	seglistsArray.set(currentTile, new LazyArrayOfLists<OsmNodeP>(160000) );
  	System.out.println( "nodes currentTile=" + currentTile );
  }

  @Override
  public void nextNode( NodeData data ) throws Exception
  {
    OsmNodeP n = data.description == null ? new OsmNodeP() : new OsmNodePT(data.description);
    n.ilon = data.ilon;
    n.ilat = data.ilat;
    n.selev = data.selev;
    
    // add to the map
    nodesMap.fastPut( data.nid, n );

    // add also to the list array
    subListForPos( n.ilon, n.ilat ).add( n );
    
    nodesLoaded++;
  }

  @Override
  public boolean wayFileStart( File wayfile ) throws Exception
  {
  	currentTile = tileForFilename( wayfile.getName() );
  	System.out.println( "ways currentTile=" + currentTile );
  	return true;
  }

  @Override
  public void nextWay( WayData way ) throws Exception
  {
    byte[] description = abUnifier.unify( way.description );

    byte wayBits = 0;
    expctxWay.decode( description );
    if ( !expctxWay.getBooleanLookupValue( "bridge" ) ) wayBits |= OsmNodeP.NO_BRIDGE_BIT;
    if ( !expctxWay.getBooleanLookupValue( "tunnel" ) ) wayBits |= OsmNodeP.NO_TUNNEL_BIT;
   
    OsmNodeP n1 = null;
    OsmNodeP n2 = null;
    for (int i=0; i<way.nodes.size(); i++)
    {
      long nid = way.nodes.get(i);
      n1 = n2;
      n2 = nodesMap.get( nid );
      if ( n1 != null && n2 != null && n1 != n2 )
      {        
        if ( tileForPos( n1.ilon, n1.ilat ) == currentTile )
        {
          OsmLinkP link = n2.createLink(n1);
          link.descriptionBitmap = description;
          linksLoaded++;
        }
      }
      if ( n2 != null )
      {
        n2.wayBits |= wayBits;
      }
    }
  }

    // from BInstallerView.java
    private int tileForFilename( String filename )
    {
    	String basename = filename.substring( 0, filename.length() - 4 );
      String uname = basename.toUpperCase();	
      int idx = uname.indexOf( "_" );
      if ( idx < 0 ) return -1;
      String slon = uname.substring( 0, idx ); 
      String slat = uname.substring( idx+1 );
      int ilon = slon.charAt(0) == 'W' ? -Integer.valueOf( slon.substring(1) ) :
    	       ( slon.charAt(0) == 'E' ?  Integer.valueOf( slon.substring(1) ) : -1 );
      int ilat = slat.charAt(0) == 'S' ? -Integer.valueOf( slat.substring(1) ) :
	           ( slat.charAt(0) == 'N' ?  Integer.valueOf( slat.substring(1) ) : -1 );
      if ( ilon < -180 || ilon >= 180 || ilon % 5 != 0 ) return -1;
      if ( ilat < - 90 || ilat >=  90 || ilat % 5 != 0 ) return -1;
      return (ilon+180) / 5 + 72*((ilat+90)/5);
    }
    
    private int tileForPos( int ilon, int ilat )
    {
    	return ilon / 5000000 + 72 * ( ilat / 5000000 );
    }

    private int subIdxForPos( int ilon, int ilat )
    {
      int lonModulo = ilon % 5000000;
      int latModulo = ilat % 5000000;
      return ( lonModulo / 12500 ) + 400 * (latModulo / 12500);
    }

    private List<OsmNodeP> subListForPos( int ilon, int ilat )
    {
      if ( ilon < 0 || ilon >= 360000000 || ilat < 0 || ilat >= 180000000 )
      {
    	  throw new IllegalArgumentException( "illegal position: " + ilon + " " + ilat );
      }
      int tileNr = tileForPos( ilon, ilat );
      if ( seglistsArray.get(tileNr) == null ) return new ArrayList<OsmNodeP>();
      return seglistsArray.get(tileNr).getList( subIdxForPos( ilon, ilat ) );
    }  
}
