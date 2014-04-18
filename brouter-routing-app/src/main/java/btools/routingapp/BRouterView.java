package btools.routingapp;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import android.app.Activity;
import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Environment;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.Toast;
import btools.expressions.BExpressionContext;
import btools.mapaccess.OsmNode;
import btools.router.OsmNodeNamed;
import btools.router.OsmTrack;
import btools.router.RoutingContext;
import btools.router.RoutingEngine;

public class BRouterView extends View
{
    RoutingEngine cr;
    private int imgw;
    private int imgh;

    private int centerLon;
    private int centerLat;
    private double scaleLon;
    private double scaleLat;
    private List<OsmNodeNamed> wpList;
    private List<OsmNodeNamed> nogoList;
    private List<OsmNodeNamed> nogoVetoList;
    private OsmTrack rawTrack;
    
    private String modesDir;
    private String tracksDir;
    private String segmentDir;
    private String profileDir;
    private String profilePath;
    private String profileName;
    private String sourceHint;
    private boolean waitingForSelection = false;

    private boolean needsViaSelection;
    private boolean needsNogoSelection;
    private boolean needsWaypointSelection;

    private long lastDataTime = System.currentTimeMillis();

    private CoordinateReader cor;

    private int[] imgPixels;

       public void startSimulation() {
        }

        public void stopSimulation() {
          if ( cr != null ) cr.terminate();
        }

        public BRouterView(Context context) {
            super(context);

            DisplayMetrics metrics = new DisplayMetrics();
            ((Activity)getContext()).getWindowManager().getDefaultDisplay().getMetrics(metrics);
            imgw = metrics.widthPixels;
            imgh = metrics.heightPixels;

            // get base dir from private file
            String baseDir = ConfigHelper.getBaseDir( getContext() );
            // check if valid
            boolean bdValid = false;
            if ( baseDir != null )
            {
              File bd = new File( baseDir );
              bdValid = bd.isDirectory();
              File brd = new File( bd, "brouter" );
              if ( brd.isDirectory() )
              {
                startSetup( baseDir, false );
                return;
              }
            }
            String message = baseDir == null ?
                    "(no basedir configured previously)" :
                    "(previous basedir " + baseDir +
                  ( bdValid ? " does not contain 'brouter' subfolder)"
                            : " is not valid)" );

            ((BRouterActivity)getContext()).selectBasedir( getStorageDirectories(), guessBaseDir(), message );
            waitingForSelection = true;
        }

        public void startSetup( String baseDir, boolean storeBasedir )
        {
            File fbd = new File( baseDir );
            if ( !fbd.isDirectory() )
            {
                throw new IllegalArgumentException( "Base-directory " + baseDir + " is not a directory " );
            }
            String basedir = fbd.getAbsolutePath();

            if ( storeBasedir )
            {
            	ConfigHelper.writeBaseDir( getContext(), baseDir );
            }

            cor = null;
            try
            {
              // create missing directories
              assertDirectoryExists( "project directory", basedir + "/brouter", null );
              segmentDir = basedir + "/brouter/segments2";
              assertDirectoryExists( "data directory", segmentDir, null );
              assertDirectoryExists( "carsubset directory", segmentDir + "/carsubset", null );
              profileDir = basedir + "/brouter/profiles2";
              assertDirectoryExists( "profile directory", profileDir, "profiles2.zip" );
              modesDir = basedir + "/brouter/modes";
              assertDirectoryExists( "modes directory", modesDir, "modes.zip" );
              
              cor = CoordinateReader.obtainValidReader( basedir );
              wpList = cor.waypoints;
              nogoList = cor.nogopoints;
              nogoVetoList = new ArrayList<OsmNodeNamed>();

              sourceHint = "(coordinate-source: " + cor.basedir + cor.rootdir + ")";

              needsViaSelection = wpList.size() > 2;
              needsNogoSelection = nogoList.size() > 0;
              needsWaypointSelection = wpList.size() == 0;

              if ( cor.tracksdir != null )
              {
                tracksDir = cor.basedir + cor.tracksdir;
                assertDirectoryExists( "track directory", tracksDir, null );
              
                // output redirect: look for a pointerfile in tracksdir
                File tracksDirPointer = new File( tracksDir + "/brouter.redirect" );
                if ( tracksDirPointer.isFile() )
                {
                  tracksDir = readSingleLineFile( tracksDirPointer );
                  if ( tracksDir == null ) throw new  IllegalArgumentException( "redirect pointer file is empty: " + tracksDirPointer );
                  if ( !(new File( tracksDir ).isDirectory()) ) throw new  IllegalArgumentException(
                  	"redirect pointer file " + tracksDirPointer + " does not point to a directory: " + tracksDir );
                }
              }

              boolean segmentFound = false;
              String[] fileNames = new File( segmentDir ).list();
              for( String fileName : fileNames )
              {
                if ( fileName.endsWith( ".rd5" ) ) segmentFound = true;
              }
              File carSubset = new File( segmentDir, "carsubset" );
              if ( carSubset.isDirectory() )
              {
                fileNames = carSubset.list();
                for( String fileName : fileNames )
                {
                  if ( fileName.endsWith( ".cd5" ) ) segmentFound = true;
                }
              }
              if ( !segmentFound )
              {
                  ((BRouterActivity)getContext()).startDownloadManager();
                  waitingForSelection = true;
                  return;
              }

              fileNames = new File( profileDir ).list();
              ArrayList<String> profiles = new ArrayList<String>();

              boolean lookupsFound = false;
              for( String fileName : fileNames )
              {
                if ( fileName.endsWith( ".brf" ) )
                {
                  profiles.add( fileName.substring( 0, fileName.length()-4 ) );
                }
                if ( fileName.equals( "lookups.dat" ) ) lookupsFound = true;
              }
              if ( !lookupsFound )
              {
                  throw new IllegalArgumentException( "The profile-directory " + profileDir
                          + " does not contain the lookups.dat file."
                          + " see www.dr-brenschede.de/brouter for setup instructions." );
              }
              if ( profiles.size() == 0 )
              {
                  throw new IllegalArgumentException( "The profile-directory " + profileDir
                          + " contains no routing profiles (*.brf)."
                          + " see www.dr-brenschede.de/brouter for setup instructions." );
              }
              ((BRouterActivity)getContext()).selectProfile( profiles.toArray( new String[0]) );
            }
            catch( Exception e )
            {
              String msg = e instanceof IllegalArgumentException
                    ? e.getMessage() + ( cor == null ? "" : " (coordinate-source: " + cor.basedir + cor.rootdir + ")" )
                    : e.toString();
              ((BRouterActivity)getContext()).showErrorMessage( msg );
            }
            waitingForSelection = true;
        }

        public void continueProcessing()
        {
          waitingForSelection = false;
          invalidate();
        }

        public void updateViaList( Set<String> selectedVias )
        {
          ArrayList<OsmNodeNamed> filtered = new ArrayList<OsmNodeNamed>(wpList.size());
          for( OsmNodeNamed n : wpList )
          {
            String name = n.name;
            if ( "from".equals( name ) || "to".equals(name) || selectedVias.contains( name ) )
                filtered.add( n );
          }
          wpList = filtered;
        }

        public void updateNogoList( boolean[] enabled )
        {
          for( int i=nogoList.size()-1; i >= 0; i-- )
          {
            if ( !enabled[i] )
            {
              nogoVetoList.add( nogoList.get(i) );
              nogoList.remove( i );
            }
          }
        }

        public void pickWaypoints()
        {
          String msg = null;
          
          Map<String,OsmNodeNamed> allpoints = cor.allpoints;
          if ( allpoints == null )
          {
            allpoints = new TreeMap<String,OsmNodeNamed>();
            cor.allpoints = allpoints;
            try { cor.readFromTo(); } catch ( Exception e ) { msg = "Error reading waypoints: " + e.toString(); }
            if ( allpoints.size() < 2 ) msg = "coordinate source does not contain enough waypoints: " + allpoints.size();
            if ( allpoints.size() > 100 ) msg = "coordinate source contains too much waypoints: " + allpoints.size() + "(please use from/to/via names)";
          }
          if ( allpoints.size() < 1 ) msg = "no more wayoints available!";

          if ( msg != null )
          {
            ((BRouterActivity)getContext()).showErrorMessage( msg );
          }
          else
          {
            String[] wpts = new String[allpoints.size()];
            int i = 0;
            for( OsmNodeNamed wp : allpoints.values() ) wpts[i++] = wp.name;
System.out.println( "calling selectWaypoint..." );
            ((BRouterActivity)getContext()).selectWaypoint( wpts );
          }
        }
        
        public void updateWaypointList( String waypoint )
        {
          wpList.add( cor.allpoints.get( waypoint ) );
          cor.allpoints.remove( waypoint );
System.out.println( "updateWaypointList: " + waypoint + " wpList.size()=" + wpList.size() );
        }

        public void finishWaypointSelection()
        {
          needsWaypointSelection = false;
        }

        public void startProcessing( String profile )
        {
          profilePath = profileDir + "/" + profile + ".brf";
          profileName = profile;

          if ( needsViaSelection )
          {
            needsViaSelection = false;
            String[] availableVias = new String[wpList.size()-2];
            for( int viaidx=0; viaidx<wpList.size()-2; viaidx++ )
                availableVias[viaidx] = wpList.get( viaidx+1 ).name;
            ((BRouterActivity)getContext()).selectVias( availableVias );
            return;
          }

          if ( needsNogoSelection )
          {
            needsNogoSelection = false;
            ((BRouterActivity)getContext()).selectNogos( nogoList );
            return;
          }

          if ( needsWaypointSelection )
          {
             String msg;
             if ( wpList.size() == 0 )
             {
            	 msg = "no from/to found\n" + sourceHint;
             }
             else
             {
               msg = "current waypoint selection:\n";
               for ( int i=0; i< wpList.size(); i++ ) msg += (i>0?"->" : "") + wpList.get(i).name;
             }
            ((BRouterActivity)getContext()).showResultMessage( "Select Action", msg, wpList.size() );
             return;
          }

          try
          {
              waitingForSelection = false;

              RoutingContext rc = new RoutingContext();
              
              // TODO: TEST!
              // rc.rawTrackPath = "/mnt/sdcard/brouter/modes/bicycle_fast_rawtrack.dat";
              
              rc.localFunction = profilePath;

              int plain_distance = 0;
              int maxlon = Integer.MIN_VALUE;
              int minlon = Integer.MAX_VALUE;
              int maxlat = Integer.MIN_VALUE;
              int minlat = Integer.MAX_VALUE;

              OsmNode prev = null;
              for( OsmNode n : wpList )
              {
                maxlon = n.ilon > maxlon ? n.ilon : maxlon;
                minlon = n.ilon < minlon ? n.ilon : minlon;
                maxlat = n.ilat > maxlat ? n.ilat : maxlat;
                minlat = n.ilat < minlat ? n.ilat : minlat;
                if ( prev != null )
                {
                  plain_distance += n.calcDistance( prev );
                }
                prev = n;
              }
              toast( "Plain distance = " +  plain_distance/1000. + " km" );

              centerLon = (maxlon + minlon)/2;
              centerLat = (maxlat + minlat)/2;

              double coslat = Math.cos( ((centerLat / 1000000.) - 90.) / 57.3 ) ;
              double difflon = maxlon - minlon;
              double difflat = maxlat - minlat;

              scaleLon = imgw / (difflon*1.5);
              scaleLat = imgh / (difflat*1.5);
              if ( scaleLon < scaleLat*coslat ) scaleLat = scaleLon/coslat;
              else scaleLon = scaleLat*coslat;

              startTime = System.currentTimeMillis();
              rc.prepareNogoPoints( nogoList );
              rc.nogopoints = nogoList;

              cr = new RoutingEngine( tracksDir + "/brouter", null, segmentDir, wpList, rc );
              cr.start();
              invalidate();

            }
            catch( Exception e )
            {
              String msg = e instanceof IllegalArgumentException ? e.getMessage() : e.toString();
              toast( msg );
            }
        }

        private void assertDirectoryExists( String message, String path, String assetZip )
        {
          File f = new File( path );
          if ( !f.exists() )
          {
              f.mkdirs();
              // default contents from assets archive
              if ( assetZip != null )
              {
                  try
                  {
                    AssetManager assetManager = getContext().getAssets();
                    InputStream is = assetManager.open( assetZip );
                    ZipInputStream zis = new ZipInputStream( is );
                    byte[] data = new byte[1024];
                    for(;;)
                    {
                      ZipEntry ze = zis.getNextEntry();
                      if ( ze == null ) break;
                      String name = ze.getName();
                      FileOutputStream fos = new FileOutputStream( new File( f, name ) );
                      
                      for(;;)
                      {
                    	int len = zis.read( data, 0, 1024 );
                    	if ( len < 0 ) break;
                    	fos.write( data, 0, len );
                      }
                      fos.close();
                    }
                    is.close();
                  }
                  catch( IOException io )
                  {
                  	throw new RuntimeException( "error expanding " + assetZip + ": " + io );
                  }
            	 
              }
          }
          if ( !f.exists() || !f.isDirectory() ) throw new IllegalArgumentException( message + ": " + path + " cannot be created" );
        }

        private void paintPosition( int ilon, int ilat, int color, int with )
        {
            int lon = ilon - centerLon;
            int lat = ilat - centerLat;
            int x = imgw/2 + (int)(scaleLon*lon);
            int y = imgh/2 - (int)(scaleLat*lat);
            for( int nx=x-with; nx<=x+with; nx++)
                for( int ny=y-with; ny<=y+with; ny++)
                {
                  if ( nx >= 0 && nx < imgw && ny >= 0 && ny < imgh )
                  {
                    imgPixels[ nx+imgw*ny] = color;
                  }
                }
        }

        private void paintCircle( Canvas canvas, OsmNodeNamed n, int color, int minradius )
        {
            int lon = n.ilon - centerLon;
            int lat = n.ilat - centerLat;
            int x = imgw/2 + (int)(scaleLon*lon);
            int y = imgh/2 - (int)(scaleLat*lat);
            int ir = (int)(n.radius * 1000000. * scaleLat);
            if ( ir > minradius )
            {
              Paint paint = new Paint();
              paint.setColor( Color.RED );
              paint.setStyle( Paint.Style.STROKE );
              canvas.drawCircle( (float)x, (float)y, (float)ir, paint );
            }
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        }

        private void toast( String msg )
        {
          Toast.makeText(getContext(),  msg, Toast.LENGTH_LONG ).show();
          lastDataTime += 4000; // give time for the toast before exiting
        }


private long lastTs = System.currentTimeMillis();
private long startTime = 0L;

        @Override
        protected void onDraw(Canvas canvas) {
        	try
        	{
        		_onDraw( canvas );
        	}
        	catch( Throwable t )
        	{
        		// on out of mem, try to stop the show
        		String hint = "";
                if ( cr != null ) hint = cr.cleanOnOOM();
        		cr = null;
        		try { Thread.sleep( 2000 ); } catch( InterruptedException ie ) {}
                ((BRouterActivity)getContext()).showErrorMessage( t.toString() + hint );
                 waitingForSelection = true;
        	}
        }

        private void _onDraw(Canvas canvas) {

            if ( waitingForSelection ) return;

            long currentTs = System.currentTimeMillis();
            long diffTs = currentTs - lastTs;
            long sleeptime = 500 - diffTs;
            while ( sleeptime < 200 ) sleeptime += 500;

            try { Thread.sleep( sleeptime ); } catch ( InterruptedException ie ) {}
            lastTs = System.currentTimeMillis();

            if ( cr == null || cr.isFinished() )
            {
                if ( cr != null )
                {
                  if ( cr.getErrorMessage() != null )
                  {
                    ((BRouterActivity)getContext()).showErrorMessage( cr.getErrorMessage() );
                    cr = null;
                    waitingForSelection = true;
                    return;
                  }
                  else
                  {
                    String result = "version = BRouter-0.9.9\n"
                    + "distance = " +  cr.getDistance()/1000. + " km\n"
                    + "filtered ascend = " +  cr.getAscend() + " m\n"
                    + "plain ascend = " + cr.getPlainAscend();

                    rawTrack = cr.getFoundRawTrack();
                    
                    String title = "Success";
                    if ( cr.getAlternativeIndex() > 0 ) title += " / " + cr.getAlternativeIndex() + ". Alternative";
                    
                    ((BRouterActivity)getContext()).showResultMessage( title, result, -1 );
                    cr = null;
                    waitingForSelection = true;
                    return;
                  }
                }
                else if ( System.currentTimeMillis() > lastDataTime )
                {
                  System.exit(0);
                }
            }
            else
            {
              lastDataTime = System.currentTimeMillis();
              imgPixels = new int[imgw*imgh];

              int[] openSet = cr.getOpenSet();
              for( int si = 0; si < openSet.length; si += 2 )
              {
                paintPosition( openSet[si], openSet[si+1], 0xffffff, 1 );
              }
              // paint nogos on top (red)
              for( int ngi=0; ngi<nogoList.size(); ngi++ )
              {
                OsmNodeNamed n = nogoList.get(ngi);
                int color = 0xff0000;
                paintPosition( n.ilon, n.ilat, color, 4  );
              }

              // paint start/end/vias on top (yellow/green/blue)
              for( int wpi=0; wpi<wpList.size(); wpi++ )
              {
                OsmNodeNamed n = wpList.get(wpi);
                int color =  wpi == 0 ?  0xffff00 : wpi < wpList.size()-1 ? 0xff : 0xff00;
                paintPosition( n.ilon, n.ilat, color, 4 );
              }

              canvas.drawBitmap(imgPixels, 0, imgw, (float)0., (float)0., imgw, imgh, false, null);

              // nogo circles if any
              for( int ngi=0; ngi<nogoList.size(); ngi++ )
              {
                OsmNodeNamed n = nogoList.get(ngi);
                int color = 0xff0000;
                paintCircle( canvas, n, color, 4  );
              }


              Paint paint = new Paint();
              paint.setColor(Color.WHITE);
              paint.setTextSize(20);

              long mseconds = System.currentTimeMillis() - startTime;
              long links = cr.getLinksProcessed();
              long perS = (1000*links)/mseconds;
              String msg = "Links: " + cr.getLinksProcessed() +  " in " + (mseconds/1000) + "s (" + perS + " l/s)";

              canvas.drawText( msg, 10, 25, paint);
            }
            // and make sure to redraw asap
            invalidate();
        }

        private String guessBaseDir()
        {
          File basedir = Environment.getExternalStorageDirectory();
          try
          {
            File bd2 = new File( basedir, "external_sd" );
            ArrayList<String> basedirGuesses = new ArrayList<String>();
            basedirGuesses.add( basedir.getAbsolutePath() );

            if ( bd2.exists() )
            {
              basedir = bd2;
              basedirGuesses.add( basedir.getAbsolutePath() );
            }

            ArrayList<CoordinateReader> rl = new ArrayList<CoordinateReader>();
            for( String bdg : basedirGuesses )
            {
              rl.add( new CoordinateReaderOsmAnd(bdg) );
              rl.add( new CoordinateReaderLocus(bdg) );
              rl.add( new CoordinateReaderOrux(bdg) );
            }
            long tmax = 0;
            CoordinateReader cor = null;
            for( CoordinateReader r : rl )
            {
              long t = r.getTimeStamp();
              if ( t > tmax )
              {
                tmax = t;
                cor = r;
              }
            }
            if ( cor != null )
            {
              return cor.basedir;
            }
          }
          catch( Exception e )
          {
              System.out.println( "guessBaseDir:" + e );
          }
          return basedir.getAbsolutePath();
        }

        public void writeRawTrackToMode( String mode )
        {
          // plus eventually the raw track for re-use
          String rawTrackPath = modesDir + "/" + mode + "_rawtrack.dat";
          if ( rawTrack != null )
          {
        	try
        	{
              rawTrack.writeBinary( rawTrackPath );
        	}
            catch( Exception e ) {}
          }
          else
          {
            new File( rawTrackPath ).delete();
          }
        }
        
        public void startConfigureService()
        {
          String[] modes = new String[] {
            "foot_short", "foot_fast",
            "bicycle_short", "bicycle_fast",
            "motorcar_short", "motorcar_fast"
          };
          boolean[] modesChecked = new boolean[6];     
          
          // parse global section of profile for mode preselection
          BExpressionContext expctxGlobal = new BExpressionContext( "global" );
          expctxGlobal.readMetaData( new File( profileDir, "lookups.dat" ) );
          expctxGlobal.parseFile( new File( profilePath ), null );
          expctxGlobal.evaluate( 1L, null );
          boolean isFoot = 0.f != expctxGlobal.getVariableValue( "validForFoot" );
          boolean isBike = 0.f != expctxGlobal.getVariableValue( "validForBikes" );
          boolean isCar  = 0.f != expctxGlobal.getVariableValue( "validForCars" );
          
          if ( isFoot || isBike || isCar )
          {
            modesChecked[ 0 ] = isFoot;
            modesChecked[ 1 ] = isFoot;
            modesChecked[ 2 ] = isBike;
            modesChecked[ 3 ] = isBike;
            modesChecked[ 4 ] = isCar;
            modesChecked[ 5 ] = isCar;
          }
          else
          {
            for( int i=0; i<6; i++)
            {
              modesChecked[i] = true;
            }
          }
          String msg = "Choose service-modes to configure (" + profileName + " [" + nogoVetoList.size() + "])";
          
          ((BRouterActivity)getContext()).selectRoutingModes( modes, modesChecked, msg );
        }

        public void configureService(String[] routingModes, boolean[] checkedModes)
        {
          // read in current config
          TreeMap<String,ServiceModeConfig> map = new TreeMap<String,ServiceModeConfig>();
          BufferedReader br = null;
          String modesFile = modesDir + "/serviceconfig.dat";
          try
          {
            br = new BufferedReader( new FileReader (modesFile ) );
            for(;;)
            {
              String line = br.readLine();
              if ( line == null ) break;
              ServiceModeConfig smc = new ServiceModeConfig( line );
              map.put( smc.mode, smc );
            }
          }
          catch( Exception e ) {}
          finally
          {
            if ( br != null  ) try { br.close(); } catch( Exception ee ) {}
          }

          // replace selected modes
          for( int i=0; i<6; i++)
          {
      	    if ( checkedModes[i] )
      	    {
        	  writeRawTrackToMode( routingModes[i] );
       		  ServiceModeConfig smc = new ServiceModeConfig( routingModes[i], profileName);
              for( OsmNodeNamed nogo : nogoVetoList)
              {
                smc.nogoVetos.add( nogo.ilon + "," + nogo.ilat );
              }
              map.put( smc.mode, smc );
            }
          }

          
          // no write new config
          BufferedWriter bw = null;
          StringBuilder msg = new StringBuilder( "Mode mapping is now:\n" );
          msg.append( "( [..] counts nogo-vetos)\n" );
          try
          {
            bw = new BufferedWriter( new FileWriter ( modesFile ) );
            for( ServiceModeConfig smc : map.values() )
            {
              bw.write( smc.toLine() );
              bw.write( '\n' );
              msg.append( smc.toString() ).append( '\n' );
            }
          }
          catch( Exception e ) {}
          finally
          {
            if ( bw != null  ) try { bw.close(); } catch( Exception ee ) {}
          }
          ((BRouterActivity)getContext()).showModeConfigOverview( msg.toString() );
        }

   private String readSingleLineFile( File f )
   {
     BufferedReader br = null;
     try
     {
       br = new BufferedReader( new InputStreamReader ( new FileInputStream( f ) ) );
       return br.readLine();
     }
     catch( Exception e ) { return null; }
     finally
     {
       if ( br != null  ) try { br.close(); } catch( Exception ee ) {}
     }
   }

   
   private static List<String> getStorageDirectories()
   {
     ArrayList<String> res = new ArrayList<String>();
     res.add( Environment.getExternalStorageDirectory().getPath() );
     BufferedReader br = null;
     try
     {
       br = new BufferedReader(new FileReader("/proc/mounts"));
       for(;;)
       {
         String line = br.readLine();
         if ( line == null ) break;
         if (line.indexOf("vfat") < 0 && line.indexOf("/mnt") < 0 ) continue;
         StringTokenizer tokens = new StringTokenizer(line, " ");
         tokens.nextToken();
         String d = tokens.nextToken();
         boolean isExternalDir = false;
         if ( line.contains( "/dev/block/vold" ) )
         {
           isExternalDir = true;
           String[] vetos = new String[] { "/mnt/secure", "/mnt/asec", "/mnt/obb", "/dev/mapper", "tmpfs" };
           for( String v: vetos )
           {
             if ( d.indexOf( v ) >= 0 )
             {
               isExternalDir = false;
             }
           }
         }
         if ( isExternalDir )
         {
           if ( !res.contains( d ) )
           {
             res.add( d );
           }
         }
       }
     }
     catch ( Exception e) { /* ignore */ }
     finally
     {
       if (br != null) { try { br.close(); } catch (Exception e) { /* ignore */ } }
     }
     return res;
   }

} 
