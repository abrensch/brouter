package btools.routingapp;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

public class ServerConfig {
  private String mSegmentUrl = "https://brouter.de/brouter/segments4/";
  private String mLookupsUrl = "https://brouter.de/brouter/profiles2/";
  private String mProfilesUrl = "https://brouter.de/brouter/profiles2/";

  private String[] mLookups = new String[]{"lookups.dat"};
  private String[] mProfiles = new String[0];

  public ServerConfig(Context ctx) {
    File configFile = new File(ConfigHelper.getBaseDir(ctx), "/brouter/segments4/serverconfig.txt");
    if (configFile.exists()) {
      try {
        BufferedReader br = new BufferedReader(new FileReader(configFile));
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
        br.close();
      } catch (IOException e) {
        e.printStackTrace();
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
