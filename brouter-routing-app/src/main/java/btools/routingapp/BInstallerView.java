package btools.routingapp;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import android.app.Activity;
import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.os.AsyncTask;
import android.os.PowerManager;
import android.os.StatFs;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;
import btools.mapaccess.PhysicalFile;
import btools.mapaccess.Rd5DiffManager;
import btools.mapaccess.Rd5DiffTool;
import btools.router.RoutingHelper;
import btools.util.ProgressListener;

public class BInstallerView extends View
{
  private static final int MASK_SELECTED_RD5 = 1;
  private static final int MASK_DELETED_RD5 = 2;
  private static final int MASK_INSTALLED_RD5 = 4;
  private static final int MASK_CURRENT_RD5 = 8;
	
    private int imgwOrig;
    private int imghOrig;
    private float scaleOrig;

    private int imgw;
    private int imgh;

    private float lastDownX;
    private float lastDownY;
    
    private Bitmap bmp;
    
    private float viewscale;
        
    private float[] testVector = new float[2];

    private int[] tileStatus;

    private boolean tilesVisible = false;
    
    private long availableSize;
    private String baseDir;
    
    private boolean isDownloading = false;
    private volatile boolean downloadCanceled = false;

    private long currentDownloadSize;
    private String currentDownloadFile = "";
    private volatile String currentDownloadOperation = "";
    private String downloadAction = "";
    private volatile String newDownloadAction = "";

    private long totalSize = 0;
    private long rd5Tiles = 0;
    private long delTiles = 0;

    protected String baseNameForTile( int tileIndex )
    {
      int lon = (tileIndex % 72 ) * 5 - 180;
      int lat = (tileIndex / 72 ) * 5 - 90;
      String slon = lon < 0 ? "W" + (-lon) : "E" + lon;
      String slat = lat < 0 ? "S" + (-lat) : "N" + lat;
      return slon + "_" + slat;
    }
    
    private int gridPos2Tileindex( int ix, int iy )
    {
      return (35-iy)*72 + ( ix >= 70 ? ix-70: ix+2 );
    }
    
    private int tileForBaseName( String basename )
    {
      String uname = basename.toUpperCase();	
      int idx = uname.indexOf( "_" );
      if ( idx < 0 ) return -1;
      String slon = uname.substring( 0, idx ); 
      String slat = uname.substring( idx+1 );
      int ilon = slon.charAt(0) == 'W' ? -Integer.valueOf( slon.substring(1) ) :
    	       ( slon.charAt(0) == 'E' ?  Integer.valueOf( slon.substring(1) ) : -1 );
      int ilat = slat.charAt(0) == 'S' ? -Integer.valueOf( slat.substring(1) ) :
	           ( slat.charAt(0) == 'N' ?  Integer.valueOf( slat.substring(1) ) : -1 );
      if ( ilon < -180 || ilon >= 180 || ilon % 5 != 0 ) return -1;
      if ( ilat < - 90 || ilat >=  90 || ilat % 5 != 0 ) return -1;
      return (ilon+180) / 5 + 72*((ilat+90)/5);
    }

    
    public boolean isDownloadCanceled()
    {
      return downloadCanceled;
    }
    
    private void toggleDownload()
    {
    	if ( isDownloading )
    	{
    		downloadCanceled = true;
    		downloadAction = "Canceling...";
    		return;
    	}

      if ( delTiles > 0 )
      {
        ( (BInstallerActivity) getContext() ).showConfirmDelete();
        return;
      }

        int tidx_min = -1;
        int min_size = Integer.MAX_VALUE;
    	
		// prepare download list
        for( int ix=0; ix<72; ix++ )
        {
            for( int iy=0; iy<36; iy++ )
            {
    	    	int tidx = gridPos2Tileindex( ix, iy );
        	      if ( ( tileStatus[tidx] & MASK_SELECTED_RD5 ) != 0 )
        	      {
                    int tilesize = BInstallerSizes.getRd5Size(tidx);
                    if ( tilesize > 0 && tilesize < min_size )
                    {
                      tidx_min = tidx;
                      min_size = tilesize;
                    }
        	      }
            }
        }
        if ( tidx_min != -1 )
        {
          tileStatus[tidx_min] ^= tileStatus[tidx_min] & MASK_SELECTED_RD5;
          startDownload( tidx_min );
        }
    }
    
    private void startDownload( int tileIndex )
    {
      
    	String namebase = baseNameForTile( tileIndex );
      String baseurl = "http://brouter.de/brouter/segments4/";
        currentDownloadFile = namebase + ".rd5";
        currentDownloadOperation = "Checking";
        String url = baseurl + currentDownloadFile;
    	isDownloading = true;
    	downloadCanceled = false;
    	currentDownloadSize = 0;
    	downloadAction = "Connecting... ";
        final DownloadTask downloadTask = new DownloadTask(getContext());
        downloadTask.execute( url );
    }

    public void downloadDone( boolean success )
    {
    	isDownloading = false;
    	if ( success )
    	{
          scanExistingFiles(); 
          toggleDownload(); // keep on if no error
    	}
    	invalidate();
    }

    private int tileIndex( float x, float y )
    {
        int ix = (int)(72.f * x / bmp.getWidth());
        int iy = (int)(36.f * y / bmp.getHeight());
        if ( ix >= 0 && ix < 72 && iy >= 0 && iy < 36 ) return gridPos2Tileindex(ix, iy);
        return -1;
    }

    private void clearTileSelection( int mask )
    {
	  // clear selection if zooming out
      for( int ix=0; ix<72; ix++ )
        for( int iy=0; iy<36; iy++ )
        {
    	  int tidx = gridPos2Tileindex( ix, iy );
      	  tileStatus[tidx] ^= tileStatus[tidx] & mask;
        }
    }
    
    // get back the current image scale
    private float currentScale()
    {
    	testVector[1] = 1.f;
    	mat.mapVectors(testVector);
    	return testVector[1] / viewscale;
    }
    
    private void deleteRawTracks()
    {
      File modeDir = new File( baseDir + "/brouter/modes" );
      String[] fileNames = modeDir.list();
      if ( fileNames == null ) return;
      for( String fileName : fileNames )
      {
        if ( fileName.endsWith( "_rawtrack.dat" ) )
        {
          File f = new File( modeDir, fileName );
          f.delete();
        }
      }
    }

    private void scanExistingFiles()
    {
      clearTileSelection( MASK_INSTALLED_RD5 | MASK_CURRENT_RD5 );

      scanExistingFiles( new File( baseDir + "/brouter/segments4" ) );
      
      File secondary = RoutingHelper.getSecondarySegmentDir( baseDir + "/brouter/segments4" );
      if ( secondary != null )
      {
          scanExistingFiles( secondary );
      }

      availableSize = -1;
      try
      {
        StatFs stat = new StatFs(baseDir);
        availableSize = (long)stat.getAvailableBlocks()*stat.getBlockSize();
      }
      catch (Exception e) { /* ignore */ }
    }

    private void scanExistingFiles( File dir )
    {
        String[] fileNames = dir.list();
        if ( fileNames == null ) return;
        String suffix = ".rd5";
        for( String fileName : fileNames )
        {
          if ( fileName.endsWith( suffix ) )
          {
        	 String basename = fileName.substring( 0, fileName.length() - suffix.length() );
        	 int tidx = tileForBaseName( basename );
        	 tileStatus[tidx] |= MASK_INSTALLED_RD5;
        	 
        	 long age = System.currentTimeMillis() - new File( dir, fileName ).lastModified();
        	 if ( age < 10800000 ) tileStatus[tidx] |= MASK_CURRENT_RD5; // 3 hours
          }
        }
    }
    
    private Matrix mat;
    private Matrix matText;
    
       public void startInstaller() {

           baseDir = ConfigHelper.getBaseDir( getContext() );

           try
           {
             AssetManager assetManager = getContext().getAssets();
             InputStream istr = assetManager.open("world.png");
             bmp = BitmapFactory.decodeStream(istr);
             istr.close();
           }
           catch( IOException io )
           {
           	throw new RuntimeException( "cannot read world.png from assets" );
           }

           tileStatus = new int[72*36];
           scanExistingFiles();
           
           float scaleX = imgwOrig / ((float)bmp.getWidth());
           float scaley = imghOrig / ((float)bmp.getHeight());
           
           viewscale = scaleX < scaley ? scaleX : scaley;            

           mat = new Matrix();
           mat.postScale( viewscale, viewscale );
           tilesVisible = false;
        }

        public BInstallerView(Context context) {
            super(context);

            DisplayMetrics metrics = new DisplayMetrics();
            ((Activity)getContext()).getWindowManager().getDefaultDisplay().getMetrics(metrics);
            imgwOrig = metrics.widthPixels;
            imghOrig = metrics.heightPixels;
            int im = imgwOrig > imghOrig ? imgwOrig : imghOrig;
            
            scaleOrig = im / 480.f;            

            matText = new Matrix();
            matText.preScale( scaleOrig, scaleOrig );
            
            imgw = (int)(imgwOrig / scaleOrig);
            imgh = (int)(imghOrig / scaleOrig);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        }

        private void toast( String msg )
        {
          Toast.makeText(getContext(),  msg, Toast.LENGTH_LONG ).show();
        }

         @Override
        protected void onDraw(Canvas canvas)
        {
          if ( !isDownloading )
          {
            canvas.setMatrix( mat );
            canvas.drawBitmap(bmp, 0,0, null);
          } 
          // draw 5*5 lattice starting at scale=3

          int iw = bmp.getWidth();
          int ih = bmp.getHeight();
          float fw = iw / 72.f;
          float fh = ih / 36.f;
          
          boolean drawGrid = tilesVisible && !isDownloading;

          if ( drawGrid )
          {
                
              Paint pnt_1 = new Paint();
              pnt_1.setColor(Color.GREEN);
            
              for( int ix=1; ix<72; ix++ )
              {
            	float fx = fw*ix;
                canvas.drawLine( fx, 0, fx, ih, pnt_1);
              }
              for( int iy=1; iy<36; iy++ )
              {
            	float fy = fh*iy;
                canvas.drawLine( 0, fy, iw, fy, pnt_1);
              }
          }
          rd5Tiles = 0;
          delTiles = 0;
          totalSize = 0;
              int mask2 = MASK_SELECTED_RD5 | MASK_DELETED_RD5 | MASK_INSTALLED_RD5;
              int mask3 = mask2 | MASK_CURRENT_RD5;
              Paint pnt_2 = new Paint();
              pnt_2.setColor(Color.GRAY);
              pnt_2.setStrokeWidth(1);
              drawSelectedTiles( canvas, pnt_2, fw, fh, MASK_INSTALLED_RD5, mask3, false, false, drawGrid );
              pnt_2.setColor(Color.BLUE);
              pnt_2.setStrokeWidth(1);
              drawSelectedTiles( canvas, pnt_2, fw, fh, MASK_INSTALLED_RD5 | MASK_CURRENT_RD5, mask3, false, false, drawGrid );
              pnt_2.setColor(Color.GREEN);
              pnt_2.setStrokeWidth(2);
              drawSelectedTiles( canvas, pnt_2, fw, fh, MASK_SELECTED_RD5, mask2, true, false, drawGrid );
              pnt_2.setColor(Color.YELLOW);
              pnt_2.setStrokeWidth(2);
              drawSelectedTiles( canvas, pnt_2, fw, fh, MASK_SELECTED_RD5 | MASK_INSTALLED_RD5, mask2, true, false, drawGrid );
              pnt_2.setColor(Color.RED);
              pnt_2.setStrokeWidth(2);
              drawSelectedTiles( canvas, pnt_2, fw, fh, MASK_DELETED_RD5 | MASK_INSTALLED_RD5, mask2, false, true, drawGrid );

        	canvas.setMatrix( matText );

            Paint paint = new Paint();
            paint.setColor(Color.RED);

            long mb = 1024*1024;

            if ( isDownloading )
           	{
              String sizeHint = currentDownloadSize > 0 ? " (" + ((currentDownloadSize + mb-1)/mb) + " MB)" : "";
              paint.setTextSize(30);
              canvas.drawText( currentDownloadOperation +  " " + currentDownloadFile + sizeHint, 30, (imgh/3)*2-30, paint);
              canvas.drawText( downloadAction, 30, (imgh/3)*2, paint);
           	}
            if ( !tilesVisible )
            {
                paint.setTextSize(40);
                canvas.drawText( "Zoom in to see grid!", 30, (imgh/3)*2, paint);
            }
            paint.setTextSize(20);
            
            
            
            String totmb = ((totalSize + mb-1)/mb) + " MB";
            String freemb = availableSize >= 0 ? ((availableSize + mb-1)/mb) + " MB" : "?";
            canvas.drawText( "Selected segments=" + rd5Tiles, 10, 25, paint );
            canvas.drawText( "Size=" + totmb + " Free=" + freemb , 10, 45, paint );


            String btnText = null;
            if ( isDownloading ) btnText = "Cancel Download";
            else if ( delTiles > 0 ) btnText = "Delete " + delTiles + " tiles";
            else if ( rd5Tiles > 0 ) btnText = "Start Download";
            
            if ( btnText != null )
            {
              canvas.drawLine( imgw-btnw, imgh-btnh, imgw-btnw, imgh-2, paint);
              canvas.drawLine( imgw-btnw, imgh-btnh, imgw-2, imgh-btnh, paint);
              canvas.drawLine( imgw-btnw, imgh-btnh, imgw-btnw, imgh-2, paint);
              canvas.drawLine( imgw-2, imgh-btnh, imgw-2, imgh-2, paint);
              canvas.drawLine( imgw-btnw, imgh-2, imgw-2, imgh-2, paint);
              canvas.drawText( btnText , imgw-btnw+5, imgh-10, paint );
            }
        }

        int btnh = 40;
        int btnw = 160;

        
float tx, ty;        

  private void drawSelectedTiles( Canvas canvas, Paint pnt, float fw, float fh, int status, int mask, boolean doCount, boolean cntDel, boolean doDraw )
  {
    for ( int ix = 0; ix < 72; ix++ )
      for ( int iy = 0; iy < 36; iy++ )
      {
        int tidx = gridPos2Tileindex( ix, iy );
        if ( ( tileStatus[tidx] & mask ) == status )
        {
          int tilesize = BInstallerSizes.getRd5Size( tidx );
          if ( tilesize > 0 )
          {
            if ( doCount )
            {
              rd5Tiles++;
              totalSize += BInstallerSizes.getRd5Size( tidx );
            }
            if ( cntDel )
            {
              delTiles++;
              totalSize += BInstallerSizes.getRd5Size( tidx );
            }
            if ( !doDraw )
              continue;
            // draw cross
            canvas.drawLine( fw * ix, fh * iy, fw * ( ix + 1 ), fh * ( iy + 1 ), pnt );
            canvas.drawLine( fw * ix, fh * ( iy + 1 ), fw * ( ix + 1 ), fh * iy, pnt );

            // draw frame
            canvas.drawLine( fw * ix, fh * iy, fw * ( ix + 1 ), fh * iy, pnt );
            canvas.drawLine( fw * ix, fh * ( iy + 1 ), fw * ( ix + 1 ), fh * ( iy + 1 ), pnt );
            canvas.drawLine( fw * ix, fh * iy, fw * ix, fh * ( iy + 1 ), pnt );
            canvas.drawLine( fw * ( ix + 1 ), fh * iy, fw * ( ix + 1 ), fh * ( iy + 1 ), pnt );
          }
        }
      }
  }

  public void deleteSelectedTiles()
  {
    for ( int ix = 0; ix < 72; ix++ )
    {
      for ( int iy = 0; iy < 36; iy++ )
      {
        int tidx = gridPos2Tileindex( ix, iy );
        if ( ( tileStatus[tidx] & MASK_DELETED_RD5 ) != 0 )
        {
          new File( baseDir + "/brouter/segments4/" + baseNameForTile( tidx ) + ".rd5").delete();
        }
      }
    }
    scanExistingFiles(); 
    invalidate();
  }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
        	
            // get pointer index from the event object
            int pointerIndex = event.getActionIndex();

            // get pointer ID
            int pointerId = event.getPointerId(pointerIndex);

            // get masked (not specific to a pointer) action
            int maskedAction = event.getActionMasked();

            switch (maskedAction) {

            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
          	  lastDownX = event.getX();
          	  lastDownY = event.getY();
            	            	
              break;
            }
            case MotionEvent.ACTION_MOVE: { // a pointer was moved
            	
            	if ( isDownloading ) break;
            	int np = event.getPointerCount();
            	int nh = event.getHistorySize();
            	if ( nh == 0 ) break;

            	float x0 = event.getX( 0 );
            	float y0 = event.getY( 0 );
            	float hx0 = event.getHistoricalX(0,0);
            	float hy0 = event.getHistoricalY(0,0);

            	if ( np > 1 ) // multi-touch
            	{
                	float x1 = event.getX( 1 );
                	float y1 = event.getY( 1 );
                	float hx1 = event.getHistoricalX(1,0);
                	float hy1 = event.getHistoricalY(1,0);
                	
                	float r = (float)Math.sqrt( (x1-x0)*(x1-x0) + (y1-y0)*(y1-y0) );
                	float hr = (float)Math.sqrt( (hx1-hx0)*(hx1-hx0) + (hy1-hy0)*(hy1-hy0) );
                	
                	if ( hr > 10. )
                	{
                	  float ratio = r/hr;

                	  float mx = (x1+x0)/2.f;
                	  float my = (y1+y0)/2.f;

                	  float scale = currentScale();
                	  float newscale = scale*ratio;
                	  
                	  if ( newscale > 10.f ) ratio *= (10.f / newscale );
                	  if ( newscale < 0.5f ) ratio *= (0.5f / newscale );

                	  mat.postScale( ratio, ratio, mx, my );
                	                  	  
                	  mat.postScale( ratio, ratio, mx, my );

                	  boolean tilesv = currentScale() >= 3.f;
                	  if ( tilesVisible && !tilesv )
                	  {
                        clearTileSelection( MASK_SELECTED_RD5 | MASK_DELETED_RD5 );
                      }
                	  tilesVisible = tilesv;
                	}
                	
                	break;
            	}
                mat.postTranslate( x0-hx0, y0-hy0 );

                break;
            }
            case MotionEvent.ACTION_UP:
            	
            	long downTime = event.getEventTime() - event.getDownTime();
            	
            	if ( downTime < 5 || downTime > 500 )
            	{
            	  break;
            	}

            	if ( Math.abs(lastDownX - event.getX() ) > 10 || Math.abs(lastDownY - event.getY() ) > 10 )
            	{
              	  break;
              	}
            	
                // download button?
              if ( ( delTiles  > 0 || rd5Tiles  > 0 || isDownloading ) && event.getX() > imgwOrig - btnw*scaleOrig && event.getY() > imghOrig-btnh*scaleOrig )
            	{
            		toggleDownload();
                	invalidate();
            		break;
            	}

            	if ( isDownloading || !tilesVisible ) break;

            	Matrix imat = new Matrix();
            	if ( mat.invert(imat) )
            	{
            	  float[] touchpoint = new float[2];
            	  touchpoint[0] = event.getX();
            	  touchpoint[1] = event.getY();
            	  imat.mapPoints(touchpoint);
            	  
            	  int tidx = tileIndex( touchpoint[0], touchpoint[1] );
            	  if ( tidx != -1 )
            	  {
            	    if ( ( tileStatus[tidx] & MASK_SELECTED_RD5 ) != 0 )
            	    {
                    tileStatus[tidx] ^= MASK_SELECTED_RD5;
            	      if ( ( tileStatus[tidx] & MASK_INSTALLED_RD5 ) != 0 )
            	      {
            	        tileStatus[tidx] |= MASK_DELETED_RD5;
            	      }
            	    }
            	    else if ( ( tileStatus[tidx] & MASK_DELETED_RD5 ) != 0 )
                  {
                    tileStatus[tidx] ^= MASK_DELETED_RD5;
                  }
            	    else
            	    {
                    tileStatus[tidx] ^= MASK_SELECTED_RD5;
            	    }
            	  }
            	  
            	  tx = touchpoint[0];
            	  ty = touchpoint[1];
            	}
            	
            	
            	break;
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
              // TODO use data
              break;
            }
            }
            invalidate();

            return true;
        }
        
        // usually, subclasses of AsyncTask are declared inside the activity class.
        // that way, you can easily modify the UI thread from here
        private class DownloadTask extends AsyncTask<String, Integer, String> implements ProgressListener {

            private Context context;
            private PowerManager.WakeLock mWakeLock;

            public DownloadTask(Context context) {
                this.context = context;
            }

            @Override
            public void updateProgress( String progress )
            {
              newDownloadAction = progress;
              publishProgress( 0 );
            }
  
            @Override
            public boolean isCanceled()
            {
              return isDownloadCanceled();
            }

              @Override
            protected String doInBackground(String... sUrls)
            {
              InputStream input = null;
              OutputStream output = null;
              HttpURLConnection connection = null;
              String surl = sUrls[0];
              String fname = null;
              File tmp_file = null;
              try
              {
                try
                {
                    int slidx = surl.lastIndexOf( "segments4/" );
                    String name = surl.substring( slidx+10 );
                    String surlBase = surl.substring( 0, slidx+10 );
                    fname = baseDir + "/brouter/segments4/" + name;

                    boolean delta = true;

                    File targetFile = new File( fname );
                    if ( targetFile.exists() )
                    {
                      updateProgress( "Calculating local checksum.." );
                    
                      // first check for a delta file
                      String md5 = Rd5DiffManager.getMD5( targetFile );
                      String surlDelta = surlBase + "diff/" + name.replace( ".rd5", "/" + md5 + ".rd5diff" );
                      
                      URL urlDelta = new URL(surlDelta);

                      updateProgress( "Connecting.." );

                      connection = (HttpURLConnection) urlDelta.openConnection();
                      connection.connect();

                      // 404 kind of expected here, means there's no delta file
                      if (connection.getResponseCode() == HttpURLConnection.HTTP_NOT_FOUND )
                      {
                        connection = null;
                      }
                    }                   
                
                    if ( connection == null )
                    {                
                      delta = false;
                      URL url = new URL(surl);
                      connection = (HttpURLConnection) url.openConnection();
                      connection.connect();
                    }
                    // expect HTTP 200 OK, so we don't mistakenly save error report
                    // instead of the file
                    if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                        return "Server returned HTTP " + connection.getResponseCode()
                              + " " + connection.getResponseMessage();
                    }

                    // this will be useful to display download percentage
                    // might be -1: server did not report the length
                    int fileLength = connection.getContentLength();
                    currentDownloadSize = fileLength;
                    if ( availableSize >= 0 && fileLength > availableSize ) return "not enough space on sd-card";

                    currentDownloadOperation = delta ? "Updating" : "Loading";
                    
                    // download the file
                    input = connection.getInputStream();
                    
                    tmp_file = new File( fname + ( delta ? "_diff" : "_tmp" ) );
                    output = new FileOutputStream( tmp_file );

                    byte data[] = new byte[4096];
                    long total = 0;
                    long t0 = System.currentTimeMillis();
                    int count;
                    while ((count = input.read(data)) != -1) {
                        if (isDownloadCanceled()) {
                            return "Download canceled!";
                        }
                        total += count;
                        // publishing the progress....
                        if (fileLength > 0) // only if total length is known
                        {
                          int pct = (int) (total * 100 / fileLength);
                          updateProgress( "Progress " + pct + "%" );
                        }
                        else
                        {
                          updateProgress( "Progress (unnown size)" );
                        }

                        output.write(data, 0, count);
                        
                        // enforce < 2 Mbit/s
                        long dt = t0 + total/524 - System.currentTimeMillis();
                        if ( dt > 0  )
                        {
                        	try { Thread.sleep( dt ); } catch( InterruptedException ie ) {}
                        }
                    }
                    output.close();
                    output = null;

                    if ( delta )
                    {
                      updateProgress( "Applying delta.." );
                      File diffFile = tmp_file;
                      tmp_file = new File( fname + "_tmp" );
                      Rd5DiffTool.recoverFromDelta( targetFile, diffFile, tmp_file, this );
                      diffFile.delete();
                    }
                    if (isDownloadCanceled())
                    {
                      return "Canceled!";
                    }
                    if ( tmp_file != null )
                    {
                      updateProgress( "Verifying integrity.." );
                      String check_result = PhysicalFile.checkFileIntegrity( tmp_file );
                      if ( check_result != null ) return check_result;

                      if ( !tmp_file.renameTo( targetFile ) )
                      {
                        return "Could not rename to " + targetFile;
                      }
                      deleteRawTracks(); // invalidate raw-tracks after data update
                    }
                    return null;
                } catch (Exception e) {
                    return e.toString();
                } finally {
                    try {
                        if (output != null)
                            output.close();
                        if (input != null)
                            input.close();
                    } catch (IOException ignored) {
                    }

                    if (connection != null)
                        connection.disconnect();
                }
              }
              finally
              {
               	if ( tmp_file != null ) tmp_file.delete(); // just to be sure
              }
            }    

            @Override
            protected void onPreExecute() {
                super.onPreExecute();
                // take CPU lock to prevent CPU from going off if the user 
                // presses the power button during download
                PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getClass().getName());
                mWakeLock.acquire();
            }

            @Override
            protected void onProgressUpdate(Integer... progress) {
            	if ( !newDownloadAction.equals( downloadAction ) )
            	{
            	  downloadAction = newDownloadAction;
            	  invalidate();
            	}
            }

            @Override
            protected void onPostExecute(String result) {
                mWakeLock.release();
                downloadDone( result == null );
                
                if (result != null)
                    Toast.makeText(context,"Download error: "+result, Toast.LENGTH_LONG).show();
                else
                    Toast.makeText(context,"File downloaded", Toast.LENGTH_SHORT).show();
            }
        
        } // download task
} 
