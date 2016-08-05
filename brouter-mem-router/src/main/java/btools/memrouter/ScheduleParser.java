/**
 * Parser for a train schedule
 *
 * @author ab
 */
package btools.memrouter;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.StringTokenizer;

import btools.expressions.BExpressionContextWay;

final class ScheduleParser
{
  public static Map<String, StationNode> parseTrainTable( File[] files, GraphLoader graph, BExpressionContextWay expctxWay )
  {
    ScheduledLine currentLine = null;
    StationNode lastStationInLine = null;

    boolean readingLocations = false;

    Map<String, StationNode> stationMap = new HashMap<String, StationNode>();

    for ( File file : files )
    {
      BufferedReader br = null;
      try
      {
        br = new BufferedReader( new InputStreamReader( new FileInputStream( file ) , "ISO-8859-1" ) );
        for ( ;; )
        {
          String line = br.readLine();
          if ( line == null )
            break;
          line = line.trim();
          if ( line.length() == 0 )
            continue;

          if ( line.startsWith( "#" ) )
            continue;

          if ( line.startsWith( "-- locations" ) )
          {
            readingLocations = true;
            continue;
          }
          if ( line.startsWith( "-- trainline" ) )
          {
            readingLocations = false;
            currentLine = new ScheduledLine();
            currentLine.name = line.substring( "-- trainline".length() ).trim();
            lastStationInLine = null;
            continue;
          }
          if ( readingLocations )
          {
            StationNode station = new StationNode();

            // Eschborn 50.14323,8.56112
            StringTokenizer tk = new StringTokenizer( line, " \t" );
            station.name = tk.nextToken();

            if ( stationMap.containsKey( station.name ) )
            {
              System.out.println( "skipping station name already known: " + station.name );
              continue;
            }

            int locIdx = 0;
            String loc = null;
            int elev = 0;
            int nconnections = 0;
            while (tk.hasMoreTokens() || locIdx == 1)
            {
              if ( tk.hasMoreTokens() )
              {
                loc = tk.nextToken();
              }
              StringTokenizer tloc = new StringTokenizer( loc, "," );
              int ilat = (int) ( ( Double.parseDouble( tloc.nextToken() ) + 90. ) * 1000000. + 0.5 );
              int ilon = (int) ( ( Double.parseDouble( tloc.nextToken() ) + 180. ) * 1000000. + 0.5 );
              if ( locIdx == 0 )
              {
                station.ilat = ilat;
                station.ilon = ilon;
              }
              else
              {
                OsmNodeP pos = new OsmNodeP();
                pos.ilat = ilat;
                pos.ilon = ilon;

                OsmNodeP node = graph.matchNodeForPosition( pos, expctxWay, false );
                if ( node != null )
                {
                  elev += node.selev;
                  nconnections++;

                  // link station to connecting node
                  OsmLinkP link = new OsmLinkP( station, node );
                  link.descriptionBitmap = null;
                  station.addLink( link );
                  node.addLink( link );

                  int distance = station.calcDistance( node );
                  System.out.println( "matched connection for station " + station.name + " at " + distance + " meter" );
                }
              }
              locIdx++;
            }
            if ( nconnections > 0 )
            {
              station.selev = (short) ( elev / nconnections );
            }
            stationMap.put( station.name, station );
          }
          else if ( currentLine != null )
          {
            int idx = line.indexOf( ' ' );
            String name = line.substring( 0, idx );
            StationNode nextStationInLine = stationMap.get( name );
            if ( nextStationInLine == null )
            {
              throw new IllegalArgumentException( "unknown station: " + name );
            }
            String value = line.substring( idx ).trim();
            int offsetMinute = 0;
            if ( lastStationInLine == null )
            {
              currentLine.schedule = new TrainSchedule( value );
            }
            else
            {
              if ( value.startsWith( "+" ) )
                value = value.substring( 1 );
              offsetMinute = Integer.parseInt( value );

              ScheduledLink link = new ScheduledLink( lastStationInLine, nextStationInLine );
              link.line = currentLine;
              link.indexInLine = currentLine.offsetMinutes.size() - 1;

              // System.out.println( "adding: " + link );
              lastStationInLine.addLink( link );
            }
            currentLine.offsetMinutes.add( Integer.valueOf( offsetMinute ) );

            lastStationInLine = nextStationInLine;
          }
        }
        System.out.println( "read " + stationMap.size() + " stations" );
      }
      catch (Exception e)
      {
        throw new RuntimeException( e );
      }
      finally
      {
        if ( br != null )
        {
          try
          {
            br.close();
          }
          catch (Exception e) { /* ignore */ }
       }
      }
    }
    return stationMap;
  }
}
