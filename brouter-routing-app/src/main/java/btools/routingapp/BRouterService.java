package btools.routingapp;


import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import btools.router.OsmNodeNamed;

public class BRouterService extends Service
{

    @Override
	public IBinder onBind(Intent arg0) {
		Log.d(getClass().getSimpleName(), "onBind()");
		return myBRouterServiceStub;
	}

  private IBRouterService.Stub myBRouterServiceStub = new IBRouterService.Stub()
	{
    @Override
    public String getTrackFromParams( Bundle params ) throws RemoteException
    {
      logBundle( params );

      BRouterWorker worker = new BRouterWorker();

      // get base dir from private file
      String baseDir = null;
      InputStream configInput = null;
      try
      {
        configInput = openFileInput( "config.dat" );
        BufferedReader br = new BufferedReader( new InputStreamReader( configInput ) );
        baseDir = br.readLine();
      }
      catch (Exception e)
      {
      }
      finally
      {
        if ( configInput != null ) try { configInput.close(); } catch (Exception ee) {}
      }
      worker.baseDir = baseDir;
      worker.segmentDir = baseDir + "/brouter/segments4";

      String remoteProfile = params.getString( "remoteProfile" );

      if ( remoteProfile == null )
      {
         remoteProfile = checkForTestDummy( baseDir );
      }

      String errMsg = remoteProfile == null
          ? getConfigFromMode( worker, baseDir, params.getString( "v" ), params.getString( "fast" ) )
          : getConfigForRemoteProfile( worker, baseDir, remoteProfile );

      if ( errMsg != null )
      {
        return errMsg;
      }

      try
      {
        return worker.getTrackFromParams( params );
      }
      catch (IllegalArgumentException iae)
      {
        return iae.getMessage();
      }
    }

    private String getConfigFromMode( BRouterWorker worker, String baseDir, String mode, String fast )
    {
      boolean isFast = "1".equals( fast ) || "true".equals( fast ) || "yes".equals( fast );
      String mode_key = mode + "_" + ( isFast ? "fast" : "short" );

      BufferedReader br = null;
      try
      {
        String modesFile = baseDir + "/brouter/modes/serviceconfig.dat";
        br = new BufferedReader( new FileReader( modesFile ) );
        for ( ;; )
        {
          String line = br.readLine();
          if ( line == null )
            break;
          ServiceModeConfig smc = new ServiceModeConfig( line );
          if ( !smc.mode.equals( mode_key ) )
            continue;
          worker.profileName = smc.profile;
          worker.profilePath = baseDir + "/brouter/profiles2/" + smc.profile + ".brf";
          worker.rawTrackPath = baseDir + "/brouter/modes/" + mode_key + "_rawtrack.dat";

          CoordinateReader cor = CoordinateReader.obtainValidReader( baseDir, worker.segmentDir, true );
          worker.nogoList = new ArrayList<OsmNodeNamed>();
          // veto nogos by profiles veto list
          for ( OsmNodeNamed nogo : cor.nogopoints )
          {
            if ( !smc.nogoVetos.contains( nogo.ilon + "," + nogo.ilat ) )
            {
              worker.nogoList.add( nogo );
            }
          }
          return null;
        }
      }
      catch (Exception e)
      {
        return "no brouter service config found, mode " + mode_key;
      }
      finally
      {
        if ( br != null  ) try { br.close(); } catch( Exception ee ) {}
      }
      return "no brouter service config found for mode " + mode_key;
    }

    private String getConfigForRemoteProfile( BRouterWorker worker, String baseDir, String remoteProfile )
    {
      worker.profileName = "remote";
      worker.profilePath = baseDir + "/brouter/profiles2/remote.brf";
      worker.rawTrackPath = baseDir + "/brouter/modes/remote_rawtrack.dat";

      // store profile only if not identical (to preserve timestamp)
      byte[] profileBytes = remoteProfile.getBytes();
      File profileFile = new File( worker.profilePath );

      try
      {
        // add nogos from waypoint database
        CoordinateReader cor = CoordinateReader.obtainValidReader( baseDir, worker.segmentDir, true );
        worker.nogoList = new ArrayList<OsmNodeNamed>( cor.nogopoints );

        if ( !fileEqual( profileBytes, profileFile ) )
        {
          OutputStream os = null;
          try
          {
            os = new FileOutputStream( profileFile );
            os.write( profileBytes );
          }
          finally
          {
            if ( os != null ) try { os.close(); } catch( IOException io ) {}
          }
        }
      }
      catch( Exception e )
      {
        return "error caching remote profile: " + e;
      }
      return null;
    }

    private boolean fileEqual( byte[] fileBytes, File file ) throws Exception
    {
      if ( !file.exists() )
      {
        return false;
      }
      int nbytes = fileBytes.length;
      int pos = 0;
      int blen = 8192;
      byte[] buf = new byte[blen];
      InputStream is = null;
      try
      {
        is = new FileInputStream( file );
        while( pos < nbytes )
        {
          int len = is.read( buf, 0, blen );
          if ( len <= 0 ) return false;
          if ( pos + len > nbytes ) return false;
          for( int j=0; j<len; j++ )
          {
            if ( fileBytes[pos++] != buf[j] )
            {
              return false;
            }
          }
        }
        return true;
      }
      finally
      {
        if ( is != null ) try { is.close(); } catch( IOException io ) {}
      }
    }

    private String checkForTestDummy( String baseDir )
    {
      File testdummy = new File( baseDir + "/brouter/profiles2/remotetestdummy.brf" );
      if ( !testdummy.exists() ) return null;
      BufferedReader br = null;
      StringBuilder sb = new StringBuilder();
      try
      {
        br = new BufferedReader( new FileReader( testdummy ) );
        for ( ;; )
        {
          String line = br.readLine();
          if ( line == null )
            break;
          sb.append( line ).append( '\n' );
        }
        return sb.toString();
      }
      catch (Exception e)
      {
        throw new RuntimeException( "error reading " + testdummy );
      }
      finally
      {
        if ( br != null  ) try { br.close(); } catch( Exception ee ) {}
      }
    }

    private void logBundle( Bundle params )
    {
      if ( AppLogger.isLogging() )
      {
        for( String k : params.keySet() )
        {
          Object val = "remoteProfile".equals( k ) ? "<..cut..>" : params.get(k);
          String desc = "key=" + k + (val == null ? "" : " class=" + val.getClass() + " val=" + val.toString() );
          AppLogger.log( desc );
        }
      }
    }

	};
	
	@Override
	public void onCreate()
	{
		super.onCreate();
		Log.d(getClass().getSimpleName(),"onCreate()");
	}
	
	@Override
	public void onDestroy()
	{
		super.onDestroy();		
		Log.d(getClass().getSimpleName(),"onDestroy()");
	}
	
	
    // This is the old onStart method that will be called on the pre-2.0
    // platform.  On 2.0 or later we override onStartCommand() so this
    // method will not be called.
    @Override
    @SuppressWarnings("deprecation")
    public void onStart(Intent intent, int startId)
    {
    	Log.d(getClass().getSimpleName(), "onStart()");
    	handleStart(intent, startId);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId)
    {
      handleStart(intent, startId);
      return START_STICKY;
    }

    void handleStart(Intent intent, int startId)
    {
    }
}
