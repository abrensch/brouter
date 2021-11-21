package btools.routingapp;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.File;

import android.content.Context;

public class ConfigHelper {
  public static File getBaseDir(Context ctx) {
    // get base dir from private file

    try (InputStream configInput = ctx.openFileInput("config15.dat");
         InputStreamReader isr = new InputStreamReader(configInput);
         BufferedReader br = new BufferedReader(isr)) {
      return new File(br.readLine());
    } catch (Exception e) {
      return null;
    }
  }

  public static void writeBaseDir(Context ctx, File baseDir) {
    try (OutputStream configOutput = ctx.openFileOutput("config15.dat", Context.MODE_PRIVATE);
         OutputStreamWriter osw = new OutputStreamWriter(configOutput);
         BufferedWriter bw = new BufferedWriter(osw)) {
      bw.write(baseDir.getAbsolutePath());
      bw.write('\n');
    } catch (Exception e) { /* ignore */ }
  }
}
