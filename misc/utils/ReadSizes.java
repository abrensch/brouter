import java.io.*;

public class ReadSizes
{
    private static int[] tileSizes = new int[72*36];

    protected static String baseNameForTile( int tileIndex )
    {
      int lon = (tileIndex % 72 ) * 5 - 180;
      int lat = (tileIndex / 72 ) * 5 - 90;
      String slon = lon < 0 ? "W" + (-lon) : "E" + lon;
      String slat = lat < 0 ? "S" + (-lat) : "N" + lat;
      return slon + "_" + slat;
    }
    
    private static int tileForBaseName( String basename )
    {
      String uname = basename.toUpperCase();	
      int idx = uname.indexOf( "_" );
      if ( idx < 0 ) return -1;
      String slon = uname.substring( 0, idx ); 
      String slat = uname.substring( idx+1 );
      int ilon = slon.charAt(0) == 'W' ? -Integer.valueOf( slon.substring(1) ) :
    	       ( slon.charAt(0) == 'E' ?  Integer.valueOf( slon.substring(1) ) : -1 );
      int ilat = slat.charAt(0) == 'S' ? -Integer.valueOf( slat.substring(1) ) :
	           ( slat.charAt(0) == 'N' ?  Integer.valueOf( slat.substring(1) ) : -1 );
      if ( ilon < -180 || ilon >= 180 || ilon % 5 != 0 ) return -1;
      if ( ilat < - 90 || ilat >=  90 || ilat % 5 != 0 ) return -1;
      return (ilon+180) / 5 + 72*((ilat+90)/5);
    }

    
    private static void scanExistingFiles( File dir )
    {
        String[] fileNames = dir.list();
        if ( fileNames == null ) return;
        String suffix = ".rd5";
        for( String fileName : fileNames )
        {
          if ( fileName.endsWith( suffix ) )
          {
        	 String basename = fileName.substring( 0, fileName.length() - suffix.length() );
        	 int tidx = tileForBaseName( basename );
        	 tileSizes[tidx] = (int)new File( dir, fileName ).length();
          }
        }
    }
    
    
    public static void main(String[] args)
    {
      scanExistingFiles( new File( args[0] ) );
      StringBuilder sb = new StringBuilder();
      for( int tidx=0; tidx < tileSizes.length; tidx++ )
      {
        if ( ( tidx % 12 ) == 0 ) sb.append( "\n        " );
        sb.append( tileSizes[tidx] ).append(',');
      }
      System.out.println( sb );
    }
    
} 
