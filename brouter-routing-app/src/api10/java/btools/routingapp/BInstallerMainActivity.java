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
import android.os.Bundle;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.StatFs;

import java.util.HashSet;
import java.util.Set;

public class BInstallerMainActivity extends Activity  {


  static public long getAvailableSpace (String baseDir) {
    StatFs stat = new StatFs(baseDir);
    return (long)stat.getAvailableBlocks()*stat.getBlockSize();
  }
}
