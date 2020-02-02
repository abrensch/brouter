package btools.server;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.TreeMap;
import java.util.zip.GZIPOutputStream;

import btools.router.OsmNodeNamed;
import btools.router.OsmTrack;
import btools.router.RoutingContext;
import btools.router.RoutingEngine;
import btools.server.request.ProfileUploadHandler;
import btools.server.request.RequestHandler;
import btools.server.request.ServerHandler;
import btools.util.StackSampler;

public class RouteServer extends Thread
{
  public static final String PROFILE_UPLOAD_URL = "/brouter/profile";
  static final String HTTP_STATUS_OK = "200 OK";
  static final String HTTP_STATUS_BAD_REQUEST = "400 Bad Request";
  static final String HTTP_STATUS_FORBIDDEN = "403 Forbidden";
  static final String HTTP_STATUS_NOT_FOUND = "404 Not Found";
  static final String HTTP_STATUS_INTERNAL_SERVER_ERROR = "500 Internal Server Error";

	public ServiceContext serviceContext;

  private Socket clientSocket = null;
  private RoutingEngine cr = null;
  private volatile boolean terminated;

  public void stopRouter()
  {
    RoutingEngine e = cr;
    if ( e != null ) e.terminate();
  }
    
  private static DateFormat tsFormat = new SimpleDateFormat( "dd.MM.yy HH:mm", new Locale( "en", "US" ) );

  private static String formattedTimestamp()
  {
    synchronized( tsFormat )
    {
      return tsFormat.format( new Date( System.currentTimeMillis() ) );
    }
  }

  public void run()
  {
          BufferedReader br = null;
          BufferedWriter bw = null;
          try
          {
            br = new BufferedReader( new InputStreamReader( clientSocket.getInputStream() , "UTF-8") );
            bw = new BufferedWriter( new OutputStreamWriter( clientSocket.getOutputStream(), "UTF-8" ) );

            // first line
            String getline = null;
            String agent = null;
            String encodings = null;

            // more headers until first empty line
            for(;;)
            {
              // headers
              String line = br.readLine();
              if ( line == null )
              {
                writeHttpHeader(bw, HTTP_STATUS_BAD_REQUEST);
                bw.flush();
                return;
              }
              if ( line.length() == 0 )
              {
                break;
              }
              if ( getline == null )
              {
                getline = line;
              }
              if ( line.startsWith( "User-Agent: " ) )
              {
                agent = line.substring( "User-Agent: ".length() );
              }
              if ( line.startsWith( "Accept-Encoding: " ) )
              {
                encodings = line.substring( "Accept-Encoding: ".length() );
              }
            }
            
            String excludedAgents = System.getProperty( "excludedAgents" );
            if ( agent != null && excludedAgents != null )
            {
              StringTokenizer tk = new StringTokenizer( excludedAgents, "," );
              while( tk.hasMoreTokens() )
              {
                if ( agent.indexOf( tk.nextToken() ) >= 0 )
                {
                  writeHttpHeader( bw, HTTP_STATUS_FORBIDDEN );
                  bw.write( "Bad agent: " + agent );
                  bw.flush();
                  return;
                }
              }
            }

            if ( getline.startsWith("GET /favicon.ico") )
            {
              writeHttpHeader( bw, HTTP_STATUS_NOT_FOUND );
              bw.flush();
              return;
            }
            if ( getline.startsWith("GET /robots.txt") )
            {
              writeHttpHeader( bw, HTTP_STATUS_OK );
              bw.write( "User-agent: *\n" );
              bw.write( "Disallow: /\n" );
              bw.flush();
              return;
            }

            InetAddress ip = clientSocket.getInetAddress();
            System.out.println( formattedTimestamp() + " ip=" + (ip==null ? "null" : ip.toString() ) + " -> " + getline );

            String url = getline.split(" ")[1];
            HashMap<String,String> params = getUrlParams(url);

            long maxRunningTime = getMaxRunningTime();

            RequestHandler handler;
            if ( params.containsKey( "lonlats" ) && params.containsKey( "profile" ) )
            {
            	handler = new ServerHandler( serviceContext, params );
            }
            else if ( url.startsWith( PROFILE_UPLOAD_URL ) )
            {
              if ( getline.startsWith("OPTIONS") )
              {
                // handle CORS preflight request (Safari)
                String corsHeaders = "Access-Control-Allow-Methods: GET, POST\n"
                                   + "Access-Control-Allow-Headers: Content-Type\n";
                writeHttpHeader( bw, "text/plain", null, corsHeaders, HTTP_STATUS_OK );
                bw.flush();
                return;
              }
              else
              {
                writeHttpHeader(bw, "application/json", HTTP_STATUS_OK);

                String profileId = null;
                if ( url.length() > PROFILE_UPLOAD_URL.length() + 1 )
                {
                  // e.g. /brouter/profile/custom_1400767688382
                  profileId = url.substring(PROFILE_UPLOAD_URL.length() + 1);
                }

                ProfileUploadHandler uploadHandler = new ProfileUploadHandler( serviceContext );
                uploadHandler.handlePostRequest( profileId, br, bw );

                bw.flush();
                return;
              }
            }
            else if ( url.startsWith( "/brouter/suspects" ) )
            {
              writeHttpHeader(bw, url.endsWith( ".json" ) ? "application/json" : "text/html", HTTP_STATUS_OK);
              SuspectManager.process( url, bw );
              return;
            }
            else
            {
              writeHttpHeader( bw, HTTP_STATUS_NOT_FOUND );
              bw.flush();
              return;
            }
            RoutingContext rc = handler.readRoutingContext();
            List<OsmNodeNamed> wplist = handler.readWayPointList();

            if ( wplist.size() < 10 )
            {
              NearRecentWps.add( wplist );
            }
            for( Map.Entry<String,String> e : params.entrySet() )
            {
              if ( "timode".equals( e.getKey() ) )
              {
                rc.turnInstructionMode = Integer.parseInt( e.getValue() );
              }
              else if ( "heading".equals( e.getKey() ) )
              {
                rc.startDirection = Integer.valueOf( Integer.parseInt( e.getValue() ) );
                rc.forceUseStartDirection = true;
              }
              else if ( e.getKey().startsWith( "profile:" ) )
              {
                if ( rc.keyValues == null )
                {
                  rc.keyValues = new HashMap<String,String>();
                }
                rc.keyValues.put( e.getKey().substring( 8 ), e.getValue() );
              }
            }
            cr = new RoutingEngine( null, null, serviceContext.segmentDir, wplist, rc );
            cr.quite = true;
            cr.doRun( maxRunningTime );

            if ( cr.getErrorMessage() != null )
            {
              writeHttpHeader(bw, HTTP_STATUS_INTERNAL_SERVER_ERROR);
              bw.write( cr.getErrorMessage() );
              bw.write( "\n" );
            }
            else
            {
              OsmTrack track = cr.getFoundTrack();
              
              String headers = encodings == null || encodings.indexOf( "gzip" ) < 0 ? null : "Content-Encoding: gzip\n";
              writeHttpHeader(bw, handler.getMimeType(), handler.getFileName(), headers, HTTP_STATUS_OK );
              if ( track != null )
              {
                if ( headers != null ) // compressed
                {
                   java.io.ByteArrayOutputStream baos = new ByteArrayOutputStream();
                   Writer w = new OutputStreamWriter( new GZIPOutputStream( baos ), "UTF-8" );
                   w.write( handler.formatTrack(track) );
                   w.close();
                   bw.flush();
                   clientSocket.getOutputStream().write( baos.toByteArray() );
                }
                else
                {
                  bw.write( handler.formatTrack(track) );
                }
              }
            }
            bw.flush();
          }
          catch (Throwable e)
          {
             try {
               writeHttpHeader(bw, HTTP_STATUS_INTERNAL_SERVER_ERROR);
               bw.flush();
             }
             catch (IOException _ignore){}
             System.out.println("RouteServer got exception (will continue): "+e);
             e.printStackTrace();
          }
          finally
          {
              cr = null;
              if ( br != null ) try { br.close(); } catch( Exception e ) {}
              if ( bw != null ) try { bw.close(); } catch( Exception e ) {}
              if ( clientSocket != null ) try { clientSocket.close(); } catch( Exception e ) {}
              terminated = true;
          }
  }

  public static void main(String[] args) throws Exception
  {
        System.out.println("BRouter 1.5.5 / 22072019");
        if ( args.length != 5 && args.length != 6)
        {
          System.out.println("serve BRouter protocol");
          System.out.println("usage: java RouteServer <segmentdir> <profiledir> <customprofiledir> <port> <maxthreads> [bindaddress]");
          return;
        }

        ServiceContext serviceContext = new ServiceContext();
        serviceContext.segmentDir = args[0];
        serviceContext.profileDir = args[1];
        System.setProperty( "profileBaseDir", serviceContext.profileDir );
        String dirs = args[2];
        StringTokenizer tk = new StringTokenizer( dirs, "," );
        serviceContext.customProfileDir = tk.nextToken();
        serviceContext.sharedProfileDir = tk.hasMoreTokens() ? tk.nextToken() : serviceContext.customProfileDir;

        int maxthreads = Integer.parseInt( args[4] );

        TreeMap<Long,RouteServer> threadMap = new TreeMap<Long,RouteServer>();

        ServerSocket serverSocket = args.length > 5 ? new ServerSocket(Integer.parseInt(args[3]),50,InetAddress.getByName(args[5])) : new ServerSocket(Integer.parseInt(args[3]));

        // stacksample for performance profiling
        // ( caution: start stacksampler only after successfully creating the server socket
        //   because that thread prevents the process from terminating, so the start-attempt
        //   by the watchdog cron would create zombies )
        File stackLog = new File( "stacks.txt" );
        if ( stackLog.exists() )
        {
          StackSampler stackSampler = new StackSampler( stackLog, 1000 );
          stackSampler.start();
          System.out.println( "*** sampling stacks into stacks.txt *** ");
        }

        long last_ts = 0;
        for (;;)
        {
          Socket clientSocket = serverSocket.accept();
          RouteServer server = new RouteServer();
          server.serviceContext = serviceContext;
          server.clientSocket = clientSocket;

          // cleanup thread list
          for(;;)
          {
            boolean removedItem = false;
            for (Map.Entry<Long,RouteServer> e : threadMap.entrySet())
            {
              if ( e.getValue().terminated )
              {
                threadMap.remove( e.getKey() );
                removedItem = true;
                break;
              }
            }
            if ( !removedItem ) break;
          }

          // kill thread if limit reached
          if ( threadMap.size() >= maxthreads )
          {
             Long k = threadMap.firstKey();
             RouteServer victim = threadMap.get( k );
             threadMap.remove( k );
             victim.stopRouter();
          }

          long ts = System.currentTimeMillis();
          while ( ts <=  last_ts ) ts++;
          threadMap.put( Long.valueOf( ts ), server );
          last_ts = ts;
          server.start();
        }
  }


  private static HashMap<String,String> getUrlParams( String url ) throws UnsupportedEncodingException
  {
	  HashMap<String,String> params = new HashMap<String,String>();
	  String decoded = URLDecoder.decode( url, "UTF-8" );
	  StringTokenizer tk = new StringTokenizer( decoded, "?&" );
	  while( tk.hasMoreTokens() )
	  {
	    String t = tk.nextToken();
	    StringTokenizer tk2 = new StringTokenizer( t, "=" );
	    if ( tk2.hasMoreTokens() )
	    {
	      String key = tk2.nextToken();
	      if ( tk2.hasMoreTokens() )
	      {
	        String value = tk2.nextToken();
	        params.put( key, value );
	      }
	    }
	  }
	  return params;
  }

  private static long getMaxRunningTime() {
    long maxRunningTime = 60000;
    String sMaxRunningTime = System.getProperty( "maxRunningTime" );
    if ( sMaxRunningTime != null )
    {
      maxRunningTime = Integer.parseInt( sMaxRunningTime ) * 1000;
    }
    return maxRunningTime;
  }

  private static void writeHttpHeader( BufferedWriter bw, String status ) throws IOException
  {
    writeHttpHeader( bw, "text/plain", status );
  }

  private static void writeHttpHeader( BufferedWriter bw, String mimeType, String status ) throws IOException
  {
    writeHttpHeader( bw, mimeType, null, status );
  }

  private static void writeHttpHeader( BufferedWriter bw, String mimeType, String fileName, String status ) throws IOException
  {
    writeHttpHeader( bw, mimeType, fileName, null, status);
  }

  private static void writeHttpHeader( BufferedWriter bw, String mimeType, String fileName, String headers, String status ) throws IOException
  {
    // http-header
    bw.write( String.format("HTTP/1.1 %s\n", status) );
    bw.write( "Connection: close\n" );
    bw.write( "Content-Type: " + mimeType + "; charset=utf-8\n" );
    if ( fileName != null )
    {
      bw.write( "Content-Disposition: attachment; filename=\"" + fileName + "\"\n" );
    }
    bw.write( "Access-Control-Allow-Origin: *\n" );
    if ( headers != null )
    {
      bw.write( headers );
    }
    bw.write( "\n" );
  }
}
