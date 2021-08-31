package btools.routingapp;

import java.io.File;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.StatFs;
import android.speech.tts.TextToSpeech.OnInitListener;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.EditText;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.os.EnvironmentCompat;

import btools.router.OsmNodeNamed;

public class BRouterMainActivity extends Activity implements OnInitListener, ActivityCompat.OnRequestPermissionsResultCallback
{

  @Override
  public void onInit( int i )
  {
  }

  public boolean checkSelfPermission (Context context, String perm ) {
    boolean b = checkSelfPermission(context, perm);
    if (b) {
      ActivityCompat.requestPermissions (this, new String[]{perm}, 0);
    }

    return b;
  }

  public String getStorageState(File f) {
    return EnvironmentCompat.getStorageState(f); //Environment.MEDIA_MOUNTED
  }

  public File[] getExternFilesDirs(String d) {
    return getExternalFilesDirs(null);
  }

  public ArrayList<File> getStorageDirectories () {
    ArrayList<File> list = null;
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
      list = new ArrayList<File>(Arrays.asList(getExternalMediaDirs()));
    } else {
      list = new ArrayList<File>(Arrays.asList(getExternFilesDirs(null)));
    }
    ArrayList<File> res = new ArrayList<File>();

    for (File f : list) {
      if (f != null) {
        if (getStorageState(f).equals(Environment.MEDIA_MOUNTED))
          res.add (f);
      }
    }

//    res.add(getContext().getFilesDir());
    return res;
  }


}


