package btools.routingapp;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
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
        File debugLog = new File( sd, "brouterapp.txt" );
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
          throw new RuntimeException( "cannot write appdebug.txt: " + e );
        }
      }
    }
}
