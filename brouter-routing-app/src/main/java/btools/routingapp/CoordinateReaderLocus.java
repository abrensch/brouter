package btools.routingapp;

import java.io.File;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import btools.router.OsmNodeNamed;

/**
 * Read coordinates from a gpx-file
 */
public class CoordinateReaderLocus extends CoordinateReader {
  public CoordinateReaderLocus(String basedir) {
    super(basedir);
    tracksdir = "/Locus/mapItems";
    rootdir = "/Locus";
  }

  @Override
  public long getTimeStamp() throws Exception {
    File f = new File(basedir + "/Locus/data/database/waypoints.db");
    long t1 = f.lastModified();
    // Android 10 delivers file size but can't read it
    boolean canRead = f.canRead();
    return canRead ? t1 : 0L;
  }

  @Override
  public int getTurnInstructionMode() {
    return 2; // locus style
  }

  /*
   * read the from and to position from a ggx-file
   * (with hardcoded name for now)
   */
  @Override
  public void readPointmap() throws Exception {
    _readPointmap(basedir + "/Locus/data/database/waypoints.db");
  }

  private void _readPointmap(String filename) throws Exception {
    SQLiteDatabase myDataBase = null;
    try {
      myDataBase = SQLiteDatabase.openDatabase(filename, null, SQLiteDatabase.OPEN_READONLY);
    } catch (Exception e) {
      // not open, do not produce an error
      return;
    }

    Cursor c = myDataBase.rawQuery("SELECT c.name, w.name, w.longitude, w.latitude FROM waypoints w, categories c where w.parent_id = c._id", null);
    if (c.getCount() == 0) {
      c.close();
      c = myDataBase.rawQuery("SELECT c.name, w.name, w.longitude, w.latitude FROM waypoints w, groups c where w.parent_id = c._id;", null);
    }
    while (c.moveToNext()) {
      OsmNodeNamed n = new OsmNodeNamed();
      String category = c.getString(0);
      n.name = c.getString(1);
      n.ilon = (int) ((c.getDouble(2) + 180.) * 1000000. + 0.5);
      n.ilat = (int) ((c.getDouble(3) + 90.) * 1000000. + 0.5);
      checkAddPoint(category, n);
    }
    c.close();
    myDataBase.close();
  }
}
