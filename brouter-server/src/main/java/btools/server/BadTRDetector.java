package btools.server;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.StringTokenizer;

import btools.mapaccess.OsmNode;
import btools.router.OsmNodeNamed;
import btools.router.RoutingContext;
import btools.router.RoutingEngine;
import btools.router.SuspectInfo;
import btools.util.CompactLongSet;
import btools.util.FrozenLongSet;

public class BadTRDetector
{
  public static void main(String[] args) throws Exception
  {
    System.out.println("BadTRDetector / 12092018 / abrensch");
    if ( args.length < 8 )
    {
      System.out.println("Find bad TR candidates in OSM");
      System.out.println("usage: java BadTRDetector <segmentdir> <lon-from> <lat-from> <lon-to> <lat-to> <profile> <radius> <overlapp>");
      return;
    }
    
    int x0 = Integer.parseInt( args[1]);
    int y0 = Integer.parseInt( args[2]);
    int x1 = Integer.parseInt( args[3]);
    int y1 = Integer.parseInt( args[4]);
    String profile = args[5];
    int radius = Integer.parseInt( args[6] );
    double overlap = Double.parseDouble( args[7] );
    
    CompactLongSet badTRSet = null;
    if ( args.length > 8 )
    {
      badTRSet = readBadTrs( new File( args[8] ) );
      System.out.println( "read badTRSet, size=" + badTRSet.size() );
    }
    
    Random rand = new Random();
    Map<Long,SuspectInfo> suspectNodes = new HashMap<Long,SuspectInfo>();
    
    for( int y = y0; y < y1; y++ )
    {    
     for( int x = x0; x < x1; x++ )
     {
      // calculate n-circles for this latitude
      int lon0 = 1000000 * ( x + 180 );
      int lat0 = 1000000 * ( y +  90 );
      OsmNode n0 = new OsmNode( lon0, lat0 );
      double arect = n0.calcDistance( new OsmNode( lon0, lat0 + 1000000 ) );
      arect *= n0.calcDistance( new OsmNode( lon0 + 1000000, lat0  ) );
      double adisc = ( Math.PI * radius ) * radius;
      int shots = (int)(1. + overlap * arect / adisc);

System.out.println( "shots for y=" + y + " x=" + x + " -> " + shots );

      List<OsmNodeNamed> wplist = new ArrayList<OsmNodeNamed>();
      for( int shot=0; shot<shots; shot++ )
      {
        OsmNodeNamed n = new OsmNodeNamed();
        n.name = "from";
        n.ilon = lon0 + rand.nextInt( 1000000 );
        n.ilat = lat0 + rand.nextInt( 1000000 );
        wplist.add( n );
      }
      RoutingContext rc = new RoutingContext();
      rc.localFunction = profile;
      rc.memoryclass = (int) ( Runtime.getRuntime().maxMemory() / 1024 / 1024 );
      rc.suspectNodes = suspectNodes;
      rc.badTRs = badTRSet;
      rc.inverseRouting = rand.nextBoolean();

      RoutingEngine re = new RoutingEngine( "mytrack", "mylog", args[0], wplist, rc );
      re.doSearch( radius );
      if ( re.getErrorMessage() != null )
      {
        System.out.println( re.getErrorMessage() );
      }
     }
    }
      // write tr-suspects to file      
      String suspectsFile = "deadend.suspects";
      BufferedWriter bw = new BufferedWriter( new FileWriter( new File( suspectsFile ) ) );
      for( Long suspect : suspectNodes.keySet() )
      {
        SuspectInfo si = suspectNodes.get( suspect );
        bw.write( suspect + " " + si.prio + " " + si.triggers + "\r\n" );
      }
      bw.close();

  }
  
  private static CompactLongSet readBadTrs( File f ) throws Exception
  {
	CompactLongSet set = new CompactLongSet();
	  
    BufferedReader br = new BufferedReader( new FileReader( f ) );
    for(;;)
    {
      String line = br.readLine();
      if ( line == null ) break;
      StringTokenizer tk = new StringTokenizer( line );
      long id = Long.parseLong( tk.nextToken() );
      set.add( id );
    }
    br.close();
    return new FrozenLongSet(set);
  }
  
}
