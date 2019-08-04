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
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
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
import btools.expressions.BExpressionContextWay;
import btools.expressions.BExpressionMetaData;
import btools.mapaccess.OsmNode;
import btools.router.OsmNodeNamed;
import btools.router.OsmNogoPolygon;
import btools.router.OsmNogoPolygon.Point;
import btools.router.OsmTrack;
import btools.router.RoutingContext;
import btools.router.RoutingEngine;
import btools.router.RoutingHelper;
import btools.util.CheapRuler;

public class BRouterView extends View
{
  RoutingEngine cr;
  private int imgw;
  private int imgh;

  private int centerLon;
  private int centerLat;
  private double scaleLon;  // ilon -> pixel
  private double scaleLat;  // ilat -> pixel
  private double scaleMeter2Pixel;
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
  private String rawTrackPath;

  private boolean needsViaSelection;
  private boolean needsNogoSelection;
  private boolean needsWaypointSelection;

  private WpDatabaseScanner dataBaseScanner;

  private long lastDataTime = System.currentTimeMillis();

  private CoordinateReader cor;

  private int[] imgPixels;

  private int memoryClass;
  
  public boolean canAccessSdCard;

  public void stopRouting()
  {
    if ( cr != null ) cr.terminate();
  }

  public BRouterView( Context context, int memoryClass )
  {
    super( context );
    this.memoryClass = memoryClass;
  }

  public void init()
  {
    try
    {
      DisplayMetrics metrics = new DisplayMetrics();
      ( (Activity) getContext() ).getWindowManager().getDefaultDisplay().getMetrics( metrics );
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
      String message = baseDir == null ? "(no basedir configured previously)" : "(previous basedir " + baseDir
          + ( bdValid ? " does not contain 'brouter' subfolder)" : " is not valid)" );

      ( (BRouterActivity) getContext() ).selectBasedir( getStorageDirectories(), guessBaseDir(), message );
      waitingForSelection = true;
    }
    catch (Exception e)
    {
      String msg = e instanceof IllegalArgumentException ? e.getMessage() : e.toString();

      AppLogger.log( msg );
      AppLogger.log( AppLogger.formatThrowable( e ) );

      ( (BRouterActivity) getContext() ).showErrorMessage( msg );
    }
  }

  public void startSetup( String baseDir, boolean storeBasedir )
  {
    cor = null;
    try
    {
      File fbd = new File( baseDir );
      if ( !fbd.isDirectory() )
      {
        throw new IllegalArgumentException( "Base-directory " + baseDir + " is not a directory " );
      }
      if ( storeBasedir )
      {
        // Android 4.4 patch: try extend the basedir if not valid
        File td = new File( fbd, "brouter" );
        try
        {
          td.mkdir();
        }
        catch (Exception e) {}
        if ( !td.isDirectory() )
        {
          File td1 = new File( fbd, "Android/data/btools/routingapp" );
          try
          {
            td1.mkdirs();
          }
          catch (Exception e){}
          td = new File( td1, "brouter" );
          try
          {
            td.mkdir();
          }
          catch (Exception e) {}
          if ( td.isDirectory() )
            fbd = td1;
        }

        ConfigHelper.writeBaseDir( getContext(), baseDir );
      }
      String basedir = fbd.getAbsolutePath();
      AppLogger.log( "using basedir: " + basedir );

      String version = "v1.5.5";

      // create missing directories
      assertDirectoryExists( "project directory", basedir + "/brouter", null, null );
      segmentDir = basedir + "/brouter/segments4";
      if ( assertDirectoryExists( "data directory", segmentDir, "segments4.zip", null ) )
      {
        ConfigMigration.tryMigrateStorageConfig(
              new File( basedir + "/brouter/segments3/storageconfig.txt" ),
              new File( basedir + "/brouter/segments4/storageconfig.txt" ) );
      }
      profileDir = basedir + "/brouter/profiles2";
      assertDirectoryExists( "profile directory", profileDir, "profiles2.zip", version );
      modesDir = basedir + "/brouter/modes";
      assertDirectoryExists( "modes directory", modesDir, "modes.zip", version );
      assertDirectoryExists( "readmes directory", basedir + "/brouter/readmes", "readmes.zip", version );

      int deviceLevel =  android.os.Build.VERSION.SDK_INT;
      int targetSdkVersion = getContext().getApplicationInfo().targetSdkVersion;
      canAccessSdCard =  deviceLevel < 23 || targetSdkVersion == 10;
      if ( canAccessSdCard )
      {
        cor = CoordinateReader.obtainValidReader( basedir, segmentDir );
      }
      else
      {
        cor = new CoordinateReaderNone();
        cor.readFromTo();
      }
      
      wpList = cor.waypoints;
      nogoList = cor.nogopoints;
      nogoVetoList = new ArrayList<OsmNodeNamed>();

      sourceHint = "(dev/trgt=" + deviceLevel + "/" + targetSdkVersion + " coordinate-source: " + cor.basedir + cor.rootdir + ")";

      needsViaSelection = wpList.size() > 2;
      needsNogoSelection = nogoList.size() > 0;
      needsWaypointSelection = wpList.size() == 0;

      if ( cor.tracksdir != null )
      {
        tracksDir = cor.basedir + cor.tracksdir;
        assertDirectoryExists( "track directory", tracksDir, null, null );

        // output redirect: look for a pointerfile in tracksdir
        File tracksDirPointer = new File( tracksDir + "/brouter.redirect" );
        if ( tracksDirPointer.isFile() )
        {
          tracksDir = readSingleLineFile( tracksDirPointer );
          if ( tracksDir == null )
            throw new IllegalArgumentException( "redirect pointer file is empty: " + tracksDirPointer );
          if ( !( new File( tracksDir ).isDirectory() ) )
            throw new IllegalArgumentException( "redirect pointer file " + tracksDirPointer + " does not point to a directory: " + tracksDir );
        }
        else
        {
          File writeTest = new File( tracksDir + "/brouter.writetest" );
          try
          {
            writeTest.createNewFile();
            writeTest.delete();
          }
          catch( Exception e )
          {
            tracksDir = null;
          }
        }
      }
      if ( tracksDir == null )
      {
        tracksDir = basedir + "/brouter"; // fallback
      }

      String[] fileNames = new File( profileDir ).list();
      ArrayList<String> profiles = new ArrayList<String>();

      boolean lookupsFound = false;
      for ( String fileName : fileNames )
      {
        if ( fileName.endsWith( ".brf" ) )
        {
          profiles.add( fileName.substring( 0, fileName.length() - 4 ) );
        }
        if ( fileName.equals( "lookups.dat" ) )
          lookupsFound = true;
      }

      // add a "last timeout" dummy profile
      File lastTimeoutFile = new File( modesDir + "/timeoutdata.txt" );
      long lastTimeoutTime = lastTimeoutFile.lastModified();
      if ( lastTimeoutTime > 0 && System.currentTimeMillis() - lastTimeoutTime < 1800000 )
      {
        BufferedReader br = new BufferedReader( new FileReader( lastTimeoutFile ) );
        String repeatProfile = br.readLine();
        br.close();
        profiles.add( 0, "<repeat:" + repeatProfile + ">" );
      }

      if ( !lookupsFound )
      {
        throw new IllegalArgumentException( "The profile-directory " + profileDir + " does not contain the lookups.dat file."
            + " see brouter.de/brouter for setup instructions." );
      }
      if ( profiles.size() == 0 )
      {
        throw new IllegalArgumentException( "The profile-directory " + profileDir + " contains no routing profiles (*.brf)."
            + " see brouter.de/brouter for setup instructions." );
      }
      if ( !RoutingHelper.hasDirectoryAnyDatafiles( segmentDir ) )
      {
        ( (BRouterActivity) getContext() ).startDownloadManager();
        waitingForSelection = true;
        return;
      }
      ( (BRouterActivity) getContext() ).selectProfile( profiles.toArray( new String[0] ) );
    }
    catch (Exception e)
    {
      String msg = e instanceof IllegalArgumentException ? e.getMessage()
          + ( cor == null ? "" : " (coordinate-source: " + cor.basedir + cor.rootdir + ")" ) : e.toString();

      AppLogger.log( msg );
      AppLogger.log( AppLogger.formatThrowable( e ) );

      ( (BRouterActivity) getContext() ).showErrorMessage( msg + "\n" + AppLogger.formatThrowable( e ) );
    }
    waitingForSelection = true;
  }


  public boolean hasUpToDateLookups()
  {
    BExpressionMetaData meta = new BExpressionMetaData();
    meta.readMetaData( new File( profileDir, "lookups.dat" ) );
    return meta.lookupVersion == 10;
  }

  public void continueProcessing()
  {
    waitingForSelection = false;
    invalidate();
  }

  public void updateViaList( Set<String> selectedVias )
  {
    ArrayList<OsmNodeNamed> filtered = new ArrayList<OsmNodeNamed>( wpList.size() );
    for ( OsmNodeNamed n : wpList )
    {
      String name = n.name;
      if ( "from".equals( name ) || "to".equals( name ) || selectedVias.contains( name ) )
        filtered.add( n );
    }
    wpList = filtered;
  }

  public void updateNogoList( boolean[] enabled )
  {
    for ( int i = nogoList.size() - 1; i >= 0; i-- )
    {
      if ( !enabled[i] )
      {
        nogoVetoList.add( nogoList.get( i ) );
        nogoList.remove( i );
      }
    }
  }

  public void pickWaypoints()
  {
    String msg = null;

    if ( cor.allpoints == null )
    {
      try
      {
        cor.readAllPoints();
      }
      catch (Exception e)
      {
        msg = "Error reading waypoints: " + e.toString();
      }

      int size = cor.allpoints.size();
      if ( size < 1 )
        msg = "coordinate source does not contain any waypoints!";
      if ( size > 1000 )
        msg = "coordinate source contains too much waypoints: " + size + "(please use from/to/via names)";
    }

    if ( msg != null )
    {
      ( (BRouterActivity) getContext() ).showErrorMessage( msg );
    }
    else
    {
      String[] wpts = new String[cor.allpoints.size()];
      int i = 0;
      for ( OsmNodeNamed wp : cor.allpoints )
        wpts[i++] = wp.name;
      ( (BRouterActivity) getContext() ).selectWaypoint( wpts );
    }
  }

  public void updateWaypointList( String waypoint )
  {
    for( OsmNodeNamed wp : cor.allpoints )
    {
      if ( wp.name.equals( waypoint ) )
      {
        if ( wp.ilat != 0 || wp.ilat != 0 )
        {
          int nwp = wpList.size();
          if ( nwp == 0 || wpList.get( nwp-1 ) != wp )
          {
            wpList.add( wp );
          }
        }
        return;
      }
    }
  }

  public void startWpDatabaseScan()
  {
    dataBaseScanner = new WpDatabaseScanner();
    dataBaseScanner.start();
    invalidate();
  }

  public void saveMaptoolDir( String dir )
  {
    ConfigMigration.saveAdditionalMaptoolDir( segmentDir, dir );
    ( (BRouterActivity) getContext() ).showResultMessage( "Success", "please restart to use new config", -1 );
  }

  public void finishWaypointSelection()
  {
    needsWaypointSelection = false;
  }

  private List<OsmNodeNamed> readWpList( BufferedReader br, boolean isNogo ) throws Exception
  {
    int cnt = Integer.parseInt( br.readLine() );
    List<OsmNodeNamed> res = new ArrayList<OsmNodeNamed>(cnt);
    for( int i=0; i<cnt; i++ )
    {
      OsmNodeNamed wp = OsmNodeNamed.decodeNogo( br.readLine() );
      wp.isNogo = isNogo;
      res.add( wp );
    }
    return res;
  }

  public void startProcessing( String profile )
  {
    rawTrackPath = null;
    if ( profile.startsWith( "<repeat" ) )
    {
      needsViaSelection = needsNogoSelection = needsWaypointSelection = false;
      try
      {
        File lastTimeoutFile = new File( modesDir + "/timeoutdata.txt" );
        BufferedReader br = new BufferedReader( new FileReader( lastTimeoutFile ) );
        profile = br.readLine();
        rawTrackPath = br.readLine();
        wpList = readWpList( br, false );
        nogoList = readWpList( br, true );
        br.close();
      }
      catch( Exception e )
      {
        AppLogger.log( AppLogger.formatThrowable( e ) );
        ( (BRouterActivity) getContext() ).showErrorMessage( e.toString() );
      }
    }
    else if ( "remote".equals( profileName ) )
    {
      rawTrackPath = modesDir + "/remote_rawtrack.dat";
    }

    profilePath = profileDir + "/" + profile + ".brf";
    profileName = profile;

    if ( needsViaSelection )
    {
      needsViaSelection = false;
      String[] availableVias = new String[wpList.size() - 2];
      for ( int viaidx = 0; viaidx < wpList.size() - 2; viaidx++ )
        availableVias[viaidx] = wpList.get( viaidx + 1 ).name;
      ( (BRouterActivity) getContext() ).selectVias( availableVias );
      return;
    }

    if ( needsNogoSelection )
    {
      needsNogoSelection = false;
      ( (BRouterActivity) getContext() ).selectNogos( nogoList );
      return;
    }

    if ( needsWaypointSelection )
    {
      String msg;
      if ( wpList.size() == 0 )
      {
        msg = "Expecting waypoint selection\n" + sourceHint;
      }
      else
      {
        msg = "current waypoint selection:\n";
        for ( int i = 0; i < wpList.size(); i++ )
          msg += ( i > 0 ? "->" : "" ) + wpList.get( i ).name;
      }
      ( (BRouterActivity) getContext() ).showResultMessage( "Select Action", msg, cor instanceof CoordinateReaderNone ? -2 : wpList.size() );
      return;
    }

    try
    {
      waitingForSelection = false;

      RoutingContext rc = new RoutingContext();

      rc.localFunction = profilePath;
      rc.turnInstructionMode = cor.getTurnInstructionMode();

      int plain_distance = 0;
      int maxlon = Integer.MIN_VALUE;
      int minlon = Integer.MAX_VALUE;
      int maxlat = Integer.MIN_VALUE;
      int minlat = Integer.MAX_VALUE;

      OsmNode prev = null;
      for ( OsmNode n : wpList )
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
      toast( "Plain distance = " + plain_distance / 1000. + " km" );

      centerLon = ( maxlon + minlon ) / 2;
      centerLat = ( maxlat + minlat ) / 2;

      double[] lonlat2m = CheapRuler.getLonLatToMeterScales( centerLat );
      double dlon2m = lonlat2m[0];
      double dlat2m = lonlat2m[1];
      double difflon = (maxlon - minlon)*dlon2m;
      double difflat = (maxlat - minlat)*dlat2m;

      scaleLon = imgw / ( difflon * 1.5 );
      scaleLat = imgh / ( difflat * 1.5 );
      scaleMeter2Pixel = scaleLon < scaleLat ? scaleLon : scaleLat;
      scaleLon = scaleMeter2Pixel*dlon2m;
      scaleLat = scaleMeter2Pixel*dlat2m;

      startTime = System.currentTimeMillis();
      RoutingContext.prepareNogoPoints( nogoList );
      rc.nogopoints = nogoList;

      rc.memoryclass = memoryClass;
      if ( memoryClass < 16 )
      {
        rc.memoryclass = 16;
      }
      else if ( memoryClass > 256 )
      {
        rc.memoryclass = 256;
      }


      // for profile remote, use ref-track logic same as service interface
      rc.rawTrackPath = rawTrackPath;

      cr = new RoutingEngine( tracksDir + "/brouter", null, segmentDir, wpList, rc );
      cr.start();
      invalidate();

    }
    catch (Exception e)
    {
      String msg = e instanceof IllegalArgumentException ? e.getMessage() : e.toString();
      toast( msg );
    }
  }

  private boolean assertDirectoryExists( String message, String path, String assetZip, String versionTag )
  {
    File f = new File( path );

    boolean exists = f.exists();
    if ( !exists )
    {
      f.mkdirs();
    }
    if ( versionTag != null )
    {
      File vtag = new File( f, versionTag );
      try
      {
        exists = !vtag.createNewFile();
      }
      catch( IOException io ) { } // well..
    }

    if ( !exists )
    {
      // default contents from assets archive
      if ( assetZip != null )
      {
        try
        {
          AssetManager assetManager = getContext().getAssets();
          InputStream is = assetManager.open( assetZip );
          ZipInputStream zis = new ZipInputStream( is );
          byte[] data = new byte[1024];
          for ( ;; )
          {
            ZipEntry ze = zis.getNextEntry();
            if ( ze == null )
              break;
            String name = ze.getName();
            FileOutputStream fos = new FileOutputStream( new File( f, name ) );

            for ( ;; )
            {
              int len = zis.read( data, 0, 1024 );
              if ( len < 0 )
                break;
              fos.write( data, 0, len );
            }
            fos.close();
          }
          is.close();
          return true;
        }
        catch (IOException io)
        {
          throw new RuntimeException( "error expanding " + assetZip + ": " + io );
        }

      }
    }
    if ( !f.exists() || !f.isDirectory() )
      throw new IllegalArgumentException( message + ": " + path + " cannot be created" );
    return false;
  }

  private void paintPosition( int ilon, int ilat, int color, int with )
  {
    int lon = ilon - centerLon;
    int lat = ilat - centerLat;
    int x = imgw / 2 + (int) ( scaleLon * lon );
    int y = imgh / 2 - (int) ( scaleLat * lat );
    for ( int nx = x - with; nx <= x + with; nx++ )
      for ( int ny = y - with; ny <= y + with; ny++ )
      {
        if ( nx >= 0 && nx < imgw && ny >= 0 && ny < imgh )
        {
          imgPixels[nx + imgw * ny] = color;
        }
      }
  }

  private void paintCircle( Canvas canvas, OsmNodeNamed n, int color, int minradius )
  {
    int lon = n.ilon - centerLon;
    int lat = n.ilat - centerLat;
    int x = imgw / 2 + (int) ( scaleLon * lon );
    int y = imgh / 2 - (int) ( scaleLat * lat );

    int ir = (int) ( n.radius * scaleMeter2Pixel );
    if ( ir > minradius )
    {
      Paint paint = new Paint();
      paint.setColor( Color.RED );
      paint.setStyle( Paint.Style.STROKE );
      canvas.drawCircle( (float) x, (float) y, (float) ir, paint );
    }
  }

  private void paintLine( Canvas canvas, final int ilon0, final int ilat0, final int ilon1, final int ilat1, final Paint paint )
  {
    final int lon0 = ilon0 - centerLon;
    final int lat0 = ilat0 - centerLat;
    final int lon1 = ilon1 - centerLon;
    final int lat1 = ilat1 - centerLat;
    final int x0 = imgw / 2 + (int) ( scaleLon * lon0 );
    final int y0 = imgh / 2 - (int) ( scaleLat * lat0 );
    final int x1 = imgw / 2 + (int) ( scaleLon * lon1 );
    final int y1 = imgh / 2 - (int) ( scaleLat * lat1 );
    canvas.drawLine( (float) x0, (float) y0,  (float) x1,  (float) y1, paint );
  }

  private void paintPolygon( Canvas canvas, OsmNogoPolygon p, int minradius )
  {
    final int ir = (int) ( p.radius * scaleMeter2Pixel );
    if ( ir > minradius )
    {
      Paint paint = new Paint();
      paint.setColor( Color.RED );
      paint.setStyle( Paint.Style.STROKE );

      Point p0 = p.isClosed ? p.points.get(p.points.size()-1) : null;

      for ( final Point p1 : p.points )
      {
        if (p0 != null)
        {
          paintLine( canvas, p0.x, p0.y, p1.x, p1.y, paint );
        }
        p0 = p1;
      }
    }
  }

  @Override
  protected void onSizeChanged( int w, int h, int oldw, int oldh )
  {
  }

  private void toast( String msg )
  {
    Toast.makeText( getContext(), msg, Toast.LENGTH_LONG ).show();
    lastDataTime += 4000; // give time for the toast before exiting
  }

  private long lastTs = System.currentTimeMillis();
  private long startTime = 0L;

  @Override
  protected void onDraw( Canvas canvas )
  {
    try
    {
      _onDraw( canvas );
    }
    catch (Throwable t)
    {
      // on out of mem, try to stop the show
      if ( cr != null )
        cr.cleanOnOOM();
      cr = null;
      try
      {
        Thread.sleep( 2000 );
      }
      catch (InterruptedException ie)
      {
      }
      ( (BRouterActivity) getContext() ).showErrorMessage( t.toString() );
      waitingForSelection = true;
    }
  }

  private void showDatabaseScanning( Canvas canvas )
  {
    try
    {
      Thread.sleep( 100 );
    }
    catch (InterruptedException ie)
    {
    }
    Paint paint1 = new Paint();
    paint1.setColor( Color.WHITE );
    paint1.setTextSize( 20 );

    Paint paint2 = new Paint();
    paint2.setColor( Color.WHITE );
    paint2.setTextSize( 10 );

    String currentDir = dataBaseScanner.getCurrentDir();
    String bestGuess = dataBaseScanner.getBestGuess();

    if ( currentDir == null ) // scan finished
    {
      if ( bestGuess.length() == 0 )
      {
        ( (BRouterActivity) getContext() ).showErrorMessage( "scan did not find any possible waypoint database" );
      }
      else
      {
        ( (BRouterActivity) getContext() ).showWpDatabaseScanSuccess( bestGuess);
      }
      cr = null;
      dataBaseScanner = null;
      waitingForSelection = true;
      return;
    }

    canvas.drawText( "Scanning:", 10, 30, paint1 );
    canvas.drawText( currentDir, 0, 60, paint2 );
    canvas.drawText( "Best Guess:", 10, 90, paint1 );
    canvas.drawText( bestGuess, 0, 120, paint2 );
    canvas.drawText( "Last Error:", 10, 150, paint1 );
    canvas.drawText( dataBaseScanner.getLastError(), 0, 180, paint2 );

    invalidate();
  }

  private void _onDraw( Canvas canvas )
  {
    if ( dataBaseScanner != null )
    {
      showDatabaseScanning( canvas );
      return;
    }

    if ( waitingForSelection )
      return;

    long currentTs = System.currentTimeMillis();
    long diffTs = currentTs - lastTs;
    long sleeptime = 500 - diffTs;
    while (sleeptime < 200)
      sleeptime += 500;

    try
    {
      Thread.sleep( sleeptime );
    }
    catch (InterruptedException ie)
    {
    }
    lastTs = System.currentTimeMillis();

    if ( cr == null || cr.isFinished() )
    {
      if ( cr != null )
      {
        if ( cr.getErrorMessage() != null )
        {
          ( (BRouterActivity) getContext() ).showErrorMessage( cr.getErrorMessage() );
          cr = null;
          waitingForSelection = true;
          return;
        }
        else
        {
          String memstat =  memoryClass + "mb pathPeak " + ((cr.getPathPeak()+500)/1000) + "k";
          String result = "version = BRouter-1.5.5\n" + "mem = " + memstat + "\ndistance = " + cr.getDistance() / 1000. + " km\n" + "filtered ascend = " + cr.getAscend()
              + " m\n" + "plain ascend = " + cr.getPlainAscend() + " m\n" + "estimated time = " + cr.getTime();

          rawTrack = cr.getFoundRawTrack();

          // for profile "remote", always persist referencetrack
          if ( cr.getAlternativeIndex() == 0 && rawTrackPath != null )
          {
            writeRawTrackToPath( rawTrackPath );
          }

          String title = "Success";
          if ( cr.getAlternativeIndex() > 0 )
            title += " / " + cr.getAlternativeIndex() + ". Alternative";

          ( (BRouterActivity) getContext() ).showResultMessage( title, result, rawTrackPath == null ? -1 : -3 );
          cr = null;
          waitingForSelection = true;
          return;
        }
      }
      else if ( System.currentTimeMillis() > lastDataTime )
      {
        System.exit( 0 );
      }
    }
    else
    {
      lastDataTime = System.currentTimeMillis();
      imgPixels = new int[imgw * imgh];

      int[] openSet = cr.getOpenSet();
      for ( int si = 0; si < openSet.length; si += 2 )
      {
        paintPosition( openSet[si], openSet[si + 1], 0xffffff, 1 );
      }
      // paint nogos on top (red)
      for ( int ngi = 0; ngi < nogoList.size(); ngi++ )
      {
        OsmNodeNamed n = nogoList.get( ngi );
        int color = 0xff0000;
        paintPosition( n.ilon, n.ilat, color, 4 );
      }

      // paint start/end/vias on top (yellow/green/blue)
      for ( int wpi = 0; wpi < wpList.size(); wpi++ )
      {
        OsmNodeNamed n = wpList.get( wpi );
        int color = wpi == 0 ? 0xffff00 : wpi < wpList.size() - 1 ? 0xff : 0xff00;
        paintPosition( n.ilon, n.ilat, color, 4 );
      }

      canvas.drawBitmap( imgPixels, 0, imgw, (float) 0., (float) 0., imgw, imgh, false, null );

      // nogo circles if any
      for ( int ngi = 0; ngi < nogoList.size(); ngi++ )
      {
        OsmNodeNamed n = nogoList.get( ngi );
        if (n instanceof OsmNogoPolygon)
        {
          paintPolygon( canvas, (OsmNogoPolygon)n, 4 );
        }
        else
        {
          int color = 0xff0000;
          paintCircle( canvas, n, color, 4 );
        }
      }

      Paint paint = new Paint();
      paint.setColor( Color.WHITE );
      paint.setTextSize( 20 );

      long mseconds = System.currentTimeMillis() - startTime;
      long links = cr.getLinksProcessed();
      long perS = ( 1000 * links ) / mseconds;
      String msg = "Links: " + cr.getLinksProcessed() + " in " + ( mseconds / 1000 ) + "s (" + perS + " l/s)";

      canvas.drawText( msg, 10, 25, paint );
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
      for ( String bdg : basedirGuesses )
      {
        rl.add( new CoordinateReaderOsmAnd( bdg ) );
        rl.add( new CoordinateReaderLocus( bdg ) );
        rl.add( new CoordinateReaderOrux( bdg ) );
      }
      long tmax = 0;
      CoordinateReader cor = null;
      for ( CoordinateReader r : rl )
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
    catch (Exception e)
    {
      System.out.println( "guessBaseDir:" + e );
    }
    return basedir.getAbsolutePath();
  }

  private void writeRawTrackToMode( String mode )
  {
    writeRawTrackToPath( modesDir + "/" + mode + "_rawtrack.dat" );
  }

  private void writeRawTrackToPath( String rawTrackPath )
  {
    if ( rawTrack != null )
    {
      try
      {
        rawTrack.writeBinary( rawTrackPath );
      }
      catch (Exception e)
      {
      }
    }
    else
    {
      new File( rawTrackPath ).delete();
    }
  }

  public void startConfigureService()
  {
    String[] modes = new String[]
    { "foot_short", "foot_fast", "bicycle_short", "bicycle_fast", "motorcar_short", "motorcar_fast" };
    boolean[] modesChecked = new boolean[6];

    String msg = "Choose service-modes to configure (" + profileName + " [" + nogoVetoList.size() + "])";

    ( (BRouterActivity) getContext() ).selectRoutingModes( modes, modesChecked, msg );
  }

  public void configureService( String[] routingModes, boolean[] checkedModes )
  {
    // read in current config
    TreeMap<String, ServiceModeConfig> map = new TreeMap<String, ServiceModeConfig>();
    BufferedReader br = null;
    String modesFile = modesDir + "/serviceconfig.dat";
    try
    {
      br = new BufferedReader( new FileReader( modesFile ) );
      for ( ;; )
      {
        String line = br.readLine();
        if ( line == null )
          break;
        ServiceModeConfig smc = new ServiceModeConfig( line );
        map.put( smc.mode, smc );
      }
    }
    catch (Exception e)
    {
    }
    finally
    {
      if ( br != null )
        try
        {
          br.close();
        }
        catch (Exception ee)
        {
        }
    }

    // replace selected modes
    for ( int i = 0; i < 6; i++ )
    {
      if ( checkedModes[i] )
      {
        writeRawTrackToMode( routingModes[i] );
        ServiceModeConfig smc = new ServiceModeConfig( routingModes[i], profileName );
        for ( OsmNodeNamed nogo : nogoVetoList )
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
      bw = new BufferedWriter( new FileWriter( modesFile ) );
      for ( ServiceModeConfig smc : map.values() )
      {
        bw.write( smc.toLine() );
        bw.write( '\n' );
        msg.append( smc.toString() ).append( '\n' );
      }
    }
    catch (Exception e)
    {
    }
    finally
    {
      if ( bw != null )
        try
        {
          bw.close();
        }
        catch (Exception ee)
        {
        }
    }
    ( (BRouterActivity) getContext() ).showModeConfigOverview( msg.toString() );
  }

  private String readSingleLineFile( File f )
  {
    BufferedReader br = null;
    try
    {
      br = new BufferedReader( new InputStreamReader( new FileInputStream( f ) ) );
      return br.readLine();
    }
    catch (Exception e)
    {
      return null;
    }
    finally
    {
      if ( br != null )
        try
        {
          br.close();
        }
        catch (Exception ee)
        {
        }
    }
  }

  private List<String> getStorageDirectories()
  {
    ArrayList<String> res = new ArrayList<String>();
    
    // check write access on internal sd
    try
    {
      File sd = Environment.getExternalStorageDirectory();
      File testDir = new File( sd, "brouter" );
      boolean didExist = testDir.isDirectory();
      if ( !didExist )
      {
        testDir.mkdir();
      }
      File testFile = new File( testDir, "test" + System.currentTimeMillis() );
      testFile.createNewFile();
      if ( testFile.exists() )
      {
        testFile.delete();
        res.add( sd.getPath() );
      }
      if ( !didExist )
      {
        testDir.delete();
      }
    }
    catch( Throwable t )
    {
      // ignore
    }
    

    try
    {
      Method method = Context.class.getDeclaredMethod("getExternalFilesDirs", new Class[]{ String.class } );
      File[] paths = (File[])method.invoke( getContext(), new Object[1] );
      for( File path : paths )
      {
        res.add( path.getPath() );
      }
    }
    catch( Exception e )
    {
      res.add( e.toString() );
      res.add( Environment.getExternalStorageDirectory().getPath() );
    }
    return res;
  }

}
