package btools.routingapp;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.speech.tts.TextToSpeech.OnInitListener;
import android.view.Display;
import android.view.WindowManager;
import android.widget.EditText;
import btools.router.OsmNodeNamed;

public class BRouterActivity  extends Activity implements OnInitListener {

    private static final int DIALOG_SELECTPROFILE_ID = 1;
    private static final int DIALOG_EXCEPTION_ID = 2;
    private static final int DIALOG_WARNEXPIRY_ID = 3;
    private static final int DIALOG_TEXTENTRY_ID = 4;
    private static final int DIALOG_VIASELECT_ID = 5;
    private static final int DIALOG_NOGOSELECT_ID = 6;
    private static final int DIALOG_SHOWRESULT_ID = 7;
    private static final int DIALOG_ROUTINGMODES_ID = 8;
    private static final int DIALOG_MODECONFIGOVERVIEW_ID = 9;
    private static final int DIALOG_PICKWAYPOINT_ID = 10;

    private BRouterView mBRouterView;
    private PowerManager mPowerManager;
    private WindowManager mWindowManager;
    private Display mDisplay;
    private WakeLock mWakeLock;

    /** Called when the activity is first created. */
    @Override
    @SuppressWarnings("deprecation")
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Get an instance of the PowerManager
        mPowerManager = (PowerManager) getSystemService(POWER_SERVICE);

        // Get an instance of the WindowManager
        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        mDisplay = mWindowManager.getDefaultDisplay();

        // Create a bright wake lock
        mWakeLock = mPowerManager.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, getClass()
                .getName());

        // instantiate our simulation view and set it as the activity's content
        mBRouterView = new BRouterView(this);
        setContentView(mBRouterView);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected Dialog onCreateDialog(int id)
    {
      AlertDialog.Builder builder;
      switch(id)
      {
        case DIALOG_SELECTPROFILE_ID:
          builder = new AlertDialog.Builder(this);
          builder.setTitle("Select a routing profile");
          builder.setItems(availableProfiles, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int item) {
                selectedProfile = availableProfiles[item];
                mBRouterView.startProcessing(selectedProfile);
            }
          });
          return builder.create();
        case DIALOG_ROUTINGMODES_ID:
          builder = new AlertDialog.Builder(this);
          builder.setTitle( message );
          builder.setMultiChoiceItems(routingModes, routingModesChecked, new DialogInterface.OnMultiChoiceClickListener() {
               @Override
               public void onClick(DialogInterface dialog, int which,
                       boolean isChecked) {
            	   routingModesChecked[which] = isChecked;
               }
            });
            builder.setPositiveButton("Ok", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int whichButton) {
                     mBRouterView.configureService(routingModes,routingModesChecked);
                   }
                 });
            return builder.create();
        case DIALOG_EXCEPTION_ID:
          builder = new AlertDialog.Builder(this);
          builder.setTitle( "An Error occured" )
                 .setMessage( errorMessage )
                 .setPositiveButton( "OK", new DialogInterface.OnClickListener() {
                     public void onClick(DialogInterface dialog, int id) {
                         mBRouterView.continueProcessing();
                     }
                 });
          return builder.create();
        case DIALOG_WARNEXPIRY_ID:
            builder = new AlertDialog.Builder(this);
            builder.setMessage( errorMessage )
                   .setPositiveButton( "OK", new DialogInterface.OnClickListener() {
                       public void onClick(DialogInterface dialog, int id) {
                         mBRouterView.startProcessing(selectedProfile);
                       }
                   });
            return builder.create();
        case DIALOG_TEXTENTRY_ID:
            builder = new AlertDialog.Builder(this);
            builder.setTitle("Enter SDCARD base dir:");
            builder.setMessage(message);
            final EditText input = new EditText(this);
            input.setText( defaultbasedir );
            builder.setView(input);
            builder.setPositiveButton("Ok", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int whichButton) {
                     String basedir = input.getText().toString();
                     mBRouterView.startSetup(basedir, true );
                   }
                 });
            return builder.create();
        case DIALOG_VIASELECT_ID:
            builder = new AlertDialog.Builder(this);
            builder.setTitle("Check VIA Selection:");
            builder.setMultiChoiceItems(availableVias, getCheckedBooleanArray( availableVias.length ),
                      new DialogInterface.OnMultiChoiceClickListener() {
               @Override
               public void onClick(DialogInterface dialog, int which,
                       boolean isChecked) {
                   if (isChecked)
                   {
                       selectedVias.add(availableVias[which]);
                   }
                   else
                   {
                       selectedVias.remove(availableVias[which]);
                   }
               }
            });
            builder.setPositiveButton("Ok", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int whichButton) {
                     mBRouterView.updateViaList( selectedVias );
                     mBRouterView.startProcessing(selectedProfile);
                   }
                 });
            return builder.create();
        case DIALOG_NOGOSELECT_ID:
            builder = new AlertDialog.Builder(this);
            builder.setTitle("Check NoGo Selection:");
            String[] nogoNames = new String[nogoList.size()];
            for( int i=0; i<nogoList.size(); i++ ) nogoNames[i] = nogoList.get(i).name;
            final boolean[] nogoEnabled = getCheckedBooleanArray(nogoList.size());
            builder.setMultiChoiceItems(nogoNames, getCheckedBooleanArray( nogoNames.length ),
                      new DialogInterface.OnMultiChoiceClickListener() {
               @Override
               public void onClick(DialogInterface dialog, int which, boolean isChecked) {
                 nogoEnabled[which] = isChecked;
               }
            });
            builder.setPositiveButton("Ok", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int whichButton) {
                     mBRouterView.updateNogoList( nogoEnabled );
                     mBRouterView.startProcessing(selectedProfile);
                   }
                 });
            return builder.create();
        case DIALOG_SHOWRESULT_ID:
          String leftLabel = wpCount < 0 ? "Exit" : ( wpCount == 0 ? "Select from" : "Select to/via" );
          String rightLabel = wpCount < 2 ? "Server-Mode" : "Calc Route";
          builder = new AlertDialog.Builder(this);
          builder.setTitle( title )
                 .setMessage( errorMessage )
                 .setPositiveButton( leftLabel, new DialogInterface.OnClickListener() {
                     public void onClick(DialogInterface dialog, int id) {
                    	 if ( wpCount < 0 ) finish();
                    	 else  mBRouterView.pickWaypoints();
                     }
                 })
                 .setNegativeButton( rightLabel, new DialogInterface.OnClickListener() {
                     public void onClick(DialogInterface dialog, int id) {
                         if ( wpCount < 2 ) mBRouterView.startConfigureService();
                         else
                         {
                           mBRouterView.finishWaypointSelection();
                           mBRouterView.startProcessing(selectedProfile);
                         }
                     }
                 });
          return builder.create();
        case DIALOG_MODECONFIGOVERVIEW_ID:
          builder = new AlertDialog.Builder(this);
          builder.setTitle( "Success" )
                 .setMessage( message )
                 .setPositiveButton( "Exit", new DialogInterface.OnClickListener() {
                     public void onClick(DialogInterface dialog, int id) {
                         finish();
                     }
                 });
          return builder.create();
        case DIALOG_PICKWAYPOINT_ID:
          builder = new AlertDialog.Builder(this);
          builder.setTitle( wpCount > 0 ? "Select to/via" : "Select from" );
          builder.setItems(availableWaypoints, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int item) {
              mBRouterView.updateWaypointList( availableWaypoints[item] );
              mBRouterView.startProcessing(selectedProfile);
            }
          });
          return builder.create();

        default:
            return null;
        }

    }

    private boolean[] getCheckedBooleanArray( int size )
    {
      boolean[] checked = new boolean[size];
      for( int i=0; i<checked.length; i++ ) checked[i] = true;
      return checked;
    }

    private String[] availableProfiles;
    private String selectedProfile = null;

    private String[] availableWaypoints;
    private String selectedWaypoint = null;

    private String[] routingModes;
    private boolean[] routingModesChecked;
    
    private String defaultbasedir = null;
    private String message = null;

    private String[] availableVias;
    private Set<String> selectedVias;

    private List<OsmNodeNamed> nogoList;

    @SuppressWarnings("deprecation")
    public void selectProfile( String[] items )
    {
      availableProfiles = items;
      showDialog( DIALOG_SELECTPROFILE_ID );
    }

    @SuppressWarnings("deprecation")
    public void selectRoutingModes( String[] modes, boolean[] modesChecked, String message )
    {
      routingModes = modes;
      routingModesChecked = modesChecked;
      this.message = message;
      showDialog( DIALOG_ROUTINGMODES_ID );
    }

    @SuppressWarnings("deprecation")
    public void showModeConfigOverview( String message )
    {
      this.message = message;
      showDialog( DIALOG_MODECONFIGOVERVIEW_ID );
    }
    
    
    @SuppressWarnings("deprecation")
    public void selectBasedir( String defaultBasedir, String message )
    {
        this.defaultbasedir = defaultBasedir;
        this.message = message;
      showDialog( DIALOG_TEXTENTRY_ID );
    }

    @SuppressWarnings("deprecation")
    public void selectVias( String[] items )
    {
      availableVias = items;
      selectedVias = new HashSet<String>(availableVias.length);
      for( String via : items ) selectedVias.add( via );
      showDialog( DIALOG_VIASELECT_ID );
    }

    @SuppressWarnings("deprecation")
    public void selectWaypoint( String[] items )
    {
      availableWaypoints = items;
      showNewDialog( DIALOG_PICKWAYPOINT_ID );
    }

    @SuppressWarnings("deprecation")
    public void selectNogos( List<OsmNodeNamed> nogoList )
    {
      this.nogoList = nogoList;
      showDialog( DIALOG_NOGOSELECT_ID );
    }

    private Set<Integer> dialogIds = new HashSet<Integer>();
    
    private void showNewDialog( int id )
    {
      if ( dialogIds.contains( new Integer( id ) ) )
      {
    	  removeDialog( id );
      }
      dialogIds.add( new Integer( id ) );
      showDialog( id );
    }
    
    private String errorMessage;
    private String title;
    private int wpCount;

    @SuppressWarnings("deprecation")
    public void showErrorMessage( String msg )
    {
        errorMessage = msg;
      showNewDialog( DIALOG_EXCEPTION_ID );
    }

    @SuppressWarnings("deprecation")
    public void showResultMessage( String title, String msg, int wpCount )
    {
        errorMessage = msg;
        this.title = title;
        this.wpCount = wpCount;
      showNewDialog( DIALOG_SHOWRESULT_ID );
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

        // Start the simulation
        mBRouterView.startSimulation();


    }

    @Override
    protected void onPause() {
        super.onPause();
        /*
         * When the activity is paused, we make sure to stop the simulation,
         * release our sensor resources and wake locks
         */

        // Stop the simulation
        mBRouterView.stopSimulation();

        // and release our wake-lock
        mWakeLock.release();


    }

    @Override
    public void onInit(int i)
    {
    }

}
