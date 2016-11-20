package btools.mapcreator;

/**
 * This is a wrapper for a 5*5 degree srtm file in ascii/zip-format
 *
 * - filter out unused nodes according to the way file
 * - enhance with SRTM elevation data
 * - split further in smaller (5*5 degree) tiles
 *
 * @author ab
 */

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.StringTokenizer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class SrtmData
{
  private SrtmRaster raster;

  public SrtmData( File file ) throws Exception
  {
    raster = new SrtmRaster();

    ZipInputStream zis = new ZipInputStream( new BufferedInputStream( new FileInputStream( file ) ) );
    try
    {
      for ( ;; )
      {
        ZipEntry ze = zis.getNextEntry();
        if ( ze.getName().endsWith( ".asc" ) )
        {
          readFromStream( zis );
          return;
        }
      }
    }
    finally
    {
      zis.close();
    }
  }

  public SrtmRaster getRaster()
  {
    return raster;
  }

  private String secondToken( String s )
  {
    StringTokenizer tk = new StringTokenizer( s, " " );
    tk.nextToken();
    return tk.nextToken();
  }

  public void readFromStream( InputStream is ) throws Exception
  {
    BufferedReader br = new BufferedReader( new InputStreamReader( is ) );
    int linenr = 0;
    for ( ;; )
    {
      linenr++;
      if ( linenr <= 6 )
      {
        String line = br.readLine();
        if ( linenr == 1 )
          raster.ncols = Integer.parseInt( secondToken( line ) );
        else if ( linenr == 2 )
          raster.nrows = Integer.parseInt( secondToken( line ) );
        else if ( linenr == 3 )
          raster.xllcorner = Double.parseDouble( secondToken( line ) );
        else if ( linenr == 4 )
          raster.yllcorner = Double.parseDouble( secondToken( line ) );
        else if ( linenr == 5 )
          raster.cellsize = Double.parseDouble( secondToken( line ) );
        else if ( linenr == 6 )
        {
          // nodata ignored here ( < -250 assumed nodata... )
          // raster.noDataValue = Short.parseShort( secondToken( line ) );
          raster.eval_array = new short[raster.ncols * raster.nrows];
        }
      }
      else
      {
        int row = 0;
        int col = 0;
        int n = 0;
        boolean negative = false;
        for ( ;; )
        {
          int c = br.read();
          if ( c < 0 )
            break;
          if ( c == ' ' )
          {
            if ( negative )
              n = -n;
            short val = n < -250 ? Short.MIN_VALUE : (short) (n);

            raster.eval_array[row * raster.ncols + col] = val;
            if ( ++col == raster.ncols )
            {
              col = 0;
              ++row;
            }
            n = 0;
            negative = false;
          }
          else if ( c >= '0' && c <= '9' )
          {
            n = 10 * n + ( c - '0' );
          }
          else if ( c == '-' )
          {
            negative = true;
          }
        }
        break;
      }
    }
    br.close();
  }
}
