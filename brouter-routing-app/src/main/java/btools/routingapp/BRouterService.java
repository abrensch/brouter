package btools.routingapp;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Base64;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import btools.router.OsmNodeNamed;
import btools.router.RoutingEngine;

public class BRouterService extends Service {

  @Override
  public IBinder onBind(Intent arg0) {
    return myBRouterServiceStub;
  }

  private IBRouterService.Stub myBRouterServiceStub = new IBRouterService.Stub() {
    @Override
    public String getTrackFromParams(Bundle params) throws RemoteException {
      logBundle(params);

      BRouterWorker worker = new BRouterWorker();

      int engineMode = 0;
      if (params.containsKey("engineMode")) {
        engineMode = params.getInt("engineMode", 0);
      }

      // get base dir from private file
      String baseDir = null;
      InputStream configInput = null;
      try {
        configInput = openFileInput("config15.dat");
        BufferedReader br = new BufferedReader(new InputStreamReader(configInput));
        baseDir = br.readLine();
      } catch (Exception e) {
      } finally {
        if (configInput != null)
          try {
            configInput.close();
          } catch (Exception ee) {
          }
      }
      worker.baseDir = baseDir;
      worker.segmentDir = new File(baseDir, "brouter/segments4");
      String errMsg = null;

      if (engineMode == RoutingEngine.BROUTER_ENGINEMODE_ROUTING) {
        String remoteProfile = params.getString("remoteProfile", null);

        if (remoteProfile == null) {
          remoteProfile = checkForTestDummy(baseDir);
        }

        if (remoteProfile != null) {
          errMsg = getConfigForRemoteProfile(worker, baseDir, remoteProfile);
        } else if (params.containsKey("profile")) {
          String profile = params.getString("profile");
          worker.profileName = profile;
          worker.profilePath = baseDir + "/brouter/profiles2/" + profile + ".brf";
          worker.rawTrackPath = baseDir + "/brouter/modes/" + profile + "_rawtrack.dat";
          if (!new File(worker.profilePath).exists()) {
            errMsg = "Profile " + profile + " does not exists";
          } else {
            try {
              readNogos(worker, baseDir);
            } catch (Exception e) {
              errMsg = e.getLocalizedMessage();
            }
          }
        } else {
          errMsg = getConfigFromMode(worker, baseDir, params.getString("v"), params.getString("fast"));
        }
      } else {
        worker.profilePath = baseDir + "/brouter/profiles2/dummy.brf";
      }
      if (errMsg != null) {
        return errMsg;
      }
      // profile is already done
      params.remove("profile");

      boolean canCompress = "true".equals(params.getString("acceptCompressedResult"));
      try {
        String gpxMessage = worker.getTrackFromParams(params);
        if (canCompress && (gpxMessage.startsWith("<") || gpxMessage.startsWith("{"))) {
          try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            baos.write("z64".getBytes(Charset.forName("UTF-8"))); // marker prefix
            OutputStream os = new GZIPOutputStream(baos);
            byte[] ab = gpxMessage.getBytes(Charset.forName("UTF-8")); // StandardCharsets.UTF_8
            gpxMessage = null;
            os.write(ab);
            ab = null;
            os.close();
            gpxMessage = Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT);
            return gpxMessage;
          } catch (Exception e) {
            return "error compressing result";
          }
        }
        return gpxMessage;
      } catch (IllegalArgumentException iae) {
        return iae.getMessage();
      }
    }

    private String getConfigFromMode(BRouterWorker worker, String baseDir, String mode, String fast) {
      boolean isFast = "1".equals(fast) || "true".equals(fast) || "yes".equals(fast);
      String mode_key = mode + "_" + (isFast ? "fast" : "short");

      BufferedReader br = null;
      try {
        String modesFile = baseDir + "/brouter/modes/serviceconfig.dat";
        br = new BufferedReader(new FileReader(modesFile));
        for (; ; ) {
          String line = br.readLine();
          if (line == null)
            break;
          ServiceModeConfig smc = new ServiceModeConfig(line);
          if (!smc.mode.equals(mode_key))
            continue;
          worker.profileName = smc.profile;
          worker.profileParams = (smc.params.equals("noparams") ? null : smc.params);
          worker.profilePath = baseDir + "/brouter/profiles2/" + smc.profile + ".brf";
          worker.rawTrackPath = baseDir + "/brouter/modes/" + mode_key + "_rawtrack.dat";

          readNogos(worker, baseDir);

          // veto nogos by profiles veto list
          List<OsmNodeNamed> nogoList = new ArrayList<>(worker.nogoList);
          worker.nogoList.clear();
          for (OsmNodeNamed nogo : nogoList) {
            if (!smc.nogoVetos.contains(nogo.ilon + "," + nogo.ilat)) {
              worker.nogoList.add(nogo);
            }
          }
          List<OsmNodeNamed> nogoPolygonsList = new ArrayList<>(worker.nogoPolygonsList);
          worker.nogoPolygonsList.clear();
          for (OsmNodeNamed nogo : nogoPolygonsList) {
            if (!smc.nogoVetos.contains(nogo.ilon + "," + nogo.ilat)) {
              worker.nogoPolygonsList.add(nogo);
            }
          }

          return null;
        }
      } catch (Exception e) {
        return "no brouter service config found, mode " + mode_key;
      } finally {
        if (br != null)
          try {
            br.close();
          } catch (Exception ee) {
          }
      }
      return "no brouter service config found for mode " + mode_key;
    }

    private String getConfigForRemoteProfile(BRouterWorker worker, String baseDir, String remoteProfile) {
      worker.profileName = "remote";
      worker.profilePath = baseDir + "/brouter/profiles2/remote.brf";
      worker.rawTrackPath = baseDir + "/brouter/modes/remote_rawtrack.dat";

      // store profile only if not identical (to preserve timestamp)
      byte[] profileBytes = remoteProfile.getBytes();
      File profileFile = new File(worker.profilePath);

      try {
        readNogos(worker, baseDir);

        if (!fileEqual(profileBytes, profileFile)) {
          OutputStream os = null;
          try {
            os = new FileOutputStream(profileFile);
            os.write(profileBytes);
          } finally {
            if (os != null)
              try {
                os.close();
              } catch (IOException io) {
              }
          }
        }
      } catch (Exception e) {
        return "error caching remote profile: " + e;
      }
      return null;
    }

    private void readNogos(BRouterWorker worker, String baseDir) throws Exception {
      // add nogos from waypoint database
      CoordinateReader cor = CoordinateReader.obtainValidReader(baseDir, true);
      worker.nogoList = new ArrayList<>(cor.nogopoints);
      worker.nogoPolygonsList = new ArrayList<>();
    }

    private boolean fileEqual(byte[] fileBytes, File file) throws Exception {
      if (!file.exists()) {
        return false;
      }
      int nbytes = fileBytes.length;
      int pos = 0;
      int blen = 8192;
      byte[] buf = new byte[blen];
      InputStream is = null;
      try {
        is = new FileInputStream(file);
        while (pos < nbytes) {
          int len = is.read(buf, 0, blen);
          if (len <= 0)
            return false;
          if (pos + len > nbytes)
            return false;
          for (int j = 0; j < len; j++) {
            if (fileBytes[pos++] != buf[j]) {
              return false;
            }
          }
        }
        return true;
      } finally {
        if (is != null)
          try {
            is.close();
          } catch (IOException io) {
          }
      }
    }

    private String checkForTestDummy(String baseDir) {
      File testdummy = new File(baseDir + "/brouter/profiles2/remotetestdummy.brf");
      if (!testdummy.exists())
        return null;
      BufferedReader br = null;
      StringBuilder sb = new StringBuilder();
      try {
        br = new BufferedReader(new FileReader(testdummy));
        for (; ; ) {
          String line = br.readLine();
          if (line == null)
            break;
          sb.append(line).append('\n');
        }
        return sb.toString();
      } catch (Exception e) {
        throw new RuntimeException("error reading " + testdummy);
      } finally {
        if (br != null)
          try {
            br.close();
          } catch (Exception ee) {
          }
      }
    }

    @SuppressWarnings("deprecation")
    private void logBundle(Bundle params) {
      if (AppLogger.isLogging()) {
        for (String k : params.keySet()) {
          Object val = "remoteProfile".equals(k) ? "<..cut..>" : params.get(k);
          String desc = "key=" + k + (val == null ? "" : " class=" + val.getClass() + " val=");
          if (val instanceof double[]) {
            desc += Arrays.toString(params.getDoubleArray(k));
          } else {
            desc += val.toString();
          }
          AppLogger.log(desc);
        }
      }
    }

  };

  @Override
  public void onCreate() {
    super.onCreate();
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
  }

  // This is the old onStart method that will be called on the pre-2.0
  // platform. On 2.0 or later we override onStartCommand() so this
  // method will not be called.
  @Override
  @SuppressWarnings("deprecation")
  public void onStart(Intent intent, int startId) {
    handleStart(intent, startId);
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    handleStart(intent, startId);
    return START_STICKY;
  }

  void handleStart(Intent intent, int startId) {
  }
}
