package btools.router;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import btools.mapaccess.NodesCache;
import btools.mapaccess.OsmLink;
import btools.mapaccess.OsmLinkHolder;
import btools.mapaccess.OsmNode;
import btools.mapaccess.OsmNodesMap;
import btools.util.SortedHeap;
import btools.util.StackSampler;

public class RoutingEngine extends Thread
{
  private OsmNodesMap nodesMap;
  private NodesCache nodesCache;
  private SortedHeap<OsmPath> openSet = new SortedHeap<OsmPath>();
  private boolean finished = false;

  protected List<OsmNodeNamed> waypoints = null;
  protected List<MatchedWaypoint> matchedWaypoints;
  private int linksProcessed = 0;

  private int nodeLimit; // used for target island search

  protected OsmTrack foundTrack = new OsmTrack();
  private OsmTrack foundRawTrack = null;
  private int alternativeIndex = 0;

  protected String errorMessage = null;

  private volatile boolean terminated;

  protected String segmentDir;
  private String outfileBase;
  private String logfileBase;
  private boolean infoLogEnabled;
  private Writer infoLogWriter;
  private StackSampler stackSampler;
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

    File baseFolder = new File( routingContext.localFunction ).getParentFile();
    baseFolder = baseFolder == null ? null : baseFolder.getParentFile();
    if ( baseFolder != null )
    {
      try
      {
        File debugLog = new File( baseFolder, "debug.txt" );
        if ( debugLog.exists() )
        {
          infoLogWriter = new FileWriter( debugLog, true );
          logInfo( "********** start request at " );
          logInfo( "********** " + new Date() );
        }
      }
      catch( IOException ioe )
      {
        throw new RuntimeException( "cannot open debug-log:" + ioe );
      }

      File stackLog = new File( baseFolder, "stacks.txt" );
      if ( stackLog.exists() )
      {
        stackSampler = new StackSampler( stackLog, 1000 );
        stackSampler.start();
        logInfo( "********** started stacksampling" );
      }
    }
    boolean cachedProfile = ProfileCache.parseProfile( rc );
    if ( hasInfo() )
    {
      logInfo( "parsed profile " + rc.localFunction + " cached=" + cachedProfile );
    }
  }

  private boolean hasInfo()
  {
    return infoLogEnabled || infoLogWriter != null;
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
        infoLogWriter.flush();
      }
      catch( IOException io )
      {
        infoLogWriter = null; 
      }
    }
  }

  private void logThrowable( Throwable t )
  {
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);
    t.printStackTrace(pw);
    logInfo( sw.toString() );
  }

  public void run()
  {
    doRun( 0 );
  }

  public void doRun( long maxRunningTime )
  {
    try
    {        	
      // delete nogos with waypoints in them
      routingContext.cleanNogolist( waypoints );

      startTime = System.currentTimeMillis();
      this.maxRunningTime = maxRunningTime;
      int nsections = waypoints.size() - 1;
      OsmTrack[] refTracks = new OsmTrack[nsections]; // used ways for alternatives
      OsmTrack[] lastTracks = new OsmTrack[nsections];
      OsmTrack track = null;
      ArrayList<String> messageList = new ArrayList<String>();
      for( int i=0;; i++ )
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
          oldTrack = null;
          track.writeGpx( filename );
          foundTrack = track;
          alternativeIndex = i;
        }
        else
        {
          if ( i == routingContext.getAlternativeIdx(0,3) )
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
      logThrowable( e );
    }
    catch( Error e)
    {
      cleanOnOOM();
      errorMessage = e.toString();
      logInfo( "Error (linksProcessed=" + linksProcessed + ": " + errorMessage );
      logThrowable( e );
    }
    finally
    {
      if ( hasInfo() && routingContext.expctxWay != null )
      {
        logInfo( "expression cache stats=" + routingContext.expctxWay.cacheStats() );
      }

      ProfileCache.releaseProfile( routingContext );
      
      if ( nodesCache != null )
      {
        if ( hasInfo() && nodesCache != null )
        {
          logInfo( "NodesCache status before close=" + nodesCache.formatStatus() );
        }
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

      if ( stackSampler != null )
      {
        try { stackSampler.close(); } catch( Exception e ) {}
        stackSampler = null;
      }

    }
  }

  public void doSearch()
  {
    try
    {
      MatchedWaypoint seedPoint = new MatchedWaypoint();
      seedPoint.waypoint = waypoints.get(0);
      List<MatchedWaypoint> listOne = new ArrayList<MatchedWaypoint>();
      listOne.add( seedPoint );
      matchWaypointsToNodes( listOne );

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
      logThrowable( e );
    }
    catch( Error e)
    {
      cleanOnOOM();
      errorMessage = e.toString();
      logInfo( "Error (linksProcessed=" + linksProcessed + ": " + errorMessage );
      logThrowable( e );
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

  public void cleanOnOOM()
  {
      nodesMap = null;
      terminate();
  }      
  
  

  private OsmTrack findTrack( OsmTrack[] refTracks, OsmTrack[] lastTracks )
  {
    OsmTrack totaltrack = new OsmTrack();
    int nUnmatched = waypoints.size();

    if ( hasInfo() )
    {
      for( OsmNodeNamed wp : waypoints )
      {
        logInfo( "wp=" + wp );
      }
    }

    // check for a track for that target
    OsmTrack nearbyTrack = null;
    if ( lastTracks[waypoints.size()-2] == null )
    {
      StringBuilder debugInfo =  hasInfo() ? new StringBuilder() : null;
      nearbyTrack = OsmTrack.readBinary( routingContext.rawTrackPath, waypoints.get( waypoints.size()-1), routingContext.getNogoChecksums(), routingContext.profileTimestamp, debugInfo );
      if ( nearbyTrack != null )
      {
        nUnmatched--;
      }
      if ( hasInfo() )
      {
        boolean found = nearbyTrack != null;
        boolean dirty = found ? nearbyTrack.isDirty : false;
        logInfo( "read referenceTrack, found=" + found + " dirty=" + dirty + " " + debugInfo );
      }
    }

    if ( matchedWaypoints == null ) // could exist from the previous alternative level
    {
      matchedWaypoints = new ArrayList<MatchedWaypoint>();
      for( int i=0; i<nUnmatched; i++ )
      {
        MatchedWaypoint mwp = new MatchedWaypoint();
        mwp.waypoint = waypoints.get(i);
        matchedWaypoints.add( mwp );
      }
      matchWaypointsToNodes( matchedWaypoints );

      // detect target islands: restricted search in inverse direction
      routingContext.inverseDirection = true;
      airDistanceCostFactor = 0.;
      for( int i=0; i<matchedWaypoints.size() -1; i++ )
      {
        nodeLimit = 200;
        OsmTrack seg = findTrack( "target-island-check", matchedWaypoints.get(i+1), matchedWaypoints.get(i), null, null, false );
        if ( seg == null && nodeLimit > 0 )
        {
          throw new IllegalArgumentException( "target island detected for section " + i );
        }
      }
      routingContext.inverseDirection = false;
      nodeLimit = 0;

      if ( nearbyTrack != null )
      {
        matchedWaypoints.add( nearbyTrack.endPoint );
      }
    }

    for( int i=0; i<matchedWaypoints.size() -1; i++ )
    {
      if ( lastTracks[i] != null )
      {
        if ( refTracks[i] == null ) refTracks[i] = new OsmTrack();
        refTracks[i].addNodes( lastTracks[i] );
      }

      OsmTrack seg = searchTrack( matchedWaypoints.get(i), matchedWaypoints.get(i+1), i == matchedWaypoints.size()-2 ? nearbyTrack : null, refTracks[i] );
      if ( seg == null ) return null;
      totaltrack.appendTrack( seg );
      lastTracks[i] = seg;
    }
    return totaltrack;
  }

  // geometric position matching finding the nearest routable way-section
  private void matchWaypointsToNodes( List<MatchedWaypoint> unmatchedWaypoints )
  {
    resetCache();
    nodesCache.waypointMatcher = new WaypointMatcherImpl( unmatchedWaypoints, 250. );
    for( MatchedWaypoint mwp : unmatchedWaypoints )
    {
      preloadPosition( mwp.waypoint );
    }

    if ( nodesCache.first_file_access_failed )
    {
      throw new IllegalArgumentException( "datafile " + nodesCache.first_file_access_name + " not found" );
    }
    for( MatchedWaypoint mwp : unmatchedWaypoints )
    {
      if ( mwp.crosspoint == null )
      {
        throw new IllegalArgumentException( mwp.waypoint.name + "-position not mapped in existing datafile" );
      }
    }
  }

  private void preloadPosition( OsmNode n )
  {
    int d = 12500;
    nodesCache.first_file_access_failed = false;
    nodesCache.first_file_access_name = null;
    nodesCache.loadSegmentFor( n.ilon, n.ilat );
    if ( nodesCache.first_file_access_failed )
    {
      throw new IllegalArgumentException( "datafile " + nodesCache.first_file_access_name + " not found" );
    }
    for( int idxLat=-1; idxLat<=1; idxLat++ )
      for( int idxLon=-1; idxLon<=1; idxLon++ )
      {
        nodesCache.loadSegmentFor( n.ilon + d*idxLon , n.ilat +d*idxLat );
      }
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
    IllegalArgumentException dirtyMessage = null;
    
    if ( nearbyTrack != null )
    {
      airDistanceCostFactor = 0.;
      try
      {
        track = findTrack( "re-routing", startWp, endWp, nearbyTrack , refTrack, true );
      }
      catch( IllegalArgumentException iae )
      {
        if ( terminated ) throw iae;
        
        // fast partial recalcs: if that timed out, but we had a match,
        // build the concatenation from the partial and the nearby track
        if ( matchPath != null )
        {
          track = mergeTrack( matchPath, nearbyTrack );
          isDirty = true;
          dirtyMessage = iae;
          logInfo( "using fast partial recalc" );
        }
        if ( maxRunningTime > 0 )
        {
          maxRunningTime += System.currentTimeMillis() - startTime; // reset timeout...
        }
      }
    }

    if ( track == null )
    {
      for( int cfi = 0; cfi < airDistanceCostFactors.length; cfi++ )
      {
        airDistanceCostFactor = airDistanceCostFactors[cfi];
        
        if ( airDistanceCostFactor < 0. )
        {
          continue;
        }
      
        OsmTrack t;
        try
        {
          t = findTrack( cfi == 0 ? "pass0" : "pass1", startWp, endWp, track , refTrack, false  );
        }
        catch( IllegalArgumentException iae )
        {
          if ( !terminated && matchPath != null ) // timeout, but eventually prepare a dirty ref track
          {
            logInfo( "supplying dirty reference track after timeout" );
            foundRawTrack = mergeTrack( matchPath, track );
            foundRawTrack.endPoint = endWp;
            foundRawTrack.nogoChecksums = routingContext.getNogoChecksums();
            foundRawTrack.profileTimestamp = routingContext.profileTimestamp;
            foundRawTrack.isDirty = true;
          }
          throw iae;
        }

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
    
    boolean wasClean = nearbyTrack != null && !nearbyTrack.isDirty;
    if ( refTrack == null && !(wasClean && isDirty) ) // do not overwrite a clean with a dirty track
    {
      logInfo( "supplying new reference track, dirty=" + isDirty );
      track.endPoint = endWp;
      track.nogoChecksums = routingContext.getNogoChecksums();
      track.profileTimestamp = routingContext.profileTimestamp;
      track.isDirty = isDirty;
      foundRawTrack = track;
    }

    if ( !wasClean && isDirty )
    {
      throw dirtyMessage;
    }

    // final run for verbose log info and detail nodes
    airDistanceCostFactor = 0.;
    guideTrack = track;
    startTime = System.currentTimeMillis(); // reset timeout...
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
    if ( hasInfo() && nodesCache != null )
    {
      logInfo( "NodesCache status before reset=" + nodesCache.formatStatus() );
    }
    nodesMap = new OsmNodesMap();
    
    long maxmem = routingContext.memoryclass * 131072L; // 1/4 of total
    
    nodesCache = new NodesCache(segmentDir, nodesMap, routingContext.expctxWay, routingContext.forceSecondaryData, maxmem, nodesCache );
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
    try
    {
      resetCache();
      return _findTrack( operationName, startWp, endWp, costCuttingTrack, refTrack, fastPartialRecalc );
    }
    finally
    {
      nodesCache.cleanNonVirgin();
    }
  }


  private OsmTrack _findTrack( String operationName, MatchedWaypoint startWp, MatchedWaypoint endWp, OsmTrack costCuttingTrack, OsmTrack refTrack, boolean fastPartialRecalc )
  {
    boolean verbose = guideTrack != null;

    int maxTotalCost = 1000000000;
    int firstMatchCost = 1000000000;
    
    logInfo( "findtrack with airDistanceCostFactor=" + airDistanceCostFactor );
    if (costCuttingTrack != null ) logInfo( "costCuttingTrack.cost=" + costCuttingTrack.cost );

    matchPath = null;
    int nodesVisited = 0;

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
    for(;;)
    {
      if ( terminated )
      {
        throw new IllegalArgumentException( "operation killed by thread-priority-watchdog after " + ( System.currentTimeMillis() - startTime)/1000 + " seconds" );
      }
      
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

      if ( matchPath != null && fastPartialRecalc && firstMatchCost < 500 && path.cost > 30L*firstMatchCost
          && !costCuttingTrack.isDirty )
      {
        logInfo( "early exit: firstMatchCost=" + firstMatchCost + " path.cost=" + path.cost );
        throw new IllegalArgumentException( "early exit for a close recalc" );
      }
      
      if ( nodeLimit > 0 ) // check node-limit for target island search
      {
        if ( --nodeLimit == 0 )
        {
          return null;
        }
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
          long nextId = nextNode.getIdFromPos();
          if ( nextId != guideNode.getIdFromPos() )
          {
            // not along the guide-track, discard, but register for voice-hint processing
            if ( routingContext.turnInstructionMode > 0 )
            {
              OsmPath detour = new OsmPath( currentNode, path, link, refTrack, true, routingContext );
              if ( detour.cost >= 0. && nextId != startNodeId1 && nextId != startNodeId2 )
              {
                guideTrack.registerDetourForId( currentNode.getIdFromPos(), OsmPathElement.create( detour, false ) );
              }
            }
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

    // for final track..
    if ( guideTrack != null )
    {
      track.copyDetours( guideTrack );
      track.processVoiceHints( routingContext );
    }
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
      if ( guideTrack != null )
      {
        ArrayList<OsmPathElement> nodes =  guideTrack.nodes;
        int[] res =  new int[nodes.size() * 2];
        int i = 0;
        for( OsmPathElement n : nodes )
        {
          res[i++] = n.getILon();
          res[i++] = n.getILat();
        }
        return res;
      }
    
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
