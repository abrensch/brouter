package btools.routingapp;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.os.EnvironmentCompat;

import java.io.File;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import btools.router.OsmNodeNamed;

public class BRouterActivity extends AppCompatActivity implements ActivityCompat.OnRequestPermissionsResultCallback {

  private static final int DIALOG_SELECTPROFILE_ID = 1;
  private static final int DIALOG_EXCEPTION_ID = 2;
  private static final int DIALOG_SHOW_DM_INFO_ID = 3;
  private static final int DIALOG_TEXTENTRY_ID = 4;
  private static final int DIALOG_VIASELECT_ID = 5;
  private static final int DIALOG_NOGOSELECT_ID = 6;
  private static final int DIALOG_SHOWRESULT_ID = 7;
  private static final int DIALOG_ROUTINGMODES_ID = 8;
  private static final int DIALOG_MODECONFIGOVERVIEW_ID = 9;
  private static final int DIALOG_PICKWAYPOINT_ID = 10;
  private static final int DIALOG_SELECTBASEDIR_ID = 11;
  private static final int DIALOG_MAINACTION_ID = 12;
  private static final int DIALOG_OLDDATAHINT_ID = 13;
  private static final int DIALOG_SHOW_REPEAT_TIMEOUT_HELP_ID = 16;
  private final Set<Integer> dialogIds = new HashSet<>();
  private BRouterView mBRouterView;
  private String[] availableProfiles;
  private String selectedProfile = null;
  private List<File> availableBasedirs;
  private String[] basedirOptions;
  private int selectedBasedir;
  private String[] availableWaypoints;
  private String[] routingModes;
  private boolean[] routingModesChecked;
  private String message = null;
  private String[] availableVias;
  private Set<String> selectedVias;
  private List<OsmNodeNamed> nogoList;
  private String errorMessage;
  private String title;
  private int wpCount;

  /**
   * Called when the activity is first created.
   */
  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
    int memoryClass = am.getMemoryClass();

    // instantiate our simulation view and set it as the activity's content
    mBRouterView = new BRouterView(this, memoryClass);
    mBRouterView.init();
    setContentView(mBRouterView);
  }

  @Override
  protected Dialog onCreateDialog(int id) {
    AlertDialog.Builder builder;
    builder = new AlertDialog.Builder(this);
    builder.setCancelable(false);

    switch (id) {
      case DIALOG_SELECTPROFILE_ID:
        builder.setTitle("Select a routing profile");
        builder.setItems(availableProfiles, new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int item) {
            selectedProfile = availableProfiles[item];
            mBRouterView.startProcessing(selectedProfile);
          }
        });
        return builder.create();
      case DIALOG_MAINACTION_ID:
        builder.setTitle("Select Main Action");
        builder.setItems(
            new String[] { "Download Manager", "BRouter App" },
            new DialogInterface.OnClickListener() {
              public void onClick(DialogInterface dialog, int item) {
                if (item == 0)
                  startDownloadManager();
                else
                  showDialog(DIALOG_SELECTPROFILE_ID);
              }
            })
            .setNegativeButton("Close", new DialogInterface.OnClickListener() {
              public void onClick(DialogInterface dialog, int id) {
                finish();
              }
            });
        return builder.create();
      case DIALOG_SHOW_DM_INFO_ID:
        builder
            .setTitle("BRouter Download Manager")
            .setMessage(
                "*** Attention: ***\n\n" + "The Download Manager is used to download routing-data "
                    + "files which can be up to 170MB each. Do not start the Download Manager "
                    + "on a cellular data connection without a data plan! "
                    + "Download speed is restricted to 16 MBit/s.")
            .setPositiveButton("I know", new DialogInterface.OnClickListener() {
              public void onClick(DialogInterface dialog, int id) {
                Intent intent = new Intent(BRouterActivity.this, BInstallerActivity.class);
                startActivity(intent);
                showNewDialog(DIALOG_MAINACTION_ID);
              }
            }).setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
              public void onClick(DialogInterface dialog, int id) {
                finish();
              }
            });
        return builder.create();
      case DIALOG_SHOW_REPEAT_TIMEOUT_HELP_ID:
        builder
            .setTitle("Successfully prepared a timeout-free calculation")
            .setMessage(
                "You successfully repeated a calculation that previously run into a timeout "
                    + "when started from your map-tool. If you repeat the same request from your "
                    + "maptool, with the exact same destination point and a close-by starting point, "
                    + "this request is guaranteed not to time out.")
            .setNegativeButton("Exit", new DialogInterface.OnClickListener() {
              public void onClick(DialogInterface dialog, int id) {
                finish();
              }
            });
        return builder.create();
      case DIALOG_OLDDATAHINT_ID:
        builder
            .setTitle("Local setup needs reset")
            .setMessage(
                "You are currently using an old version of the lookup-table "
                    + "together with routing data made for this old table. "
                    + "Before downloading new datafiles made for the new table, "
                    + "you have to reset your local setup by 'moving away' (or deleting) "
                    + "your <basedir>/brouter directory and start a new setup by calling the " + "BRouter App again.")
            .setPositiveButton("OK", new DialogInterface.OnClickListener() {
              public void onClick(DialogInterface dialog, int id) {
                finish();
              }
            });
        return builder.create();
      case DIALOG_ROUTINGMODES_ID:
        builder.setTitle(message);
        builder.setMultiChoiceItems(routingModes, routingModesChecked,
            new DialogInterface.OnMultiChoiceClickListener() {
              @Override
              public void onClick(DialogInterface dialog, int which, boolean isChecked) {
                routingModesChecked[which] = isChecked;
              }
            });
        builder.setPositiveButton("Ok", new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int whichButton) {
            mBRouterView.configureService(routingModes, routingModesChecked);
          }
        });
        return builder.create();
      case DIALOG_EXCEPTION_ID:
        builder
            .setTitle("An Error occured")
            .setMessage(errorMessage)
            .setPositiveButton("OK",
                new DialogInterface.OnClickListener() {
                  public void onClick(DialogInterface dialog, int id) {
                    mBRouterView.continueProcessing();
                  }
                });
        return builder.create();
      case DIALOG_TEXTENTRY_ID:
        builder.setTitle("Enter SDCARD base dir:");
        builder.setMessage(message);
        final EditText input = new EditText(this);
        // input.setText(defaultbasedir);
        builder.setView(input);
        builder.setPositiveButton("Ok", new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int whichButton) {
            String basedir = input.getText().toString();
            mBRouterView.startSetup(new File(basedir), true);
          }
        });
        return builder.create();
      case DIALOG_SELECTBASEDIR_ID:
        builder.setTitle("Choose brouter data base dir:");
        // builder.setMessage( message );
        builder.setSingleChoiceItems(basedirOptions, 0, new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface dialog, int item) {
            selectedBasedir = item;
          }
        });
        builder.setPositiveButton("Ok", new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int whichButton) {
            if (selectedBasedir < availableBasedirs.size()) {
              mBRouterView.startSetup(availableBasedirs.get(selectedBasedir), true);
            } else {
              showDialog(DIALOG_TEXTENTRY_ID);
            }
          }
        });
        return builder.create();
      case DIALOG_VIASELECT_ID:
        builder.setTitle("Check VIA Selection:");
        builder.setMultiChoiceItems(availableVias, getCheckedBooleanArray(availableVias.length),
            new DialogInterface.OnMultiChoiceClickListener() {
              @Override
              public void onClick(DialogInterface dialog, int which, boolean isChecked) {
                if (isChecked) {
                  selectedVias.add(availableVias[which]);
                } else {
                  selectedVias.remove(availableVias[which]);
                }
              }
            });
        builder.setPositiveButton("Ok", new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int whichButton) {
            mBRouterView.updateViaList(selectedVias);
            mBRouterView.startProcessing(selectedProfile);
          }
        });
        return builder.create();
      case DIALOG_NOGOSELECT_ID:
        builder.setTitle("Check NoGo Selection:");
        String[] nogoNames = new String[nogoList.size()];
        for (int i = 0; i < nogoList.size(); i++)
          nogoNames[i] = nogoList.get(i).name;
        final boolean[] nogoEnabled = getCheckedBooleanArray(nogoList.size());
        builder.setMultiChoiceItems(nogoNames, getCheckedBooleanArray(nogoNames.length),
            new DialogInterface.OnMultiChoiceClickListener() {
              @Override
              public void onClick(DialogInterface dialog, int which, boolean isChecked) {
                nogoEnabled[which] = isChecked;
              }
            });
        builder.setPositiveButton("Ok", new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int whichButton) {
            mBRouterView.updateNogoList(nogoEnabled);
            mBRouterView.startProcessing(selectedProfile);
          }
        });
        return builder.create();
      case DIALOG_SHOWRESULT_ID:
        // -3: Repeated route calculation
        // -2: Unused?
        // -1: Route calculated
        // other: Select waypoints for route calculation
        builder.setTitle(title).setMessage(errorMessage);

        // Neutral button
        if (wpCount == 0) {
          builder.setNeutralButton("Server-Mode", (dialog, which) -> {
            mBRouterView.startConfigureService();
          });
        } else if (wpCount == -3) {
          builder.setNeutralButton("Info", (dialog, which) -> {
            showRepeatTimeoutHelp();
          });
        } else if (wpCount >= 2) {
          builder.setNeutralButton("Calc Route", (dialog, which) -> {
            mBRouterView.finishWaypointSelection();
            mBRouterView.startProcessing(selectedProfile);
          });
        }

        // Positive button
        if (wpCount == -3 || wpCount == -1) {
          builder.setPositiveButton("Share GPX", (dialog, which) -> {
            mBRouterView.shareTrack();
            finish();
          });
        } else if (wpCount >= 0) {
          String selectLabel = wpCount == 0 ? "Select from" : "Select to/via";
          builder.setPositiveButton(selectLabel, (dialog, which) -> {
            mBRouterView.pickWaypoints();
          });
        }

        // Negative button
        builder.setNegativeButton("Exit", (dialog, which) -> {
          finish();
        });

        return builder.create();
      case DIALOG_MODECONFIGOVERVIEW_ID:
        builder
            .setTitle("Success")
            .setMessage(message)
            .setPositiveButton("Exit",
                new DialogInterface.OnClickListener() {
                  public void onClick(DialogInterface dialog, int id) {
                    finish();
                  }
                });
        return builder.create();
      case DIALOG_PICKWAYPOINT_ID:
        builder.setTitle(wpCount > 0 ? "Select to/via" : "Select from");
        builder.setItems(availableWaypoints, new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int item) {
            mBRouterView.updateWaypointList(availableWaypoints[item]);
            mBRouterView.startProcessing(selectedProfile);
          }
        });
        return builder.create();

      default:
        return null;
    }
  }

  private boolean[] getCheckedBooleanArray(int size) {
    boolean[] checked = new boolean[size];
    Arrays.fill(checked, true);
    return checked;
  }

  public void selectProfile(String[] items) {
    availableProfiles = items;
    Arrays.sort(availableProfiles);

    // show main dialog
    showDialog(DIALOG_MAINACTION_ID);
  }

  public void startDownloadManager() {
    showDialog(DIALOG_SHOW_DM_INFO_ID);
  }

  public void selectBasedir(ArrayList<File> items, String message) {
    this.message = message;
    availableBasedirs = items;
    ArrayList<Long> dirFreeSizes = new ArrayList<>();
    for (File f : items) {
      long size = 0L;
      try {
        StatFs stat = new StatFs(f.getAbsolutePath());
        size = (long) stat.getAvailableBlocks() * stat.getBlockSize();
      } catch (Exception e) {
        /* ignore */ }
      dirFreeSizes.add(size);
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      basedirOptions = new String[items.size()];
    } else {
      basedirOptions = new String[items.size() + 1];
    }

    int bdidx = 0;
    DecimalFormat df = new DecimalFormat("###0.00");
    for (int idx = 0; idx < availableBasedirs.size(); idx++) {
      basedirOptions[bdidx++] = availableBasedirs.get(idx) + " ("
          + df.format(dirFreeSizes.get(idx) / 1024. / 1024. / 1024.) + " GB free)";
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
      basedirOptions[bdidx] = "Enter path manually";
    }

    showDialog(DIALOG_SELECTBASEDIR_ID);
  }

  public void selectRoutingModes(String[] modes, boolean[] modesChecked, String message) {
    routingModes = modes;
    routingModesChecked = modesChecked;
    this.message = message;
    showDialog(DIALOG_ROUTINGMODES_ID);
  }

  public void showModeConfigOverview(String message) {
    this.message = message;
    showDialog(DIALOG_MODECONFIGOVERVIEW_ID);
  }

  public void selectVias(String[] items) {
    availableVias = items;
    selectedVias = new HashSet<>(availableVias.length);
    Collections.addAll(selectedVias, items);
    showDialog(DIALOG_VIASELECT_ID);
  }

  public void selectWaypoint(String[] items) {
    availableWaypoints = items;
    showNewDialog(DIALOG_PICKWAYPOINT_ID);
  }

  public void showRepeatTimeoutHelp() {
    showNewDialog(DIALOG_SHOW_REPEAT_TIMEOUT_HELP_ID);
  }

  public void selectNogos(List<OsmNodeNamed> nogoList) {
    this.nogoList = nogoList;
    showDialog(DIALOG_NOGOSELECT_ID);
  }

  private void showNewDialog(int id) {
    if (dialogIds.contains(id)) {
      removeDialog(id);
    }
    dialogIds.add(id);
    showDialog(id);
  }

  public void showErrorMessage(String msg) {
    errorMessage = msg;
    showNewDialog(DIALOG_EXCEPTION_ID);
  }

  public void showResultMessage(String title, String msg, int wpCount) {
    errorMessage = msg;
    this.title = title;
    this.wpCount = wpCount;
    showNewDialog(DIALOG_SHOWRESULT_ID);
  }

  @Override
  protected void onPause() {
    super.onPause();

    // When the activity is paused, we make sure to stop the router
    mBRouterView.stopRouting();
  }

  public ArrayList<File> getStorageDirectories() {
    ArrayList<File> list = null;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      list = new ArrayList<>(Arrays.asList(getExternalMediaDirs()));
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
      list = new ArrayList<>(Arrays.asList(getExternalFilesDirs(null)));
    }
    ArrayList<File> res = new ArrayList<>();

    if (list != null) {
      for (File f : list) {
        if (f != null) {
          if (EnvironmentCompat.getStorageState(f).equals(Environment.MEDIA_MOUNTED))
            res.add(f);
        }
      }
    }

    if (checkExternalStorageWritable()) {
      res.add(Environment.getExternalStorageDirectory());
    }

    return res;
  }

  private boolean checkExternalStorageWritable() {
    boolean isWritable = false;
    try {
      File sd = Environment.getExternalStorageDirectory();
      File testDir = new File(sd, "brouter");
      boolean didExist = testDir.isDirectory();
      if (!didExist) {
        testDir.mkdir();
      }
      File testFile = new File(testDir, "test" + System.currentTimeMillis());
      testFile.createNewFile();
      if (testFile.exists()) {
        testFile.delete();
        isWritable = true;
      }
    } catch (Throwable t) {
      // ignore
    }
    return isWritable;
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (requestCode == 0) {
      if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
        mBRouterView.startSetup(null, true);
      } else {
        mBRouterView.init();
      }
    }
  }
}
