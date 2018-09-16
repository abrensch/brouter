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
    String country = "";
    String filter = null;

    while ( tk.hasMoreTokens() )
    {
      String c = tk.nextToken();
      if ( "all".equals( c ) || "new".equals( c ) )
      {
        filter = c;
        break;
      }
      country += "/" + c;
    }
    
    if ( filter == null ) // generate country list
    {
      bw.write( "<table>\n" );
      File countryParent = new File( "worldpolys" + country );
      File[] files = countryParent.listFiles();
      TreeSet<String> names = new TreeSet<String>();
      for ( File f : files )
      {
        String name = f.getName();
        if ( name.endsWith( ".poly" ) )
        {
          names.add( name.substring( 0, name.length() - 5 ) );
        }
      }
      for ( String c : names )
      {
        String url2 = "/brouter/suspects" + country + "/" + c;
        String linkNew = "<td>&nbsp;<a href=\"" + url2 + "/new\">new</a>&nbsp;</td>";
        String linkAll = "<td>&nbsp;<a href=\"" + url2 + "/all\">all</a>&nbsp;</td>";
        
        String linkSub = "";
        if ( new File( countryParent, c ).exists() )
        {
          linkSub = "<td>&nbsp;<a href=\"" + url2 + "\">sub-regions</a>&nbsp;</td>";
        }
        bw.write( "<tr><td>" + c + "</td>" + linkNew + linkAll + linkSub + "\n" );
      }
      bw.write( "</table>\n" );
      bw.write( "</body></html>\n" );
      bw.flush();
      return;
    }

    File polyFile = new File( "worldpolys" + country + ".poly" );
    if ( !polyFile.exists() )
    {
      bw.write( "polygon file for country '" + country + "' not found\n" );
      bw.write( "</body></html>\n" );
      bw.flush();
      return;
    }
    
    Area polygon = new Area( polyFile );

    File suspectFile = new File( "worldsuspects.txt" );
    if ( !suspectFile.exists() )
    {
      bw.write( "suspect file worldsuspects.txt not found\n" );
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
      bw.write( "watchlist for " + country + "\n" );
      bw.write( "<br><a href=\"/brouter/suspects\">back to country list</a><br><br>\n" );

      SuspectList suspects = getAllSuspects( suspectFile );
      for( int isuspect = 0; isuspect<suspects.cnt; isuspect++ )
      {
        id = suspects.ids[isuspect];

        if ( !polygon.isInBoundingBox( id ) )
        {
          continue; // not in selected polygon (pre-check)
        }
        if ( new File( "falsepositives/" + id ).exists() )
        {
          continue; // known false positive
        }
        File confirmedEntry = new File( "confirmednegatives/" + id );
        if ( !( isFixed( id, suspectFile ) && confirmedEntry.exists() ) )
        {
          continue;
        }
        long age = System.currentTimeMillis() - confirmedEntry.lastModified();
        if ( age / 1000 < 3600 * 24 * 8 )
        {
          continue;
        }
        if ( !polygon.isInArea( id ) )
        {
          continue; // not in selected polygon
        }
        String countryId = country + "/" + filter + "/" + id;
        String hint = "&nbsp;&nbsp;&nbsp;confirmed " + formatAge( confirmedEntry ) + " ago";
        int ilon = (int) ( id >> 32 );
        int ilat = (int) ( id & 0xffffffff );
        double dlon = ( ilon - 180000000 ) / 1000000.;
        double dlat = ( ilat - 90000000 ) / 1000000.;
        String url2 = "/brouter/suspects" + countryId;
        bw.write( "<a href=\"" + url2 + "\">" + dlon + "," + dlat + "</a>" + hint + "<br>\n" );
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
        File fixedMarker = new File( "fixedsuspects/" + id );
        if ( !fixedMarker.exists() )
        {
          fixedMarker.createNewFile();
        }
        id = 0L;

        int hideDays = 0;
        if ( tk.hasMoreTokens() )
        {
          String param = tk.nextToken();
          hideDays = Integer.parseInt( param );
          fixedMarker.setLastModified( System.currentTimeMillis() + hideDays*86400000L );
        }
        fixedMarker.setLastModified( System.currentTimeMillis() + hideDays*86400000L );
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
      File configFile = new File( "configs/profile.cfg" );
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
      String url4a = "https://overpass-turbo.eu/?Q=[date:&quot;" + formatZ( weekAgo ) + "Z&quot;];way[highway]({{bbox}});out meta geom;&C="
                  + dlat + ";" + dlon + ";18&R";

      String url4b = "https://overpass-turbo.eu/?Q=(node(around%3A1%2C%7B%7Bcenter%7D%7D)-%3E.n%3Bway(bn.n)%3Brel(bn.n%3A%22via%22)%5Btype%3Drestriction%5D%3B)%3Bout%20meta%3B%3E%3Bout%20skel%20qt%3B&C="
                  + dlat + ";" + dlon + ";18&R";

      String url5 = "https://tyrasd.github.io/latest-changes/#16/" + dlat + "/" + dlon;

      if ( message != null )
      {
        bw.write( "<strong>" + message + "</strong><br><br>\n" );
      }
      bw.write( "<a href=\"" + url1 + "\">Open in BRouter-Web</a><br><br>\n" );
      bw.write( "<a href=\"" + url2 + "\">Open in OpenStreetmap</a><br><br>\n" );
      bw.write( "<a href=\"" + url3 + "\">Open in JOSM (via remote control)</a><br><br>\n" );
      bw.write( "Overpass: <a href=\"" + url4a + "\">minus one week</a> &nbsp;&nbsp; <a href=\"" + url4b + "\">node context</a><br><br>\n" );
      bw.write( "<a href=\"" + url5 + "\">Open in Latest-Changes / last week</a><br><br>\n" );
      bw.write( "<br>\n" );
      if ( isFixed( id, suspectFile ) )
      {
        bw.write( "<br><br><a href=\"/brouter/suspects/" + country + "/" + filter + "/watchlist\">back to watchlist</a><br><br>\n" );
      }
      else
      {
        bw.write( "<a href=\"/brouter/suspects" + countryId + "/falsepositive\">mark false positive (=not an issue)</a><br><br>\n" );
        File confirmedEntry = new File( "confirmednegatives/" + id );
        if ( confirmedEntry.exists() )
        {
          String prefix = "<a href=\"/brouter/suspects" + countryId + "/fixed";
          String prefix2 = " &nbsp;&nbsp;" + prefix;
          bw.write( prefix + "\">mark as fixed</a><br><br>\n" );
          bw.write( "hide for " );
          bw.write( prefix2 + "/7\">1 week</a>" );
          bw.write( prefix2 + "/30\">1 month</a>" );
          bw.write( prefix2 + "/91\">3 months</a>" );
          bw.write( prefix2 + "/182\">6 months</a><br><br>\n" );
        }
        else
        {
          bw.write( "<a href=\"/brouter/suspects" + countryId + "/confirm\">mark as a confirmed issue</a><br><br>\n" );
        }
        bw.write( "<br><br><a href=\"/brouter/suspects" + country + "/" + filter + "\">back to issue list</a><br><br>\n" );
      }
    }
    else
    {
      bw.write( filter + " suspect list for " + country + "\n" );
      bw.write( "<br><a href=\"/brouter/suspects" + country + "/" + filter + "/watchlist\">see watchlist</a>\n" );
      bw.write( "<br><a href=\"/brouter/suspects\">back to country list</a><br><br>\n" );
      int maxprio = 0;
      {
        SuspectList suspects = getAllSuspects( suspectFile );
        for( int isuspect = 0; isuspect<suspects.cnt; isuspect++ )
        {
          id = suspects.ids[isuspect];
          int prio = suspects.prios[isuspect];
          int nprio = ( ( prio + 1 ) / 2 ) * 2; // normalize (no link prios)
          if ( nprio < maxprio )
          {
            if ( maxprio == 0 )
            {
              bw.write( "current level: " + getLevelDecsription( maxprio ) + "<br><br>\n" );
            }
            break;
          }
          if ( !polygon.isInBoundingBox( id ) )
          {
            continue; // not in selected polygon (pre-check)
          }
          if ( new File( "falsepositives/" + id ).exists() )
          {
            continue; // known false positive
          }
          if ( isFixed( id, suspectFile ) )
          {
            continue; // known fixed
          }
          if ( "new".equals( filter ) && new File( "suspectarchive/" + id ).exists() )
          {
            continue; // known archived
          }
          if ( !polygon.isInArea( id ) )
          {
            continue; // not in selected polygon
          }
          if ( maxprio == 0 )
          {
            maxprio = nprio;
            bw.write( "current level: " + getLevelDecsription( maxprio ) + "<br><br>\n" );
          }
          String countryId = country + "/" + filter + "/" + id;
          File confirmedEntry = new File( "confirmednegatives/" + id );
          String hint = "";
          if ( confirmedEntry.exists() )
          {
            hint = "&nbsp;&nbsp;&nbsp;confirmed " + formatAge( confirmedEntry ) + " ago";
          }
          int ilon = (int) ( id >> 32 );
          int ilat = (int) ( id & 0xffffffff );
          double dlon = ( ilon - 180000000 ) / 1000000.;
          double dlat = ( ilat - 90000000 ) / 1000000.;
          String url2 = "/brouter/suspects" + countryId;
          bw.write( "<a href=\"" + url2 + "\">" + dlon + "," + dlat + "</a>" + hint + "<br>\n" );
        }
      }
    }
    bw.write( "</body></html>\n" );
    bw.flush();
    return;
  }
  

  private static boolean isFixed( long id, File suspectFile )
  {
    File fixedEntry = new File( "fixedsuspects/" + id );
    return fixedEntry.exists() && fixedEntry.lastModified() > suspectFile.lastModified();
  }
  
  
  private static final class SuspectList
  {
    int cnt;
    long[] ids;
    int[] prios;
    
    SuspectList( int count )
    {
      cnt = count;
      ids = new long[cnt];
      prios = new int[cnt];
    }
  }

  private static SuspectList allSuspects;
  private static Object allSuspectsSync = new Object();
  private static long allSuspectsTimestamp;
  
  private static SuspectList getAllSuspects( File suspectFile ) throws IOException
  {
    synchronized( allSuspectsSync )
    {
      if ( suspectFile.lastModified() == allSuspectsTimestamp )
      {
        return allSuspects;
      }
      allSuspectsTimestamp = suspectFile.lastModified();

      // count prios
      int[] prioCount = new int[100];
      BufferedReader r = new BufferedReader( new FileReader( suspectFile ) );
      for ( ;; )
      {
        String line = r.readLine();
        if ( line == null ) break;
        StringTokenizer tk2 = new StringTokenizer( line );
        tk2.nextToken();
        int prio = Integer.parseInt( tk2.nextToken() );
        int nprio = ( ( prio + 1 ) / 2 ) * 2; // normalize (no link prios)
        prioCount[nprio]++;
      }
      r.close();

      // sum up
      int pointer = 0;
      for( int i=99; i>=0; i-- )
      {
        int cnt = prioCount[i];
        prioCount[i] = pointer;
        pointer += cnt;
      }

      // sort into suspect list
      allSuspects = new SuspectList( pointer );
      r = new BufferedReader( new FileReader( suspectFile ) );
      for ( ;; )
      {
        String line = r.readLine();
        if ( line == null ) break;
        StringTokenizer tk2 = new StringTokenizer( line );
        long id = Long.parseLong( tk2.nextToken() );
        int prio = Integer.parseInt( tk2.nextToken() );
        int nprio = ( ( prio + 1 ) / 2 ) * 2; // normalize (no link prios)
        pointer = prioCount[nprio]++;
        allSuspects.ids[pointer] = id;
        allSuspects.prios[pointer] = prio;
      }
      r.close();
      return allSuspects;
    }  
  }  
}
