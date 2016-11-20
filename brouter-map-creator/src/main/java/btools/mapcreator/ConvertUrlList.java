package btools.mapcreator;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

public class ConvertUrlList
{
  public static final short NODATA = -32767;

  public static void main( String[] args ) throws Exception
  {
    BufferedReader br = new BufferedReader( new FileReader( args[0] ) );

    for ( ;; )
    {
      String line = br.readLine();
      if ( line == null )
      {
        break;
      }
      int idx1 = line.indexOf( "srtm_" );
      if ( idx1 < 0 )
      {
        continue;
      }

      String filename90 = line.substring( idx1 );
      String filename30 = filename90.substring( 0, filename90.length() - 3 ) + "bef";

      if ( new File( filename30 ).exists() )
      {
        continue;
      }

      // int srtmLonIdx = (ilon+5000000)/5000000; -> ilon = (srtmLonIdx-1)*5
      // int srtmLatIdx = (154999999-ilat)/5000000; -> ilat = 155 - srtmLatIdx*5

      int srtmLonIdx = Integer.parseInt( filename90.substring( 5, 7 ).toLowerCase() );
      int srtmLatIdx = Integer.parseInt( filename90.substring( 8, 10 ).toLowerCase() );

      int ilon_base = ( srtmLonIdx - 1 ) * 5 - 180;
      int ilat_base = 150 - srtmLatIdx * 5 - 90;

      SrtmRaster raster90 = null;

      File file90 = new File( new File( args[1] ), filename90 );
      if ( file90.exists() )
      {
        System.out.println( "reading " + file90 );
        raster90 = new SrtmData( file90 ).getRaster();
      }
      
      ConvertSrtmTile.doConvert( args[2], args[3], ilon_base, ilat_base, filename30, raster90 );
    }
    br.close();
  }
  

}
