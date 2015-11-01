package btools.routingapp;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;

import btools.router.OsmNodeNamed;

/**
 * Read coordinates from a gpx-file
 */
public class CoordinateReaderOsmAnd extends CoordinateReader
{
  private String osmandDir;

  public CoordinateReaderOsmAnd( String basedir )
  {
    this( basedir, false );
  }

  public CoordinateReaderOsmAnd( String basedir, boolean shortPath )
  {
    super( basedir );
    if ( shortPath )
    {
      osmandDir = basedir;
      tracksdir = "/tracks";
      rootdir = "";
    }
    else
    {
      osmandDir = basedir + "/osmand";
      tracksdir = "/osmand/tracks";
      rootdir = "/osmand";
    }
  }

  @Override
  public long getTimeStamp() throws Exception
  {
    long t1 = new File( osmandDir + "/favourites_bak.gpx" ).lastModified();
    long t2 = new File( osmandDir + "/favourites.gpx" ).lastModified();
    return t1 > t2 ? t1 : t2;
  }

  /*
   * read the from and to position from a gpx-file
   * (with hardcoded name for now)
   */
  @Override
  public void readPointmap() throws Exception
  {
    try
    {
      _readPointmap( osmandDir + "/favourites_bak.gpx" );
    }
    catch( Exception e )
    {
      _readPointmap( osmandDir + "/favourites.gpx" );
    }
  }

  private void _readPointmap( String filename ) throws Exception
  {
      BufferedReader br = new BufferedReader(
                           new InputStreamReader(
                            new FileInputStream( filename ) ) );
      OsmNodeNamed n = null;

      for(;;)
      {
        String line = br.readLine();
        if ( line == null ) break;

        int idx0 = line.indexOf( "<wpt lat=\"" );
        int idx10 = line.indexOf( "<name>" );
        if ( idx0 >= 0 )
        {
          n = new OsmNodeNamed();
          idx0 += 10;
          int idx1 = line.indexOf( '"', idx0 );
          n.ilat = (int)( (Double.parseDouble( line.substring( idx0, idx1 ) ) + 90. )*1000000. + 0.5);
          int idx2 = line.indexOf( " lon=\"" );
          if ( idx2 < 0 ) continue;
          idx2 += 6;
          int idx3 = line.indexOf( '"', idx2 );
          n.ilon = (int)( ( Double.parseDouble( line.substring( idx2, idx3 ) ) + 180. )*1000000. + 0.5);
          continue;
        }
        if ( n != null && idx10 >= 0 )
        {
          idx10 += 6;
          int idx11 = line.indexOf( "</name>", idx10 );
          if ( idx11 >= 0 )
          {
            n.name = line.substring( idx10, idx11 ).trim();
            checkAddPoint( "(one-for-all)", n );
          }
        }
      }
      br.close();
  }
}
