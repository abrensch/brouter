package btools.routingapp;

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
import android.widget.EditText;

import java.io.File;
import java.lang.reflect.Method;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import btools.router.OsmNodeNamed;

public class BRouterMainActivity extends Activity
{

  public boolean checkSelfPermission (Context context, String perm ) {
    return true;
  }

  public String getStorageState(File f ) {
    return Environment.getExternalStorageState();
  }

  public ArrayList<File> getStorageDirectories() {
    List<String> list = getFilesDirs();
    ArrayList<File> flist = new ArrayList<>();
    for (String s: list) {
      File f = new File(s);
      flist.add(f);
    }
    return flist;
  }

  private List<String> getFilesDirs()
  {
    ArrayList<String> res = new ArrayList<String>();

    // check write access on internal sd
    try
    {
      File sd = Environment.getExternalStorageDirectory();
      File testDir = new File( sd, "brouter" );
      boolean didExist = testDir.isDirectory();
      if ( !didExist )
      {
        testDir.mkdir();
      }
      File testFile = new File( testDir, "test" + System.currentTimeMillis() );
      testFile.createNewFile();
      if ( testFile.exists() )
      {
        testFile.delete();
        res.add( sd.getPath() );
      }
      if ( !didExist )
      {
        testDir.delete();
      }
    }
    catch( Throwable t )
    {
      // ignore
    }

    /*
    // not on api 10
    try
    {
      Method method = Context.class.getDeclaredMethod("getExternalFilesDirs", new Class[]{ String.class } );
      File[] paths = (File[])method.invoke( this, new Object[1] );
      for( File path : paths )
      {
        res.add( path.getPath() );
      }
    }
    catch( Exception e )
    {
      res.add( e.toString() );
      res.add( Environment.getExternalStorageDirectory().getPath() );
    }
    */

    return res;
  }

}
