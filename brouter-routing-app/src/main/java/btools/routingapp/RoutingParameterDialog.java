package btools.routingapp;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.CheckBoxPreference;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This activity is used to define the parameter for the six BRouter routing modes.
 * But it could also used from other apps to get parameters for an extern profile
 * So the app can send a remote profile and add some parameters for the handling
 * <p>
 * How to use from extern app
 * <p>
 * - copy the class btools.routingapp.RoutingParam to your app java folder without any changes
 * - copy the routine 'getParamsFromProfile' below to your favorite setting class
 * inside BRouter the getParamsFromProfile is called to handle the internal parameters
 * <p>
 * Call the Parameter Setting Dialog from your app like this:
 * <p>
 * // read the variable parameters into array
 * List<RoutingParam> listParams = new ArrayList<>();
 * String file = "xyz.brf";
 * InputStream fis = new FileInputStream(file);  // could be also a stream from DocumentFile
 * listParams = getParamsFromProfile(fis);
 * fis.close();
 * <p>
 * // call the BRouter param dialog
 * ComponentName cn = new ComponentName("btools.routingapp", "btools.routingapp.RoutingParameterDialog");
 * Intent i = new Intent();
 * i.setComponent(cn);
 * <p>
 * // fill some parameter for the dialog
 * String profile_hash = String.format("X%X", file.hashCode()); // some identify code
 * i.putExtra("PROFILE_HASH", profile_hash);
 * i.putExtra("PROFILE", profile_name);   // the profile name, only used for display
 * i.putExtra("PARAMS", listParams);      // the settings list
 * i.putExtra("PARAMS_VALUES", saved_params);  // your stored profile parameter or nothing
 * <p>
 * startActivityForResult(i, ROUTE_SETTING_REQUEST);
 * <p>
 * onActivityResult:
 * if (requestCode == ROUTE_SETTING_REQUEST) {
 * String profile = null;
 * String profile_hash = null;
 * String sparams = null;
 * // get back the selected parameter (only PARAMS_VALUES is needed)
 * if (data != null && data.hasExtra("PARAMS_VALUES")) {
 * sparams = data.getExtras().getString("PARAMS_VALUES", "");
 * Log.d(TAG, "result sparams " + sparams);
 * }
 * if (data != null && data.hasExtra("PROFILE")) {
 * profile = data.getExtras().getString("PROFILE", "");
 * Log.d(TAG, "result profile " + profile);
 * }
 * if (data != null && data.hasExtra("PROFILE_HASH")) {
 * profile_hash = data.getExtras().getString("PROFILE_HASH", "");
 * Log.d(TAG, "result profile_hash " + profile_hash);
 * }
 * }
 */

public class RoutingParameterDialog extends AppCompatActivity {

  static String TAG = "RoutingParameterDialog";

  static SharedPreferences sharedValues;
  static ArrayList<RoutingParam> listParams;
  static String profile;
  static String profile_hash;

  /**
   * collect a list of parameter in a profile
   *
   * @param fis - inputstream from profile
   * @return - list of variable params in this profile
   * @throws IOException if not readable
   */
  static public List<RoutingParam> getParamsFromProfile(final InputStream fis) throws IOException {
    List<RoutingParam> list = null;

    if (fis != null) {
      list = new ArrayList<>();

      // prepare the file for reading
      InputStreamReader chapterReader = new InputStreamReader(fis);
      BufferedReader buffreader = new BufferedReader(chapterReader);

      String line;

      // read every line of the file into the line-variable, on line at the time
      do {
        line = buffreader.readLine();
        // do something with the line
        if (line != null &&
          line.contains("#") &&
          line.contains("%") &&
          line.lastIndexOf("%") != line.indexOf("%")
        ) {
          String s = line.substring(line.indexOf("#") + 1);
          String v = line.substring(0, line.indexOf("#"));
          try {
            String[] sa = s.split("\\|");
            RoutingParam p = new RoutingParam();
            p.name = sa[0].trim();
            p.name = p.name.substring(1, p.name.length() - 1);
            // turnInstructionMode may transfered from client direct, use only in web client
            if (p.name.equals("turnInstructionMode")) continue;
            p.description = sa[1].trim();
            p.type = sa[2].trim();
            String[] sav = v.trim().split(" +");
            if (sav[1].equals(p.name)) {
              if (sav[0].equals("assign")) {
                if (sav[2].equals("=")) {
                  p.value = sav[3];
                } else {
                  p.value = sav[2];
                }
              }
              list.add(p);
            }
          } catch (Exception e) {
            Log.e(TAG, Log.getStackTraceString(e));
          }
        }
      } while (line != null);

    }

    return list;
  }


  @Override
  protected void onCreate(final Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    getSupportFragmentManager().beginTransaction().replace(android.R.id.content, new MyPreferenceFragment()).commit();


    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
        OnBackInvokedDispatcher.PRIORITY_DEFAULT,
        new OnBackInvokedCallback() {
          @Override
          public void onBackInvoked() {
            handleBackPressed();
          }
        }
      );
    } else {
      OnBackPressedCallback callback = new OnBackPressedCallback(true) {
        @Override
        public void handleOnBackPressed() {
          handleBackPressed();
        }
      };
      getOnBackPressedDispatcher().addCallback(this, callback);
    }
  }

  private void handleBackPressed() {
    StringBuilder sb = null;
    if (sharedValues != null) {
      // fill preference with used params
      // for direct use in the BRouter interface "extraParams"
      sb = new StringBuilder();
      for (Map.Entry<String, ?> entry : sharedValues.getAll().entrySet()) {
        if (!entry.getKey().equals("params")) {
          sb.append(sb.length() > 0 ? "&" : "")
            .append(entry.getKey())
            .append("=");
          String s = entry.getValue().toString();
          if (s.equals("true")) s = "1";
          else if (s.equals("false")) s = "0";
          sb.append(s);
        }
      }
    }
    // and return the array
    // one should be enough
    Intent i = new Intent();
    // i.putExtra("PARAMS", listParams);
    i.putExtra("PROFILE", profile);
    i.putExtra("PROFILE_HASH", profile_hash);
    if (sb != null) i.putExtra("PARAMS_VALUES", sb.toString());

    setResult(Activity.RESULT_OK, i);
    finish();

  }

  @Override
  public void onConfigurationChanged(Configuration newConfig) {
    super.onConfigurationChanged(newConfig);
  }


  public static class MyPreferenceFragment extends PreferenceFragmentCompat {

    private Activity mActivity;

    @Override
    public void onAttach(@NonNull Context context) {
      super.onAttach(context);
      if (context instanceof Activity) {
        mActivity = (Activity) context;
      }
    }

    @Override
    public void onDetach() {
      super.onDetach();
      mActivity = null;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onCreate(Bundle savedInstanceState) {
      super.onCreate(savedInstanceState);

      mActivity.setTitle("Profile Settings");
      listParams = new ArrayList<>();
      String sparams = "";
      try {
        Intent i = mActivity.getIntent();
        if (i == null) {
          mActivity.finish();
          return;
        }

        if (i.hasExtra("PROFILE")) {
          profile = i.getStringExtra("PROFILE");
        }
        if (i.hasExtra("PROFILE_HASH")) {
          profile_hash = i.getStringExtra("PROFILE_HASH");
        }

        if (i.hasExtra("PARAMS")) {
          List<?> result;
          if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            result = (List<?>) i.getExtras().getSerializable("PARAMS");
          } else {
            result = (List<?>) i.getExtras().getSerializable("PARAMS", ArrayList.class);
          }
          if (result instanceof ArrayList) {
            for (Object o : result) {
              if (o instanceof RoutingParam) listParams.add((RoutingParam) o);
            }
          }
        }
        if (i.hasExtra("PARAMS_VALUES")) {
          sparams = i.getExtras().getString("PARAMS_VALUES", "");
        }
      } catch (Exception e) {
        Log.e(TAG, Log.getStackTraceString(e));
      }

      getPreferenceManager().setSharedPreferencesName("prefs_profile_" + profile_hash);
      sharedValues = getPreferenceManager().getSharedPreferences();
      // clear all
      // sharedValues.edit().clear().commit();

      setPreferenceScreen(createPreferenceHierarchy(sparams));

    }

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {

    }

    private PreferenceScreen createPreferenceHierarchy(String sparams) {
      // Root
      Activity a = this.getActivity();
      if (a == null) return null;

      // fill incoming params
      Map<String, String> params = new HashMap<>();
      if (sparams != null && sparams.length() > 0) {
        String[] sa = sparams.split("&");
        for (String sar : sa) {
          String[] sa2 = sar.split("=");
          if (sa2.length == 2) params.put(sa2[0], sa2[1]);

        }
      }

      PreferenceScreen root = getPreferenceManager().createPreferenceScreen(a);

      PreferenceCategory gpsPrefCat = new PreferenceCategory(this.getActivity());
      if (profile.length() > 0) {
        gpsPrefCat.setTitle(profile);
      } else {
        gpsPrefCat.setTitle("Profile Settings");
      }
      root.addPreference(gpsPrefCat);

      if (listParams != null) {
        for (RoutingParam p : listParams) {
          if (p.type.equals("number")) {
            EditTextPreference numberTextPref = new EditTextPreference(this.getActivity());
            numberTextPref.setDialogTitle(p.name);
            numberTextPref.setKey(p.name);
            numberTextPref.setSummary(p.description);
            String s = (params.get(p.name) != null ? params.get(p.name) : p.value);
            if (p.value.equals(s)) sharedValues.edit().remove(p.name).apply();
            numberTextPref.setTitle(p.name + ": " + s);
            numberTextPref.setText(s);
            //EditText speedEditText = (EditText) speedTextPref.getText();
            //speedEditText.setKeyListener(DigitsKeyListener.getInstance(false, true));
            numberTextPref.setOnPreferenceChangeListener((Preference preference, Object newValue) -> {
              p.value = (String) newValue;
              numberTextPref.setTitle(p.name + ": " + p.value);
              return true;
            });

            gpsPrefCat.addPreference(numberTextPref);

          } else if (p.type.equals("boolean")) {
            CheckBoxPreference boolPref = new CheckBoxPreference(this.getActivity());
            boolPref.setKey(p.name);
            boolPref.setTitle(p.name);
            boolPref.setSummary(p.description);
            boolean checked = false;
            boolean vchecked = p.value != null && (p.value.equals("1") || p.value.equals("true"));

            if (params.get(p.name) != null) {
              checked = params.get(p.name).equals("1") || params.get(p.name).equals("true");
            } else {
              checked = vchecked;
            }
            if (vchecked == checked) sharedValues.edit().remove(p.name).apply();

            boolPref.setChecked(checked);
            //historyPref.setDefaultValue(sharedValues.getBoolean(p.name, p.value != null ? p.value.equals("1") || p.value.equals("true") : false));
            boolPref.setDefaultValue(p.value != null && (p.value.equals("1") || p.value.equals("true")));
            boolPref.setOnPreferenceClickListener((Preference preference) -> {
              p.value = (((CheckBoxPreference) preference).isChecked() ? "1" : "0");
              return true;

            });
            gpsPrefCat.addPreference(boolPref);

          } else if (p.type.contains("[") && p.type.contains("]")) {
            String[] sa = p.type.substring(p.type.indexOf("[") + 1, p.type.indexOf("]")).split(",");
            String[] entryValues = new String[sa.length];
            String[] entries = new String[sa.length];
            int i = 0, ii = 0;
            String s = (params.get(p.name) != null ? params.get(p.name) : p.value); //sharedValues.getString(p.name, p.value);
            for (String tmp : sa) {
              // Add the name and address to the ListPreference enties and entyValues
              //L.v("AFTrack", "device: "+device.getName() + " -- " + device.getAddress());
              entryValues[i] = "" + i;
              entries[i] = tmp.trim();
              if (entryValues[i].equals(s)) ii = i;
              i++;
            }
            if (p.value.equals(s)) sharedValues.edit().remove(p.name).apply();

            ListPreference listPref = new ListPreference(this.getActivity());
            listPref.setEntries(entries);
            listPref.setEntryValues(entryValues);
            listPref.setDialogTitle(p.name);
            listPref.setKey(p.name);
            listPref.setValueIndex(ii);
            listPref.setTitle(p.name + ": " + entries[ii]);
            listPref.setSummary(p.description);
            listPref.setOnPreferenceChangeListener((Preference preference, Object newValue) -> {
              p.value = (String) newValue;
              int iii = Integer.decode(p.value);
              listPref.setTitle(p.name + ": " + entries[iii]);

              return true;

            });

            gpsPrefCat.addPreference(listPref);

          }
        }
      }
      return root;
    }

  }

}
