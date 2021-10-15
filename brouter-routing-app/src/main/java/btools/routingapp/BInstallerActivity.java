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

public class BInstallerActivity extends Activity {

  public static final String DOWNLOAD_ACTION = "btools.routingapp.download";

  private static final int DIALOG_CONFIRM_DELETE_ID = 1;
  private BInstallerView mBInstallerView;
  private DownloadReceiver downloadReceiver;

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

    mBInstallerView = new BInstallerView(this);
    setContentView(mBInstallerView);
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
        mBInstallerView.setState(txt, ready);
      }
    }
  }
}
