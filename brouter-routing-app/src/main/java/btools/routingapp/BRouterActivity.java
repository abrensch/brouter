package btools.routingapp;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.os.EnvironmentCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
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
  //private static final int DIALOG_OLDDATAHINT_ID = 13;
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
  private boolean startSilent;

  ActivityResultLauncher<Intent> someActivityResultLauncher;

  /**
   * Called when the activity is first created.
   */
  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    someActivityResultLauncher = registerForActivityResult(
      new ActivityResultContracts.StartActivityForResult(),
      new ActivityResultCallback<ActivityResult>() {
        @Override
        public void onActivityResult(ActivityResult result) {
          if (result.getResultCode() == Activity.RESULT_OK) {
            // There are no request codes
            Intent data = result.getData();
            String profile = null;
            String profile_hash = null;
            String sparams = null;
            if (data != null && data.hasExtra("PARAMS_VALUES")) {
              sparams = data.getExtras().getString("PARAMS_VALUES", "");
            }
            if (data != null && data.hasExtra("PROFILE")) {
              profile = data.getExtras().getString("PROFILE", "");
            }
            if (data != null && data.hasExtra("PROFILE_HASH")) {
              profile_hash = data.getExtras().getString("PROFILE_HASH", "");
            }
            mBRouterView.configureServiceParams(profile, sparams);
          }

        }
      });

    ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
    int memoryClass = am.getMemoryClass();

    Intent i = getIntent();
    if (i.hasExtra("runsilent")) startSilent = true;
    // startQuiete = true;
    // instantiate our simulation view and set it as the activity's content
    mBRouterView = new BRouterView(this, memoryClass);
    mBRouterView.init(startSilent);
    setContentView(mBRouterView);
  }

  protected Dialog createADialog(int id) {
    AlertDialog.Builder builder;
    builder = new AlertDialog.Builder(this);
    builder.setCancelable(false);

    switch (id) {
      case DIALOG_SELECTPROFILE_ID:
        builder.setTitle(R.string.action_select_profile);
        builder.setItems(availableProfiles, new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int item) {
            selectedProfile = availableProfiles[item];
            mBRouterView.startProcessing(selectedProfile);
          }
        });
        return builder.create();
      case DIALOG_MAINACTION_ID:
        builder.setTitle(R.string.main_action);
        builder.setItems(
            new String[]{getString(R.string.main_action_1), getString(R.string.main_action_2)},
            new DialogInterface.OnClickListener() {
              public void onClick(DialogInterface dialog, int item) {
                if (item == 0)
                  startDownloadManager();
                else
                  showADialog(DIALOG_SELECTPROFILE_ID);
              }
            })
          .setNegativeButton(getString(R.string.close), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
              finish();
            }
          });
        return builder.create();
      case DIALOG_SHOW_DM_INFO_ID:
        builder
          .setTitle(R.string.title_download)
          .setMessage(R.string.summary_download)
          .setPositiveButton(R.string.i_know, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
              Intent intent = new Intent(BRouterActivity.this, BInstallerActivity.class);
              startActivity(intent);
              showNewDialog(DIALOG_MAINACTION_ID);
            }
          }).setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
              finish();
            }
          });
        return builder.create();
      case DIALOG_SHOW_REPEAT_TIMEOUT_HELP_ID:
        builder
          .setTitle(R.string.title_timeoutfree)
          .setMessage(R.string.summary_timeoutfree)
          .setNegativeButton(R.string.exit, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
              finish();
            }
          });
        return builder.create();
        /*
      case DIALOG_OLDDATAHINT_ID:
        builder
          .setTitle("Local setup needs reset")
          .setMessage(
            "You are currently using an old version of the lookup-table "
              + "together with routing data made for this old table. "
              + "Before downloading new datafiles made for the new table, "
              + "you have to reset your local setup by 'moving away' (or deleting) "
              + "your <basedir>/brouter directory and start a new setup by calling the " + "BRouter App again.")
          .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
              finish();
            }
          });
        return builder.create();
         */
      case DIALOG_ROUTINGMODES_ID:
        builder.setTitle(message);
        builder.setMultiChoiceItems(routingModes, routingModesChecked,
          new DialogInterface.OnMultiChoiceClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which, boolean isChecked) {
              routingModesChecked[which] = isChecked;
            }
          });
        builder.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int whichButton) {
            mBRouterView.configureService(routingModes, routingModesChecked);
          }
        });
        return builder.create();
      case DIALOG_EXCEPTION_ID:
        builder
          .setTitle(R.string.error)
          .setMessage(errorMessage)
          .setPositiveButton(R.string.ok,
            new DialogInterface.OnClickListener() {
              public void onClick(DialogInterface dialog, int id) {
                mBRouterView.continueProcessing();
              }
            });
        return builder.create();
      case DIALOG_TEXTENTRY_ID:
        builder.setTitle(R.string.title_sdcard);
        builder.setMessage(message);
        final EditText input = new EditText(this);
        // input.setText(defaultbasedir);
        builder.setView(input);
        builder.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int whichButton) {
            String basedir = input.getText().toString();
            mBRouterView.startSetup(new File(basedir), true, false);
          }
        });
        return builder.create();
      case DIALOG_SELECTBASEDIR_ID:
        builder.setTitle(getString(R.string.action_choose_folder));
        // builder.setMessage( message );
        builder.setSingleChoiceItems(basedirOptions, 0, new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface dialog, int item) {
            selectedBasedir = item;
          }
        });
        builder.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int whichButton) {
            if (selectedBasedir < availableBasedirs.size()) {
              mBRouterView.startSetup(availableBasedirs.get(selectedBasedir), true, false);
            } else {
              showADialog(DIALOG_TEXTENTRY_ID);
            }
          }
        });
        return builder.create();
      case DIALOG_VIASELECT_ID:
        builder.setTitle(R.string.action_via_select);
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
        builder.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int whichButton) {
            mBRouterView.updateViaList(selectedVias);
            mBRouterView.startProcessing(selectedProfile);
          }
        });
        return builder.create();
      case DIALOG_NOGOSELECT_ID:
        builder.setTitle(R.string.action_nogo_select);
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
        builder.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
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

        builder.setTitle(title);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
          LayoutInflater inflater = (LayoutInflater) this.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
          View v = inflater.inflate(R.layout.dialog_message, null);
          builder.setView(v);
          TextView tv = v.findViewById(R.id.message);
          tv.setText(errorMessage);
        } else {
          // builder.setMessage(errorMessage);
        }
        List<String> slist = new ArrayList<>();
        // Neutral button
        if (wpCount == 0) {
          slist.add(getString(R.string.action_servermode));
        } else if (wpCount == -3) {
          slist.add(getString(R.string.action_info));
        } else if (wpCount >= 2) {
          slist.add(getString(R.string.action_calc_route));
        }

        if (wpCount == 0) {
          slist.add(getString(R.string.action_profile_settings));
        }
        // Positive button
        if (wpCount == -3 || wpCount == -1) {
          slist.add(getString(R.string.action_share));
        } else if (wpCount >= 0) {
          String selectLabel = wpCount == 0 ? getString(R.string.action_select_from) : getString(R.string.action_select_to);
          slist.add(selectLabel);
        }

        String[] sArr = new String[slist.size()];
        sArr = slist.toArray(sArr);
        builder.setItems(
          sArr,
          new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int item) {
              if (slist.size() > 1 && item == 0) {
                if (wpCount == 0) {
                  mBRouterView.startConfigureService();
                } else if (wpCount == -3) {
                  showRepeatTimeoutHelp();
                } else if (wpCount >= 2) {
                  mBRouterView.finishWaypointSelection();
                  mBRouterView.startProcessing(selectedProfile);
                }
              } else {
                if (slist.size() == 3 && item == 1) {
                  showProfileSettings(selectedProfile);
                  // finish();
                } else {
                  if (wpCount == -3 || wpCount == -1) {
                    mBRouterView.shareTrack();
                    finish();
                  } else if (wpCount >= 0) {
                    mBRouterView.pickWaypoints();
                  }
                }
              }
            }

          });

          /*
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
        */

        // Negative button
        builder.setNegativeButton(R.string.exit, (dialog, which) -> {
          finish();
        });

        return builder.create();
      case DIALOG_MODECONFIGOVERVIEW_ID:
        builder
          .setTitle(R.string.success)
          .setMessage(message)
          .setPositiveButton(R.string.exit,
            new DialogInterface.OnClickListener() {
              public void onClick(DialogInterface dialog, int id) {
                finish();
              }
            });
        return builder.create();
      case DIALOG_PICKWAYPOINT_ID:
        builder.setTitle(wpCount == 0 ? getString(R.string.action_select_from) : getString(R.string.action_select_to));
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

  private void showProfileSettings(String selectedProfile) {
    List<RoutingParam> listParams = new ArrayList<>();
    File baseDir = ConfigHelper.getBaseDir(getBaseContext());
    File profile = new File(baseDir, "brouter/profiles2/" + selectedProfile + ".brf");
    if (profile.exists()) {
      InputStream fis = null;
      try {
        fis = new FileInputStream(profile);
        listParams = RoutingParameterDialog.getParamsFromProfile(fis);
      } catch (FileNotFoundException e) {
        throw new RuntimeException(e);
      } catch (IOException e) {
        throw new RuntimeException(e);
      } finally {
        if (fis != null) {
          try {
            fis.close();
          } catch (IOException e) {
          }
        }
      }
    }

    String sparams = mBRouterView.getConfigureServiceParams(selectedProfile);
    if (sparams != null) {
      if (listParams.size() > 0) {
        Intent i = new Intent(BRouterActivity.this, RoutingParameterDialog.class);
        i.putExtra("PROFILE", selectedProfile);
        i.putExtra("PROFILE_HASH", String.format("B%X", profile.getAbsolutePath().hashCode()));
        i.putExtra("PARAMS", (Serializable) listParams);
        i.putExtra("PARAMS_VALUES", sparams);
        //startActivityForResult(i, 100);
        someActivityResultLauncher.launch(i);
      } else {
        Toast.makeText(this, R.string.msg_no_profile, Toast.LENGTH_LONG).show();
        finish();
      }
    } else {
      Toast.makeText(this, selectedProfile + getString(R.string.msg_no_used_profile), Toast.LENGTH_LONG).show();
      finish();
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
    showADialog(DIALOG_MAINACTION_ID);
  }

  public void startDownloadManager() {
    showADialog(DIALOG_SHOW_DM_INFO_ID);
  }

  @SuppressWarnings("deprecation")
  public void selectBasedir(ArrayList<File> items, String message) {
    this.message = message;
    availableBasedirs = items;
    ArrayList<Long> dirFreeSizes = new ArrayList<>();
    for (File f : items) {
      long size = 0L;
      try {
        StatFs stat = new StatFs(f.getAbsolutePath());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
          size = stat.getAvailableBlocksLong() * stat.getBlockSizeLong();
        } else {
          size = (long) stat.getAvailableBlocks() * stat.getBlockSize();
        }
      } catch (Exception e) {
        /* ignore */
      }
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

    if (startSilent) {
      mBRouterView.startSetup(availableBasedirs.get(0), true, startSilent);
      Intent intent = new Intent(BRouterActivity.this, BInstallerActivity.class);
      startActivity(intent);
      finish();
    } else {
      showADialog(DIALOG_SELECTBASEDIR_ID);
    }
  }

  public void selectRoutingModes(String[] modes, boolean[] modesChecked, String message) {
    routingModes = modes;
    routingModesChecked = modesChecked;
    this.message = message;
    showADialog(DIALOG_ROUTINGMODES_ID);
  }

  public void showModeConfigOverview(String message) {
    this.message = message;
    showADialog(DIALOG_MODECONFIGOVERVIEW_ID);
  }

  public void selectVias(String[] items) {
    availableVias = items;
    selectedVias = new HashSet<>(availableVias.length);
    Collections.addAll(selectedVias, items);
    showADialog(DIALOG_VIASELECT_ID);
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
    showADialog(DIALOG_NOGOSELECT_ID);
  }

  private void showADialog(int id) {
    Dialog d = createADialog(id);
    if (d != null) d.show();
  }

  private void showNewDialog(int id) {
    if (dialogIds.contains(id)) {
      // removeDialog(id);
    }
    dialogIds.add(id);
    showADialog(id);
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
        mBRouterView.startSetup(null, true, false);
      } else {
        mBRouterView.init(false);
      }
    }
  }

  private void onItemClick(AdapterView<?> adapterView, View view, int which, long l) {
    if (which == 0) {
      if (wpCount == 0) {
        mBRouterView.startConfigureService();
      } else if (wpCount == -3) {
        showRepeatTimeoutHelp();
      } else if (wpCount >= 2) {
        mBRouterView.finishWaypointSelection();
        mBRouterView.startProcessing(selectedProfile);
      }
    } else {
      if (wpCount == -3 || wpCount == -1) {
        mBRouterView.shareTrack();
        finish();
      } else if (wpCount >= 0) {
        mBRouterView.pickWaypoints();
      }
    }
  }
}
