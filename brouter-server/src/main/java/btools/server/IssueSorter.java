package btools.server;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.HashSet;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.TreeMap;

import btools.router.SuspectInfo;


public class IssueSorter
{
  public static void main(String[] args) throws Exception
  {
    if ( args.length < 2 )
    {
      System.out.println( "usage : IssueSorter <in-file> <out-file> [<osm-filter>]" );
      System.exit(1);
    }
    
    File osmFilter = args.length > 2 ? new File( args[2] ) : null;

    Set<Long> filterSet = null;
    
    // if the osm-filter exists, read it
    if ( osmFilter != null && osmFilter.exists() )
    {
      filterSet = new HashSet<Long>();
      BufferedReader r = new BufferedReader( new FileReader( osmFilter ) );
      for(;;)
      {
        String line = r.readLine();
        if ( line == null ) break;
        int idx0 = line.indexOf( "<node id=\"" );
        if ( idx0 < 0 ) continue;
        idx0 += 10;
        int idx1 = line.indexOf( '"', idx0 );
        long nodeId = Long.parseLong( line.substring( idx0, idx1 ) );
        filterSet.add( Long.valueOf( nodeId ) );
      }
      r.close();
    }
  
    TreeMap<String,TreeMap<Long,SuspectInfo>> keys = new TreeMap<String,TreeMap<Long,SuspectInfo>>();
  
    BufferedReader br = new BufferedReader( new FileReader( new File( args[0] ) ) );
    for(;;)
    {
      String line = br.readLine();
      if ( line == null ) break;
      StringTokenizer tk = new StringTokenizer( line );
      long id = Long.parseLong( tk.nextToken() );
      SuspectInfo info = new SuspectInfo();
      info.prio = Integer.parseInt( tk.nextToken() );
      info.triggers = tk.hasMoreTokens() ? Integer.parseInt( tk.nextToken() ) : 0;

      if ( filterSet != null && !filterSet.contains( Long.valueOf( id ) ) )
      {
        continue;
      }
      int ilon = (int) ( id >> 32 );
      int ilat = (int) ( id & 0xffffffff );
      
      String key = getKey( ilon, ilat );
      TreeMap<Long,SuspectInfo> map = keys.get( key );
      if ( map == null )
      {
        map = new TreeMap<Long,SuspectInfo>();
        keys.put( key, map );
      }
      map.put( Long.valueOf( id ), info );
    }
    br.close(); 
  
    // write suspects to file      
    BufferedWriter bw = new BufferedWriter( new FileWriter( new File( args[1] ) ) );
    for( String key : keys.keySet() )
    {
      TreeMap<Long,SuspectInfo> map = keys.get( key );
      for( Long suspect : map.keySet() )
      {
        SuspectInfo info =  map.get( suspect );
        bw.write( suspect + " " + info.prio + " " + info.triggers + "\r\n" );
      }
    }
    bw.close();

    // if the osm-filter does not exist, write it
    if ( osmFilter != null && !osmFilter.exists() )
    {
      bw = new BufferedWriter( new FileWriter( osmFilter ) );
      bw.write( "<?xml version='1.0' encoding='UTF-8'?>\n" );
      bw.write( "<osm version=\"0.6\">\n" );
      for( String key : keys.keySet() )
      {
        TreeMap<Long,SuspectInfo> map = keys.get( key );
        for( Long suspect : map.keySet() )
        {
          long id = suspect.longValue();
          int ilon = (int) ( id >> 32 );
          int ilat = (int) ( id & 0xffffffff );
          double dlon = (ilon-180000000)/1000000.;
          double dlat = (ilat-90000000)/1000000.;
          bw.write( "<node id=\"" + id + "\" version=\"1\" timestamp=\"2017-01-10T12:00:00Z\" uid=\"1\" user=\"me\" changeset=\"1\" lat=\"" + dlat + "\" lon=\"" + dlon + "\"/>\n" );
        }
      }
      bw.write( "</osm>\n" );
      bw.close();
    }

  }


    public static String getKey( int ilon, int ilat )
    {
      int lon = (ilon / 1000000 );
      int lat = (ilat / 1000000 );
      return "" + (100000 + lon*360 + lat); 
    }
}
