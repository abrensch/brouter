package btools.mapcreator;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;

import btools.util.Raster2Png;

public class TrafficData2Png
{
    private static int minLon;
    private static int minLat;
    private static int maxLon;
    private static int maxLat;
    private static int ncols;
    private static int nrows;
    private static int[] pixels;

    public static void main( String[] args) throws Exception
    {
      if ( args.length == 8 )
      {
    	doConvert( args[0], args[1],
    	  Double.parseDouble( args[2] ), Double.parseDouble( args[3] ), Double.parseDouble( args[4] ), Double.parseDouble( args[5] ),
    	  Integer.parseInt( args[6] ), Integer.parseInt( args[7] )  );
      }
      else if ( args.length == 4 )
      {
        int lon0 = Integer.parseInt( args[0] );
        int lat0 = Integer.parseInt( args[1] );
        String inputFile = "traffic/E" + lon0 + "_N" + lat0 + ".trf";
        for( int lon = lon0; lon < lon0+5; lon++ )
          for( int lat = lat0; lat < lat0+5; lat++ )
          {
            String imageFile = "traffic_pics/E" + lon + "_N" + lat + ".png";
            System.out.println( "file=" + inputFile + " image=" + imageFile );
            doConvert( inputFile, imageFile, lon, lat, lon+1, lat+1, Integer.parseInt( args[2] ), Integer.parseInt( args[3] ) );
          }
      }
          
    }
    	    	
    public static void doConvert( String inputFile, String imageFile, double lon0, double lat0, double lon1, double lat1,
        int cols, int rows ) throws Exception
    {      
      OsmTrafficMap trafficMap = new OsmTrafficMap();
      minLon = (int)(lon0 * 1000000 + 180000000);
      maxLon = (int)(lon1 * 1000000 + 180000000);
      minLat = (int)(lat0 * 1000000 +  90000000);
      maxLat = (int)(lat1 * 1000000 +  90000000);
      ncols = cols;
      nrows = rows;

      long[] keys = trafficMap.load( new File( inputFile ), minLon, minLat, maxLon, maxLat, true );

      pixels = new int[cols*rows];
      
      int[] tclasses = new int[] { 1,2,3,4,5,6,7, -1 };
      for( int tclass : tclasses  )
      {
        for(long key : keys )
        {
          OsmTrafficMap.OsmTrafficElement e = trafficMap.getElement(key );
          while( e != null )
          {
            long key2 = e.node2;
            e = e.next;
            int trafficClass = trafficMap.getTrafficClass( key, key2 );
            if ( trafficClass != tclass ) continue;

            int[] from = getImagePosition( key );
            int[] to = getImagePosition( key2 );
            
            int rgb = 0;
            if ( trafficClass == -1 ) rgb = 0x0000ff; // blue
            else if ( trafficClass == 1  ) rgb = 0x404040;  // dark grey 
            else if ( trafficClass == 2  ) rgb = 0xa0a0a0; // light grey
            else if ( trafficClass == 3  ) rgb = 0x00ff00; // green
            else if ( trafficClass == 4  ) rgb = 0xf4e500; // yellow
            else if ( trafficClass == 5  ) rgb = 0xf18e1c; // orange
            else if ( trafficClass == 6  ) rgb = 0xe32322; // red
            else if ( trafficClass == 7  ) rgb = 0xc0327d; // pink
            if ( rgb != 0 )
            {
              drawLine( from, to, rgb );
            }
          }
        }
      }


  	  Raster2Png r2p = new Raster2Png( Raster2Png.FILTER_NONE, 6, cols, rows, pixels );
      byte[] png = r2p.pngEncode(  );
       
      System.out.println( "got png of size: " + png.length );
      DataOutputStream dos = new DataOutputStream( new BufferedOutputStream( new FileOutputStream( imageFile ) ) );
      dos.write(png);
      dos.close();
    }
    
    private static void drawLine( int[] from, int[] to, int rgb )
    {
      int ix = from[0];
      int iy = from[1];
      int ixx = to[0];
      int iyy = to[1];
      
      int sx = ixx > ix ? 1 : -1;
      int sy = iyy > iy ? 1 : -1;
      
      int dx = (ixx-ix)*sx;
      int dy = (iyy-iy)*sy;
      
      int sum = 0;      
      
      for(;;)
      {
        drawPixel( ix, iy, rgb );
        if ( ix == ixx && iy == iyy ) break;

        if ( Math.abs( sum+dx ) < Math.abs( sum-dy ) )
        {
          iy+= sy;
          sum += dx;
        }
        else
        {
          ix+= sx;
          sum -= dy;
        }
      }
    }
    
    private static void drawPixel( int ix, int iy, int rgb )
    {
          if ( ix >= 0 && ix < ncols && iy >= 0 && iy < nrows )
          {
            pixels[ (nrows-1-iy)*ncols + ix ] = rgb;
          }
    }
    
    private static int[] getImagePosition( long key )
    {
          int ilon = (int)(key >> 32);
          int ilat = (int)(key & 0xffffffff);
          double lonDelta = maxLon-minLon;
          double latDelta = maxLat-minLat;
          int[] res = new int[2];
          res[0] = (int)( ( (ilon-minLon)/lonDelta ) *ncols );
          res[1] = (int)( ( (ilat-minLat)/latDelta ) *nrows );
          return res;
    }
}
