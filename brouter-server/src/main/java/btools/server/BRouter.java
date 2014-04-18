package btools.server;

import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;

import btools.router.OsmNodeNamed;
import btools.router.RoutingContext;
import btools.router.RoutingEngine;

public class BRouter
{
  public static void main(String[] args) throws Exception
  {
    if ( args.length == 2) // cgi-input-mode
    {
      try
      {
        String queryString = args[1];
        int sepIdx = queryString.indexOf( '=' );
        if ( sepIdx >= 0 ) queryString = queryString.substring( sepIdx + 1 );
        queryString = URLDecoder.decode( queryString, "ISO-8859-1" );
        int ntokens = 1;
        for( int ic = 0; ic<queryString.length(); ic++ )
        {
          if ( queryString.charAt(ic) == '_' ) ntokens++;
        }
        String[] a2 = new String[ntokens + 1];
        int idx = 1;
        int pos = 0;
        for(;;)
        {
          int p = queryString.indexOf( '_', pos );
          if ( p < 0 )
          {
            a2[idx++] = queryString.substring( pos );
            break;
          }
          a2[idx++] = queryString.substring( pos, p );
          pos = p+1;
        }

        // cgi-header
        System.out.println( "Content-type: text/plain" );
        System.out.println();
        OsmNodeNamed from = readPosition( a2, 1, "from" );
        OsmNodeNamed to = readPosition( a2, 3, "to" );


        int airDistance = from.calcDistance( to );

        String airDistanceLimit = System.getProperty( "airDistanceLimit" );
        if ( airDistanceLimit != null )
        {
          int maxKm = Integer.parseInt( airDistanceLimit );
          if ( airDistance > maxKm * 1000 )
          {
            System.out.println( "airDistance " + (airDistance/1000) + "km exceeds limit for online router (" + maxKm + "km)" );
            return;
          }
        }

      long maxRunningTime = 60000; // the cgi gets a 1 Minute timeout
      String sMaxRunningTime = System.getProperty( "maxRunningTime" );
      if ( sMaxRunningTime != null )
      {
        maxRunningTime = Integer.parseInt( sMaxRunningTime ) * 1000;
      }

        List<OsmNodeNamed> wplist = new ArrayList<OsmNodeNamed>();
        wplist.add( from );
        wplist.add( to );

        RoutingEngine re = new RoutingEngine( null, null, args[0], wplist, readRoutingContext(a2) );
        re.doRun( maxRunningTime );
        if ( re.getErrorMessage() != null )
        {
          System.out.println( re.getErrorMessage() );
        }
      }
      catch( Throwable e )
      {
        System.out.println( "unexpected exception: " + e );
      }
      System.exit(0);
    }
    System.out.println("BRouter 0.9.9 / 18042014 / abrensch");
    if ( args.length < 6 )
    {
      System.out.println("Find routes in an OSM map");
      System.out.println("usage: java -jar brouter.jar <segmentdir> <lon-from> <lat-from> <lon-to> <lat-to> <profile>");
      return;
    }
    List<OsmNodeNamed> wplist = new ArrayList<OsmNodeNamed>();
    wplist.add( readPosition( args, 1, "from" ) );
    wplist.add( readPosition( args, 3, "to" ) );
    RoutingEngine re = new RoutingEngine( "mytrack", "mylog", args[0], wplist, readRoutingContext(args) );
    re.doRun( 0 );
    if ( re.getErrorMessage() != null )
    {
    	System.out.println( re.getErrorMessage() );
    }
  }


  private static OsmNodeNamed readPosition( String[] args, int idx, String name )
  {
    OsmNodeNamed n = new OsmNodeNamed();
    n.name = name;
    n.ilon = (int)( ( Double.parseDouble( args[idx  ] ) + 180. ) *1000000. + 0.5);
    n.ilat = (int)( ( Double.parseDouble( args[idx+1] ) +  90. ) *1000000. + 0.5);
    return n;
  }

  private static RoutingContext readRoutingContext( String[] args )
  {
    RoutingContext c = new RoutingContext();
    if ( args.length > 5 )
    {
      c.localFunction = args[5];
      if ( args.length > 6 )
      {
        c.setAlternativeIdx( Integer.parseInt( args[6] ) );
      }
    }
    return c;
  }
}
