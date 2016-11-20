package btools.mapcreator;

import java.io.*;
import java.util.zip.*;

public class ConvertSrtmTile
{
  public static int NROWS;
  public static int NCOLS;

  public static final short SKIPDATA = -32766; // >50 degree skipped pixel
  public static final short NODATA2 = -32767; // bil-formats nodata
  public static final short NODATA = Short.MIN_VALUE;

  static short[] imagePixels;

  public static int[] diffs = new int[100];

  private static void readBilZip( String filename, int rowOffset, int colOffset, boolean halfCols ) throws Exception
  {
    ZipInputStream zis = new ZipInputStream( new BufferedInputStream( new FileInputStream( filename ) ) );
    try
    {
      for ( ;; )
      {
        ZipEntry ze = zis.getNextEntry();
        if ( ze.getName().endsWith( ".bil" ) )
        {
          readBilFromStream( zis, rowOffset, colOffset, halfCols );
          return;
        }
      }
    }
    finally
    {
      zis.close();
    }
  }

  private static void readBilFromStream( InputStream is, int rowOffset, int colOffset, boolean halfCols )
      throws Exception
  {
    DataInputStream dis = new DataInputStream( new BufferedInputStream( is ) );
    for ( int ir = 0; ir < 3601; ir++ )
    {
      int row = rowOffset + ir;

      for ( int ic = 0; ic < 3601; ic++ )
      {
        int col = colOffset + ic;

        if ( ( ic % 2 ) == 1 && halfCols )
        {
          if ( getPixel( row, col ) == NODATA )
          {
            setPixel( row, col, SKIPDATA );
          }
          continue;
        }

        int i0 = dis.read();
        int i1 = dis.read();

        if ( i0 == -1 || i1 == -1 )
          throw new RuntimeException( "unexcepted end of file reading bil entry!" );

        short val = (short) ( ( i1 << 8 ) | i0 );

        if ( val == NODATA2 )
        {
          val = NODATA;
        }

        setPixel( row, col, val );
      }
    }
  }


  private static void setPixel( int row, int col, short val )
  {
    if ( row >= 0 && row < NROWS && col >= 0 && col < NCOLS )
    {
      imagePixels[row * NCOLS + col] = val;
    }
  }

  private static short getPixel( int row, int col )
  {
    if ( row >= 0 && row < NROWS && col >= 0 && col < NCOLS )
    {
      return imagePixels[row * NCOLS + col];
    }
    return NODATA;
  }


  public static void doConvert( String inputDir, String v1Dir, int lonDegreeStart, int latDegreeStart, String outputFile, SrtmRaster raster90 ) throws Exception
  {
    int extraBorder = 10;
    int datacells = 0;
    int mismatches = 0;

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

    boolean halfCol5 = latDegreeStart >= 50 || latDegreeStart < -50;

    for ( int row90 = 0; row90 < 6001; row90++ )
    {
      int crow = 3 * row90 + extraBorder; // center row of 3x3
      for ( int col90 = 0; col90 < 6001; col90++ )
      {
        int ccol = 3 * col90 + extraBorder; // center col of 3x3

        // evaluate 3x3 area
        if ( raster90 != null && (!halfCol5 || (col90 % 2) == 0 ) )
        {
          short v90 = raster90.eval_array[row90 * 6001 + col90];

          int sum = 0;
          int nodatas = 0;
          int datas = 0;
          int colstep = halfCol5 ? 2 : 1;
          for ( int row = crow - 1; row <= crow + 1; row++ )
          {
            for ( int col = ccol - colstep; col <= ccol + colstep; col += colstep )
            {
              short v30 = imagePixels[row * NCOLS + col];
              if ( v30 == NODATA )
              {
                nodatas++;
              }
              else if ( v30 != SKIPDATA )
              {
                sum += v30;
                datas++;
              }
            }
          }
          boolean doReplace = nodatas > 0 || v90 == NODATA || datas < 7;
          if ( !doReplace )
          {
            datacells++;
            int diff = sum - datas * v90;
            if ( diff < -4 || diff > 4 )
            {
              doReplace = true;
              mismatches++;
            }

            if ( diff > -50 && diff < 50 && ( row90 % 1200 ) != 0 && ( col90 % 1200 ) != 0 )
            {
              diffs[diff + 50]++;
            }
          }
          if ( doReplace )
          {
            for ( int row = crow - 1; row <= crow + 1; row++ )
            {
              for ( int col = ccol - colstep; col <= ccol + colstep; col += colstep )
              {
                imagePixels[row * NCOLS + col] = v90;
              }
            }
          }
        }
      }
    }

    SrtmRaster raster = new SrtmRaster();
    raster.nrows = NROWS;
    raster.ncols = NCOLS;
    raster.halfcol = halfCol5;
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

    // compare decoding result
    for ( int row = 0; row < NROWS; row++ )
    {
      int colstep = halfCol5 ? 2 : 1;
      for ( int col = 0; col < NCOLS; col += colstep )
      {
        int idx = row * NCOLS + col;
        if ( imagePixels[idx] == SKIPDATA )
        {
          continue;
        }
        short p2 = pix2[idx];
        if ( p2 > SKIPDATA )
        {
          p2 /= 2;
        }
        if ( p2 != imagePixels[idx] )
        {
          throw new RuntimeException( "content mismatch!" );
        }
      }
    }
    
    for(int i=1; i<100;i++) System.out.println( "diff[" + (i-50) + "] = " + diffs[i] ); 
    System.out.println( "datacells=" + datacells + " mismatch%=" + (100.*mismatches)/datacells );
btools.util.MixCoderDataOutputStream.stats();    
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
