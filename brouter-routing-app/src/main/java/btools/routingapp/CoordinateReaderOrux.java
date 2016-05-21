package btools.routingapp;

import java.io.File;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import btools.router.OsmNodeNamed;

/**
 * Read coordinates from a gpx-file
 */
public class CoordinateReaderOrux extends CoordinateReader
{
  public CoordinateReaderOrux( String basedir )
  {
    super( basedir );
    tracksdir = "/oruxmaps/tracklogs";
    rootdir = "/oruxmaps";
  }

  @Override
  public long getTimeStamp() throws Exception
  {
    long t1 = new File( basedir + "/oruxmaps/tracklogs/oruxmapstracks.db" ).lastModified();
    return t1;
  }

  @Override
  public int getTurnInstructionMode()
  {
    return 0; // none
  }

  /*
   * read the from and to position from a ggx-file
   * (with hardcoded name for now)
   */
  @Override
  public void readPointmap() throws Exception
  {
    _readPointmap( basedir + "/oruxmaps/tracklogs/oruxmapstracks.db" );
  }

  private void _readPointmap( String filename ) throws Exception
  {
    SQLiteDatabase myDataBase = SQLiteDatabase.openDatabase( filename, null, SQLiteDatabase.OPEN_READONLY);
    Cursor c = myDataBase.rawQuery("SELECT poiname, poilon, poilat, poifolder FROM pois", null);
    while (c.moveToNext())
    {
      OsmNodeNamed n = new OsmNodeNamed();
      n.name = c.getString(0);
      n.ilon = (int)( ( c.getDouble(1) + 180. )*1000000. + 0.5);
      n.ilat = (int)( ( c.getDouble(2) + 90. )*1000000. + 0.5);
      String category = c.getString(3);
      checkAddPoint( category, n );
    }
    myDataBase.close();
  }
}
