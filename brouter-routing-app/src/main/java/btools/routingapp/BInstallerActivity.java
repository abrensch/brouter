package btools.routingapp;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.StatFs;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;

public class BInstallerActivity extends Activity {

  public static final String DOWNLOAD_ACTION = "btools.routingapp.download";
  private static final int DIALOG_CONFIRM_DELETE_ID = 1;
  public static boolean downloadCanceled = false;
  private File baseDir;
  private BInstallerView mBInstallerView;
  private DownloadReceiver downloadReceiver;
  private View mDownloadInfo;
  private TextView mDownloadInfoText;
  private Button mButtonDownloadCancel;

  static public long getAvailableSpace(String baseDir) {
    StatFs stat = new StatFs(baseDir);

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
      return stat.getAvailableBlocksLong() * stat.getBlockSizeLong();
    } else {
      //noinspection deprecation
      return (long) stat.getAvailableBlocks() * stat.getBlockSize();
    }
  }

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

    setContentView(R.layout.activity_binstaller);
    mBInstallerView = findViewById(R.id.BInstallerView);
    mDownloadInfo = findViewById(R.id.view_download_progress);
    mDownloadInfoText = findViewById(R.id.textViewDownloadProgress);
    mButtonDownloadCancel = findViewById(R.id.buttonDownloadCancel);
    mButtonDownloadCancel.setOnClickListener(view -> {
      cancelDownload();
    });

    baseDir = ConfigHelper.getBaseDir(this);
  }

  private String baseNameForTile(int tileIndex) {
    int lon = (tileIndex % 72) * 5 - 180;
    int lat = (tileIndex / 72) * 5 - 90;
    String slon = lon < 0 ? "W" + (-lon) : "E" + lon;
    String slat = lat < 0 ? "S" + (-lat) : "N" + lat;
    return slon + "_" + slat;
  }

  private void deleteRawTracks() {
    File modeDir = new File(baseDir, "brouter/modes");
    String[] fileNames = modeDir.list();
    if (fileNames == null) return;
    for (String fileName : fileNames) {
      if (fileName.endsWith("_rawtrack.dat")) {
        File f = new File(modeDir, fileName);
        f.delete();
      }
    }
  }

  private void cancelDownload() {
    downloadCanceled = true;
    mDownloadInfoText.setText(getString(R.string.download_info_cancel));
  }

  public void downloadAll(ArrayList<Integer> downloadList) {
    ArrayList<String> urlparts = new ArrayList<>();
    for (Integer i : downloadList) {
      urlparts.add(baseNameForTile(i));
    }

    mBInstallerView.setVisibility(View.GONE);
    mDownloadInfo.setVisibility(View.VISIBLE);
    downloadCanceled = false;
    mDownloadInfoText.setText(R.string.download_info_start);

    Intent intent = new Intent(this, DownloadService.class);
    intent.putExtra("dir", baseDir.getAbsolutePath() + "/brouter/");
    intent.putExtra("urlparts", urlparts);
    startService(intent);

    deleteRawTracks(); // invalidate raw-tracks after data update
  }

  @Override
  protected void onResume() {
    super.onResume();

    IntentFilter filter = new IntentFilter();
    filter.addAction(DOWNLOAD_ACTION);

    downloadReceiver = new DownloadReceiver();
    registerReceiver(downloadReceiver, filter);
  }

  @Override
  protected void onPause() {
    super.onPause();
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    if (downloadReceiver != null) unregisterReceiver(downloadReceiver);
    System.exit(0);
  }

  @Override
  protected Dialog onCreateDialog(int id) {
    AlertDialog.Builder builder;
    switch (id) {
      case DIALOG_CONFIRM_DELETE_ID:
        builder = new AlertDialog.Builder(this);
        builder
          .setTitle("Confirm Delete")
          .setMessage("Really delete?").setPositiveButton("Yes", new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int id) {
            mBInstallerView.deleteSelectedTiles();
          }
        }).setNegativeButton("No", new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int id) {
          }
        });
        return builder.create();

      default:
        return null;
    }
  }

  public void showConfirmDelete() {
    showDialog(DIALOG_CONFIRM_DELETE_ID);
  }

  public class DownloadReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
      if (intent.hasExtra("txt")) {
        String txt = intent.getStringExtra("txt");
        boolean ready = intent.getBooleanExtra("ready", false);
        if (!ready) {
          mBInstallerView.setVisibility(View.VISIBLE);
          mDownloadInfo.setVisibility(View.GONE);
          scanExistingFiles();
        }
        mDownloadInfoText.setText(txt);
      }
    }
  }
}
