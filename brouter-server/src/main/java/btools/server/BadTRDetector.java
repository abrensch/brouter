package btools.server;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import btools.router.OsmNodeNamed;
import btools.router.RoutingContext;
import btools.router.RoutingEngine;
import btools.router.SearchBoundary;

public class BadTRDetector
{
  public static void main(String[] args) throws Exception
  {
    System.out.println("BadTRDetector / 12092018 / abrensch");
    if ( args.length < 7 )
    {
      System.out.println("Find bad TR candidates in OSM");
      System.out.println("usage: java BadTRDetector <segmentdir> <lon-from> <lat-from> <lon-to> <lat-to> <profile> <nshots>");
      return;
    }
    
    int nshots = Integer.parseInt( args[6] );
    boolean findTrs = false;
    if ( nshots < 0 )
    {
      findTrs = true;
      nshots = -nshots;
    }

    OsmNodeNamed lowerLeft = BRouter.readPosition( args, 1, "lowerLeft" );
    OsmNodeNamed uppperRight = BRouter.readPosition( args, 3, "uppperRight" );
    
    Random rand = new Random();
    Map<Long,Integer> suspectTRs = new HashMap<Long,Integer>();

     
    
    for( int nshot = 0; nshot < nshots; nshot++ )
    {
      OsmNodeNamed n = new OsmNodeNamed();
      n.name = "from";
      n.ilon = lowerLeft.ilon + (int)(rand.nextDouble() * ( uppperRight.ilon - lowerLeft.ilon ) );
      n.ilat = lowerLeft.ilat + (int)(rand.nextDouble() * ( uppperRight.ilat - lowerLeft.ilat ) );
      
      List<OsmNodeNamed> wplist = new ArrayList<OsmNodeNamed>();
      wplist.add( n );

      SearchBoundary boundary = new SearchBoundary( n, 100000, 0 );
      
      RoutingContext rc = new RoutingContext();
      rc.localFunction = args[5];
      rc.memoryclass = (int) ( Runtime.getRuntime().maxMemory() / 1024 / 1024 );
      if ( findTrs )
      {
        rc.suspectTRs = suspectTRs;
        rc.considerTurnRestrictions = false;
      }
      else
      {
        rc.suspectNodes = suspectTRs;
        rc.inverseRouting = rand.nextBoolean();
      }
      
      RoutingEngine re = new RoutingEngine( "mytrack", "mylog", args[0], wplist, rc );
      re.boundary = boundary;

      re.doSearch();
      if ( re.getErrorMessage() != null )
      {
        System.out.println( re.getErrorMessage() );
      }
    }
      // write tr-suspects to file      
      String suspectsFile = "deadend.suspects";
      BufferedWriter bw = new BufferedWriter( new FileWriter( new File( suspectsFile ) ) );
      for( Long suspect : suspectTRs.keySet() )
      {
          bw.write( suspect + " " + suspectTRs.get( suspect ) + "\r\n" );
      }
      bw.close();

  }
}
