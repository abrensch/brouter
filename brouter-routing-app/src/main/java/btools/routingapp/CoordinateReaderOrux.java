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
    Cursor c = myDataBase.rawQuery("SELECT poiname, poilon, poilat FROM pois", null);
    while (c.moveToNext())
    {
      OsmNodeNamed n = new OsmNodeNamed();
      n.name = c.getString(0);
      n.ilon = (int)( ( Double.parseDouble( c.getString(1) ) + 180. )*1000000. + 0.5);
      n.ilat = (int)( ( Double.parseDouble( c.getString(2) ) + 90. )*1000000. + 0.5);
      checkAddPoint( n );
    }
    myDataBase.close();
  }
}
