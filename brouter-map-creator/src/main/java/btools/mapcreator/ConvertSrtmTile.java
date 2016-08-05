package btools.mapcreator;

import java.io.*;
import java.util.zip.*;

public class ConvertSrtmTile
{
  public static int NROWS;
  public static int NCOLS;

  public static final short NODATA2 = -32767; // bil-formats nodata
  public static final short NODATA = Short.MIN_VALUE;

  static short[] imagePixels;

  public static int[] diffs = new int[100];

  private static void readBilZip( String filename, int rowOffset, int colOffset, boolean halfCols ) throws Exception
  {
    int fileRows = 3601;
    int fileCols = 3601;
    ZipInputStream zis = new ZipInputStream( new BufferedInputStream( new FileInputStream( filename ) ) );
    try
    {
      for ( ;; )
      {
        ZipEntry ze = zis.getNextEntry();
        if ( ze.getName().endsWith( ".bil" ) )
        {
          readBilFromStream( zis, rowOffset, colOffset, fileRows, fileCols, halfCols );
          return;
        }
      }
    }
    finally
    {
      zis.close();
    }
  }

  private static void readBilFromStream( InputStream is, int rowOffset, int colOffset, int fileRows, int fileCols, boolean halfCols )
      throws Exception
  {
    DataInputStream dis = new DataInputStream( new BufferedInputStream( is ) );
    for ( int ir = 0; ir < fileRows; ir++ )
    {
      int row = rowOffset + ir;

      short lastVal = 0;
      boolean fillGap = false;

      for ( int ic = 0; ic < fileCols; ic++ )
      {
        int col = colOffset + ic;
        short val;
        if ( ( ic % 2 ) == 1 && halfCols )
        {
          fillGap = true;
        }
        else
        {
          int i0 = dis.read();
          int i1 = dis.read();

          if ( i0 == -1 || i1 == -1 )
            throw new RuntimeException( "unexcepted end of file reading bil entry!" );

          val = (short) ( ( i1 << 8 ) | i0 );
          
          if ( val == NODATA2 )
          {
            val = NODATA;
          }

          if ( fillGap )
          {
            setPixel( row, col - 1, val, lastVal );
            fillGap = false;
          }

if ( row == 18010 ) System.out.print( val + " " );

          setPixel( row, col, val, val );
          lastVal = val;
        }
      }
    }
  }

  private static void setPixel( int row, int col, short val1, short val2 )
  {
    if ( row >= 0 && row < NROWS && col >= 0 && col < NCOLS )
    {
      if ( val1 != NODATA && val2 != NODATA )
      {
        int val = val1 + val2;
        if ( val < -32768 || val > 32767 )
          throw new IllegalArgumentException( "val1=" + val1 + " val2=" + val2 );

        imagePixels[row * NCOLS + col] = (short) ( val );
      }
    }
  }

  public static void main( String[] args ) throws Exception
  {
    doConvert( args[0], Integer.parseInt( args[1] ), Integer.parseInt( args[2] ), args[3], null );
  }

  public static void doConvert( String inputDir, int lonDegreeStart, int latDegreeStart, String outputFile, SrtmRaster raster90 ) throws Exception
  {
    int extraBorder = 10;
    int datacells = 0;
    int matchingdatacells = 0;

    NROWS = 5 * 3600 + 1 + 2 * extraBorder;
    NCOLS = 5 * 3600 + 1 + 2 * extraBorder;

    imagePixels = new short[NROWS * NCOLS]; // 650 MB !

    // prefill as NODATA
    for ( int row = 0; row < NROWS; row++ )
    {
      for ( int col = 0; col < NCOLS; col++ )
      {
        imagePixels[row * NCOLS + col] = NODATA;
      }
    }

    for ( int latIdx = -1; latIdx <= 5; latIdx++ )
    {
      int latDegree = latDegreeStart + latIdx;
      int rowOffset = extraBorder + ( 4 - latIdx ) * 3600;

      for ( int lonIdx = -1; lonIdx <= 5; lonIdx++ )
      {
        int lonDegree = lonDegreeStart + lonIdx;
        int colOffset = extraBorder + lonIdx * 3600;

        String filename = inputDir + "/" + formatLat( latDegree ) + "_" + formatLon( lonDegree ) + "_1arc_v3_bil.zip";
        File f = new File( filename );
        if ( f.exists() && f.length() > 0 )
        {
          System.out.println( "exist: " + filename );
          boolean halfCol = latDegree >= 50 || latDegree < -50;
          readBilZip( filename, rowOffset, colOffset, halfCol );
        }
        else
        {
          System.out.println( "none : " + filename );
        }
      }
    }

    if ( raster90 != null )
    {
      for ( int row90 = 0; row90 < 6001; row90++ )
      {
        int crow = 3 * row90 + extraBorder; // center row of 3x3
        for ( int col90 = 0; col90 < 6001; col90++ )
        {
          int ccol = 3 * col90 + extraBorder; // center col of 3x3

          short v90 = raster90.eval_array[row90 * 6001 + col90];

          // evaluate 3x3 area
          int sum = 0;
          int nodatas = 0;
          for ( int row = crow - 1; row <= crow + 1; row++ )
          {
            for ( int col = ccol - 1; col <= ccol + 1; col++ )
            {
              short v30 = imagePixels[row * NCOLS + col];
              sum += v30;
              if ( v30 == NODATA )
              {
                nodatas++;
              }
            }
          }
          boolean doReplace = nodatas > 0 || v90 == NODATA;
          short replaceValue = NODATA;
          if ( !doReplace )
          {
            datacells++;
            int diff = sum - 9 * v90;
            replaceValue= (short)(diff / 9);
            doReplace = true;
            if ( diff < -9 || diff > 9 )
            {
//              System.out.println( "replacing due to median missmatch: sum=" + sum + " v90=" + v90 );
              doReplace = true;
            }
            if ( diff > -50 && diff < 50 )
            {
              matchingdatacells++;
              diffs[diff+50]++;
            }
          }
          if ( doReplace )
          {
            for ( int row = crow - 1; row <= crow + 1; row++ )
            {
              for ( int col = ccol - 1; col <= ccol + 1; col++ )
              {
//                imagePixels[row * NCOLS + col] = v90;
                imagePixels[row * NCOLS + col] = replaceValue;
              }
            }
          }
        }
      }
    }

    SrtmRaster raster = new SrtmRaster();
    raster.nrows = NROWS;
    raster.ncols = NCOLS;
    raster.noDataValue = NODATA;
    raster.cellsize = 1 / 3600.;
    raster.xllcorner = lonDegreeStart - ( 0.5 + extraBorder ) * raster.cellsize;
    raster.yllcorner = latDegreeStart - ( 0.5 + extraBorder ) * raster.cellsize;
    raster.eval_array = imagePixels;

    // encode the raster
    OutputStream os = new BufferedOutputStream( new FileOutputStream( outputFile ) );
    new RasterCoder().encodeRaster( raster, os );
    os.close();

    // decode the raster
    InputStream is = new BufferedInputStream( new FileInputStream( outputFile ) );
    SrtmRaster raster2 = new RasterCoder().decodeRaster( is );
    is.close();

    short[] pix2 = raster2.eval_array;
    if ( pix2.length != imagePixels.length )
      throw new RuntimeException( "length mismatch!" );

    for ( int i = 0; i < pix2.length; i++ )
    {
      if ( pix2[i] != imagePixels[i] )
      {
        throw new RuntimeException( "content mismatch!" );
      }
    }
    
    for(int i=0; i<100;i++) System.out.println( "diff[" + (i-50) + "] = " + diffs[i] ); 
    System.out.println( "datacells=" + datacells + " mismatch%=" + 100.*(datacells-matchingdatacells)/datacells );
    // test( raster );
    // raster.calcWeights( 50. );
    // test( raster );
    // 39828330 &lon=3115280&layer=OpenStreetMap
  }

  private static void test( SrtmRaster raster )
  {
    int lat0 = 39828330;
    int lon0 = 3115280;

    for ( int iy = -9; iy <= 9; iy++ )
    {
      StringBuilder sb = new StringBuilder();
      for ( int ix = -9; ix <= 9; ix++ )
      {
        int lat = lat0 + 90000000 - 100 * iy;
        int lon = lon0 + 180000000 + 100 * ix;
        int ival = (int) ( raster.getElevation( lon, lat ) / 4. );
        String sval = "     " + ival;
        sb.append( sval.substring( sval.length() - 4 ) );
      }
      System.out.println( sb );
      System.out.println();
    }
  }

  private static String formatLon( int lon )
  {
    if ( lon >= 180 )
      lon -= 180; // TODO: w180 oder E180 ?

    String s = "e";
    if ( lon < 0 )
    {
      lon = -lon;
      s = "w";
    }
    String n = "000" + lon;
    return s + n.substring( n.length() - 3 );
  }

  private static String formatLat( int lat )
  {
    String s = "n";
    if ( lat < 0 )
    {
      lat = -lat;
      s = "s";
    }
    String n = "00" + lat;
    return s + n.substring( n.length() - 2 );
  }

}
