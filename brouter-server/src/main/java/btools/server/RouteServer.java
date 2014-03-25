package btools.server;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

import btools.router.OsmNodeNamed;
import btools.router.OsmTrack;
import btools.router.RoutingContext;
import btools.router.RoutingEngine;
import btools.server.request.RequestHandler;
import btools.server.request.ServerHandler;
import btools.server.request.YoursHandler;

public class RouteServer extends Thread
{
	public ServiceContext serviceContext;
  public short port = 17777;

  private boolean serverStopped = false;
  private ServerSocket serverSocket = null;

  public void close()
  {
    serverStopped = true;
    try
    {
      ServerSocket ss = serverSocket;
      serverSocket = null;
      ss.close();
    }
    catch( Throwable t ) {}
  }

  private void killOtherServer() throws Exception
  {
    Socket socket = new Socket( "localhost", port );
    BufferedWriter bw = null;
    try
    {
      bw = new BufferedWriter( new OutputStreamWriter( socket.getOutputStream() ) );
      bw.write( "EXIT\n" );
    }
    finally
    {
      bw.close();
      socket.close();
    }
  }

  public void run()
  {
    // first go an kill any other server on that port

    for(;;)
    {
      try
      {
        killOtherServer();
        System.out.println( "killed, waiting" );
        try { Thread.sleep( 3000 ); } catch( InterruptedException ie ) {}
      }
      catch( Throwable t ) {
          System.out.println( "not killed: " + t );
          break;
          }
    }
    try
    {
        serverSocket = new ServerSocket(port);
        for(;;)
        {
      System.out.println("RouteServer accepting connections..");
          Socket clientSocket = serverSocket.accept();
          if ( !serveRequest( clientSocket ) ) break;
        }
    }
    catch( Throwable e )
    {
      System.out.println("RouteServer main loop got exception (exiting): "+e);
      if ( serverSocket != null )
      {
        try { serverSocket.close(); } catch( Throwable t ) {}
      }
      System.exit(0);
    }

  }



  public boolean serveRequest( Socket clientSocket )
  {
          BufferedReader br = null;
          BufferedWriter bw = null;
          try
          {
            br = new BufferedReader( new InputStreamReader( clientSocket.getInputStream() ) );
            bw = new BufferedWriter( new OutputStreamWriter( clientSocket.getOutputStream() ) );

            // we just read the first line
            String getline = br.readLine();
            if ( getline == null || getline.startsWith( "EXIT") )
            {
              throw new RuntimeException( "socketExitRequest" );
            }
            if ( getline.startsWith("GET /favicon.ico") )
            {
            	return true;
            }

            String url = getline.split(" ")[1];
            HashMap<String,String> params = getUrlParams(url);

            long maxRunningTime = getMaxRunningTime();
            long startTime = System.currentTimeMillis();

            RequestHandler handler;
            if ( params.containsKey( "lonlats" ) && params.containsKey( "profile" ) )
            {
            	handler = new ServerHandler( serviceContext, params );
            }
            else
            {
            	handler =  new YoursHandler( serviceContext, params );
            }
            RoutingContext rc = handler.readRoutingContext();
            List<OsmNodeNamed> wplist = handler.readWayPointList();

            RoutingEngine cr = new RoutingEngine( null, null, serviceContext.segmentDir, wplist, rc );
            cr.quite = true;
            cr.doRun( maxRunningTime );

            // http-header
            bw.write( "HTTP/1.1 200 OK\n" );
            bw.write( "Connection: close\n" );
            bw.write( "Content-Type: text/xml; charset=utf-8\n" );
            bw.write( "Access-Control-Allow-Origin: *\n" );
            bw.write( "\n" );

            if ( cr.getErrorMessage() != null )
            {
              bw.write( cr.getErrorMessage() );
              bw.write( "\n" );
            }
            else
            {
              OsmTrack track = cr.getFoundTrack();
              if ( track != null )
              {
                bw.write( handler.formatTrack(track) );
              }
            }
            bw.flush();
          }
          catch (Throwable e)
          {
             if ( "socketExitRequest".equals( e.getMessage() ) )
             {
               return false;
             }
             System.out.println("RouteServer got exception (will continue): "+e);
             e.printStackTrace();
          }
          finally
          {
              if ( br != null ) try { br.close(); } catch( Exception e ) {}
              if ( bw != null ) try { bw.close(); } catch( Exception e ) {}
          }
          return true;
  }

  public static void main(String[] args) throws Exception
  {
        System.out.println("BRouter 0.9.8 / 12012014 / abrensch");
        if ( args.length != 3 )
        {
          System.out.println("serve YOURS protocol for BRouter");
          System.out.println("usage: java RouteServer <segmentdir> <profile-list> <port>");
          System.out.println("");
          System.out.println("serve BRouter protocol");
          System.out.println("usage: java RouteServer <segmentdir> <profiledir> <port>");
          return;
        }

        ServiceContext serviceContext = new ServiceContext();
        serviceContext.segmentDir = args[0];
        File profileMapOrDir = new File( args[1] );
        if ( profileMapOrDir.isDirectory() )
        {
        	System.setProperty( "profileBaseDir", args[1] );
        }
        else 
        {
        	serviceContext.profileMap = loadProfileMap( profileMapOrDir );
        }

        ServerSocket serverSocket = new ServerSocket(Integer.parseInt(args[2]));
        for (;;)
        {
          Socket clientSocket = serverSocket.accept();
          RouteServer server = new RouteServer();
          server.serviceContext = serviceContext;
          server.serveRequest( clientSocket );
        }
  }

  private static Map<String,String> loadProfileMap( File file ) throws IOException 
  {
    Map<String,String> profileMap = new HashMap<String,String>();

    BufferedReader pr = new BufferedReader( new InputStreamReader( new FileInputStream( file ) ) );
    for(;;)
    {
      String key = pr.readLine();
      if ( key == null ) break;
      key = key.trim();
      if ( key.length() == 0 ) continue;
      String value = pr.readLine();
      value = value.trim();
      profileMap.put( key, value );
    }
    
    return profileMap;
  }

  private static HashMap<String,String> getUrlParams( String url )
  {
	  HashMap<String,String> params = new HashMap<String,String>();
	  StringTokenizer tk = new StringTokenizer( url, "?&" );
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
}
