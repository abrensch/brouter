package btools.routingapp;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ServerConfig {
  private static String mServerConfigName = "serverconfig.txt";

  private String mSegmentUrl = "https://brouter.de/brouter/segments4/";
  private String mLookupsUrl = "https://brouter.de/brouter/profiles2/";
  private String mProfilesUrl = "https://brouter.de/brouter/profiles2/";

  private String[] mLookups = new String[]{"lookups.dat"};
  private String[] mProfiles = new String[0];

  public ServerConfig(Context ctx) {
    File configFile = new File(ConfigHelper.getBaseDir(ctx), "/brouter/segments4/" + mServerConfigName);
    readConfigFile(configFile);
  }

  public ServerConfig(Context context, File file) {
    readConfigFile(file);
  }

  private void readConfigFile(File file) {
    if (file.exists()) {
      BufferedReader br = null;
      try {
        br = new BufferedReader(new FileReader(file));
        for (; ; ) {
          String line = br.readLine();
          if (line == null) break;
          if (line.trim().startsWith("segment_url=")) {
            mSegmentUrl = line.substring(12);
          } else if (line.trim().startsWith("lookup_url=")) {
            mLookupsUrl = line.substring(11);
          } else if (line.trim().startsWith("profiles_url=")) {
            mProfilesUrl = line.substring(13);
          } else if (line.trim().startsWith("check_lookup=")) {
            mLookups = line.substring(13).split(",");
          } else if (line.trim().startsWith("check_profiles=")) {
            mProfiles = line.substring(15).split(",");
          }
        }
      } catch (IOException e) {
        e.printStackTrace();
      } finally {
        try {
          if (br != null) br.close();
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }

    }

  }

  public static void checkForUpdate(Context context, File path, String assetZip) {
    if (assetZip != null) {
      writeTmpFromAsset(context, path, assetZip);

      File configFileOld = new File(ConfigHelper.getBaseDir(context), "/brouter/segments4/" + mServerConfigName);
      File configFileNew = new File(ConfigHelper.getBaseDir(context), "/brouter/segments4/" + mServerConfigName + ".tmp");
      if (configFileOld.length() != configFileNew.length()) {
        ServerConfig serverConfigOld = new ServerConfig(context, configFileOld);
        ServerConfig serverConfigNew = new ServerConfig(context, configFileNew);
        if (serverConfigOld.getSegmentUrl().equals(serverConfigNew.getSegmentUrl()) &&
          serverConfigOld.getProfilesUrl().equals(serverConfigNew.getProfilesUrl()) &&
          serverConfigOld.getLookupUrl().equals(serverConfigNew.getLookupUrl())
        ) {
          // replace when servers wasn't changed
          configFileOld.delete();
          configFileNew.renameTo(configFileOld);
        }
      } else {
        configFileNew.delete();
      }
    }
  }

  private static void writeTmpFromAsset(Context context, File path, String assetZip) {
    InputStream is = null;
    try {
      AssetManager assetManager = context.getAssets();
      is = assetManager.open(assetZip);
      ZipInputStream zis = new ZipInputStream(is);
      byte[] data = new byte[1024];
      for (; ; ) {
        ZipEntry ze = zis.getNextEntry();
        if (ze == null)
          break;
        if (ze.isDirectory()) {
          continue;
        }
        String name = ze.getName();
        if (name.equals(mServerConfigName)) {
          File outfile = new File(path, name + ".tmp");
          String canonicalPath = outfile.getCanonicalPath();
          if (canonicalPath.startsWith(path.getCanonicalPath()) &&
            !outfile.exists() &&
            outfile.getParentFile() != null) {
            outfile.getParentFile().mkdirs();
            FileOutputStream fos = new FileOutputStream(outfile);

            for (; ; ) {
              int len = zis.read(data, 0, 1024);
              if (len < 0)
                break;
              fos.write(data, 0, len);
            }
            fos.close();
          }
        }
        zis.closeEntry();
      }
      zis.close();
    } catch (IOException io) {
      throw new RuntimeException("error expanding " + assetZip + ": " + io);
    } finally {
      try {
        if (is != null) is.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  public String getSegmentUrl() {
    return mSegmentUrl;
  }

  public String getLookupUrl() {
    return mLookupsUrl;
  }

  public String getProfilesUrl() {
    return mProfilesUrl;
  }

  public String[] getLookups() {
    return mLookups;
  }

  public String[] getProfiles() {
    return mProfiles;
  }

}
