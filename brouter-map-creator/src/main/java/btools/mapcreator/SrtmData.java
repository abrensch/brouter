/**
 * This is a wrapper for a 5*5 degree srtm file in ascii/zip-format
 *
 * - filter out unused nodes according to the way file
 * - enhance with SRTM elevation data
 * - split further in smaller (5*5 degree) tiles
 *
 * @author ab
 */
package btools.mapcreator;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;


public class SrtmData
{
  public int ncols;
  public int nrows;
  public double xllcorner;
  public double yllcorner;
  public double cellsize;
  public int nodata_value;
  public short[] eval_array;

  private double minlon;
  private double minlat;

  public void init()
  {
    minlon = xllcorner;
    minlat = yllcorner;
  }

  private boolean missingData = false;

  public short getElevation( int ilon, int ilat )
  {
    double lon = ilon / 1000000. - 180.;
    double lat = ilat / 1000000. - 90.;

    double dcol = (lon - minlon)/cellsize -0.5;
    double drow = (lat - minlat)/cellsize -0.5;
    int row = (int)drow;
    int col = (int)dcol;
    if ( col < 0 ) col = 0;
    if ( col >= ncols-1 ) col = ncols - 2;
    if ( row < 0 ) row = 0;
    if ( row >= nrows-1 ) row = nrows - 2;
    double wrow = drow-row;
    double wcol = dcol-col;
    missingData = false;
    double eval = (1.-wrow)*(1.-wcol)*get(row  ,col  )
             + (   wrow)*(1.-wcol)*get(row+1,col  )
             + (1.-wrow)*(   wcol)*get(row  ,col+1)
             + (   wrow)*(   wcol)*get(row+1,col+1);
    return missingData ? Short.MIN_VALUE : (short)(eval);
  }

  private short get( int r, int c )
  {
    short e = eval_array[r*ncols + c ];
    if ( e == Short.MIN_VALUE ) missingData = true;
    return e;
  }

  public SrtmData( File file ) throws Exception
  {
    ZipInputStream zis = new ZipInputStream( new FileInputStream( file ) );
    try
    {
      for(;;)
      {
        ZipEntry ze = zis.getNextEntry();
        if ( ze.getName().endsWith( ".asc" ) )
        {
          readFromStream( zis );

/*        // test
        int[] ca = new int[]{ 50477121, 8051915, // 181
                              50477742, 8047408, // 154
                              50477189, 8047308, // 159
                                  };
        for( int i=0; i<ca.length; i+=2 )
        {
           int lat=ca[i] + 90000000;
           int lon=ca[i+1] + 180000000;
            System.err.println( "lat=" + lat + " lon=" + lon + " elev=" + getElevation( lon, lat )/4. );
        }
        // end test
*/
          return;
        }
      }
    }
    finally
    {
      zis.close();
    }
  }


  public void readFromStream( InputStream is ) throws Exception
  {
    BufferedReader br = new BufferedReader(new InputStreamReader(is));
    int linenr = 0;
    for(;;)
    {
      linenr++;
      if ( linenr <= 6 )
      {
        String line = br.readLine();
        if      ( linenr == 1 ) ncols = Integer.parseInt( line.substring(14) );
        else if ( linenr == 2 ) nrows = Integer.parseInt( line.substring(14) );
        else if ( linenr == 3 ) xllcorner = Double.parseDouble( line.substring(14) );
        else if ( linenr == 4 ) yllcorner = Double.parseDouble( line.substring(14) );
        else if ( linenr == 5 ) cellsize = Double.parseDouble( line.substring(14) );
        else if ( linenr == 6 )
        {
          nodata_value = Integer.parseInt( line.substring(14) );
          eval_array = new short[ncols * nrows];
        }
      }
      else
      {
        int row = 0;
        int col = 0;
        int n = 0;
        boolean negative = false;
        for(;;)
        {
          int c = br.read();
          if ( c < 0 ) break;
          if ( c == ' ' )
          {
            if ( negative ) n = -n;
            short val = n == nodata_value ? Short.MIN_VALUE : (short)(n*4);
            if ( val < -1000 ) val = Short.MIN_VALUE;

            eval_array[ (nrows-1-row)*ncols + col ] = val;
            if (++col == ncols )
            {
              col = 0;
              ++row;
            }
            n = 0;
            negative = false;
          }
          else if ( c >= '0' && c <= '9' )
          {
            n = 10*n + (c-'0');
          }
          else if ( c == '-' )
          {
            negative = true;
          }
        }
        break;
      }
    }
    init();
    br.close();
  }

}
