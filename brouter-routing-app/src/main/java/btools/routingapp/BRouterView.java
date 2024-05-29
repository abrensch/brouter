package btools.routingapp;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import btools.expressions.BExpressionMetaData;
import btools.mapaccess.OsmNode;
import btools.router.OsmNodeNamed;
import btools.router.OsmNogoPolygon;
import btools.router.OsmNogoPolygon.Point;
import btools.router.OsmTrack;
import btools.router.RoutingContext;
import btools.router.RoutingEngine;
import btools.router.RoutingHelper;
import btools.util.CheapRuler;

public class BRouterView extends View {

  private final int memoryClass;
  RoutingEngine cr;
  private int imgw;
  private int imgh;
  private int centerLon;
  private int centerLat;
  private double scaleLon;  // ilon -> pixel
  private double scaleLat;  // ilat -> pixel
  private double scaleMeter2Pixel;
  private List<OsmNodeNamed> wpList;
  private List<OsmNodeNamed> nogoList;
  private List<OsmNodeNamed> nogoVetoList;
  private OsmTrack rawTrack;
  private File retryBaseDir;
  private File modesDir;
  private File tracksDir;
  private File segmentDir;
  private File profileDir;
  private String profileName;
  private boolean waitingForSelection = false;
  private boolean waitingForMigration = false;
  private String rawTrackPath;
  private String oldMigrationPath;
  private String trackOutfile;
  private boolean needsViaSelection;
  private boolean needsNogoSelection;
  private boolean needsWaypointSelection;
  private long lastDataTime = System.currentTimeMillis();
  private CoordinateReader cor;
  private int[] imgPixels;
  private long lastTs = System.currentTimeMillis();
  private long startTime = 0L;

  public BRouterView(Context context, int memoryClass) {
    super(context);
    this.memoryClass = memoryClass;
  }

  public void stopRouting() {
    if (cr != null) cr.terminate();
  }

  public void init(boolean silent) {
    try {
      // get base dir from private file
      File baseDir = ConfigHelper.getBaseDir(getContext());
      // check if valid
      boolean bdValid = false;
      if (baseDir != null) {
        bdValid = baseDir.isDirectory();
        File brd = new File(baseDir, "brouter");
        if (brd.isDirectory()) {
          if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
            !brd.getAbsolutePath().contains("/Android/media/btools.routingapp")) {

            // don't ask twice
            String version = "v" + getContext().getString(R.string.app_version);
            File vFile = new File(brd, "profiles2/" + version);
            if (vFile.exists()) {
              startSetup(baseDir, false, silent);
              return;
            }
            String message = "(previous basedir " + baseDir + " has to migrate )";

            ((BRouterActivity) getContext()).selectBasedir(((BRouterActivity) getContext()).getStorageDirectories(), message);
            waitingForSelection = true;
            waitingForMigration = true;
            oldMigrationPath = brd.getAbsolutePath();
          } else {
            startSetup(baseDir, false, silent);
          }
          return;
        }
      }
      String message = baseDir == null ? "(no basedir configured previously)" : "(previous basedir " + baseDir
        + (bdValid ? " does not contain 'brouter' subfolder)" : " is not valid)");

      ((BRouterActivity) getContext()).selectBasedir(((BRouterActivity) getContext()).getStorageDirectories(), message);
      waitingForSelection = true;
    } catch (Exception e) {
      String msg = e instanceof IllegalArgumentException ? e.getMessage() : e.toString();

      AppLogger.log(msg);
      AppLogger.log(AppLogger.formatThrowable(e));

      ((BRouterActivity) getContext()).showErrorMessage(msg);
    }
  }

  public void startSetup(File baseDir, boolean storeBasedir, boolean silent) {
    if (baseDir == null) {
      baseDir = retryBaseDir;
      retryBaseDir = null;
    }

    if (storeBasedir) {
      File td = new File(baseDir, "brouter");
      try {
        td.mkdirs();
      } catch (Exception e) {
        Log.d("BRouterView", "Error creating base directory: " + e.getMessage());
        e.printStackTrace();
      }

      if (!td.isDirectory()) {
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_DENIED) {
          retryBaseDir = baseDir;
          ActivityCompat.requestPermissions((BRouterActivity) getContext(), new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 0);
        } else {
          ((BRouterActivity) getContext()).selectBasedir(((BRouterActivity) getContext()).getStorageDirectories(), "Cannot access " + baseDir.getAbsolutePath() + "; select another");
        }
        return;
      }
      ConfigHelper.writeBaseDir(getContext(), baseDir);
    }
    try {
      cor = null;

      String basedir = baseDir.getAbsolutePath();
      AppLogger.log("using basedir: " + basedir);

      populateBasedir(basedir);

      // new init is done move old files
      if (waitingForMigration) {
        Log.d("BR", "path " + oldMigrationPath + " " + basedir);
        Thread t = new Thread(new Runnable() {
          @Override
          public void run() {
            if (!oldMigrationPath.equals(basedir + "/brouter"))
              moveFolders(oldMigrationPath, basedir + "/brouter");
          }});
        t.start();
        try {
          t.join(500);
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
        waitingForMigration = false;
      }

      cor = CoordinateReader.obtainValidReader(basedir);

      wpList = cor.waypoints;
      nogoList = cor.nogopoints;
      nogoVetoList = new ArrayList<>();

      needsViaSelection = wpList.size() > 2;
      needsNogoSelection = nogoList.size() > 0;
      needsWaypointSelection = wpList.size() == 0;

      if (cor.tracksdir != null) {
        tracksDir = new File(cor.basedir, cor.tracksdir);
        assertDirectoryExists("track directory", tracksDir, null, null);
      }
      if (tracksDir == null) {
        tracksDir = new File(basedir, "brouter"); // fallback
      }

      String[] fileNames = profileDir.list();
      ArrayList<String> profiles = new ArrayList<>();

      boolean lookupsFound = false;
      if (fileNames != null) {
        for (String fileName : fileNames) {
          if (fileName.endsWith(".brf")) {
            profiles.add(fileName.substring(0, fileName.length() - 4));
          }
          if (fileName.equals("lookups.dat"))
            lookupsFound = true;
        }
      }

      // add a "last timeout" dummy profile
      File lastTimeoutFile = new File(modesDir + "/timeoutdata.txt");
      long lastTimeoutTime = lastTimeoutFile.lastModified();
      if (lastTimeoutTime > 0 &&
        lastTimeoutFile.length() > 0 &&
        System.currentTimeMillis() - lastTimeoutTime < 1800000) {
        BufferedReader br = new BufferedReader(new FileReader(lastTimeoutFile));
        String repeatProfile = br.readLine();
        br.close();
        profiles.add(0, "<repeat:" + repeatProfile + ">");
      }

      if (!lookupsFound) {
        throw new IllegalArgumentException("The profile-directory " + profileDir + " does not contain the lookups.dat file."
          + " see brouter.de/brouter for setup instructions.");
      }
      if (profiles.size() == 0) {
        throw new IllegalArgumentException("The profile-directory " + profileDir + " contains no routing profiles (*.brf)."
          + " see brouter.de/brouter for setup instructions.");
      }
      if (silent) {
        Intent intent = new Intent(getContext(), BInstallerActivity.class);
        getContext().startActivity(intent);
        return;
      };

      if (!RoutingHelper.hasDirectoryAnyDatafiles(segmentDir)) {
        ((BRouterActivity) getContext()).startDownloadManager();
        waitingForSelection = true;
        return;
      }
      ((BRouterActivity) getContext()).selectProfile(profiles.toArray(new String[0]));
    } catch (Exception e) {
      String msg = e instanceof IllegalArgumentException ? e.getMessage()
        + (cor == null ? "" : " (coordinate-source: " + cor.basedir + cor.rootdir + ")") : e.toString();

      AppLogger.log(msg);
      AppLogger.log(AppLogger.formatThrowable(e));

      ((BRouterActivity) getContext()).showErrorMessage(msg + "\n" + AppLogger.formatThrowable(e));
    }
    waitingForSelection = true;
  }

  private void populateBasedir(String basedir) {
    String version = "v" + getContext().getString(R.string.app_version);

    // create missing directories
    assertDirectoryExists("project directory", new File(basedir, "brouter"), null, null);
    segmentDir = new File(basedir, "/brouter/segments4");
    if (assertDirectoryExists("data directory", segmentDir, "segments4.zip", null)) {
      ConfigMigration.tryMigrateStorageConfig(
        new File(basedir + "/brouter/segments3/storageconfig.txt"),
        new File(basedir + "/brouter/segments4/storageconfig.txt"));
    } else {
      ServerConfig.checkForUpdate(getContext(), segmentDir, "segments4.zip");
    }
    profileDir = new File(basedir, "brouter/profiles2");
    assertDirectoryExists("profile directory", profileDir, "profiles2.zip", version);
    modesDir = new File(basedir, "/brouter/modes");
    assertDirectoryExists("modes directory", modesDir, "modes.zip", version);
    assertDirectoryExists("readmes directory", new File(basedir, "brouter/readmes"), "readmes.zip", version);

    File inputDir = new File(basedir, "brouter/import");
    assertDirectoryExists("input directory", inputDir, null, version);
  }

  private void moveFolders(String oldMigrationPath, String basedir) {
    File oldDir = new File(oldMigrationPath);
    File[] oldFiles = oldDir.listFiles();
    if (oldFiles != null) {
      for (File f : oldFiles) {
        if (f.isDirectory()) {
          int index = f.getAbsolutePath().lastIndexOf("/");
          String tmpdir = basedir + f.getAbsolutePath().substring(index);
          moveFolders(f.getAbsolutePath(), tmpdir);
        } else {
          if (!f.getName().startsWith("v1.6")) {
            moveFile(oldMigrationPath, f.getName(), basedir);
          }
        }

      }
    }
  }

  private void copyFile(String inputPath, String inputFile, String outputPath) {
    InputStream in;
    OutputStream out;

    try {
      //create output directory if it doesn't exist
      File dir = new File(outputPath);
      if (!dir.exists()) {
        dir.mkdirs();
      }

      in = new FileInputStream(new File(inputPath, inputFile));
      out = new FileOutputStream(new File(outputPath, inputFile));

      byte[] buffer = new byte[1024];
      int read;
      while ((read = in.read(buffer)) != -1) {
        out.write(buffer, 0, read);
      }
      in.close();

      // write the output file
      out.flush();
      out.close();

    } catch (FileNotFoundException fileNotFoundException) {
      Log.e("tag", fileNotFoundException.getMessage());
    } catch (Exception e) {
      Log.e("tag", e.getMessage());
    }
  }

  private void moveFile(String inputPath, String inputFile, String outputPath) {
    copyFile(inputPath, inputFile, outputPath);
    // delete the original file
    new File(inputPath, inputFile).delete();
  }

  public boolean hasUpToDateLookups() {
    BExpressionMetaData meta = new BExpressionMetaData();
    meta.readMetaData(new File(profileDir, "lookups.dat"));
    return meta.lookupVersion == 10;
  }

  public void continueProcessing() {
    waitingForSelection = false;
    invalidate();
  }

  public void updateViaList(Set<String> selectedVias) {
    ArrayList<OsmNodeNamed> filtered = new ArrayList<>(wpList.size());
    for (OsmNodeNamed n : wpList) {
      String name = n.name;
      if ("from".equals(name) || "to".equals(name) || selectedVias.contains(name))
        filtered.add(n);
    }
    wpList = filtered;
  }

  public void updateNogoList(boolean[] enabled) {
    for (int i = nogoList.size() - 1; i >= 0; i--) {
      if (enabled[i]) {
        nogoVetoList.add(nogoList.get(i));
        nogoList.remove(i);
      }
    }
  }

  public void pickWaypoints() {
    String msg = null;

    if (cor.allpoints == null) {
      try {
        cor.readAllPoints();
      } catch (Exception e) {
        msg = getContext().getString(R.string.msg_read_wpt_error)+  ": " + e;
      }

      int size = cor.allpoints.size();
      if (size < 1)
        msg = getContext().getString(R.string.msg_no_wpt);
      if (size > 1000)
        msg = String.format(getContext().getString(R.string.msg_too_much_wpts), size);
    }

    if (msg != null) {
      ((BRouterActivity) getContext()).showErrorMessage(msg);
    } else {
      String[] wpts = new String[cor.allpoints.size()];
      int i = 0;
      for (OsmNodeNamed wp : cor.allpoints)
        wpts[i++] = wp.name;
      ((BRouterActivity) getContext()).selectWaypoint(wpts);
    }
  }

  public void updateWaypointList(String waypoint) {
    for (OsmNodeNamed wp : cor.allpoints) {
      if (wp.name.equals(waypoint)) {
        if (wp.ilat != 0 || wp.ilon != 0) {
          int nwp = wpList.size();
          if (nwp == 0 || wpList.get(nwp - 1) != wp) {
            wpList.add(wp);
          }
        }
        return;
      }
    }
  }

  public void finishWaypointSelection() {
    needsWaypointSelection = false;
  }

  private List<OsmNodeNamed> readWpList(BufferedReader br, boolean isNogo) throws Exception {
    int cnt = Integer.parseInt(br.readLine());
    List<OsmNodeNamed> res = new ArrayList<>(cnt);
    for (int i = 0; i < cnt; i++) {
      OsmNodeNamed wp = OsmNodeNamed.decodeNogo(br.readLine());
      wp.isNogo = isNogo;
      res.add(wp);
    }
    return res;
  }

  public void startProcessing(String profile) {
    rawTrackPath = null;
    if (profile.startsWith("<repeat")) {
      needsViaSelection = needsNogoSelection = needsWaypointSelection = false;
      try {
        File lastTimeoutFile = new File(modesDir + "/timeoutdata.txt");
        BufferedReader br = new BufferedReader(new FileReader(lastTimeoutFile));
        profile = br.readLine();
        rawTrackPath = br.readLine();
        wpList = readWpList(br, false);
        nogoList = readWpList(br, true);
        br.close();
      } catch (Exception e) {
        AppLogger.log(AppLogger.formatThrowable(e));
        ((BRouterActivity) getContext()).showErrorMessage(e.toString());
      }
    } else if ("remote".equals(profileName)) {
      rawTrackPath = modesDir + "/remote_rawtrack.dat";
    }

    String profilePath = profileDir + "/" + profile + ".brf";
    profileName = profile;

    if (needsViaSelection) {
      needsViaSelection = false;
      String[] availableVias = new String[wpList.size() - 2];
      for (int viaidx = 0; viaidx < wpList.size() - 2; viaidx++)
        availableVias[viaidx] = wpList.get(viaidx + 1).name;
      ((BRouterActivity) getContext()).selectVias(availableVias);
      return;
    }

    if (needsNogoSelection) {
      needsNogoSelection = false;
      ((BRouterActivity) getContext()).selectNogos(nogoList);
      return;
    }

    if (needsWaypointSelection) {
      StringBuilder msg;
      if (wpList.size() == 0) {
        msg = new StringBuilder(getContext().getString(R.string.msg_no_wpt_selection) + "(coordinate-source: " + cor.basedir + cor.rootdir + ")");
      } else {
        msg = new StringBuilder(getContext().getString(R.string.msg_wpt_selection));
        for (int i = 0; i < wpList.size(); i++)
          msg.append(i > 0 ? "->" : "").append(wpList.get(i).name);
      }
      ((BRouterActivity) getContext()).showResultMessage(getContext().getString(R.string.title_action), msg.toString(), wpList.size());
      return;
    }

    try {
      waitingForSelection = false;

      RoutingContext rc = new RoutingContext();

      rc.localFunction = profilePath;
      rc.turnInstructionMode = cor.getTurnInstructionMode();

      int plain_distance = 0;
      int maxlon = Integer.MIN_VALUE;
      int minlon = Integer.MAX_VALUE;
      int maxlat = Integer.MIN_VALUE;
      int minlat = Integer.MAX_VALUE;

      OsmNode prev = null;
      for (OsmNode n : wpList) {
        maxlon = Math.max(n.ilon, maxlon);
        minlon = Math.min(n.ilon, minlon);
        maxlat = Math.max(n.ilat, maxlat);
        minlat = Math.min(n.ilat, minlat);
        if (prev != null) {
          plain_distance += n.calcDistance(prev);
        }
        prev = n;
      }
      toast("Plain distance = " + plain_distance / 1000. + " km");

      centerLon = (maxlon + minlon) / 2;
      centerLat = (maxlat + minlat) / 2;

      double[] lonlat2m = CheapRuler.getLonLatToMeterScales(centerLat);
      double dlon2m = lonlat2m[0];
      double dlat2m = lonlat2m[1];
      double difflon = (maxlon - minlon) * dlon2m;
      double difflat = (maxlat - minlat) * dlat2m;

      scaleLon = imgw / (difflon * 1.5);
      scaleLat = imgh / (difflat * 1.5);
      scaleMeter2Pixel = Math.min(scaleLon, scaleLat);
      scaleLon = scaleMeter2Pixel * dlon2m;
      scaleLat = scaleMeter2Pixel * dlat2m;

      startTime = System.currentTimeMillis();
      RoutingContext.prepareNogoPoints(nogoList);
      rc.nogopoints = nogoList;

      rc.memoryclass = memoryClass;
      if (memoryClass < 16) {
        rc.memoryclass = 16;
      } else if (memoryClass > 256) {
        rc.memoryclass = 256;
      }


      // for profile remote, use ref-track logic same as service interface
      rc.rawTrackPath = rawTrackPath;

      cr = new RoutingEngine(tracksDir.getAbsolutePath() + "/brouter", null, segmentDir, wpList, rc);
      cr.start();
      invalidate();

    } catch (Exception e) {
      String msg = e instanceof IllegalArgumentException ? e.getMessage() : e.toString();
      toast(msg);
    }
  }

  private boolean assertDirectoryExists(String message, File path, String assetZip, String versionTag) {
    boolean exists = path.exists();
    if (!exists) {
      path.mkdirs();
    }
    if (versionTag != null) {
      File vtag = new File(path, versionTag);
      try {
        exists = !vtag.createNewFile();
      } catch (IOException ignored) {
      } // well..
    }

    if (!exists) {
      // default contents from assets archive
      if (assetZip != null) {
        try {
          AssetManager assetManager = getContext().getAssets();
          InputStream is = assetManager.open(assetZip);
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
            File outfile = new File(path, name);
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
          zis.close();
          is.close();
          return true;
        } catch (IOException io) {
          throw new RuntimeException("error expanding " + assetZip + ": " + io);
        }

      }
    }

    if (!path.exists() || !path.isDirectory())
      throw new IllegalArgumentException(message + ": " + path + " cannot be created");
    return false;
  }

  private void paintPosition(int ilon, int ilat, int color, int with) {
    int lon = ilon - centerLon;
    int lat = ilat - centerLat;
    int x = imgw / 2 + (int) (scaleLon * lon);
    int y = imgh / 2 - (int) (scaleLat * lat);
    for (int nx = x - with; nx <= x + with; nx++)
      for (int ny = y - with; ny <= y + with; ny++) {
        if (nx >= 0 && nx < imgw && ny >= 0 && ny < imgh) {
          imgPixels[nx + imgw * ny] = color;
        }
      }
  }

  private void paintCircle(Canvas canvas, OsmNodeNamed n, int color, int minradius) {
    int lon = n.ilon - centerLon;
    int lat = n.ilat - centerLat;
    int x = imgw / 2 + (int) (scaleLon * lon);
    int y = imgh / 2 - (int) (scaleLat * lat);

    int ir = (int) (n.radius * scaleMeter2Pixel);
    if (ir > minradius) {
      Paint paint = new Paint();
      paint.setColor(color);
      paint.setStyle(Paint.Style.STROKE);
      canvas.drawCircle((float) x, (float) y, (float) ir, paint);
    }
  }

  private void paintLine(Canvas canvas, final int ilon0, final int ilat0, final int ilon1, final int ilat1, final Paint paint) {
    final int lon0 = ilon0 - centerLon;
    final int lat0 = ilat0 - centerLat;
    final int lon1 = ilon1 - centerLon;
    final int lat1 = ilat1 - centerLat;
    final int x0 = imgw / 2 + (int) (scaleLon * lon0);
    final int y0 = imgh / 2 - (int) (scaleLat * lat0);
    final int x1 = imgw / 2 + (int) (scaleLon * lon1);
    final int y1 = imgh / 2 - (int) (scaleLat * lat1);
    canvas.drawLine((float) x0, (float) y0, (float) x1, (float) y1, paint);
  }

  private void paintPolygon(Canvas canvas, OsmNogoPolygon p, int minradius) {
    final int ir = (int) (p.radius * scaleMeter2Pixel);
    if (ir > minradius) {
      Paint paint = new Paint();
      paint.setColor(Color.RED);
      paint.setStyle(Paint.Style.STROKE);

      Point p0 = p.isClosed ? p.points.get(p.points.size() - 1) : null;

      for (final Point p1 : p.points) {
        if (p0 != null) {
          paintLine(canvas, p0.x, p0.y, p1.x, p1.y, paint);
        }
        p0 = p1;
      }
    }
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    imgw = w;
    imgh = h;
  }

  private void toast(String msg) {
    Toast.makeText(getContext(), msg, Toast.LENGTH_LONG).show();
    lastDataTime += 4000; // give time for the toast before exiting
  }

  @Override
  protected void onDraw(Canvas canvas) {
    try {
      _onDraw(canvas);
    } catch (Throwable t) {
      // on out of mem, try to stop the show
      if (cr != null)
        cr.cleanOnOOM();
      cr = null;
      try {
        Thread.sleep(2000);
      } catch (InterruptedException ignored) {
      }
      ((BRouterActivity) getContext()).showErrorMessage(t.toString());
      waitingForSelection = true;
    }
  }

  private void _onDraw(Canvas canvas) {
    if (waitingForSelection)
      return;

    long currentTs = System.currentTimeMillis();
    long diffTs = currentTs - lastTs;
    long sleeptime = 500 - diffTs;
    while (sleeptime < 200)
      sleeptime += 500;

    try {
      Thread.sleep(sleeptime);
    } catch (InterruptedException ignored) {
    }
    lastTs = System.currentTimeMillis();

    if (cr == null || cr.isFinished()) {
      if (cr != null) {
        if (cr.getErrorMessage() != null) {
          ((BRouterActivity) getContext()).showErrorMessage(cr.getErrorMessage());
        } else {
          String memstat = memoryClass + "mb pathPeak " + ((cr.getPathPeak() + 500) / 1000) + "k";
          String result = String.format(getContext().getString(R.string.msg_status_result),
            getContext().getString(R.string.app_version),
            memstat,
            Double.toString(cr.getDistance() / 1000.),
            Integer.toString(cr.getAscend()),
            Integer.toString(cr.getPlainAscend()),
            cr.getTime());

          rawTrack = cr.getFoundRawTrack();

          // for profile "remote", always persist referencetrack
          if (cr.getAlternativeIndex() == 0 && rawTrackPath != null) {
            writeRawTrackToPath(rawTrackPath);
          }

          String title = getContext().getString(R.string.success);
          if (cr.getAlternativeIndex() > 0)
            title += " / " + cr.getAlternativeIndex() + ". " + getContext().getString(R.string.msg_alternative);

          ((BRouterActivity) getContext()).showResultMessage(title, result, rawTrackPath == null ? -1 : -3);
          trackOutfile = cr.getOutfile();
        }
        cr = null;
        waitingForSelection = true;
        return;
      } else if (System.currentTimeMillis() > lastDataTime) {
        System.exit(0);
      }
    } else {
      lastDataTime = System.currentTimeMillis();
      imgPixels = new int[imgw * imgh];

      int[] openSet = cr.getOpenSet();
      for (int si = 0; si < openSet.length; si += 2) {
        paintPosition(openSet[si], openSet[si + 1], 0xffffff, 1);
      }
      // paint nogos on top (red)
      int minradius = 4;
      for (int ngi = 0; ngi < nogoList.size(); ngi++) {
        OsmNodeNamed n = nogoList.get(ngi);
        int color = 0xff0000;
        paintPosition(n.ilon, n.ilat, color, minradius);
      }

      // paint start/end/vias on top (yellow/green/blue)
      for (int wpi = 0; wpi < wpList.size(); wpi++) {
        OsmNodeNamed n = wpList.get(wpi);
        int color = wpi == 0 ? 0xffff00 : wpi < wpList.size() - 1 ? 0xff : 0xff00;
        paintPosition(n.ilon, n.ilat, color, minradius);
      }

      Bitmap bmp = Bitmap.createBitmap(imgPixels, imgw, imgh, Bitmap.Config.RGB_565);
      canvas.drawBitmap(bmp, 0, 0, null);

      // nogo circles if any
      for (int ngi = 0; ngi < nogoList.size(); ngi++) {
        OsmNodeNamed n = nogoList.get(ngi);
        if (n instanceof OsmNogoPolygon) {
          paintPolygon(canvas, (OsmNogoPolygon) n, minradius);
        } else {
          int color = Color.RED;
          paintCircle(canvas, n, color, minradius);
        }
      }

      Paint paint = new Paint();
      paint.setColor(Color.WHITE);
      paint.setTextSize(20);

      long mseconds = System.currentTimeMillis() - startTime;
      long links = cr.getLinksProcessed();
      long perS = (1000 * links) / mseconds;
      String msg = "Links: " + cr.getLinksProcessed() + " in " + (mseconds / 1000) + "s (" + perS + " l/s)";

      canvas.drawText(msg, 10, 25, paint);
    }
    // and make sure to redraw asap
    invalidate();
  }

  private void writeRawTrackToMode(String mode) {
    writeRawTrackToPath(modesDir + "/" + mode + "_rawtrack.dat");
  }

  private void writeRawTrackToPath(String rawTrackPath) {
    if (rawTrack != null) {
      try {
        rawTrack.writeBinary(rawTrackPath);
      } catch (Exception ignored) {
      }
    } else {
      new File(rawTrackPath).delete();
    }
  }

  public void startConfigureService() {
    String[] modes = new String[]
      {"foot_short", "foot_fast", "bicycle_short", "bicycle_fast", "motorcar_short", "motorcar_fast"};
    boolean[] modesChecked = new boolean[6];

    String msg = "Choose service-modes to configure (" + profileName + " [" + nogoVetoList.size() + "])";

    ((BRouterActivity) getContext()).selectRoutingModes(modes, modesChecked, msg);
  }

  public void configureService(String[] routingModes, boolean[] checkedModes) {
    // read in current config
    TreeMap<String, ServiceModeConfig> map = new TreeMap<>();
    BufferedReader br = null;
    String modesFile = modesDir + "/serviceconfig.dat";
    try {
      br = new BufferedReader(new FileReader(modesFile));
      for (; ; ) {
        String line = br.readLine();
        if (line == null)
          break;
        ServiceModeConfig smc = new ServiceModeConfig(line);
        map.put(smc.mode, smc);
      }
    } catch (Exception ignored) {
    } finally {
      if (br != null)
        try {
          br.close();
        } catch (Exception ignored) {
        }
    }

    // replace selected modes
    for (int i = 0; i < 6; i++) {
      if (checkedModes[i]) {
        writeRawTrackToMode(routingModes[i]);
        ServiceModeConfig sm = map.get(routingModes[i]);
        String s = null;
        String p = null;
        if (sm != null) {
          s = sm.params;
          p = sm.profile;
        }
        if (s == null || !p.equals(profileName)) s = "noparams";
        ServiceModeConfig smc = new ServiceModeConfig(routingModes[i], profileName, s);
        for (OsmNodeNamed nogo : nogoVetoList) {
          smc.nogoVetos.add(nogo.ilon + "," + nogo.ilat);
        }
        map.put(smc.mode, smc);
      }
    }

    // no write new config
    BufferedWriter bw = null;
    StringBuilder msg = new StringBuilder("Mode mapping is now:\n");
    msg.append("( [");
    msg.append(nogoVetoList.size() > 0 ? nogoVetoList.size() : "..").append("] counts nogo-vetos)\n");
    try {
      bw = new BufferedWriter(new FileWriter(modesFile));
      for (ServiceModeConfig smc : map.values()) {
        bw.write(smc.toLine());
        bw.write('\n');
        msg.append(smc).append('\n');
      }
    } catch (Exception ignored) {
    } finally {
      if (bw != null)
        try {
          bw.close();
        } catch (Exception ignored) {
        }
    }
    ((BRouterActivity) getContext()).showModeConfigOverview(msg.toString());
  }

  public void configureServiceParams(String profile, String sparams) {
    List<ServiceModeConfig> map = new ArrayList<>();
    BufferedReader br = null;
    String modesFile = modesDir + "/serviceconfig.dat";
    try {
      br = new BufferedReader(new FileReader(modesFile));
      for (; ; ) {
        String line = br.readLine();
        if (line == null)
          break;
        ServiceModeConfig smc = new ServiceModeConfig(line);
        if (smc.profile.equals(profile)) smc.params = sparams;
        map.add(smc);
      }
    } catch (Exception ignored) {
    } finally {
      if (br != null)
        try {
          br.close();
        } catch (Exception ignored) {
        }
    }

    // now write new config
    BufferedWriter bw = null;
    StringBuilder msg = new StringBuilder("Mode mapping is now:\n");
    msg.append("( [");
    msg.append(nogoVetoList.size() > 0 ? nogoVetoList.size() : "..").append("] counts nogo-vetos)\n");
    try {
      bw = new BufferedWriter(new FileWriter(modesFile));
      for (ServiceModeConfig smc : map) {
        bw.write(smc.toLine());
        bw.write('\n');
        msg.append(smc).append('\n');
      }
    } catch (Exception ignored) {
    } finally {
      if (bw != null)
        try {
          bw.close();
        } catch (Exception ignored) {
        }
    }
    ((BRouterActivity) getContext()).showModeConfigOverview(msg.toString());
  }

  public String getConfigureServiceParams(String profile) {
    List<ServiceModeConfig> map = new ArrayList<>();
    BufferedReader br = null;
    String modesFile = modesDir + "/serviceconfig.dat";
    try {
      br = new BufferedReader(new FileReader(modesFile));
      for (; ; ) {
        String line = br.readLine();
        if (line == null)
          break;
        ServiceModeConfig smc = new ServiceModeConfig(line);
        if (smc.profile.equals(profile)) {
          if (!smc.params.equals("noparams")) return smc.params;
          else return "";
        }
        map.add(smc);
      }
    } catch (Exception ignored) {
    } finally {
      if (br != null)
        try {
          br.close();
        } catch (Exception ignored) {
        }
    }
    // no profile found
    return null;
  }

  public void shareTrack() {
    File track = new File(trackOutfile);
    // Copy file to cache to ensure FileProvider allows sharing the file
    File cacheDir = getContext().getCacheDir();
    copyFile(track.getParent(), track.getName(), cacheDir.getAbsolutePath());
    Intent intent = new Intent();
    intent.setDataAndType(FileProvider.getUriForFile(getContext(), "btools.routing.fileprovider", new File(cacheDir, track.getName())),
      "application/gpx+xml");
    intent.setAction(Intent.ACTION_VIEW);
    intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    getContext().startActivity(intent);
  }
}
