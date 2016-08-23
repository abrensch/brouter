package btools.memrouter;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import btools.expressions.BExpressionContext;
import btools.router.OsmNodeNamed;
import btools.router.OsmTrack;
import btools.router.RoutingContext;
import btools.router.RoutingEngine;

public class TwinRoutingEngine extends RoutingEngine
{
  private static Object graphSync = new Object();
  private static GraphLoader graph;
  
  public TwinRoutingEngine( String outfileBase, String logfileBase, String segmentDir,
          List<OsmNodeNamed> waypoints, RoutingContext rc )
  {
    super( outfileBase, logfileBase, segmentDir, waypoints, rc );
  }

  public void doRun( long maxRunningTime )
  {
	if ( routingContext.localFunction != null && routingContext.localFunction.startsWith( "../im/") )
	{
	  doMemoryRun( maxRunningTime );
	}
	else
	{
      super.doRun( maxRunningTime );
	}
  }

  static private Map<String,BExpressionContext[]> expressionCache = new HashMap<String,BExpressionContext[]>();
  
  private void doMemoryRun( long maxRunningTime )
  {
    try
    {
	  synchronized( graphSync )
	  {
	    if ( graph == null )
	    {
	    	loadGraph();
	    }
	    
/*	    // reuse old expression-caches
	    BExpressionContext[] exp = expressionCache.get( routingContext.localFunction );
	    if ( exp == null )
	    {
	    	exp = new BExpressionContext[2];
	    	exp[0] = routingContext.expctxWay;
	    	exp[1] = routingContext.expctxNode;
	    	expressionCache.put( routingContext.localFunction, exp );
	    }
	    else
	    {
	      System.out.println( "re-using exp-ctx for : " + routingContext.localFunction );
	      routingContext.expctxWay = exp[0];
	      routingContext.expctxNode = exp[1];
	    }	    
*/
	    OsmLinkP.currentserial++;
	    ScheduledRouter router = new ScheduledRouter( graph, routingContext, this );
	    foundTrack = router.findRoute( waypoints.get(0), waypoints.get(1), routingContext.getAlternativeIdx(-1,10) );
        System.out.println( "linksProcessed=" + router.linksProcessed + " linksReProcessed=" +  router.linksReProcessed);
        System.out.println( "skippedChained=" + router.skippedChained + " closedSkippedChained=" +  router.closedSkippedChained);
        
	    System.out.println( "expCtxWay: requests: " + routingContext.expctxWay.cacheStats() );
        
	  }
    }
    catch( Exception e )
    {
    	e.printStackTrace();
    	errorMessage = e.getMessage();
    }
  }

  
  private void loadGraph() throws Exception
  {
	  File parentDir = new File( segmentDir ).getParentFile();
	  File nodeTilesIn = new File( parentDir, "unodes55");
	  File wayTilesIn = new File( parentDir, "waytiles55");
	  File[] fahrplanFiles = new File[2];
      fahrplanFiles[0] = new File( parentDir, "fahrplan_nahverkehr.txt" );
      fahrplanFiles[1] = new File( parentDir, "fahrplan_dbfern.txt" );
      
	  graph = new GraphLoader();
	  graph.process( nodeTilesIn, wayTilesIn, fahrplanFiles, routingContext.expctxWay );
  }

  
}
