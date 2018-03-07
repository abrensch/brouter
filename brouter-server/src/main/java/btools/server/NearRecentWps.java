package btools.server;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.TreeMap;
import java.util.TreeSet;

import btools.router.OsmNodeNamed;
import btools.router.OsmTrack;
import btools.router.RoutingContext;
import btools.router.RoutingEngine;
import btools.server.request.ProfileUploadHandler;
import btools.server.request.RequestHandler;
import btools.server.request.ServerHandler;

public class NearRecentWps 
{
  private static OsmNodeNamed[] recentWaypoints = new OsmNodeNamed[2000];
  private static int nextRecentIndex = 0;

  public static void add( List<OsmNodeNamed> wplist )
  {
    synchronized( recentWaypoints )
    {
      for( OsmNodeNamed wp : wplist )
      {
        recentWaypoints[nextRecentIndex++] = wp;
        if ( nextRecentIndex >= recentWaypoints.length )
        {
          nextRecentIndex = 0;
        }
      }
    }
  }

  public static int count( long id )
  {
    int cnt = 0;
    int ilon = (int) ( id >> 32 );
    int ilat = (int) ( id & 0xffffffff );
    synchronized( recentWaypoints )
    {
      for ( int i=0; i<recentWaypoints.length; i++ )
      {
        OsmNodeNamed n = recentWaypoints[i];
        if ( n != null )
        {
          int dlat = ilat - n.ilat;
          int dlon = ilon - n.ilon;
          if ( dlat > -29999 && dlat < 29999 && dlon > -39999 && dlon < 39999 )
          {
            cnt++;
          }
        }
      }
    }
    return cnt;
  }
}
