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

public class BadTRDetector
{
  public static void main(String[] args) throws Exception
  {
    System.out.println("BadTRDetector / 15102017 / abrensch");
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
      
      // target ca 10km weg

      OsmNodeNamed t = new OsmNodeNamed();
      n.name = "to";
      double dir = rand.nextDouble() + 2. * Math.PI;
      t.ilon = n.ilon + (int)( 300000. * Math.sin( dir ) );
      t.ilat = n.ilat + (int)( 200000. * Math.cos( dir ) );

      List<OsmNodeNamed> wplist = new ArrayList<OsmNodeNamed>();
      wplist.add( n );
      wplist.add( t );
      
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
      }
      
      RoutingEngine re = new RoutingEngine( "mytrack", "mylog", args[0], wplist, rc );
      re.doRun( 5000 );
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
