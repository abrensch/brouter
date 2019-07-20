package btools.routingapp;

import java.text.DecimalFormat;
import java.util.ArrayList;
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
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.StatFs;
import android.speech.tts.TextToSpeech.OnInitListener;
import android.widget.EditText;
import btools.router.OsmNodeNamed;

public class BRouterActivity extends Activity implements OnInitListener
{
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
  private static final int DIALOG_SHOW_WP_HELP_ID = 14;
  private static final int DIALOG_SHOW_WP_SCANRESULT_ID = 15;
  private static final int DIALOG_SHOW_REPEAT_TIMEOUT_HELP_ID = 16;
  private static final int DIALOG_SHOW_API23_HELP_ID = 17;
  

  private BRouterView mBRouterView;
  private PowerManager mPowerManager;
  private WakeLock mWakeLock;

  /** Called when the activity is first created. */
  @Override
  @SuppressWarnings("deprecation")
  public void onCreate( Bundle savedInstanceState )
  {
    super.onCreate( savedInstanceState );

    // Get an instance of the PowerManager
    mPowerManager = (PowerManager) getSystemService( POWER_SERVICE );

    // Create a bright wake lock
    mWakeLock = mPowerManager.newWakeLock( PowerManager.SCREEN_BRIGHT_WAKE_LOCK, getClass().getName() );

    ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
    int memoryClass = am.getMemoryClass();

    // instantiate our simulation view and set it as the activity's content
    mBRouterView = new BRouterView( this, memoryClass );
    mBRouterView.init();
    setContentView( mBRouterView );
  }

  @Override
  @SuppressWarnings("deprecation")
  protected Dialog onCreateDialog( int id )
  {
    AlertDialog.Builder builder;
    switch ( id )
    {
    case DIALOG_SELECTPROFILE_ID:
      builder = new AlertDialog.Builder( this );
      builder.setTitle( "Select a routing profile" );
      builder.setItems( availableProfiles, new DialogInterface.OnClickListener()
      {
        public void onClick( DialogInterface dialog, int item )
        {
          selectedProfile = availableProfiles[item];
          mBRouterView.startProcessing( selectedProfile );
        }
      } );
      return builder.create();
    case DIALOG_MAINACTION_ID:
      builder = new AlertDialog.Builder( this );
      builder.setTitle( "Select Main Action" );
      builder.setItems( new String[]
      { "Download Manager", "BRouter App" }, new DialogInterface.OnClickListener()
      {
        public void onClick( DialogInterface dialog, int item )
        {
          if ( item == 0 )
            startDownloadManager();
          else
            showDialog( DIALOG_SELECTPROFILE_ID );
        }
      } );
      return builder.create();
    case DIALOG_SHOW_DM_INFO_ID:
      builder = new AlertDialog.Builder( this );
      builder
          .setTitle( "BRouter Download Manager" )
          .setMessage(
              "*** Attention: ***\n\n" + "The Download Manager is used to download routing-data "
                  + "files which can be up to 170MB each. Do not start the Download Manager " + "on a cellular data connection without a data plan! "
                  + "Download speed is restricted to 4 MBit/s." ).setPositiveButton( "I know", new DialogInterface.OnClickListener()
          {
            public void onClick( DialogInterface dialog, int id )
            {
              Intent intent = new Intent( BRouterActivity.this, BInstallerActivity.class );
              startActivity( intent );
              finish();
            }
          } ).setNegativeButton( "Cancel", new DialogInterface.OnClickListener()
          {
            public void onClick( DialogInterface dialog, int id )
            {
              finish();
            }
          } );
      return builder.create();
    case DIALOG_SHOW_WP_HELP_ID:
      builder = new AlertDialog.Builder( this );
      builder
          .setTitle( "No Waypoint Database found" )
          .setMessage(
              "The simple scan did not find any map-tool directory including a waypoint database. "
            + "Reason could be there is no map-tool installed (osmand, locus or oruxmaps), or at an "
            + "unusual path, or it contains no waypoints yet. That's o.k. if you want to use BRouter "
            + "in server-mode only - in that case you can still use the 'Server-Mode' button to "
            + "configure the profile mapping. But you will not be able to use nogo-points or do "
            + "long distance calculations. If you know the path to your map-tool, you can manually "
            + "configure it in 'storageconfig.txt'. Or I can do an extended scan searching "
            + "your sd-card for a valid waypoint database" ).setPositiveButton( "Scan", new DialogInterface.OnClickListener()
          {
            public void onClick( DialogInterface dialog, int id )
            {
              mBRouterView.startWpDatabaseScan();
            }
          } ).setNegativeButton( "Exit", new DialogInterface.OnClickListener()
          {
            public void onClick( DialogInterface dialog, int id )
            {
              finish();
            }
          } );
      return builder.create();
    case DIALOG_SHOW_API23_HELP_ID:
      builder = new AlertDialog.Builder( this );
      builder
          .setTitle( "Android >=6 limitations" )
          .setMessage(
              "You are using the BRouter APP on Android >= 6, where classic mode is no longer supported. "
            + "Reason is that security policy does not permit any longer to read the waypoint databases of other apps. "
            + "That's o.k. if you want to use BRouter in server-mode only, where the apps actively send the waypoints "
            + "via a remote procedure call to BRouter (And Locus can also send nogo areas). "
            + "So the only functions you need to start the BRouter App are 1) to initially define the base directory "
            + "2) to download routing data files and 3) to configure the profile mapping via the 'Server-Mode' button. "
            + "You will eventually not be able to define nogo-areas (OsmAnd, Orux) or to do "
            + "very long distance calculations. If you want to get classic mode back, you can manually install "
            + "the APK of the BRouter App from the release page ( http://brouter.de/brouter/revisions.html ), which "
            + "is still built against Android API 10, and does not have these limitations. "
            ).setNegativeButton( "Exit", new DialogInterface.OnClickListener()
          {
            public void onClick( DialogInterface dialog, int id )
            {
              finish();
            }
          } );
      return builder.create();
    case DIALOG_SHOW_REPEAT_TIMEOUT_HELP_ID:
      builder = new AlertDialog.Builder( this );
      builder
          .setTitle( "Successfully prepared a timeout-free calculation" )
          .setMessage(
              "You successfully repeated a calculation that previously run into a timeout "
            + "when started from your map-tool. If you repeat the same request from your "
            + "maptool, with the exact same destination point and a close-by starting point, "
            + "this request is guaranteed not to time out." ).setNegativeButton( "Exit", new DialogInterface.OnClickListener()
          {
            public void onClick( DialogInterface dialog, int id )
            {
              finish();
            }
          } );
      return builder.create();
    case DIALOG_SHOW_WP_SCANRESULT_ID:
      builder = new AlertDialog.Builder( this );
      builder
          .setTitle( "Waypoint Database " )
          .setMessage( "Found Waypoint-Database(s) for maptool-dir: " + maptoolDirCandidate
            + " Configure that?" ).setPositiveButton( "Yes", new DialogInterface.OnClickListener()
          {
            public void onClick( DialogInterface dialog, int id )
            {
              mBRouterView.saveMaptoolDir( maptoolDirCandidate );
            }
          } ).setNegativeButton( "No", new DialogInterface.OnClickListener()
          {
            public void onClick( DialogInterface dialog, int id )
            {
              finish();
            }
          } );
      return builder.create();
    case DIALOG_OLDDATAHINT_ID:
      builder = new AlertDialog.Builder( this );
      builder
          .setTitle( "Local setup needs reset" )
          .setMessage(
              "You are currently using an old version of the lookup-table " + "together with routing data made for this old table. "
                  + "Before downloading new datafiles made for the new table, "
                  + "you have to reset your local setup by 'moving away' (or deleting) "
                  + "your <basedir>/brouter directory and start a new setup by calling the " + "BRouter App again." )
          .setPositiveButton( "OK", new DialogInterface.OnClickListener()
          {
            public void onClick( DialogInterface dialog, int id )
            {
              finish();
            }
          } );
      return builder.create();
    case DIALOG_ROUTINGMODES_ID:
      builder = new AlertDialog.Builder( this );
      builder.setTitle( message );
      builder.setMultiChoiceItems( routingModes, routingModesChecked, new DialogInterface.OnMultiChoiceClickListener()
      {
        @Override
        public void onClick( DialogInterface dialog, int which, boolean isChecked )
        {
          routingModesChecked[which] = isChecked;
        }
      } );
      builder.setPositiveButton( "Ok", new DialogInterface.OnClickListener()
      {
        public void onClick( DialogInterface dialog, int whichButton )
        {
          mBRouterView.configureService( routingModes, routingModesChecked );
        }
      } );
      return builder.create();
    case DIALOG_EXCEPTION_ID:
      builder = new AlertDialog.Builder( this );
      builder.setTitle( "An Error occured" ).setMessage( errorMessage ).setPositiveButton( "OK", new DialogInterface.OnClickListener()
      {
        public void onClick( DialogInterface dialog, int id )
        {
          mBRouterView.continueProcessing();
        }
      } );
      return builder.create();
    case DIALOG_TEXTENTRY_ID:
      builder = new AlertDialog.Builder( this );
      builder.setTitle( "Enter SDCARD base dir:" );
      builder.setMessage( message );
      final EditText input = new EditText( this );
      input.setText( defaultbasedir );
      builder.setView( input );
      builder.setPositiveButton( "Ok", new DialogInterface.OnClickListener()
      {
        public void onClick( DialogInterface dialog, int whichButton )
        {
          String basedir = input.getText().toString();
          mBRouterView.startSetup( basedir, true );
        }
      } );
      return builder.create();
    case DIALOG_SELECTBASEDIR_ID:
      builder = new AlertDialog.Builder( this );
      builder.setTitle( "Select an SDCARD base dir:" );
      builder.setSingleChoiceItems( basedirOptions, 0, new DialogInterface.OnClickListener()
      {
        @Override
        public void onClick( DialogInterface dialog, int item )
        {
          selectedBasedir = item;
        }
      } );
      builder.setPositiveButton( "Ok", new DialogInterface.OnClickListener()
      {
        public void onClick( DialogInterface dialog, int whichButton )
        {
          if ( selectedBasedir < availableBasedirs.size() )
          {
            mBRouterView.startSetup( availableBasedirs.get( selectedBasedir ), true );
          }
          else
          {
            showDialog( DIALOG_TEXTENTRY_ID );
          }
        }
      } );
      return builder.create();
    case DIALOG_VIASELECT_ID:
      builder = new AlertDialog.Builder( this );
      builder.setTitle( "Check VIA Selection:" );
      builder.setMultiChoiceItems( availableVias, getCheckedBooleanArray( availableVias.length ), new DialogInterface.OnMultiChoiceClickListener()
      {
        @Override
        public void onClick( DialogInterface dialog, int which, boolean isChecked )
        {
          if ( isChecked )
          {
            selectedVias.add( availableVias[which] );
          }
          else
          {
            selectedVias.remove( availableVias[which] );
          }
        }
      } );
      builder.setPositiveButton( "Ok", new DialogInterface.OnClickListener()
      {
        public void onClick( DialogInterface dialog, int whichButton )
        {
          mBRouterView.updateViaList( selectedVias );
          mBRouterView.startProcessing( selectedProfile );
        }
      } );
      return builder.create();
    case DIALOG_NOGOSELECT_ID:
      builder = new AlertDialog.Builder( this );
      builder.setTitle( "Check NoGo Selection:" );
      String[] nogoNames = new String[nogoList.size()];
      for ( int i = 0; i < nogoList.size(); i++ )
        nogoNames[i] = nogoList.get( i ).name;
      final boolean[] nogoEnabled = getCheckedBooleanArray( nogoList.size() );
      builder.setMultiChoiceItems( nogoNames, getCheckedBooleanArray( nogoNames.length ), new DialogInterface.OnMultiChoiceClickListener()
      {
        @Override
        public void onClick( DialogInterface dialog, int which, boolean isChecked )
        {
          nogoEnabled[which] = isChecked;
        }
      } );
      builder.setPositiveButton( "Ok", new DialogInterface.OnClickListener()
      {
        public void onClick( DialogInterface dialog, int whichButton )
        {
          mBRouterView.updateNogoList( nogoEnabled );
          mBRouterView.startProcessing( selectedProfile );
        }
      } );
      return builder.create();
    case DIALOG_SHOWRESULT_ID:
      String leftLabel = wpCount < 0 ? ( wpCount != -2 ? "Exit" : "Help") : ( wpCount == 0 ? "Select from" : "Select to/via" );
      String rightLabel = wpCount < 2 ? ( wpCount == -3 ? "Help" : "Server-Mode" ) : "Calc Route";
      builder = new AlertDialog.Builder( this );
      builder.setTitle( title ).setMessage( errorMessage ).setPositiveButton( leftLabel, new DialogInterface.OnClickListener()
      {
        public void onClick( DialogInterface dialog, int id )
        {
          if ( wpCount == -2 )
          {
            showWaypointDatabaseHelp();
          }
          else if ( wpCount == -1 || wpCount == -3  )
          {
            finish();
          }
          else
          {
            mBRouterView.pickWaypoints();
          }
        }
      } ).setNegativeButton( rightLabel, new DialogInterface.OnClickListener()
      {
        public void onClick( DialogInterface dialog, int id )
        {
          if ( wpCount == -3 )
          {
            showRepeatTimeoutHelp();
          }
          else if ( wpCount < 2 )
          {
            mBRouterView.startConfigureService();
          }
          else
          {
            mBRouterView.finishWaypointSelection();
            mBRouterView.startProcessing( selectedProfile );
          }
        }
      } );
      return builder.create();
    case DIALOG_MODECONFIGOVERVIEW_ID:
      builder = new AlertDialog.Builder( this );
      builder.setTitle( "Success" ).setMessage( message ).setPositiveButton( "Exit", new DialogInterface.OnClickListener()
      {
        public void onClick( DialogInterface dialog, int id )
        {
          finish();
        }
      } );
      return builder.create();
    case DIALOG_PICKWAYPOINT_ID:
      builder = new AlertDialog.Builder( this );
      builder.setTitle( wpCount > 0 ? "Select to/via" : "Select from" );
      builder.setItems( availableWaypoints, new DialogInterface.OnClickListener()
      {
        public void onClick( DialogInterface dialog, int item )
        {
          mBRouterView.updateWaypointList( availableWaypoints[item] );
          mBRouterView.startProcessing( selectedProfile );
        }
      } );
      return builder.create();

    default:
      return null;
    }
  }

  private boolean[] getCheckedBooleanArray( int size )
  {
    boolean[] checked = new boolean[size];
    for ( int i = 0; i < checked.length; i++ ) checked[i] = true;
    return checked;
  }

  private String[] availableProfiles;
  private String selectedProfile = null;

  private List<String> availableBasedirs;
  private String[] basedirOptions;
  private int selectedBasedir;

  private String[] availableWaypoints;

  private String[] routingModes;
  private boolean[] routingModesChecked;

  private String defaultbasedir = null;
  private String message = null;

  private String[] availableVias;
  private Set<String> selectedVias;

  private List<OsmNodeNamed> nogoList;

  private String maptoolDirCandidate;

  public boolean isOnline()
  {
    ConnectivityManager cm = (ConnectivityManager) getSystemService( Context.CONNECTIVITY_SERVICE );

    return cm.getActiveNetworkInfo() != null && cm.getActiveNetworkInfo().isConnectedOrConnecting();
  }

  @SuppressWarnings("deprecation")
  public void selectProfile( String[] items )
  {
    availableProfiles = items;

    // if we have internet access, first show the main action dialog
    if ( isOnline() )
    {
      showDialog( DIALOG_MAINACTION_ID );
    }
    else
    {
      showDialog( DIALOG_SELECTPROFILE_ID );
    }
  }

  @SuppressWarnings("deprecation")
  public void startDownloadManager()
  {
    if ( !mBRouterView.hasUpToDateLookups() )
    {
      showDialog( DIALOG_OLDDATAHINT_ID );
    }
    else
    {
      showDialog( DIALOG_SHOW_DM_INFO_ID );
    }
  }

  @SuppressWarnings("deprecation")
  public void selectBasedir( List<String> items, String defaultBasedir, String message )
  {
    this.defaultbasedir = defaultBasedir;
    this.message = message;
    availableBasedirs = new ArrayList<String>();
    ArrayList<Long> dirFreeSizes = new ArrayList<Long>();
    for ( String d : items )
    {
      long size = 0L;
      try
      {
        StatFs stat = new StatFs( d );
        size = (long) stat.getAvailableBlocks() * stat.getBlockSize();
      }
      catch (Exception e) { /* ignore */ }
      availableBasedirs.add( d );
      dirFreeSizes.add( Long.valueOf( size ) );
    }

    basedirOptions = new String[items.size() + 1];
    int bdidx = 0;
    DecimalFormat df = new DecimalFormat( "###0.00" );
    for ( int idx = 0; idx < availableBasedirs.size(); idx++ )
    {
      basedirOptions[bdidx++] = availableBasedirs.get( idx ) + " (" + df.format( dirFreeSizes.get( idx ) / 1024. / 1024. / 1024. ) + " GB free)";
    }
    basedirOptions[bdidx] = "Other";

    showDialog( DIALOG_SELECTBASEDIR_ID );
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
  public void selectVias( String[] items )
  {
    availableVias = items;
    selectedVias = new HashSet<String>( availableVias.length );
    for ( String via : items )
      selectedVias.add( via );
    showDialog( DIALOG_VIASELECT_ID );
  }

  @SuppressWarnings("deprecation")
  public void selectWaypoint( String[] items )
  {
    availableWaypoints = items;
    showNewDialog( DIALOG_PICKWAYPOINT_ID );
  }

  @SuppressWarnings("deprecation")
  public void showWaypointDatabaseHelp()
  {
    if ( mBRouterView.canAccessSdCard )
    {
      showNewDialog( DIALOG_SHOW_WP_HELP_ID );
    }
    else
    {
      showNewDialog( DIALOG_SHOW_API23_HELP_ID );
    }
  }

  @SuppressWarnings("deprecation")
  public void showRepeatTimeoutHelp()
  {
    showNewDialog( DIALOG_SHOW_REPEAT_TIMEOUT_HELP_ID );
  }

  @SuppressWarnings("deprecation")
  public void showWpDatabaseScanSuccess( String bestGuess )
  {
    maptoolDirCandidate = bestGuess;
    showNewDialog( DIALOG_SHOW_WP_SCANRESULT_ID );
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
    if ( dialogIds.contains( Integer.valueOf( id ) ) )
    {
      removeDialog( id );
    }
    dialogIds.add( Integer.valueOf( id ) );
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
  protected void onResume()
  {
    super.onResume();
    /*
     * when the activity is resumed, we acquire a wake-lock so that the screen
     * stays on, since the user will likely not be fiddling with the screen or
     * buttons.
     */
    mWakeLock.acquire();
  }

  @Override
  protected void onPause()
  {
    super.onPause();
    /*
     * When the activity is paused, we make sure to stop the router
     */

    // Stop the simulation
    mBRouterView.stopRouting();

    // and release our wake-lock
    mWakeLock.release();
  }

  @Override
  public void onInit( int i )
  {
  }
}
