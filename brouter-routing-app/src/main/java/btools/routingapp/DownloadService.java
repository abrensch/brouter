package btools.routingapp;

import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.net.TrafficStats;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.StatFs;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

import btools.mapaccess.PhysicalFile;
import btools.mapaccess.Rd5DiffManager;
import btools.mapaccess.Rd5DiffTool;
import btools.util.ProgressListener;

public class DownloadService extends Service implements ProgressListener  {

    private static final boolean DEBUG = false;

    String segmenturl = "https://brouter.de/brouter/segments4/";
    String lookupurl = "https://brouter.de/brouter/segments4/";
    String profilesurl = "https://brouter.de/brouter/segments4/";
    String checkLookup = "lookups.dat";
    String checkProfiles = "";

    private NotificationHelper mNotificationHelper;
    private List<String> mUrlList;
    private String baseDir;

    private volatile String newDownloadAction = "";
    private volatile String currentDownloadOperation = "";
    private long availableSize;

    private Looper mServiceLooper;
    private ServiceHandler mServiceHandler;
    private NotificationManager mNM;
    String downloadUrl;
    public static boolean serviceState = false;
    private boolean bIsDownloading;

    // Handler that receives messages from the thread
    private final class ServiceHandler extends Handler {
        public ServiceHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            bIsDownloading = true;
            downloadFiles();

            stopForeground(true);
            stopSelf(msg.arg1);
            mNotificationHelper.stopNotification();
        }
    }


    @Override
    public void onCreate() {
        if (DEBUG) Log.d("SERVICE", "onCreate");
        serviceState = true;

        HandlerThread thread = new HandlerThread("ServiceStartArguments", 1);
        thread.start();

        // Get the HandlerThread's Looper and use it for our Handler
        mServiceLooper = thread.getLooper();
        mServiceHandler = new ServiceHandler(mServiceLooper);

        availableSize = -1;
       try
        {
            StatFs stat = new StatFs(baseDir);
            availableSize = (long)stat.getAvailableBlocksLong()*stat.getBlockSizeLong();
        }
        catch (Exception e) { /* ignore */ }

    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (DEBUG) Log.d("SERVICE", "onStartCommand");

        mNotificationHelper = new NotificationHelper(this);
        Bundle extra = intent.getExtras();
        if (extra != null) {
            String dir = extra.getString("dir");
            List<String> urlparts = extra.getStringArrayList("urlparts");
            mUrlList = urlparts;
            baseDir = dir;

            File configFile = new File (dir, "segments4/serverconfig.txt");
            if ( configFile.exists() ) {
                try {
                    BufferedReader br = new BufferedReader( new FileReader( configFile ) );
                    for ( ;; )
                    {
                        String line = br.readLine();
                        if ( line == null ) break;
                        if ( line.trim().startsWith( "segment_url=" ) ) {
                            segmenturl = line.substring(12);
                        }
                        else if ( line.trim().startsWith( "lookup_url=" ) ) {
                            lookupurl = line.substring(11);
                        }
                        else if ( line.trim().startsWith( "profiles_url=" ) ) {
                            profilesurl = line.substring(13);
                        }
                        else if ( line.trim().startsWith( "check_lookup=" ) ) {
                            checkLookup = line.substring(13);
                        }
                        else if ( line.trim().startsWith( "check_profiles=" ) ) {
                            checkProfiles = line.substring(15);
                        }
                    }
                    br.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }

            }

        }

        mNotificationHelper.startNotification(this);

        Message msg = mServiceHandler.obtainMessage();
        msg.arg1 = startId;
        mServiceHandler.sendMessage(msg);

        // If we get killed, after returning from here, restart
        return START_STICKY;
    }


    @Override
    public void onDestroy() {
        if (DEBUG) Log.d("SERVICE", "onDestroy");
        serviceState = false;
        super.onDestroy();
     }


    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }


    public void downloadFiles() {

        // first check lookup table and prifles
        String result = checkScripts();
        if ( result != null) {
            if (DEBUG) Log.d("BR", "error: " + result);
            bIsDownloading = false;
            updateProgress( "finished " );

            Toast.makeText(this, result, Toast.LENGTH_LONG).show();
            return;
        }


        int count = 1;
        int size = mUrlList.size();
        for (String part: mUrlList) {
            String url = segmenturl + part  + ".rd5";
            if (DEBUG) Log.d("BR", "downlaod " + url);

            result = download(count, size, url);
            if (result != null) {
                if (DEBUG) Log.d("BR", "" + result);
                Toast.makeText(this, result, Toast.LENGTH_LONG).show();
                break;
            } else {
                updateProgress( "Download "  + part + " " + count + "/"+ size + " finshed");
            }
            count++;
        }

        bIsDownloading = false;
        updateProgress( "finished " );
    }


    public void updateProgress( String progress )
    {
        if ( !newDownloadAction.equals( progress ) )
        {
            if (DEBUG) Log.d("BR", "up " + progress);
            Intent intent = new Intent(BInstallerActivity.DOWNLOAD_ACTION);
            intent.putExtra("txt", progress);
            intent.putExtra("ready", bIsDownloading);
            sendBroadcast(intent);;
            newDownloadAction = progress;
            mNotificationHelper.progressUpdate(newDownloadAction);
        }

    }

    private String download(int counter, int size, String surl)
    {
        InputStream input = null;
        OutputStream output = null;
        HttpURLConnection connection = null;
        File fname = null;
        File tmp_file = null;
        try
        {
            try
            {
                TrafficStats.setThreadStatsTag(1);

                int slidx = surl.lastIndexOf( "segments4/" );
                String name = surl.substring( slidx+10 );
                String surlBase = surl.substring( 0, slidx+10 );
                fname = new File (baseDir, "segments4/" + name);

                boolean delta = true;

                 // if (!targetFile.getParentFile().exists())  targetFile.getParentFile().mkdirs();
                if ( fname.exists() )
                {
                    updateProgress( "Calculating local checksum.." );

                    // first check for a delta file
                    String md5 = Rd5DiffManager.getMD5( fname );
                    String surlDelta = surlBase + "diff/" + name.replace( ".rd5", "/" + md5 + ".df5" );

                    URL urlDelta = new URL(surlDelta);

                    connection = (HttpURLConnection) urlDelta.openConnection();
                    connection.connect();

                    // 404 kind of expected here, means there's no delta file
                    if (connection.getResponseCode() == HttpURLConnection.HTTP_NOT_FOUND )
                    {
                         connection = null;
                    } else {
                        updateProgress( "Connecting.." + surlDelta );
                    }
                }

                if ( connection == null )
                {
                    updateProgress( "Connecting.." + surl );

                    delta = false;
                    URL url = new URL(surl);
                    connection = (HttpURLConnection) url.openConnection();
                    connection.connect();
                }

                updateProgress( "Connecting.." + counter + "/"+size );

                // expect HTTP 200 OK, so we don't mistakenly save error report
                // instead of the file
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    return "Server returned HTTP " + connection.getResponseCode()
                            + " " + connection.getResponseMessage();
                }

                // this will be useful to display download percentage
                // might be -1: server did not report the length
                int fileLength = connection.getContentLength();
                long currentDownloadSize = fileLength;
                if ( availableSize >= 0 && fileLength > availableSize ) return "not enough space on sd-card";

                currentDownloadOperation = delta ? "Updating" : "Loading";
                updateProgress( currentDownloadOperation);

                // download the file
                input = connection.getInputStream();

                tmp_file = new File( fname.getAbsolutePath() + ( delta ? "_diff" : "_tmp" ) );
                output = new FileOutputStream( tmp_file );

                byte[] data = new byte[4096];
                long total = 0;
                long t0 = System.currentTimeMillis();
                int count;
                while ((count = input.read(data)) != -1) {
                    if (isCanceled()) {
                        return "Download canceled!";
                    }
                    total += count;
                    // publishing the progress....
                    if (fileLength > 0) // only if total length is known
                    {
                        int pct = (int) (total * 100 / fileLength);
                        updateProgress( "Progress "  + counter + "/"+size  + " .. " + pct + "%" );
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
                    Rd5DiffTool.recoverFromDelta( fname, diffFile, tmp_file, this );
                    diffFile.delete();
               }
                if (isCanceled())
                {
                    return "Canceled!";
                }
                if ( tmp_file != null )
                {
                    updateProgress( "Verifying integrity.." );
                    String check_result = PhysicalFile.checkFileIntegrity( tmp_file );
                    if ( check_result != null ) {
                        if (check_result.startsWith("version old lookups.dat") ) {

                        }
                        return check_result;
                    }

                    if ( !tmp_file.renameTo( fname ) )
                    {
                        return "Could not rename to " + fname.getAbsolutePath();
                    }

                }
                return null;
            } catch (Exception e) {
                e.printStackTrace(); ;
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

    private String checkScripts() {

        String[] sa = checkLookup.split(",");
        for (String f: sa) {
            if (f.length()>0) {
                File file = new File(baseDir + "profiles2", f);
                checkOrDownloadLookup(f, file);
            }
        }

        sa = checkProfiles.split(",");
        for (String f : sa) {
            if (f.length()>0) {
                File file = new File(baseDir + "profiles2", f);
                if (file.exists()) {
                    String result = checkOrDownloadScript(f, file);
                    if (result != null) {
                        return result;
                    }
                }
            }
        }
        return null;
    }

    private String checkOrDownloadLookup(String fileName, File f) {
        String url = lookupurl + fileName;
        return downloadScript(url, f);
    }

    private String checkOrDownloadScript(String fileName, File f) {
        String url = profilesurl + fileName;
        return downloadScript(url, f);
    }

    private String downloadScript(String surl, File f) {
        long size = 0L;
        if (f.exists()) {
            size = f.length();
        }

        InputStream input = null;
        OutputStream output = null;
        HttpURLConnection connection = null;
        File tmp_file = null;
        File targetFile = f;

        try
        {
            try
            {
                TrafficStats.setThreadStatsTag(1);

                if ( connection == null )
                {
                    URL url = new URL(surl);
                    connection = (HttpURLConnection) url.openConnection();
                    connection.connect();
                }
                // expect HTTP 200 OK, so we don't mistakenly save error report
                // instead of the file
                if (connection.getResponseCode() == HttpURLConnection.HTTP_NOT_FOUND) {
                    return null;
                }
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    return "Server returned HTTP " + connection.getResponseCode()
                            + " " + connection.getResponseMessage() + " " + f.getName();
                }


                // this will be useful to display download percentage
                // might be -1: server did not report the length
                long fileLength = (long)connection.getContentLength();
                if (DEBUG) Log.d("BR", "file size " + size + " == " + fileLength + " " + f.getName());
                if (fileLength != size) {
                    long currentDownloadSize = fileLength;
                    if (availableSize >= 0 && fileLength > availableSize)
                        return "not enough space on sd-card";

                    currentDownloadOperation = "Updating";

                    // download the file
                    input = connection.getInputStream();

                    tmp_file = new File(f.getAbsolutePath() + "_tmp");
                    output = new FileOutputStream(tmp_file);

                    byte data[] = new byte[4096];
                    long total = 0;
                    long t0 = System.currentTimeMillis();
                    int count;
                    while ((count = input.read(data)) != -1) {
                        if (isCanceled()) {
                            return "Download canceled!";
                        }
                        total += count;
                        // publishing the progress....
                        if (fileLength > 0) // only if total length is known
                        {
                            int pct = (int) (total * 100 / fileLength);
                            updateProgress("Progress " + pct + "%");
                        } else {
                            updateProgress("Progress (unnown size)");
                        }

                        output.write(data, 0, count);

                        // enforce < 2 Mbit/s
                        long dt = t0 + total / 524 - System.currentTimeMillis();
                        if (dt > 0) {
                            try {
                                Thread.sleep(dt);
                            } catch (InterruptedException ie) {
                            }
                        }
                    }
                    output.close();
                    output = null;
                }

                if (isCanceled())
                {
                    return "Canceled!";
                }
                if ( tmp_file != null )
                {
                    f.delete();

                    if ( !tmp_file.renameTo( f ) )
                    {
                        return "Could not rename to " + f.getName();
                    }
                    if (DEBUG) Log.d("BR", "update " + f.getName());
                }
                return null;
            } catch (Exception e) {
                return e.toString() ;
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


    public boolean isCanceled() {
        return BInstallerView.downloadCanceled;
    }

}
