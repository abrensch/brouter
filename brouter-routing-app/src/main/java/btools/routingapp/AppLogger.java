package btools.routingapp;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Date;

import android.os.Environment;


/**
 * static logger interface to be used in the android app
 */
public class AppLogger
{
    private static FileWriter debugLogWriter = null;
    private static boolean initDone = false;
    
    private static void init()
    {
      try
      {
        // open logfile if existing
    	File sd = Environment.getExternalStorageDirectory();
    	if ( sd == null ) return;
        File debugLog = new File( sd, "Android/data/btools.routingapp/files/brouterapp.txt" );
        if ( debugLog.exists() )
        {
          debugLogWriter = new FileWriter( debugLog, true );
        }
      }
      catch( IOException ioe ) {}
    }

    /**
	 * log an info trace to the app log file, if any
	 */
    public static boolean isLogging()
    {
    	if ( !initDone )
    	{
    	  initDone = true;
    	  init();
    	  log( "logging started at " + new Date() );
    	}
    	return debugLogWriter != null;
    }

    /**
	 * log an info trace to the app log file, if any
	 */
    public static void log( String msg )
    {
      if ( isLogging() )
      {
        try
        {
    	  debugLogWriter.write( msg );
    	  debugLogWriter.write( '\n' );
    	  debugLogWriter.flush();
        }
        catch( IOException e )
        {
          throw new RuntimeException( "cannot write brouterapp.txt: " + e );
        }
      }
    }

    /**
	 * Format an exception using 
	 */
    public static String formatThrowable( Throwable t )
    {
      StringWriter sw = new StringWriter();
      PrintWriter pw = new PrintWriter( sw );
      t.printStackTrace( pw );
      return sw.toString();
    }

}
