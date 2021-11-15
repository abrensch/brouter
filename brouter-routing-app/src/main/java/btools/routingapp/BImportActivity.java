package btools.routingapp;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.stream.Collectors;

public class BImportActivity extends Activity {
  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    Intent intent = getIntent();
    String action = intent.getAction();
    String type = intent.getType();
    System.out.println("Brouter START xxxxxx BrouterActivity");
    if (Intent.ACTION_VIEW.equals(action)) {
      System.out.println("Brouter Intent.ACTION_VIEW detected");
      import_profile(intent);
      return;
    }
  }

  private void import_profile(Intent intent) {
    TextView scanResults;
    //  app was activated by an intent with "action_view"...
    System.out.println("Brouter Intent.ACTION_VIEW detected");

    if (intent.getData() == null) {
      System.out.println("Brouter intent.get(data) is NULL   ERROR");
    } else {
      setContentView(R.layout.import_intent);
      scanResults = (TextView) findViewById(R.id.Info_brouter);
      scanResults.setText("Start importing profile: \n");

      Uri dataUri = intent.getData();
      System.out.println("Brouter DATA=" + dataUri);

      // by some apps (bluemail) the file name must be "extracted" from the URI, as example with the following code
      // see https://stackoverflow.com/questions/14364091/retrieve-file-path-from-caught-downloadmanager-intent
      // URI example ==>  dat=content://me.bluemail.mail.attachmentprovider/a2939069-76b5-44e4-8cbd-94485d0fd4ff/cc32b61d-97a6-4871-b67f-945d1d1d43c8/VIEW
      String filename = null;
      Long filesize = null;
      Cursor cursor = null;
      try {
        cursor = this.getContentResolver().query(intent.getData(), new String[]{
          OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null);
        if (cursor != null && cursor.moveToFirst()) {
          filename = cursor.getString(0);
          filesize = cursor.getLong(1);
        }
      } finally {
        if (cursor != null)
          cursor.close();
      }
      System.out.println("Brouter filename=" + filename + "\n file size=" + filesize);
      scanResults.setText(scanResults.getText() + "File name=" + filename + "\nFile size=" + filesize + " bytes\n");
      //  is the file extention ".brf" in the file name
      if (filename.indexOf(".brf") != -1 && (filename.indexOf(".brf") == (filename.length() - 4))) {
        System.out.println("Brouter  OK, file extention is .brf!!!");
      } else {
        System.out.println("Brouter  ERROR, please provide a file with the valid extention \".brf\"...");
        // report error in UI and stop
        scanResults.setText(scanResults.getText() + "ERROR: File extention must be \".brf\" \n");
        return;
      }
      // profile size is generally < 30 kb, so set max size to 100 kb
      if (filesize > 100000) {
        System.out.println("Brouter  file size too big!!!");
        // report error in UI and stop
        scanResults.setText(scanResults.getText() + "ERROR: File size too big !!! \n");
        return;
      }

      ContentResolver cr = getContentResolver();
      String Profile_code = "";
      try {
        //    try to read the file
        InputStream input = cr.openInputStream(dataUri);

        BufferedReader reader = new BufferedReader(
          new InputStreamReader(input));
        Profile_code = reader.lines().collect(Collectors.joining(
          System.getProperty("line.separator"))).toString();
        System.out.println("Brouter Profile_CODE=" + Profile_code);

        // consistency check
        if (Profile_code.indexOf("highway=") == -1 || (Profile_code.indexOf("costfactor") == -1) || (Profile_code.indexOf("---context:global") == -1)) {
          System.out.println("Brouter  ERROR, file content is not a valid profile for Brouter!, please provide a valid profile ...");
          // report error in UI and stop
          scanResults.setText(scanResults.getText() + "ERROR: this file is not a valid brouter-profile!!!! \n");
          return;
        }
      } catch (IOException e) {
        System.out.println(e);
        // report error in UI and stop
        scanResults.setText(scanResults.getText() + "ERROR: " + e + "/n");
      }

      String baseDir = null;
      InputStream configInput = null;
      try
      {
        configInput = openFileInput( "config15.dat" );
        BufferedReader br = new BufferedReader( new InputStreamReader( configInput ) );
        baseDir = br.readLine();
        System.out.println("Brouter  baseDir=" + baseDir );
      }
      catch (Exception e)
      {
        System.out.println("Brouter  exception by read config15.dat  " + e );
        scanResults.setText(scanResults.getText() + "ERROR: " + e + " /n");
      }
      finally
      {
        if ( configInput != null ) try { configInput.close(); } catch (Exception ee) {}
      }

      //  now save profile as file in ./brouter/profiles2/...
      try {
        File path = getExternalFilesDir(null);
        File file = new File(baseDir, "brouter/profiles2/" + filename);
        FileOutputStream stream = new FileOutputStream(file);
        stream.write(Profile_code.getBytes());
        stream.close();
        System.out.println("Brouter: profile was installed in ./brouter/profiles2 !!!");
        // report success in UI and stop
        scanResults.setText(scanResults.getText() + "Profile successfully imported into:\n" + baseDir + "brouter/profiles2/" + filename + " \n\nIt can be used now in the same way as a basis profile! ");
        return;
      } catch (IOException e) {
        System.out.println("Exception, File write failed: " + e.toString());
        // report error in UI and stop
        scanResults.setText(scanResults.getText() + "ERROR: " + e + " /n");
        return;
      }
    }
  }
}
