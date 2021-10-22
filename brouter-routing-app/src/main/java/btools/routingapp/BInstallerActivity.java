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
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.speech.tts.TextToSpeech.OnInitListener;
import android.os.StatFs;
import android.util.Log;

public class BInstallerActivity extends Activity {

    public static final String DOWNLOAD_ACTION = "btools.routingapp.download";

    private static final int DIALOG_CONFIRM_DELETE_ID = 1;

    private BInstallerView mBInstallerView;
    private PowerManager mPowerManager;
    private WakeLock mWakeLock;
    private DownloadReceiver myReceiver;


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

    
    /** Called when the activity is first created. */
    @Override
    @SuppressWarnings("deprecation")
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Get an instance of the PowerManager
        mPowerManager = (PowerManager) getSystemService(POWER_SERVICE);

        // Create a bright wake lock
        mWakeLock = mPowerManager.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, getClass()
                .getName());

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        
        // instantiate our simulation view and set it as the activity's content
        mBInstallerView = new BInstallerView(this);
        setContentView(mBInstallerView);
    }

    @Override
    protected void onResume() {
        super.onResume();
        /*
         * when the activity is resumed, we acquire a wake-lock so that the
         * screen stays on, since the user will likely not be fiddling with the
         * screen or buttons.
         */
        mWakeLock.acquire();

        IntentFilter filter = new IntentFilter();
        filter.addAction(DOWNLOAD_ACTION);

        myReceiver = new DownloadReceiver();
        registerReceiver(myReceiver, filter);

        // Start the download manager
        mBInstallerView.startInstaller();
    }

    @Override
    protected void onPause() {
        super.onPause();


        super.onPause();

        mWakeLock.release();

    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (myReceiver != null) unregisterReceiver(myReceiver);
        System.exit(0);
    }

  @Override
  @SuppressWarnings("deprecation")
  protected Dialog onCreateDialog( int id )
  {
    AlertDialog.Builder builder;
    switch ( id )
    {
    case DIALOG_CONFIRM_DELETE_ID:
      builder = new AlertDialog.Builder( this );
      builder
          .setTitle( "Confirm Delete" )
          .setMessage( "Really delete?" ).setPositiveButton( "Yes", new DialogInterface.OnClickListener()
          {
            public void onClick( DialogInterface dialog, int id )
            {
              mBInstallerView.deleteSelectedTiles();
            }
          } ).setNegativeButton( "No", new DialogInterface.OnClickListener()
          {
            public void onClick( DialogInterface dialog, int id )
            {
            }
          } );
      return builder.create();

    default:
      return null;
    }
  }
    
  @SuppressWarnings("deprecation")
  public void showConfirmDelete()
  {
    showDialog( DIALOG_CONFIRM_DELETE_ID );
  }

  private Set<Integer> dialogIds = new HashSet<Integer>();

  private void showNewDialog( int id )
  {
    if ( dialogIds.contains( Integer.valueOf( id ) ) )
    {
      removeDialog( id );
    }
    dialogIds.add( Integer.valueOf( id ) );
    showDialog( id );
  }


  static public long getAvailableSpace (String baseDir) {
    StatFs stat = new StatFs(baseDir);

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
      return stat.getAvailableBlocksLong()*stat.getBlockSizeLong();
    }
    else {
      return stat.getAvailableBlocks()*stat.getBlockSize();
    }
  }
}
