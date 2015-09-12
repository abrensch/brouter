package btools.router;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import btools.expressions.BExpressionContext;
import btools.expressions.BExpressionContextGlobal;
import btools.expressions.BExpressionContextNode;
import btools.expressions.BExpressionContextWay;
import btools.expressions.BExpressionMetaData;
import btools.mapaccess.NodesCache;
import btools.mapaccess.OsmLink;
import btools.mapaccess.OsmLinkHolder;
import btools.mapaccess.OsmNode;
import btools.mapaccess.OsmNodesMap;
import btools.util.SortedHeap;

public class RoutingEngine extends Thread
{
  private OsmNodesMap nodesMap;
  private NodesCache nodesCache;
  private SortedHeap<OsmPath> openSet = new SortedHeap<OsmPath>();
  private boolean finished = false;

  protected List<OsmNodeNamed> waypoints = null;
  private int linksProcessed = 0;

  protected OsmTrack foundTrack = new OsmTrack();
  private OsmTrack foundRawTrack = null;
  private int alternativeIndex = 0;

  protected String errorMessage = null;

  private volatile boolean terminated;

  protected File profileDir;
  protected String segmentDir;
  private String outfileBase;
  private String logfileBase;
  private boolean infoLogEnabled;
  private Writer infoLogWriter;
  protected RoutingContext routingContext;

  public double airDistanceCostFactor;
  private OsmTrack guideTrack;

  private OsmPathElement matchPath;
  
  private long startTime;
  private long maxRunningTime;
  public SearchBoundary boundary;

  public boolean quite = false;

  public RoutingEngine( String outfileBase, String logfileBase, String segmentDir,
          List<OsmNodeNamed> waypoints, RoutingContext rc )
  {
    this.segmentDir = segmentDir;
    this.outfileBase = outfileBase;
    this.logfileBase = logfileBase;
    this.waypoints = waypoints;
    this.infoLogEnabled = outfileBase != null;
    this.routingContext = rc;

    if ( rc.localFunction != null )
    {
      String profileBaseDir = System.getProperty( "profileBaseDir" );
      File profileFile;
      if ( profileBaseDir == null )
      {
        profileDir = new File( rc.localFunction ).getParentFile();
        profileFile = new File( rc.localFunction ) ;
      }
      else
      {
        profileDir = new File( profileBaseDir );
        profileFile = new File( profileDir, rc.localFunction + ".brf" ) ;
      }
      
      BExpressionMetaData meta = new BExpressionMetaData();
      
      BExpressionContextGlobal expctxGlobal = new BExpressionContextGlobal( meta );
      rc.expctxWay = new BExpressionContextWay( rc.serversizing ? 262144 : 4096, meta );
      rc.expctxNode = new BExpressionContextNode( rc.serversizing ?  16384 : 1024, meta );
      
      meta.readMetaData( new File( profileDir, "lookups.dat" ) );

      expctxGlobal.parseFile( profileFile, null );
      expctxGlobal.evaluate( new int[0] );
      rc.readGlobalConfig(expctxGlobal);

      rc.expctxWay.parseFile( profileFile, "global" );
      rc.expctxNode.parseFile( profileFile, "global" );
    }
  }

  private void logInfo( String s )
  {
    if ( infoLogEnabled )
    {
      System.out.println( s );
    }
    if ( infoLogWriter != null )
    {
      try
      {
        infoLogWriter.write( s );
        infoLogWriter.write( '\n' );
      }
      catch( IOException io )
      {
        infoLogWriter = null; 
      }
    }
  }

  public void run()
  {
    doRun( 0 );
  }

  public void doRun( long maxRunningTime )
  {
    try
    {
      File debugLog = new File( profileDir, "../debug.txt" );
      if ( debugLog.exists() )
      {
        infoLogWriter = new FileWriter( debugLog, true );
        logInfo( "start request at " + new Date() );
      }
        	
      startTime = System.currentTimeMillis();
      this.maxRunningTime = maxRunningTime;
      int nsections = waypoints.size() - 1;
      OsmTrack[] refTracks = new OsmTrack[nsections]; // used ways for alternatives
      OsmTrack[] lastTracks = new OsmTrack[nsections];
      OsmTrack track = null;
      ArrayList<String> messageList = new ArrayList<String>();
      for( int i=0; !terminated; i++ )
      {
        track = findTrack( refTracks, lastTracks );
        track.message = "track-length = " + track.distance + " filtered ascend = " + track.ascend
        + " plain-ascend = " +  track.plainAscend + " cost=" + track.cost;
        track.name = "brouter_" + routingContext.getProfileName() + "_" + i;

        messageList.add( track.message );
        track.messageList = messageList;
        if ( outfileBase != null )
        {
          String filename = outfileBase + i + ".gpx";
          OsmTrack oldTrack = new OsmTrack();
          oldTrack.readGpx(filename);
          if ( track.equalsTrack( oldTrack ) )
          {
            continue;
          }
          track.writeGpx( filename );
          foundTrack = track;
          alternativeIndex = i;
        }
        else
        {
          if ( i == routingContext.getAlternativeIdx() )
          {
            if ( "CSV".equals( System.getProperty( "reportFormat" ) ) )
            {
              track.dumpMessages( null, routingContext );
            }
            else
            {
              if ( !quite )
              {
                System.out.println( track.formatAsGpx() );
              }
            }
            foundTrack = track;
          }
          else
          {
            continue;
          }
        }
        if ( logfileBase != null )
        {
          String logfilename = logfileBase + i + ".csv";
          track.dumpMessages( logfilename, routingContext );
        }
        break;
      }
      long endTime = System.currentTimeMillis();
      logInfo( "execution time = " + (endTime-startTime)/1000. + " seconds" );
    }
    catch( IllegalArgumentException e)
    {
      errorMessage = e.getMessage();
      logInfo( "Exception (linksProcessed=" + linksProcessed + ": " + errorMessage );
    }
    catch( Exception e)
    {
      errorMessage = e instanceof IllegalArgumentException ? e.getMessage() : e.toString();
      logInfo( "Exception (linksProcessed=" + linksProcessed + ": " + errorMessage );
      e.printStackTrace();
    }
    catch( Error e)
    {
      String hint = cleanOnOOM();
      errorMessage = e.toString() + hint;
      logInfo( "Error (linksProcessed=" + linksProcessed + ": " + errorMessage );
      e.printStackTrace();
    }
    finally
    {
      if ( nodesCache != null )
      {
        nodesCache.close();
        nodesCache = null;
      }
      openSet.clear();
      finished = true; // this signals termination to outside

      if ( infoLogWriter != null )
      {
        try { infoLogWriter.close(); } catch( Exception e ) {}
        infoLogWriter = null;
      }
    }
  }

  public void doSearch()
  {
    try
    {
      MatchedWaypoint seedPoint = matchNodeForPosition( waypoints.get(0) );
      routingContext.countTraffic = true;

      findTrack( "seededSearch", seedPoint, null, null, null, false );
    }
    catch( IllegalArgumentException e)
    {
      errorMessage = e.getMessage();
      logInfo( "Exception (linksProcessed=" + linksProcessed + ": " + errorMessage );
    }
    catch( Exception e)
    {
      errorMessage = e instanceof IllegalArgumentException ? e.getMessage() : e.toString();
      logInfo( "Exception (linksProcessed=" + linksProcessed + ": " + errorMessage );
      e.printStackTrace();
    }
    catch( Error e)
    {
      String hint = cleanOnOOM();
      errorMessage = e.toString() + hint;
      logInfo( "Error (linksProcessed=" + linksProcessed + ": " + errorMessage );
      e.printStackTrace();
    }
    finally
    {
      if ( nodesCache != null )
      {
        nodesCache.close();
        nodesCache = null;
      }
      openSet.clear();
      finished = true; // this signals termination to outside

      if ( infoLogWriter != null )
      {
        try { infoLogWriter.close(); } catch( Exception e ) {}
        infoLogWriter = null;
      }
    }
  }

  public String cleanOnOOM()
  {
	  boolean oom_carsubset_hint = nodesCache == null ? false : nodesCache.oom_carsubset_hint;
      nodesMap = null;
      terminate();
      return oom_carsubset_hint ? "\nPlease use 'carsubset' maps for long-distance car-routing" : "";
  }      
  
  

  private OsmTrack findTrack( OsmTrack[] refTracks, OsmTrack[] lastTracks )
  {
    OsmTrack totaltrack = new OsmTrack();
    MatchedWaypoint[] wayointIds = new MatchedWaypoint[waypoints.size()];

    // check for a track for that target
    OsmTrack nearbyTrack = null;
    if ( refTracks[waypoints.size()-2] == null )
    {
      nearbyTrack = OsmTrack.readBinary( routingContext.rawTrackPath, waypoints.get( waypoints.size()-1), routingContext.getNogoChecksums() );
      if ( nearbyTrack != null )
      {
          wayointIds[waypoints.size()-1] = nearbyTrack.endPoint;
      }
    }
    
    // match waypoints to nodes
    for( int i=0; i<waypoints.size(); i++ )
    {
      if ( wayointIds[i] == null )
      {
        wayointIds[i] = matchNodeForPosition( waypoints.get(i) );
      }
    }

    for( int i=0; i<waypoints.size() -1; i++ )
    {
      if ( lastTracks[i] != null )
      {
        if ( refTracks[i] == null ) refTracks[i] = new OsmTrack();
        refTracks[i].addNodes( lastTracks[i] );
      }

      OsmTrack seg = searchTrack( wayointIds[i], wayointIds[i+1], i == waypoints.size()-2 ? nearbyTrack : null, refTracks[i] );
      if ( seg == null ) return null;
      totaltrack.appendTrack( seg );
      lastTracks[i] = seg;
    }
    return totaltrack;
  }

  // geometric position matching finding the nearest routable way-section
  private MatchedWaypoint matchNodeForPosition( OsmNodeNamed wp )
  {
     try
     {
         routingContext.setWaypoint( wp, false );
         
         int minRingWith = 1;
         for(;;)
         {
           MatchedWaypoint mwp = _matchNodeForPosition( wp, minRingWith );
           if ( mwp.node1 != null )
           {
             int mismatch = wp.calcDistance( mwp.crosspoint );
             if ( mismatch < 50*minRingWith )
             {
               return mwp;
             }
           }
           if ( minRingWith == 1 && nodesCache.first_file_access_failed )
           {
             throw new IllegalArgumentException( "datafile " + nodesCache.first_file_access_name + " not found" );
           }
           if ( minRingWith++ == 5 )
           {
             throw new IllegalArgumentException( wp.name + "-position not mapped in existing datafile" );
           }
         }
     }
     finally
     {
         routingContext.unsetWaypoint();
     }
  }

  private MatchedWaypoint _matchNodeForPosition( OsmNodeNamed wp, int minRingWidth )
  {
    wp.radius = 1e9;
    resetCache();
    preloadPosition( wp, minRingWidth, 2000 );
    nodesCache.distanceChecker = routingContext;
    List<OsmNode> nodeList = nodesCache.getAllNodes();

    MatchedWaypoint mwp = new MatchedWaypoint();
    mwp.waypoint = wp;

    // first loop just to expand reverse links
    for( OsmNode n : nodeList )
    {
        if ( !nodesCache.obtainNonHollowNode( n ) )
        {
          continue;
        }
        expandHollowLinkTargets( n );
        OsmLink startLink = new OsmLink();
        startLink.targetNode = n;
        OsmPath startPath = new OsmPath( startLink );
        startLink.addLinkHolder( startPath );
        for( OsmLink link = n.firstlink; link != null; link = link.next )
        {
          if ( link.descriptionBitmap == null ) continue; // reverse link not found
          OsmNode nextNode = link.targetNode;
          if ( nextNode.isHollow() ) continue; // border node?
          if ( nextNode.firstlink == null ) continue; // don't care about dead ends
          if ( nextNode == n ) continue; // ?
          double oldRadius = wp.radius;
          OsmPath testPath = new OsmPath( n, startPath, link, null, false, routingContext );
          if ( wp.radius < oldRadius )
          {
           if ( testPath.cost < 0 )
           {
             wp.radius = oldRadius; // no valid way
           }
           else
           {
             mwp.node1 = n;
             mwp.node2 = nextNode;
             mwp.radius = wp.radius;
             mwp.cost = testPath.cost;
             mwp.crosspoint = new OsmNodeNamed();
             mwp.crosspoint.ilon = routingContext.ilonshortest;
             mwp.crosspoint.ilat = routingContext.ilatshortest;
           }
          }
        }
    }
    return mwp;
  }

  // expand hollow link targets and resolve reverse links
  private void expandHollowLinkTargets( OsmNode n )
  {
    for( OsmLink link = n.firstlink; link != null; link = link.next )
    {
      nodesCache.obtainNonHollowNode( link.targetNode );
    }
  }

  private OsmTrack searchTrack( MatchedWaypoint startWp, MatchedWaypoint endWp, OsmTrack nearbyTrack, OsmTrack refTrack )
  {
    OsmTrack track = null;
    double[] airDistanceCostFactors = new double[]{ routingContext.pass1coefficient, routingContext.pass2coefficient };
    boolean isDirty = false;
    
    if ( nearbyTrack != null )
    {
      airDistanceCostFactor = 0.;
      try
      {
        track = findTrack( "re-routing", startWp, endWp, nearbyTrack , refTrack, true );
      }
      catch( IllegalArgumentException iae )
      {
        // fast partial recalcs: if that timed out, but we had a match,
        // build the concatenation from the partial and the nearby track
        if ( matchPath != null )
        {
          track = mergeTrack( matchPath, nearbyTrack );
          isDirty = true;
          logInfo( "using fast partial recalc" );
        }
    	maxRunningTime += System.currentTimeMillis() - startTime; // reset timeout...
      }
    }

    if ( track == null )
    {
      for( int cfi = 0; cfi < airDistanceCostFactors.length && !terminated; cfi++ )
      {
        airDistanceCostFactor = airDistanceCostFactors[cfi];
        
        if ( airDistanceCostFactor < 0. )
        {
          continue;
        }
      
        OsmTrack t = findTrack( cfi == 0 ? "pass0" : "pass1", startWp, endWp, track , refTrack, false  );
        if ( t == null && track != null && matchPath != null )
        {
          // ups, didn't find it, use a merge
          t = mergeTrack( matchPath, track );
          logInfo( "using sloppy merge cause pass1 didn't reach destination" );
        }
        if ( t != null )
        {
          track = t;
        }
        else
        {
          throw new IllegalArgumentException( "no track found at pass=" + cfi );
        }
      }
    }
    if ( track == null ) throw new IllegalArgumentException( "no track found" );
    
    if ( refTrack == null && !isDirty )
    {
      logInfo( "supplying new reference track" );
      track.endPoint = endWp;
      track.nogoChecksums = routingContext.getNogoChecksums();
      foundRawTrack = track;
    }

    // final run for verbose log info and detail nodes
    airDistanceCostFactor = 0.;
    guideTrack = track;
    try
    {
      OsmTrack tt = findTrack( "re-tracking", startWp, endWp, null , refTrack, false );
      if ( tt == null ) throw new IllegalArgumentException( "error re-tracking track" );
      return tt;
    }
    finally
    {
      guideTrack = null;
    }
  }


  private void resetCache()
  {
    nodesMap = new OsmNodesMap();
    BExpressionContext ctx = routingContext.expctxWay;
    nodesCache = new NodesCache(segmentDir, nodesMap, ctx.meta.lookupVersion, ctx.meta.lookupMinorVersion, routingContext.carMode, routingContext.forceSecondaryData, nodesCache );
  }

  private OsmNode getStartNode( long startId )
  {
    // initialize the start-node
    OsmNode start = new OsmNode( startId );
    start.setHollow();
    if ( !nodesCache.obtainNonHollowNode( start ) )
    {
      return null;
    }
    expandHollowLinkTargets( start );
    return start;
  }

  private OsmPath getStartPath( OsmNode n1, OsmNode n2, MatchedWaypoint mwp, OsmNodeNamed endPos, boolean sameSegmentSearch )
  {
    OsmPath p = getStartPath( n1, n2, mwp.waypoint, endPos );
    
    // special case: start+end on same segment
    if ( sameSegmentSearch )
    {
      OsmPath pe = getEndPath( n1, p.getLink(), endPos );
      OsmPath pt = getEndPath( n1, p.getLink(), null );
      int costdelta = pt.cost - p.cost;
      if ( pe.cost >= costdelta )
      {
    	pe.cost -= costdelta;

    	if ( guideTrack != null )
    	{
    	  // nasty stuff: combine the path cause "new OsmPath()" cannot handle start+endpoint
    	  OsmPathElement startElement = p.originElement;
          while( startElement.origin != null )
          {
            startElement = startElement.origin;
          }
    	  if ( pe.originElement.cost > costdelta )
    	  {
    	    OsmPathElement e = pe.originElement;
       	    while( e.origin != null && e.origin.cost > costdelta )
    	    {
    	      e = e.origin;
    	      e.cost -= costdelta;
    	    }
    	    e.origin = startElement;
    	  }
    	  else
    	  {
    	    pe.originElement = startElement;
    	  }
    	}
        return pe;
      }
    }
    return p;
  }

    
    
  private OsmPath getStartPath( OsmNode n1, OsmNode n2, OsmNodeNamed wp, OsmNode endPos )
  {
    try
    {
      routingContext.setWaypoint( wp, false );
      OsmPath bestPath = null;
      OsmLink bestLink = null;
      OsmLink startLink = new OsmLink();
      startLink.targetNode = n1;
      OsmPath startPath = new OsmPath( startLink );
      startLink.addLinkHolder( startPath );
      double minradius = 1e10;
      for( OsmLink link = n1.firstlink; link != null; link = link.next )
      {
        OsmNode nextNode = link.targetNode;
        if ( nextNode.isHollow() ) continue; // border node?
        if ( nextNode.firstlink == null ) continue; // don't care about dead ends
        if ( nextNode == n1 ) continue; // ?
        if ( nextNode != n2 ) continue; // just that link

         wp.radius = 1e9;
         OsmPath testPath = new OsmPath( null, startPath, link, null, guideTrack != null, routingContext );
         testPath.airdistance = endPos == null ? 0 : nextNode.calcDistance( endPos );
         if ( wp.radius < minradius )
         {
           bestPath = testPath;
           minradius = wp.radius;
           bestLink = link;
         }
      }
      if ( bestLink != null )
      {
        bestLink.addLinkHolder( bestPath );
      }
      bestPath.treedepth = 1;

      return bestPath;
    }
    finally
    {
      routingContext.unsetWaypoint();
    }
  }

  private OsmPath getEndPath( OsmNode n1, OsmLink link, OsmNodeNamed wp )
  {
    try
    {
      if ( wp != null ) routingContext.setWaypoint( wp, true );
      OsmLink startLink = new OsmLink();
      startLink.targetNode = n1;
      OsmPath startPath = new OsmPath( startLink );
      startLink.addLinkHolder( startPath );

      if ( wp != null ) wp.radius = 1e-5;
     
      return new OsmPath( n1, startPath, link, null, guideTrack != null, routingContext );
    }
    finally
    {
      if ( wp != null ) routingContext.unsetWaypoint();
    }
  }

  private OsmTrack findTrack( String operationName, MatchedWaypoint startWp, MatchedWaypoint endWp, OsmTrack costCuttingTrack, OsmTrack refTrack, boolean fastPartialRecalc )
  {
    boolean verbose = guideTrack != null;

    int maxTotalCost = 1000000000;
    int firstMatchCost = 1000000000;
    
    logInfo( "findtrack with airDistanceCostFactor=" + airDistanceCostFactor );
    if (costCuttingTrack != null ) logInfo( "costCuttingTrack.cost=" + costCuttingTrack.cost );

    matchPath = null;
    int nodesVisited = 0;

    resetCache();
    long endNodeId1 = endWp == null ? -1L : endWp.node1.getIdFromPos();
    long endNodeId2 = endWp == null ? -1L : endWp.node2.getIdFromPos();
    long startNodeId1 = startWp.node1.getIdFromPos();
    long startNodeId2 = startWp.node2.getIdFromPos();

    OsmNodeNamed endPos = endWp == null ? null : endWp.crosspoint;
    
    boolean sameSegmentSearch = ( startNodeId1 == endNodeId1 && startNodeId2 == endNodeId2 )
                             || ( startNodeId1 == endNodeId2 && startNodeId2 == endNodeId1 );
    
    OsmNode start1 = getStartNode( startNodeId1 );
    if ( start1 == null ) return null;
    OsmNode start2 = null;
    for( OsmLink link = start1.firstlink; link != null; link = link.next )
    {
    	if ( link.targetNode.getIdFromPos() == startNodeId2 )
    	{
    	  start2 = link.targetNode;
    	  break;
    	}
    }
    if ( start2 == null ) return null;


    

    if ( start1 == null || start2 == null ) return null;

    OsmPath startPath1 = getStartPath( start1, start2, startWp, endPos, sameSegmentSearch );
    OsmPath startPath2 = getStartPath( start2, start1, startWp, endPos, sameSegmentSearch );

    // check for an INITIAL match with the cost-cutting-track
    if ( costCuttingTrack != null )
    {
        OsmPathElement pe1 = costCuttingTrack.getLink( startNodeId1, startNodeId2 );
        if ( pe1 != null ) { logInfo( "initialMatch pe1.cost=" + pe1.cost );
        	int c = startPath1.cost - pe1.cost; if ( c < 0 ) c = 0; if ( c < firstMatchCost ) firstMatchCost = c; }

        OsmPathElement pe2 = costCuttingTrack.getLink( startNodeId2, startNodeId1 );
        if ( pe2 != null ) { logInfo( "initialMatch pe2.cost=" + pe2.cost );
            int c = startPath2.cost - pe2.cost; if ( c < 0 ) c = 0; if ( c < firstMatchCost ) firstMatchCost = c; }
        
        if ( firstMatchCost < 1000000000 ) logInfo( "firstMatchCost from initial match=" + firstMatchCost );
    }
    
    synchronized( openSet )
    {
      openSet.clear();
      addToOpenset( startPath1 );
      addToOpenset( startPath2 );
    }
    while(!terminated)
    {
      if ( maxRunningTime > 0 )
      {
        long timeout = ( matchPath == null && fastPartialRecalc ) ? maxRunningTime/3 : maxRunningTime;
        if ( System.currentTimeMillis() - startTime > timeout )
        {
          throw new IllegalArgumentException( operationName + " timeout after " + (timeout/1000) + " seconds" );
        }
      }
      OsmPath path = null;
      synchronized( openSet )
      {
        path = openSet.popLowestKeyValue();
      }
      if ( path == null ) break;
      if ( path.airdistance == -1 )
      {
        path.unregisterUpTree( routingContext );
        continue;
      }

      if ( matchPath != null && fastPartialRecalc && firstMatchCost < 500 && path.cost > 30L*firstMatchCost )
      {
        logInfo( "early exit: firstMatchCost=" + firstMatchCost + " path.cost=" + path.cost );
        throw new IllegalArgumentException( "early exit for a close recalc" );
      }
      
      nodesVisited++;
      linksProcessed++;
      
      OsmLink currentLink = path.getLink();
      OsmNode currentNode = currentLink.targetNode;
      OsmNode sourceNode = path.getSourceNode();

      long currentNodeId = currentNode.getIdFromPos();
      if ( sourceNode != null )
      {
        long sourceNodeId = sourceNode.getIdFromPos();
        if ( ( sourceNodeId == endNodeId1 && currentNodeId == endNodeId2 )
          || ( sourceNodeId == endNodeId2 && currentNodeId == endNodeId1 ) )
        {
          // track found, compile
          logInfo( "found track at cost " + path.cost +  " nodesVisited = " + nodesVisited );
          return compileTrack( path, verbose );
        }
        
        // check for a match with the cost-cutting-track
        if ( costCuttingTrack != null )
        {
          OsmPathElement pe = costCuttingTrack.getLink( sourceNodeId, currentNodeId );
          if ( pe != null )
          {
            // remember first match cost for fast termination of partial recalcs
        	int parentcost = path.originElement == null ? 0 : path.originElement.cost;
        	
        	// hitting start-element of costCuttingTrack?
        	int c = path.cost - parentcost - pe.cost;
        	if ( c > 0 ) parentcost += c;
        	
        	if ( parentcost < firstMatchCost ) firstMatchCost = parentcost;
        	  
            int costEstimate = path.cost
                             + path.elevationCorrection( routingContext )
                             + ( costCuttingTrack.cost - pe.cost );
            if ( costEstimate <= maxTotalCost )
            {
              matchPath = OsmPathElement.create( path, routingContext.countTraffic );
            }
            if ( costEstimate < maxTotalCost )
            {
              logInfo( "maxcost " + maxTotalCost + " -> " + costEstimate );
              maxTotalCost = costEstimate;
            }
          }
        }
      }

      // recheck cutoff before doing expensive stuff
      if ( path.cost + path.airdistance > maxTotalCost + 10 )
      {
        path.unregisterUpTree( routingContext );
        continue;
      }

      expandHollowLinkTargets( currentNode );

      if ( sourceNode != null )
      {
        sourceNode.unlinkLink ( currentLink );
      }

      OsmLink counterLink = null;
      for( OsmLink link = currentNode.firstlink; link != null; link = link.next )
      {
        OsmNode nextNode = link.targetNode;

        if ( nextNode.isHollow() )
        {
          continue; // border node?
        }
        if ( nextNode.firstlink == null )
        {
          continue; // don't care about dead ends
        }
        if ( nextNode == sourceNode )
        {
          counterLink = link;
          continue; // border node?
        }

        if ( guideTrack != null )
        {
          int gidx = path.treedepth + 1;
          if ( gidx >= guideTrack.nodes.size() )
          {
            continue;
          }
          OsmPathElement guideNode = guideTrack.nodes.get( gidx );
          if ( nextNode.getILat() != guideNode.getILat() || nextNode.getILon() != guideNode.getILon() )
          {
            continue;
          }
        }

        OsmPath bestPath = null;

        boolean isFinalLink = false;
        long targetNodeId = link.targetNode.getIdFromPos();
        if ( currentNodeId == endNodeId1 || currentNodeId == endNodeId2 )
        {
          if ( targetNodeId == endNodeId1 || targetNodeId == endNodeId2 )
          {
            isFinalLink = true;
          }
        }

        for( OsmLinkHolder linkHolder = currentLink.firstlinkholder; linkHolder != null; linkHolder = linkHolder.getNextForLink() )
        {
          OsmPath otherPath = (OsmPath)linkHolder;
          try
          {
            if ( isFinalLink )
            {
              endPos.radius = 1e-5;
              routingContext.setWaypoint( endPos, true );
            }
            OsmPath testPath = new OsmPath( currentNode, otherPath, link, refTrack, guideTrack != null, routingContext );
            if ( testPath.cost >= 0 && ( bestPath == null || testPath.cost < bestPath.cost ) )
            {
              bestPath = testPath;
            }
          }
          finally
          {
            routingContext.unsetWaypoint();
          }
          if ( otherPath != path )
          {
            otherPath.airdistance = -1; // invalidate the entry in the open set
          }
        }
        if ( bestPath != null )
        {
          boolean trafficSim = endPos == null;

          bestPath.airdistance = trafficSim ? path.airdistance : ( isFinalLink ? 0 : nextNode.calcDistance( endPos ) );
          
          boolean inRadius = boundary == null || boundary.isInBoundary( nextNode, bestPath.cost );

          if ( inRadius && ( isFinalLink || bestPath.cost + bestPath.airdistance <= maxTotalCost + 10 ) )
          {
            // add only if this may beat an existing path for that link
        	OsmLinkHolder dominator = link.firstlinkholder;
            while( !trafficSim && dominator != null )
            {
              if ( bestPath.definitlyWorseThan( (OsmPath)dominator, routingContext ) )
              {
                break;
              }
              dominator = dominator.getNextForLink();
            }

        	if ( dominator == null )
        	{
              if ( trafficSim && boundary != null && path.cost == 0 && bestPath.cost > 0 )
              {
                bestPath.airdistance += boundary.getBoundaryDistance( nextNode );
              }

              bestPath.treedepth = path.treedepth + 1;
              link.addLinkHolder( bestPath );
              synchronized( openSet )
              {
                addToOpenset( bestPath );
              }
        	}
          }
        }
      }
      // if the counterlink does not yet have a path, remove it
      if ( counterLink != null && counterLink.firstlinkholder == null )
      {
        currentNode.unlinkLink(counterLink);
      }
      path.unregisterUpTree( routingContext );
    }
    return null;
  }
  
  private void addToOpenset( OsmPath path )
  {
    if ( path.cost >= 0 )
    {
      openSet.add( path.cost + (int)(path.airdistance*airDistanceCostFactor), path );
      path.registerUpTree();
    }
  }

  private void preloadPosition( OsmNode n, int minRingWidth, int minCount )
  {
    int c = 0;
    int ring = 0;
    while( ring <= minRingWidth || ( c < minCount && ring <= 5 ) )
    {
      c += preloadRing( n, ring++ );
    }
  }

  private int preloadRing( OsmNode n, int ring )
  {
    int d = 12500;
    int c = 0;
    for( int idxLat=-ring; idxLat<=ring; idxLat++ )
      for( int idxLon=-ring; idxLon<=ring; idxLon++ )
      {
        int absLat = idxLat < 0 ? -idxLat : idxLat;
        int absLon = idxLon < 0 ? -idxLon : idxLon;
        int max = absLat > absLon ? absLat : absLon;
        if ( max < ring ) continue;
        c += nodesCache.loadSegmentFor( n.ilon + d*idxLon , n.ilat +d*idxLat );
      }
    return c;
  }

  private OsmTrack compileTrack( OsmPath path, boolean verbose )
  {
    OsmPathElement element = OsmPathElement.create( path, false );

    // for final track, cut endnode
    if ( guideTrack != null ) element = element.origin;

    OsmTrack track = new OsmTrack();
    track.cost = path.cost;

    int distance = 0;
    double ascend = 0;
    double ehb = 0.;

    short ele_start = Short.MIN_VALUE;
    short ele_end = Short.MIN_VALUE;
    
    while ( element != null )
    {
      track.addNode( element );
      OsmPathElement nextElement = element.origin;
      
      short ele = element.getSElev();
      if ( ele != Short.MIN_VALUE ) ele_start = ele;
      if ( ele_end == Short.MIN_VALUE ) ele_end = ele;

      if ( nextElement != null )
      {
        distance += element.calcDistance( nextElement );
        short ele_next = nextElement.getSElev();
        if ( ele_next != Short.MIN_VALUE )
        {
          ehb = ehb + (ele - ele_next)/4.;
        }
        if ( ehb > 10. )
        {
          ascend += ehb-10.;
          ehb = 10.;
        }
        else if ( ehb < 0. )
        {
          ehb = 0.;
        }
      }
      element = nextElement ;
    }
    ascend += ehb;
    track.distance = distance;
    track.ascend = (int)ascend;
    track.plainAscend = ( ele_end - ele_start ) / 4;
    logInfo( "track-length = " + track.distance );
    logInfo( "filtered ascend = " + track.ascend );
    track.buildMap();
    return track;
  }

  private OsmTrack mergeTrack( OsmPathElement match, OsmTrack oldTrack )
  {
	  
    OsmPathElement element = match;
    OsmTrack track = new OsmTrack();

    while ( element != null )
    {
      track.addNode( element );
      element = element.origin ;
    }
    long lastId = 0;
    long id1 = match.getIdFromPos();
    long id0 = match.origin == null ? 0 : match.origin.getIdFromPos();
    boolean appending = false;
    for( OsmPathElement n : oldTrack.nodes )
    {
      if ( appending )
      {
        track.nodes.add( n );
      }
    	
      long id = n.getIdFromPos();
      if ( id == id1 && lastId == id0 )
      {
        appending = true;
      }
      lastId = id;
    }
    
    
    track.buildMap();
    return track;
  }

  public int[] getOpenSet()
  {
    synchronized( openSet )
    {
      List<OsmPath> extract = openSet.getExtract();
      int[] res =  new int[extract.size() * 2];
      int i = 0;
      for( OsmPath p : extract )
      {
          OsmNode n = p.getLink().targetNode;
          res[i++] = n.ilon;
          res[i++] = n.ilat;
      }
      return res;
    }
  }

  public boolean isFinished()
  {
    return finished;
  }

  public int getLinksProcessed()
  {
      return linksProcessed;
  }

  public int getDistance()
  {
    return foundTrack.distance;
  }

  public int getAscend()
  {
    return foundTrack.ascend;
  }

  public int getPlainAscend()
  {
    return foundTrack.plainAscend;
  }

  public OsmTrack getFoundTrack()
  {
    return foundTrack;
  }

  public int getAlternativeIndex()
  {
    return alternativeIndex;
  }

  public OsmTrack getFoundRawTrack()
  {
    return foundRawTrack;
  }

  public String getErrorMessage()
  {
    return errorMessage;
  }

  public void terminate()
  {
    terminated = true;
  }

  public boolean isTerminated()
  {
	  return terminated;
  }

}
