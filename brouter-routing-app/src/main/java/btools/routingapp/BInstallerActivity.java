package btools.routingapp;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.speech.tts.TextToSpeech.OnInitListener;

public class BInstallerActivity  extends Activity implements OnInitListener {

    private BInstallerView mBInstallerView;
    private PowerManager mPowerManager;
    private WakeLock mWakeLock;
    
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

        // Start the download manager
        mBInstallerView.startInstaller();
    }

    @Override
    protected void onPause() {
        super.onPause();
        System.exit(0);
    }

    @Override
    public void onInit(int i)
    {
    }

    

}
