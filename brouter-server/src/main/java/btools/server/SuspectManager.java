package btools.server;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.StringTokenizer;
import java.util.TreeSet;

public class SuspectManager extends Thread
{
  private static SimpleDateFormat dfTimestampZ = new SimpleDateFormat( "yyyy-MM-dd'T'HH:mm:ss" );

  private static String formatZ( Date date )
  {
    synchronized( dfTimestampZ )
    {
      return dfTimestampZ.format( date );
    }
  }

  private static String formatAge( File f )
  {
    long age = System.currentTimeMillis() - f.lastModified();
    long minutes = age / 60000;
    if ( minutes < 60 )
    {
      return minutes + " minutes";
    }
    long hours = minutes / 60;
    if ( hours < 24 )
    {
      return hours + " hours";
    }
    long days = hours / 24;
    return days + " days";
  }

  private static String getLevelDecsription( int level )
  {
    switch( level )
    {
      case 30 : return "motorway";
      case 28 : return "trunk";
      case 26 : return "primary";
      case 24 : return "secondary";
      case 22 : return "tertiary";
      default: return "none";
    }
  }

  public static void process( String url, BufferedWriter bw ) throws IOException
  {
    bw.write( "<html><body>\n" );
    bw.write( "BRouter suspect manager. <a href=\"http://brouter.de/brouter/suspect_manager_help.html\">Help</a><br><br>\n" );

    StringTokenizer tk = new StringTokenizer( url, "/" );
    tk.nextToken();
    tk.nextToken();
    long id = 0L;
    String country = null;
    String filter = null;

    if ( tk.hasMoreTokens() )
    {
      String ctry = tk.nextToken();
      if ( new File( "suspects/suspects_" + ctry + ".txt" ).exists() )
      {
        country = ctry;

        if ( tk.hasMoreTokens() )
        {
          filter = tk.nextToken();
        }
      }
    }
    if ( country == null ) // generate country list
    {
      bw.write( "<table>\n" );
      File[] files = new File( "suspects" ).listFiles();
      TreeSet<String> names = new TreeSet<String>();
      for ( File f : files )
      {
        String name = f.getName();
        if ( name.startsWith( "suspects_" ) && name.endsWith( ".txt" ) )
        {
          names.add( name.substring( 9, name.length() - 4 ) );
        }
      }
      for ( String ctry : names )
      {
        String url2 = "/brouter/suspects/" + ctry;
        bw.write( "<tr><td>" + ctry + "</td><td>&nbsp;<a href=\"" + url2 + "/new\">new</a>&nbsp;</td><td>&nbsp;<a href=\"" + url2 + "/all\">all</a>&nbsp;</td>\n" );
      }
      bw.write( "</table>\n" );
      bw.write( "</body></html>\n" );
      bw.flush();
      return;
    }

    boolean showWatchList = false;
    if ( tk.hasMoreTokens() )
    {
      String t = tk.nextToken();
      if ( "watchlist".equals( t ) )
      {
        showWatchList = true;
      }
      else
      {
        id = Long.parseLong( t );
      }
    }

    if ( showWatchList )
    {
      File suspects = new File( "suspects/suspects_" + country + ".txt" );
      bw.write( "watchlist for " + country + "\n" );
      bw.write( "<br><a href=\"/brouter/suspects\">back to country list</a><br><br>\n" );
      if ( suspects.exists() )
      {
        BufferedReader r = new BufferedReader( new FileReader( suspects ) );
        for ( ;; )
        {
          String line = r.readLine();
          if ( line == null )
            break;
          StringTokenizer tk2 = new StringTokenizer( line );
          id = Long.parseLong( tk2.nextToken() );
          String countryId = country + "/" + filter + "/" + id;

          if ( new File( "falsepositives/" + id ).exists() )
          {
            continue; // known false positive
          }
          File fixedEntry = new File( "fixedsuspects/" + id );
          File confirmedEntry = new File( "confirmednegatives/" + id );
          if ( !( fixedEntry.exists() && confirmedEntry.exists() ) )
          {
            continue;
          }
          long age = System.currentTimeMillis() - confirmedEntry.lastModified();
          if ( age / 1000 < 3600 * 24 * 8 )
          {
            continue;
          }
          String hint = "&nbsp;&nbsp;&nbsp;confirmed " + formatAge( confirmedEntry ) + " ago";
          int ilon = (int) ( id >> 32 );
          int ilat = (int) ( id & 0xffffffff );
          double dlon = ( ilon - 180000000 ) / 1000000.;
          double dlat = ( ilat - 90000000 ) / 1000000.;
          String url2 = "/brouter/suspects/" + countryId;
          bw.write( "<a href=\"" + url2 + "\">" + dlon + "," + dlat + "</a>" + hint + "<br>\n" );
        }
        r.close();
      }
      bw.write( "</body></html>\n" );
      bw.flush();
      return;
    }

    String message = null;
    if ( tk.hasMoreTokens() )
    {
      String command = tk.nextToken();
      if ( "falsepositive".equals( command ) )
      {
        int wps = NearRecentWps.count( id );
        if ( wps < 8 )
        {
          message = "marking false-positive requires at least 8 recent nearby waypoints from BRouter-Web, found: " + wps;
        }
        else
        {
          new File( "falsepositives/" + id ).createNewFile();
          id = 0L;
        }
      }
      if ( "confirm".equals( command ) )
      {
        int wps = NearRecentWps.count( id );
        if ( wps < 2 )
        {
          message = "marking confirmed requires at least 2 recent nearby waypoints from BRouter-Web, found: " + wps;
        }
        else
        {
          new File( "confirmednegatives/" + id ).createNewFile();
        }
      }
      if ( "fixed".equals( command ) )
      {
        new File( "fixedsuspects/" + id ).createNewFile();
        id = 0L;
      }
    }
    if ( id != 0L )
    {
      String countryId = country + "/" + filter + "/" + id;

      int ilon = (int) ( id >> 32 );
      int ilat = (int) ( id & 0xffffffff );
      double dlon = ( ilon - 180000000 ) / 1000000.;
      double dlat = ( ilat - 90000000 ) / 1000000.;

      String profile = "car-eco";
      File configFile = new File( "configs/" + country + ".cfg" );
      if ( configFile.exists() )
      {
        BufferedReader br = new BufferedReader( new FileReader( configFile ) );
        profile = br.readLine();
        br.close();
      }

      String url1 = "http://brouter.de/brouter-web/#zoom=18&lat=" + dlat + "&lon=" + dlon
          + "&lonlats=" + dlon + "," + dlat + "&profile=" + profile;

      // String url1 = "http://localhost:8080/brouter-web/#map=18/" + dlat + "/"
      // + dlon + "/Mapsforge Tile Server&lonlats=" + dlon + "," + dlat;

      String url2 = "https://www.openstreetmap.org/?mlat=" + dlat + "&mlon=" + dlon + "#map=19/" + dlat + "/" + dlon + "&layers=N";

      double slon = 0.00156;
      double slat = 0.001;
      String url3 = "http://127.0.0.1:8111/load_and_zoom?left=" + ( dlon - slon )
          + "&bottom=" + ( dlat - slat ) + "&right=" + ( dlon + slon ) + "&top=" + ( dlat + slat );

      Date weekAgo = new Date( System.currentTimeMillis() - 604800000L );
      String url4 = "https://overpass-turbo.eu/?Q=[date:&quot;" + formatZ( weekAgo ) + "Z&quot;];way[highway]({{bbox}});out meta geom;&C="
                  + dlat + ";" + dlon + ";18";

      if ( message != null )
      {
        bw.write( "<strong>" + message + "</strong><br><br>\n" );
      }
      bw.write( "<a href=\"" + url1 + "\">Open in BRouter-Web</a><br><br>\n" );
      bw.write( "<a href=\"" + url2 + "\">Open in OpenStreetmap</a><br><br>\n" );
      bw.write( "<a href=\"" + url3 + "\">Open in JOSM (via remote control)</a><br><br>\n" );
      bw.write( "<a href=\"" + url4 + "\">Open in Overpass / minus one week</a><br><br>\n" );
      bw.write( "<br>\n" );
      File fixedEntry = new File( "fixedsuspects/" + id );
      if ( fixedEntry.exists() )
      {
        bw.write( "<br><br><a href=\"/brouter/suspects/" + country + "/" + filter + "/watchlist\">back to watchlist</a><br><br>\n" );
      }
      else
      {
        bw.write( "<a href=\"/brouter/suspects/" + countryId + "/falsepositive\">mark false positive (=not an issue)</a><br><br>\n" );
        File confirmedEntry = new File( "confirmednegatives/" + id );
        if ( confirmedEntry.exists() )
        {
          bw.write( "<a href=\"/brouter/suspects/" + countryId + "/fixed\">mark as fixed</a><br><br>\n" );
        }
        else
        {
          bw.write( "<a href=\"/brouter/suspects/" + countryId + "/confirm\">mark as a confirmed issue</a><br><br>\n" );
        }
        bw.write( "<br><br><a href=\"/brouter/suspects/" + country + "/" + filter + "\">back to issue list</a><br><br>\n" );
      }
    }
    else
    {
      File suspects = new File( "suspects/suspects_" + country + ".txt" );
      bw.write( filter + " suspect list for " + country + "\n" );
      bw.write( "<br><a href=\"/brouter/suspects/" + country + "/" + filter + "/watchlist\">see watchlist</a>\n" );
      bw.write( "<br><a href=\"/brouter/suspects\">back to country list</a><br><br>\n" );
      if ( suspects.exists() )
      {
        int maxprio = 0;
        for ( int pass = 1; pass <= 2; pass++ )
        {
          if ( pass == 2 )
          {
            bw.write( "current level: " + getLevelDecsription( maxprio ) + "<br><br>\n" );
          }

          BufferedReader r = new BufferedReader( new FileReader( suspects ) );
          for ( ;; )
          {
            String line = r.readLine();
            if ( line == null )
              break;
            StringTokenizer tk2 = new StringTokenizer( line );
            id = Long.parseLong( tk2.nextToken() );

            int prio = Integer.parseInt( tk2.nextToken() );
            prio = ( ( prio + 1 ) / 2 ) * 2; // normalize (no link prios)
            String countryId = country + "/" + filter + "/" + id;

            String hint = "";

            if ( new File( "falsepositives/" + id ).exists() )
            {
              continue; // known false positive
            }
            if ( new File( "fixedsuspects/" + id ).exists() )
            {
              continue; // known fixed
            }
            if ( "new".equals( filter ) && new File( "suspectarchive/" + id ).exists() )
            {
              continue; // known fixed
            }
            if ( pass == 1 )
            {
              if ( prio > maxprio )
                maxprio = prio;
              continue;
            }
            else
            {
              if ( prio < maxprio )
                continue;
            }
            File confirmedEntry = new File( "confirmednegatives/" + id );
            if ( confirmedEntry.exists() )
            {
              hint = "&nbsp;&nbsp;&nbsp;confirmed " + formatAge( confirmedEntry ) + " ago";
            }
            int ilon = (int) ( id >> 32 );
            int ilat = (int) ( id & 0xffffffff );
            double dlon = ( ilon - 180000000 ) / 1000000.;
            double dlat = ( ilat - 90000000 ) / 1000000.;
            String url2 = "/brouter/suspects/" + countryId;
            bw.write( "<a href=\"" + url2 + "\">" + dlon + "," + dlat + "</a>" + hint + "<br>\n" );
          }
          r.close();
        }
      }
    }
    bw.write( "</body></html>\n" );
    bw.flush();
    return;
  }
  
}
