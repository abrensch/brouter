package btools.routingapp;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.format.Formatter;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

public class BImportActivity extends Activity {
  // profile size is generally < 30 kb, so set max size to 100 kb
  private static final int MAX_PROFILE_SIZE = 100000;
  TextView mImportResultView;

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    setContentView(R.layout.import_intent);
    mImportResultView = findViewById(R.id.Info_brouter);

    Intent intent = getIntent();
    String action = intent.getAction();
    if (Intent.ACTION_VIEW.equals(action)) {
      importProfile(intent);
    }
  }

  private void importProfile(Intent intent) {
    StringBuilder resultMessage = new StringBuilder();
    if (intent.getData() == null) {
      return;
    }
    setContentView(R.layout.import_intent);
    mImportResultView = findViewById(R.id.Info_brouter);
    mImportResultView.setText("Start importing profile: \n");

    Uri dataUri = intent.getData();
    System.out.println("Brouter DATA=" + dataUri);

    // by some apps (bluemail) the file name must be "extracted" from the URI, as example with the following code
    // see https://stackoverflow.com/questions/14364091/retrieve-file-path-from-caught-downloadmanager-intent
    // URI example ==>  dat=content://me.bluemail.mail.attachmentprovider/a2939069-76b5-44e4-8cbd-94485d0fd4ff/cc32b61d-97a6-4871-b67f-945d1d1d43c8/VIEW
    String filename = null;
    long filesize = 0L;
    try (Cursor cursor = this.getContentResolver().query(intent.getData(), new String[]{
      OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
      if (cursor != null && cursor.moveToFirst()) {
        filename = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME));
        filesize = cursor.getLong(cursor.getColumnIndex(OpenableColumns.SIZE));
      }
    }
    System.out.println("Brouter filename=" + filename + "\n file size=" + filesize);
    resultMessage.append("File name=").append(filename).append("\n");
    resultMessage.append("File size=").append(Formatter.formatFileSize(this, filesize)).append(" bytes\n");
    mImportResultView.setText(resultMessage);
    //  is the file extention ".brf" in the file name
    if (filename == null || !filename.endsWith(".brf")) {
      resultMessage.append("ERROR: File extention must be \".brf\"\n");
      mImportResultView.setText(resultMessage);
      return;
    }

    if (filesize > MAX_PROFILE_SIZE) {
      resultMessage.append("ERROR: File size exceeds limit (").append(Formatter.formatFileSize(this, MAX_PROFILE_SIZE)).append(")\n");
      mImportResultView.setText(resultMessage);
      return;
    }

    String profileData = "";
    try (
      InputStream inputStream = getContentResolver().openInputStream(dataUri);
      BufferedReader br = new BufferedReader(new InputStreamReader(inputStream))) {
      StringBuilder sb = new StringBuilder();
      String line = br.readLine();

      while (line != null) {
        sb.append(line);
        sb.append(System.getProperty("line.separator"));
        line = br.readLine();
      }
      profileData = sb.toString();
    } catch (IOException e) {
      System.out.println(e);
      resultMessage.append("ERROR: " + e + "\n");
      mImportResultView.setText(resultMessage);
    }

    if (!profileData.contains("highway=") || (!profileData.contains("costfactor")) || (!profileData.contains("---context:global"))) {
      resultMessage.append("ERROR: this file is not a valid brouter-profile\n");
      mImportResultView.setText(resultMessage);
      return;
    }

    writeProfile(filename, profileData);
  }

  void writeProfile(String filename, String profileData) {
    File baseDir = ConfigHelper.getBaseDir(this);

    try {
      File file = new File(baseDir, "brouter/profiles2/" + filename);
      FileOutputStream stream = new FileOutputStream(file);
      stream.write(profileData.getBytes());
      stream.close();
      System.out.println("Brouter: profile was installed in ./brouter/profiles2");
      // report success in UI and stop
      mImportResultView.setText(mImportResultView.getText() + "Profile successfully imported into:\n" + baseDir + "brouter/profiles2/" + filename + " \n\nIt can be used now in the same way as a basis profile! ");
    } catch (IOException e) {
      System.out.println("Exception, File write failed: " + e.toString());
      // report error in UI and stop
      mImportResultView.setText(mImportResultView.getText() + "ERROR: " + e + " \n");
    }
  }

}
