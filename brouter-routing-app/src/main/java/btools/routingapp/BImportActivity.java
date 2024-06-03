package btools.routingapp;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.format.Formatter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

public class BImportActivity extends AppCompatActivity {
  // profile size is generally < 30 kb, so set max size to 100 kb
  private static final int MAX_PROFILE_SIZE = 100000;
  private EditText mTextFilename;
  private Button mButtonImport;
  private String mProfileData;
  private EditText mTextProfile;

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    setContentView(R.layout.import_intent);
    mTextFilename = findViewById(R.id.editTextFilename);
    mButtonImport = findViewById(R.id.buttonImport);
    mButtonImport.setEnabled(false);
    mButtonImport.setOnClickListener(view -> importProfile());
    mTextProfile = findViewById(R.id.editTextProfile);

    Intent intent = getIntent();
    String action = intent.getAction();
    if (Intent.ACTION_VIEW.equals(action)) {
      parseIntent(intent);
    }
  }

  private boolean isBuiltinProfile(String filename) {
    String[] builtinProfiles = new ServerConfig(this).getProfiles();
    for (String builtinProfile : builtinProfiles) {
      if (filename.equals(builtinProfile)) {
        return true;
      }
    }
    return false;
  }

  private boolean isInvalidProfileFilename(String filename) {
    return !filename.endsWith(".brf");
  }

  private void parseIntent(Intent intent) {
    if (intent.getData() == null) {
      return;
    }

    StringBuilder resultMessage = new StringBuilder();
    Uri dataUri = intent.getData();

    // by some apps (bluemail) the file name must be "extracted" from the URI, as example with the following code
    // see https://stackoverflow.com/questions/14364091/retrieve-file-path-from-caught-downloadmanager-intent
    // URI example ==>  dat=content://me.bluemail.mail.attachmentprovider/a2939069-76b5-44e4-8cbd-94485d0fd4ff/cc32b61d-97a6-4871-b67f-945d1d1d43c8/VIEW
    String filename = null;
    long filesize = 0L;

    try (Cursor cursor = this.getContentResolver().query(intent.getData(), new String[]{
      OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
      if (cursor != null && cursor.moveToFirst()) {
        filename = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME));
        filesize = cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE));
      }
    } catch (Exception e) {
      resultMessage.append("ERROR: File not accessible\n");
      displayMessage(resultMessage.toString());
      return;
    }

    // is the file extention ".brf" in the file name
    if (filename == null || isInvalidProfileFilename(filename)) {
      resultMessage.append("ERROR: File extention must be \".brf\"\n");
      displayMessage(resultMessage.toString());
      return;
    }

    if (filesize > MAX_PROFILE_SIZE) {
      String errorMessage = String.format("ERROR: File size (%s) exceeds limit (%s)\n",
        Formatter.formatFileSize(this, filesize),
        Formatter.formatFileSize(this, MAX_PROFILE_SIZE));
      displayMessage(errorMessage);
      return;
    }

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
      mProfileData = sb.toString();
    } catch (IOException e) {
      resultMessage.append(String.format("ERROR: failed to load profile content (%s)", e.getMessage()));
      displayMessage(resultMessage.toString());
    }

    if (!mProfileData.contains("---context:global") || (!mProfileData.contains("---context:way"))) {
      resultMessage.append("ERROR: this file is not a valid brouter-profile\n");
      displayMessage(resultMessage.toString());
      return;
    }

    mTextFilename.setText(filename);
    mTextProfile.setText(mProfileData);
    mButtonImport.setEnabled(true);
  }

  void displayMessage(String message) {
    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
  }

  boolean importProfile() {
    String filename = mTextFilename.getText().toString();
    if (isInvalidProfileFilename(filename)) {
      displayMessage("ERROR: File extention must be \".brf\"\n");
      return false;
    } else if (isBuiltinProfile(filename)) {
      displayMessage("ERROR: Built-in profile exists\n");
      return false;
    }

    writeProfile(filename, mProfileData);
    return true;
  }

  void writeProfile(String filename, String profileData) {
    File baseDir = ConfigHelper.getBaseDir(this);

    try {
      File file = new File(baseDir, "brouter/profiles2/" + filename);
      FileOutputStream stream = new FileOutputStream(file);
      stream.write(profileData.getBytes());
      stream.close();
      displayMessage("Profile successfully imported");
    } catch (IOException e) {
      displayMessage(String.format("Profile import failed: %s", e.getMessage()));
    }
  }

}
