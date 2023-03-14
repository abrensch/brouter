package btools.routingapp;

import static btools.routingapp.BInstallerView.MASK_CURRENT_RD5;
import static btools.routingapp.BInstallerView.MASK_DELETED_RD5;
import static btools.routingapp.BInstallerView.MASK_INSTALLED_RD5;
import static btools.routingapp.BInstallerView.MASK_SELECTED_RD5;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.pm.ActivityInfo;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.os.StatFs;
import android.text.format.Formatter;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.WorkRequest;

import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;

import btools.router.RoutingHelper;

public class BInstallerActivity extends AppCompatActivity {

  private static final int DIALOG_CONFIRM_DELETE_ID = 1;
  public static boolean downloadCanceled = false;
  private File mBaseDir;
  private BInstallerView mBInstallerView;
  private Button mButtonDownload;
  private TextView mSummaryInfo;
  private LinearProgressIndicator mProgressIndicator;

  public static long getAvailableSpace(String baseDir) {
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

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      if (ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.POST_NOTIFICATIONS)
        == PackageManager.PERMISSION_GRANTED) {
        // nothing to do
      }
      if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
        //
      } else {
        // You can directly ask for the permission.
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, MY_PERMISSIONS_REQUEST_NITIFICATION);
      }
    }

    mSummaryInfo = findViewById(R.id.textViewSegmentSummary);
    mBInstallerView = findViewById(R.id.BInstallerView);
    mBInstallerView.setOnSelectListener(
      () -> {
        updateDownloadButton();
      }
    );
    mButtonDownload = findViewById(R.id.buttonDownload);
    mButtonDownload.setOnClickListener(
      view -> {
        if (mBInstallerView.getSelectedTiles(MASK_DELETED_RD5).size() > 0) {
          showConfirmDelete();
        } else if (mBInstallerView.getSelectedTiles(MASK_SELECTED_RD5).size() > 0) {
          downloadSelectedTiles();
        } else {
          downloadInstalledTiles();
        }
      }
    );
    mProgressIndicator = findViewById(R.id.progressDownload);

    mBaseDir = ConfigHelper.getBaseDir(this);
    scanExistingFiles();
  }

  private String getSegmentsPlural(int count) {
    Resources res = getResources();
    return res.getQuantityString(R.plurals.numberOfSegments, count, count);
  }

  private void updateDownloadButton() {
    final ArrayList<Integer> selectedTilesDownload = mBInstallerView.getSelectedTiles(MASK_SELECTED_RD5);
    final ArrayList<Integer> selectedTilesUpdate = mBInstallerView.getSelectedTiles(MASK_INSTALLED_RD5);
    final ArrayList<Integer> selectedTilesDelete = mBInstallerView.getSelectedTiles(MASK_DELETED_RD5);
    mSummaryInfo.setText("");

    if (selectedTilesDelete.size() > 0) {
      mButtonDownload.setText(getString(R.string.action_delete, getSegmentsPlural(selectedTilesDelete.size())));
      mButtonDownload.setEnabled(true);
    } else if (selectedTilesDownload.size() > 0) {
      long tileSize = 0;
      for (int tileIndex : selectedTilesDownload) {
        tileSize += BInstallerSizes.getRd5Size(tileIndex);
      }
      mButtonDownload.setText(getString(R.string.action_download, getSegmentsPlural(selectedTilesDownload.size())));
      mButtonDownload.setEnabled(true);
      mSummaryInfo.setText(getString(R.string.summary_segments, Formatter.formatFileSize(this, tileSize), Formatter.formatFileSize(this, getAvailableSpace(mBaseDir.getAbsolutePath()))));
    } else if (selectedTilesUpdate.size() > 0) {
      mButtonDownload.setText(getString(R.string.action_update, getSegmentsPlural(selectedTilesUpdate.size())));
      mButtonDownload.setEnabled(true);
    } else {
      mButtonDownload.setText(getString(R.string.action_select));
      mButtonDownload.setEnabled(false);
    }
  }

  private void deleteRawTracks() {
    File modeDir = new File(mBaseDir, "brouter/modes");
    String[] fileNames = modeDir.list();
    if (fileNames == null) return;
    for (String fileName : fileNames) {
      if (fileName.endsWith("_rawtrack.dat")) {
        File f = new File(modeDir, fileName);
        f.delete();
      }
    }
  }

  public void downloadAll(ArrayList<Integer> downloadList) {
    ArrayList<String> urlparts = new ArrayList<>();
    for (Integer i : downloadList) {
      urlparts.add(baseNameForTile(i));
    }

    downloadCanceled = false;

    Data inputData = new Data.Builder()
      .putStringArray(DownloadWorker.KEY_INPUT_SEGMENT_NAMES, urlparts.toArray(new String[0]))
      .build();

    Constraints constraints = new Constraints.Builder()
      .setRequiredNetworkType(NetworkType.CONNECTED)
      .build();

    WorkRequest downloadWorkRequest =
      new OneTimeWorkRequest.Builder(DownloadWorker.class)
        .setInputData(inputData)
        .setConstraints(constraints)
        .build();

    WorkManager workManager = WorkManager.getInstance(getApplicationContext());
    workManager.enqueue(downloadWorkRequest);

    workManager
      .getWorkInfoByIdLiveData(downloadWorkRequest.getId())
      .observe(this, workInfo -> {
        if (workInfo != null) {
          if (workInfo.getState() == WorkInfo.State.ENQUEUED) {
            Toast.makeText(this, "Download scheduled. Check internet connection if it doesn't start.", Toast.LENGTH_LONG).show();
            mProgressIndicator.hide();
            mProgressIndicator.setIndeterminate(true);
            mProgressIndicator.show();
          }

          if (workInfo.getState() == WorkInfo.State.RUNNING) {
            Data progress = workInfo.getProgress();
            String segmentName = progress.getString(DownloadWorker.PROGRESS_SEGMENT_NAME);
            int percent = progress.getInt(DownloadWorker.PROGRESS_SEGMENT_PERCENT, 0);
            if (percent > 0) {
              mProgressIndicator.setIndeterminate(false);
            }
            mProgressIndicator.setProgress(percent);
          }

          if (workInfo.getState().isFinished()) {
            String result;
            switch (workInfo.getState()) {
              case FAILED:
                result = "Download failed";
                break;
              case CANCELLED:
                result = "Download cancelled";
                break;
              case SUCCEEDED:
                result = "Download succeeded";
                break;
              default:
                result = "";
            }
            if (workInfo.getState() != WorkInfo.State.FAILED) {
              Toast.makeText(this, result, Toast.LENGTH_SHORT).show();
            } else {
              String error = workInfo.getOutputData().getString(DownloadWorker.KEY_OUTPUT_ERROR);
              Toast.makeText(this, result + ": " + error, Toast.LENGTH_LONG).show();
            }
            mProgressIndicator.hide();
            scanExistingFiles();
          }
        }
      });

    deleteRawTracks(); // invalidate raw-tracks after data update
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
              deleteSelectedTiles();
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

  private void scanExistingFiles() {
    mBInstallerView.clearAllTilesStatus(MASK_CURRENT_RD5 | MASK_INSTALLED_RD5 | MASK_DELETED_RD5 | MASK_SELECTED_RD5);

    scanExistingFiles(new File(mBaseDir, "brouter/segments4"));

    File secondary = RoutingHelper.getSecondarySegmentDir(new File(mBaseDir, "brouter/segments4"));
    if (secondary != null) {
      scanExistingFiles(secondary);
    }
  }

  private void scanExistingFiles(File dir) {
    String[] fileNames = dir.list();
    if (fileNames == null) return;
    String suffix = ".rd5";
    for (String fileName : fileNames) {
      if (fileName.endsWith(suffix)) {
        String basename = fileName.substring(0, fileName.length() - suffix.length());
        int tileIndex = tileForBaseName(basename);
        mBInstallerView.setTileStatus(tileIndex, MASK_INSTALLED_RD5);

        long age = System.currentTimeMillis() - new File(dir, fileName).lastModified();
        if (age < 10800000) mBInstallerView.setTileStatus(tileIndex, MASK_CURRENT_RD5); // 3 hours
      }
    }
  }

  private void deleteSelectedTiles() {
    ArrayList<Integer> selectedTiles = mBInstallerView.getSelectedTiles(MASK_DELETED_RD5);
    for (int tileIndex : selectedTiles) {
      new File(mBaseDir, "brouter/segments4/" + baseNameForTile(tileIndex) + ".rd5").delete();
    }
    scanExistingFiles();
  }

  private void downloadSelectedTiles() {
    ArrayList<Integer> selectedTiles = mBInstallerView.getSelectedTiles(MASK_SELECTED_RD5);
    downloadAll(selectedTiles);
    mBInstallerView.clearAllTilesStatus(MASK_SELECTED_RD5);
  }

  private void downloadInstalledTiles() {
    ArrayList<Integer> selectedTiles = mBInstallerView.getSelectedTiles(MASK_INSTALLED_RD5);
    downloadAll(selectedTiles);
  }

  private int tileForBaseName(String basename) {
    String uname = basename.toUpperCase(Locale.ROOT);
    int idx = uname.indexOf("_");
    if (idx < 0) return -1;
    String slon = uname.substring(0, idx);
    String slat = uname.substring(idx + 1);
    int ilon = slon.charAt(0) == 'W' ? -Integer.parseInt(slon.substring(1)) :
      (slon.charAt(0) == 'E' ? Integer.parseInt(slon.substring(1)) : -1);
    int ilat = slat.charAt(0) == 'S' ? -Integer.parseInt(slat.substring(1)) :
      (slat.charAt(0) == 'N' ? Integer.parseInt(slat.substring(1)) : -1);
    if (ilon < -180 || ilon >= 180 || ilon % 5 != 0) return -1;
    if (ilat < -90 || ilat >= 90 || ilat % 5 != 0) return -1;
    return (ilon + 180) / 5 + 72 * ((ilat + 90) / 5);
  }

  private String baseNameForTile(int tileIndex) {
    int lon = (tileIndex % 72) * 5 - 180;
    int lat = (tileIndex / 72) * 5 - 90;
    String slon = lon < 0 ? "W" + (-lon) : "E" + lon;
    String slat = lat < 0 ? "S" + (-lat) : "N" + lat;
    return slon + "_" + slat;
  }
}
