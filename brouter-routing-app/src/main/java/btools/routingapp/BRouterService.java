package btools.routingapp;


import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.StringTokenizer;

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
		public String getTrackFromParams(Bundle params) throws RemoteException
		{
            BRouterWorker worker = new BRouterWorker();

            // get base dir from private file
            String baseDir = null;
            InputStream configInput = null;
            try
            {
              configInput = openFileInput( "config.dat" );
              BufferedReader br = new BufferedReader( new InputStreamReader (configInput ) );
              baseDir = br.readLine();
            }
            catch( Exception e ) {}
            finally
            {
              if ( configInput != null  ) try { configInput.close(); } catch( Exception ee ) {}
            }
            
            String fast = params.getString( "fast" );
            boolean isFast = "1".equals( fast ) || "true".equals( fast ) || "yes".equals( fast );
            String mode_key = params.getString( "v" ) + "_" + (isFast ? "fast" : "short");

            boolean configFound = false;
            
            BufferedReader br = null;
            try
            {
              String modesFile = baseDir + "/brouter/modes/serviceconfig.dat";
              br = new BufferedReader( new FileReader (modesFile ) );
              worker.segmentDir = baseDir + "/brouter/segments2";
              for(;;)
              {
                String line = br.readLine();
                if ( line == null ) break;
                ServiceModeConfig smc = new ServiceModeConfig( line );
                if ( !smc.mode.equals( mode_key ) ) continue;
                worker.profilePath = baseDir + "/brouter/profiles2/" + smc.profile + ".brf";
                worker.rawTrackPath = baseDir + "/brouter/modes/" + mode_key + "_rawtrack.dat";
                
                CoordinateReader cor = CoordinateReader.obtainValidReader( baseDir );
                worker.nogoList = new ArrayList<OsmNodeNamed>();
                // veto nogos by profiles veto list
                for(OsmNodeNamed nogo : cor.nogopoints )
                {
                  if ( !smc.nogoVetos.contains( nogo.ilon + "," + nogo.ilat ) )
                  {
                	  worker.nogoList.add( nogo );
                  }
                }
                configFound = true;
              }                
            }
            catch( Exception e )
            {
              return "no brouter service config found, mode " + mode_key;
            }
            finally
            {
              if ( br != null  ) try { br.close(); } catch( Exception ee ) {}
            }
            
            if ( !configFound )
            {
              return "no brouter service config found for mode " + mode_key;
            }

			try
			{
				return worker.getTrackFromParams(params);
			}
			catch( IllegalArgumentException iae )
			{
			  return iae.getMessage();
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
