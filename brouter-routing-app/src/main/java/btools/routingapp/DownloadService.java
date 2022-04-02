package btools.routingapp;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
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

public class DownloadService extends Service implements ProgressListener {
  private static final String PROFILES_DIR = "profiles2/";
  private static final String SEGMENTS_DIR = "segments4/";
  private static final String SEGMENT_DIFF_SUFFIX = ".df5";
  private static final String SEGMENT_SUFFIX = ".rd5";

  private static final boolean DEBUG = false;
  public static boolean serviceState = false;
  private ServerConfig mServerConfig;
  private NotificationHelper mNotificationHelper;
  private List<String> mSegmentNames;
  private String baseDir;
  private volatile String lastProgress = "";
  private ServiceHandler mServiceHandler;
  private boolean bIsDownloading;
  private int mSegmentIndex;
  private int mSegmentCount;

  @Override
  public void onCreate() {
    if (DEBUG) Log.d("SERVICE", "onCreate");
    serviceState = true;
    mServerConfig = new ServerConfig(getApplicationContext());

    HandlerThread thread = new HandlerThread("ServiceStartArguments", 1);
    thread.start();

    // Get the HandlerThread's Looper and use it for our Handler
    Looper serviceLooper = thread.getLooper();
    mServiceHandler = new ServiceHandler(serviceLooper);
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (DEBUG) Log.d("SERVICE", "onStartCommand");

    mNotificationHelper = new NotificationHelper(this);
    Bundle extra = intent.getExtras();
    if (extra != null) {
      String dir = extra.getString("dir");
      mSegmentNames = extra.getStringArrayList("urlparts");
      baseDir = dir;
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
    try {
      mSegmentIndex = 0;
      downloadLookupAndProfiles();

      mSegmentIndex = 1;
      mSegmentCount = mSegmentNames.size();
      for (String segmentName : mSegmentNames) {
        downloadSegment(mServerConfig.getSegmentUrl(), segmentName + SEGMENT_SUFFIX);
        mSegmentIndex++;
      }
    } catch (IOException e) {
      Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show();
    } catch (InterruptedException e) {
      updateProgress("canceled");
    } finally {
      bIsDownloading = false;
      updateProgress("finished");
    }
  }

  public void updateProgress(String progress) {
    if (!lastProgress.equals(progress)) {
      if (mSegmentIndex > 0) {
        progress = mSegmentIndex + "/" + mSegmentCount + " " + progress;
      }
      if (DEBUG) Log.d("BR", "up " + progress);
      Intent intent = new Intent(BInstallerActivity.DOWNLOAD_ACTION);
      intent.putExtra("txt", progress);
      intent.putExtra("ready", bIsDownloading);
      sendBroadcast(intent);
      mNotificationHelper.progressUpdate(progress);
      lastProgress = progress;
    }
  }

  private void downloadLookupAndProfiles() throws IOException, InterruptedException {
    String[] lookups = mServerConfig.getLookups();
    for (String fileName : lookups) {
      if (fileName.length() > 0) {
        File lookupFile = new File(baseDir, PROFILES_DIR + fileName);
        String lookupLocation = mServerConfig.getLookupUrl() + fileName;
        URL lookupUrl = new URL(lookupLocation);
        downloadFile(lookupUrl, lookupFile, false);
      }
    }

    String[] profiles = mServerConfig.getProfiles();
    for (String fileName : profiles) {
      if (fileName.length() > 0) {
        File profileFile = new File(baseDir, PROFILES_DIR + fileName);
        if (profileFile.exists()) {
          String profileLocation = mServerConfig.getProfilesUrl() + fileName;
          URL profileUrl = new URL(profileLocation);
          downloadFile(profileUrl, profileFile, false);
        }
      }
    }
  }

  private void downloadSegment(String segmentBaseUrl, String segmentName) throws IOException, InterruptedException {
    File segmentFile = new File(baseDir, SEGMENTS_DIR + segmentName);
    File segmentFileTemp = new File(segmentFile.getAbsolutePath() + "_tmp");
    try {
      if (segmentFile.exists()) {
        updateProgress("Calculating local checksum...");
        String md5 = Rd5DiffManager.getMD5(segmentFile);
        String segmentDeltaLocation = segmentBaseUrl + "diff/" + segmentName.replace(SEGMENT_SUFFIX, "/" + md5 + SEGMENT_DIFF_SUFFIX);
        URL segmentDeltaUrl = new URL(segmentDeltaLocation);
        if (httpFileExists(segmentDeltaUrl)) {
          File segmentDeltaFile = new File(segmentFile.getAbsolutePath() + "_diff");
          try {
            downloadFile(segmentDeltaUrl, segmentDeltaFile, true);
            updateProgress("Applying delta...");
            Rd5DiffTool.recoverFromDelta(segmentFile, segmentDeltaFile, segmentFileTemp, this);
          } catch (IOException e) {
            throw new IOException("Failed to download & apply delta update", e);
          } finally {
            segmentDeltaFile.delete();
          }
        }
      }

      if (!segmentFileTemp.exists()) {
        URL segmentUrl = new URL(segmentBaseUrl + segmentName);
        downloadFile(segmentUrl, segmentFileTemp, true);
      }

      PhysicalFile.checkFileIntegrity(segmentFileTemp);
      if (segmentFile.exists()) {
        if (!segmentFile.delete()) {
          throw new IOException("Failed to delete existing " + segmentFile.getAbsolutePath());
        }
      }

      if (!segmentFileTemp.renameTo(segmentFile)) {
        throw new IOException("Failed to write " + segmentFile.getAbsolutePath());
      }
    } finally {
      segmentFileTemp.delete();
    }
  }

  private boolean httpFileExists(URL downloadUrl) throws IOException {
    HttpURLConnection connection = (HttpURLConnection) downloadUrl.openConnection();
    connection.setConnectTimeout(5000);
    connection.setRequestMethod("HEAD");
    connection.connect();

    return connection.getResponseCode() == HttpURLConnection.HTTP_OK;
  }


  private void downloadFile(URL downloadUrl, File outputFile, boolean limitDownloadSpeed) throws IOException, InterruptedException {
    // For all those small files the progress reporting is really noisy
    boolean reportDownloadProgress = limitDownloadSpeed;
    HttpURLConnection connection = (HttpURLConnection) downloadUrl.openConnection();
    connection.setConnectTimeout(5000);
    connection.connect();

    if (reportDownloadProgress) updateProgress("Connecting...");

    if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
      throw new IOException("HTTP Request failed");
    }
    int fileLength = connection.getContentLength();
    if (reportDownloadProgress) updateProgress("Loading");

    try (
      InputStream input = connection.getInputStream();
      OutputStream output = new FileOutputStream(outputFile)
    ) {
      byte[] buffer = new byte[4096];
      long total = 0;
      long t0 = System.currentTimeMillis();
      int count;
      while ((count = input.read(buffer)) != -1) {
        if (isCanceled()) {
          throw new InterruptedException();
        }
        total += count;
        // publishing the progress....
        if (fileLength > 0) // only if total length is known
        {
          int pct = (int) (total * 100 / fileLength);
          if (reportDownloadProgress) updateProgress("Progress " + pct + "%");
        } else {
          if (reportDownloadProgress) updateProgress("Progress (unknown size)");
        }
        output.write(buffer, 0, count);

        if (limitDownloadSpeed) {
          // enforce < 16 Mbit/s
          long dt = t0 + total / 2096 - System.currentTimeMillis();
          if (dt > 0) {
            Thread.sleep(dt);
          }
        }
      }
    }
  }

  public boolean isCanceled() {
    return BInstallerActivity.downloadCanceled;
  }

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

}
