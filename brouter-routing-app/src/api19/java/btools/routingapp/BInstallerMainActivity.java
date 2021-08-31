package btools.routingapp;

import java.util.HashSet;
import java.util.Set;

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
import android.speech.tts.TextToSpeech.OnInitListener;
import android.os.StatFs;
import android.util.Log;

public class BInstallerMainActivity  extends Activity implements OnInitListener {


    @Override
    public void onInit(int i)
    {
    }


  static public long getAvailableSpace (String baseDir) {
    StatFs stat = new StatFs(baseDir);

    return (long)stat.getAvailableBlocksLong()*stat.getBlockSizeLong();
  }

}
