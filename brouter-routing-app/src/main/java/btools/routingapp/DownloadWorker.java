package btools.routingapp;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.work.Data;
import androidx.work.ForegroundInfo;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.File;
import java.io.FileFilter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import btools.expressions.BExpressionMetaData;
import btools.mapaccess.PhysicalFile;
import btools.mapaccess.Rd5DiffManager;
import btools.mapaccess.Rd5DiffTool;
import btools.util.ProgressListener;

public class DownloadWorker extends Worker {
  public static final String WORKER_NAME = "BRouterWorker";

  private final static boolean DEBUG = false;

  public static final String KEY_INPUT_SEGMENT_NAMES = "SEGMENT_NAMES";
  public static final String KEY_INPUT_SEGMENT_ALL = "SEGMENT_ALL";
  public static final String KEY_OUTPUT_ERROR = "ERROR";

  public static final int VALUE_SEGMENT_PARTS = 0;
  public static final int VALUE_SEGMENT_ALL = 1;
  public static final int VALUE_SEGMENT_DIFFS = 2;
  public static final int VALUE_SEGMENT_DROPDIFFS = 3;

  public static final String PROGRESS_SEGMENT_NAME = "PROGRESS_SEGMENT_NAME";
  public static final String PROGRESS_SEGMENT_PERCENT = "PROGRESS_SEGMENT_PERCENT";

  private static final int NOTIFICATION_ID = new Random().nextInt();
  public static final String PROFILES_DIR = "profiles2/";
  private static final String SEGMENTS_DIR = "segments4/";
  private static final String SEGMENT_DIFF_SUFFIX = ".df5";
  private static final String SEGMENT_SUFFIX = ".rd5";
  private static final String LOG_TAG = "DownloadWorker";

  private final NotificationManager notificationManager;
  private final ServerConfig mServerConfig;
  private final File baseDir;
  private final ProgressListener diffProgressListener;
  private final DownloadProgressListener downloadProgressListener;
  private final Data.Builder progressBuilder = new Data.Builder();
  private final NotificationCompat.Builder notificationBuilder;
  private int downloadAll;
  private boolean versionChanged;
  private List<URL> done = new ArrayList<>();

  int version = -1;
  int appversion = -1;
  String errorCode = null;
  private boolean bHttpDownloadProblem;

  public DownloadWorker(
    @NonNull Context context,
    @NonNull WorkerParameters parameters) {
    super(context, parameters);
    notificationManager = (NotificationManager)
      context.getSystemService(Context.NOTIFICATION_SERVICE);
    mServerConfig = new ServerConfig(context);
    baseDir = new File(ConfigHelper.getBaseDir(context), "brouter");

    notificationBuilder = createNotificationBuilder();

    downloadProgressListener = new DownloadProgressListener() {
      private String currentDownloadName;
      private DownloadType currentDownloadType;
      private int lastProgressPercent;

      @Override
      public void onDownloadStart(String downloadName, DownloadType downloadType) {
        if (DEBUG) Log.d(LOG_TAG, "onDownloadStart " + downloadName);
        currentDownloadName = downloadName;
        currentDownloadType = downloadType;
        if (downloadType == DownloadType.SEGMENT) {
          progressBuilder.putString(PROGRESS_SEGMENT_NAME, downloadName);
          notificationBuilder.setContentText(downloadName);
        } else {
          progressBuilder.putString(PROGRESS_SEGMENT_NAME, "check profiles");
        }
        setProgressAsync(progressBuilder.build());
      }

      @Override
      public void onDownloadInfo(String info) {
        if (DEBUG) Log.d(LOG_TAG, "onDownloadInfo " + info);
        notificationBuilder.setContentText(currentDownloadName + ": " + info);
        notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
      }

      @Override
      public void onDownloadProgress(int max, int progress) {
        int progressPercent = (int) (progress * 100L / max);

        // Only report segments and update if it changed to avoid hammering NotificationManager
        if (currentDownloadType != DownloadType.SEGMENT || progressPercent == lastProgressPercent) {
          return;
        }

        if (max > 0) {
          notificationBuilder.setProgress(max, progress, false);
          progressBuilder.putInt(PROGRESS_SEGMENT_PERCENT, progressPercent);
        } else {
          notificationBuilder.setProgress(0, 0, true);
          progressBuilder.putInt(PROGRESS_SEGMENT_PERCENT, -1);
        }
        progressBuilder.putString(PROGRESS_SEGMENT_NAME, currentDownloadName);
        notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
        setProgressAsync(progressBuilder.build());

        lastProgressPercent = progressPercent;
      }

      @Override
      public void onDownloadFinished() {
        if (DEBUG) Log.d(LOG_TAG, "onDownloadFinished ");
      }
    };

    diffProgressListener = new ProgressListener() {
      @Override
      public void updateProgress(String task, int progress) {
        if (DEBUG) Log.d(LOG_TAG, "updateProgress " + task + " " + progress);
        downloadProgressListener.onDownloadInfo(task);
        downloadProgressListener.onDownloadProgress(100, progress);
      }

      @Override
      public boolean isCanceled() {
        return isStopped();
      }
    };
  }

  @NonNull
  @Override
  public Result doWork() {
    Data inputData = getInputData();
    Data.Builder output = new Data.Builder();
    String[] segmentNames = inputData.getStringArray(KEY_INPUT_SEGMENT_NAMES);
    downloadAll = inputData.getInt(KEY_INPUT_SEGMENT_ALL, 0);
    if (DEBUG)
      Log.d(LOG_TAG, "doWork done " + done.size() + " segs " + segmentNames.length + " " + this);
    if (segmentNames == null) {
      if (DEBUG) Log.d(LOG_TAG, "Failure: no segmentNames");
      return Result.failure();
    }
    notificationBuilder.setContentText("Starting Download");
    // Mark the Worker as important
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
      setForegroundAsync(new ForegroundInfo(NOTIFICATION_ID, notificationBuilder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC));
    else
      setForegroundAsync(new ForegroundInfo(NOTIFICATION_ID, notificationBuilder.build()));
    try {
      if (DEBUG) Log.d(LOG_TAG, "Download lookup & profiles");
      if (!downloadLookup()) {
        output.putString(KEY_OUTPUT_ERROR, (errorCode != null ? errorCode : "Version error"));
        return Result.failure(output.build());
      }

      if (!versionChanged && downloadAll != VALUE_SEGMENT_ALL) {
        List<String> tmpSegementNames = new ArrayList<>();
        File segmentFolder = new File(baseDir, SEGMENTS_DIR);
        File[] files = segmentFolder.listFiles(new FileFilter() {
          @Override
          public boolean accept(File file) {
            return (file.getPath().endsWith(SEGMENT_SUFFIX));
          }
        });
        for (File f : files) {
          int thePFversion = PhysicalFile.checkVersionIntegrity(f);
          if (DEBUG) Log.d("worker", "check " + f.getName() + " " + thePFversion + "=" + version);
          if (thePFversion != -1 && thePFversion != version) {
            tmpSegementNames.add(f.getName().substring(0, f.getName().indexOf(".")));
            versionChanged = true;
          }
        }
        if (tmpSegementNames.size() > 0 && (downloadAll != VALUE_SEGMENT_DIFFS && downloadAll != VALUE_SEGMENT_DROPDIFFS)) {
          output.putString(KEY_OUTPUT_ERROR, "Version diffs");
          return Result.failure(output.build());
        }
        if (downloadAll == VALUE_SEGMENT_DIFFS) {
          segmentNames = tmpSegementNames.toArray(new String[0]);
        } else if (downloadAll == VALUE_SEGMENT_DROPDIFFS) {
          for (String segmentName : tmpSegementNames) {
            File segmentFile = new File(baseDir, SEGMENTS_DIR + segmentName + SEGMENT_SUFFIX);
            segmentFile.delete();
          }
          return Result.success();
        }
      }

      downloadProfiles();

      for (String segmentName : segmentNames) {
        if (isStopped()) break;
        downloadProgressListener.onDownloadStart(segmentName, DownloadType.SEGMENT);
        if (DEBUG) Log.d(LOG_TAG, "Download segment " + segmentName);
        downloadSegment(mServerConfig.getSegmentUrl(), segmentName + SEGMENT_SUFFIX);
      }
    } catch (IOException e) {
      output.putString(KEY_OUTPUT_ERROR, e.getMessage());
      return Result.failure(output.build());
    } catch (InterruptedException e) {
      output.putString(KEY_OUTPUT_ERROR, e.getMessage());
      return Result.failure(output.build());
    }
    if (DEBUG) Log.d(LOG_TAG, "doWork finished");
    return Result.success();
  }

  private boolean downloadLookup() throws IOException, InterruptedException {
    String[] lookups = mServerConfig.getLookups();
    for (String fileName : lookups) {
      appversion = BuildConfig.VERSION_CODE;
      if (fileName.length() > 0) {
        File lookupFile = new File(baseDir, PROFILES_DIR + fileName);
        BExpressionMetaData meta = new BExpressionMetaData();
        meta.readMetaData(lookupFile);
        version = meta.lookupVersion;
        int newappversion = meta.minAppVersion;

        int size = (int) (lookupFile.exists() ? lookupFile.length() : 0);
        File tmplookupFile = new File(baseDir, PROFILES_DIR + fileName + ".tmp");
        boolean changed = false;
        if (tmplookupFile.exists()) {
          lookupFile.delete();
          tmplookupFile.renameTo(lookupFile);
          versionChanged = true;
          meta.readMetaData(lookupFile);
          version = meta.lookupVersion;
          newappversion = meta.minAppVersion;
        } else {
          String lookupLocation = mServerConfig.getLookupUrl() + fileName;
          if (bHttpDownloadProblem) lookupLocation = lookupLocation.replace("https://", "http://");
          URL lookupUrl = new URL(lookupLocation);
          downloadProgressListener.onDownloadStart(fileName, DownloadType.LOOKUP);
          changed = downloadFile(lookupUrl, tmplookupFile, size, false, DownloadType.LOOKUP);
          downloadProgressListener.onDownloadFinished();
          done.add(lookupUrl);
        }
        int newversion = version;
        if (changed) {
          meta = new BExpressionMetaData();
          meta.readMetaData(tmplookupFile);
          newversion = meta.lookupVersion;
          newappversion = meta.minAppVersion;
        }
        if (newappversion != -1 && newappversion > appversion) {
          if (DEBUG) Log.d(LOG_TAG, "app version old " + appversion + " new " + newappversion);
          errorCode = "Version new app";
          return false;
        }
        if (changed && downloadAll == VALUE_SEGMENT_PARTS) {
          if (DEBUG) Log.d(LOG_TAG, "version old " + version + " new " + newversion);
          if (version != newversion) {
            errorCode = "Version error";
            return false;
          }
        }
        if (changed) {
          lookupFile.delete();
          tmplookupFile.renameTo(lookupFile);
          versionChanged = changed;
          meta.readMetaData(lookupFile);
          version = meta.lookupVersion;
        } else {
          if (tmplookupFile.exists()) tmplookupFile.delete();
        }

      }
    }

    return true;
  }

  private void downloadProfiles() throws IOException, InterruptedException {
    String[] profiles = mServerConfig.getProfiles();
    for (String fileName : profiles) {
      if (isStopped()) break;
      if (fileName.length() > 0) {
        File profileFile = new File(baseDir, PROFILES_DIR + fileName);
        //if (profileFile.exists())
        {
          String profileLocation = mServerConfig.getProfilesUrl() + fileName;
          if (bHttpDownloadProblem) profileLocation = profileLocation.replace("https://", "http://");
          URL profileUrl = new URL(profileLocation);
          int size = (int) (profileFile.exists() ? profileFile.length() : 0);

          try {
            downloadProgressListener.onDownloadStart(fileName, DownloadType.PROFILE);
            downloadFile(profileUrl, profileFile, size, false, DownloadType.PROFILE);
            downloadProgressListener.onDownloadFinished();
            done.add(profileUrl);
          } catch (IOException e) {
            // no need to block other updates
          } catch (InterruptedException e) {
            throw new RuntimeException(e.getMessage());
          }
        }
      }
    }
  }

  private void downloadSegment(String segmentBaseUrl, String segmentName) throws IOException, InterruptedException {
    File segmentFile = new File(baseDir, SEGMENTS_DIR + segmentName);
    File segmentFileTemp = new File(segmentFile.getAbsolutePath() + "_tmp");
    if (bHttpDownloadProblem) segmentBaseUrl = segmentBaseUrl.replace("https://", "http://");

    if (DEBUG) Log.d(LOG_TAG, "Download " + segmentName + " " + version + " " + versionChanged);
    try {
      if (segmentFile.exists()) {
        if (!versionChanged) {    // no diff file on version change
          String md5 = Rd5DiffManager.getMD5(segmentFile);
          if (DEBUG) Log.d(LOG_TAG, "Calculating local checksum " + md5);
          String segmentDeltaLocation = segmentBaseUrl + "diff/" + segmentName.replace(SEGMENT_SUFFIX, "/" + md5 + SEGMENT_DIFF_SUFFIX);
          if (bHttpDownloadProblem) segmentDeltaLocation = segmentDeltaLocation.replace("https://", "http://");
          URL segmentDeltaUrl = new URL(segmentDeltaLocation);
          if (httpFileExists(segmentDeltaUrl)) {
            File segmentDeltaFile = new File(segmentFile.getAbsolutePath() + "_diff");
            try {
              downloadFile(segmentDeltaUrl, segmentDeltaFile, 0, true, DownloadType.SEGMENT);
              done.add(segmentDeltaUrl);
              if (DEBUG) Log.d(LOG_TAG, "Applying delta");
              Rd5DiffTool.recoverFromDelta(segmentFile, segmentDeltaFile, segmentFileTemp, diffProgressListener);
            } catch (IOException e) {
              throw new IOException("Failed to download & apply delta update", e);
            } finally {
              segmentDeltaFile.delete();
            }
          }
        } else {
          if (segmentFileTemp.exists()) {
            segmentFileTemp.delete();
          }
        }
      }

      if (!segmentFileTemp.exists()) {
        URL segmentUrl = new URL(segmentBaseUrl + segmentName);
        downloadFile(segmentUrl, segmentFileTemp, 0, true, DownloadType.SEGMENT);
        done.add(segmentUrl);
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
    HttpURLConnection connection = null;
    try {
      connection = (HttpURLConnection) downloadUrl.openConnection();
      connection.setConnectTimeout(5000);
      connection.setRequestMethod("HEAD");
      connection.setDoInput(false);
      connection.connect();
      return connection.getResponseCode() == HttpURLConnection.HTTP_OK;
    } catch (javax.net.ssl.SSLHandshakeException e) {
      String url = downloadUrl.toString().replace("https://", "http://");
      downloadUrl = new URL(url);
      try {
        connection = (HttpURLConnection) downloadUrl.openConnection();
        connection.setConnectTimeout(5000);
        connection.setRequestMethod("HEAD");
        connection.setDoInput(false);
        connection.connect();
        bHttpDownloadProblem = true;
        return connection.getResponseCode() == HttpURLConnection.HTTP_OK;
      } finally {
        connection.disconnect();
      }
    } finally {
      connection.disconnect();
    }

  }

  private boolean downloadFile(URL downloadUrl, File outputFile, int fileSize, boolean limitDownloadSpeed, DownloadType type) throws IOException, InterruptedException {
    if (DEBUG) Log.d(LOG_TAG, "download " + outputFile.getAbsolutePath());
    HttpURLConnection connection = null;
    InputStream input = null;
    OutputStream output = null;
    try {
      try {
        connection = (HttpURLConnection) downloadUrl.openConnection();
        connection.setConnectTimeout(5000);
        connection.setDefaultUseCaches(false);
        connection.connect();
      } catch (javax.net.ssl.SSLHandshakeException e) {
        String url = downloadUrl.toString().replace("https://", "http://");
        downloadUrl = new URL(url);
        connection = (HttpURLConnection) downloadUrl.openConnection();
        connection.setConnectTimeout(5000);
        connection.setDefaultUseCaches(false);
        connection.connect();
        bHttpDownloadProblem = true;
      }

      if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
        throw new IOException("HTTP Request failed: " + downloadUrl + " returned " + connection.getResponseCode());
      }
      int dataLength = connection.getContentLength();
      // no need of download when size equal
      // file size not the best coice but easy to handle, date is not available
      switch (type) {
        case LOOKUP:
          if (fileSize == dataLength) return false;
          break;
        case PROFILE:
          if (fileSize == dataLength) return false;
          break;
        default:
          break;
      }
      input = connection.getInputStream();
      output = new FileOutputStream(outputFile);

      byte[] buffer = new byte[4096];
      int total = 0;
      long t0 = System.currentTimeMillis();
      int count;
      while ((count = input.read(buffer)) != -1) {
        if (isStopped()) {
          throw new InterruptedException();
        }
        total += count;
        output.write(buffer, 0, count);

        downloadProgressListener.onDownloadProgress(dataLength, total);

        if (limitDownloadSpeed) {
          // enforce < 16 Mbit/s
          long dt = t0 + total / 2096 - System.currentTimeMillis();
          if (dt > 0) {
            Thread.sleep(dt);
          }
        }
      }
    } finally {
      if (input != null) input.close();
      if (output != null) output.close();
      connection.disconnect();
    }
    return true;
  }

  @NonNull
  private NotificationCompat.Builder createNotificationBuilder() {
    Context context = getApplicationContext();
    String id = context.getString(R.string.notification_channel_id);
    String title = context.getString(R.string.notification_title);
    String cancel = context.getString(R.string.cancel_download);
    // This PendingIntent can be used to cancel the worker
    PendingIntent intent = WorkManager.getInstance(context)
      .createCancelPendingIntent(getId());

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      createChannel();
    }

    return new NotificationCompat.Builder(context, id)
      .setContentTitle(title)
      .setTicker(title)
      .setOnlyAlertOnce(true)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setSmallIcon(android.R.drawable.stat_sys_download)
      .setOngoing(true)
      // Add the cancel action to the notification which can
      // be used to cancel the worker
      .addAction(android.R.drawable.ic_delete, cancel, intent);
  }

  @RequiresApi(Build.VERSION_CODES.O)
  private void createChannel() {
    CharSequence name = getApplicationContext().getString(R.string.channel_name);
    int importance = NotificationManager.IMPORTANCE_LOW;
    NotificationChannel channel = new NotificationChannel(getApplicationContext().getString(R.string.notification_channel_id), name, importance);
    // Register the channel with the system; you can't change the importance
    // or other notification behaviors after this
    notificationManager.createNotificationChannel(channel);
  }

  public enum DownloadType {
    LOOKUP,
    PROFILE,
    SEGMENT
  }

  interface DownloadProgressListener {
    void onDownloadStart(String downloadName, DownloadType downloadType);

    void onDownloadInfo(String info);

    void onDownloadProgress(int max, int progress);

    void onDownloadFinished();
  }
}
