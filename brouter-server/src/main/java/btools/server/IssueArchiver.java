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
import java.util.TreeSet;


public class IssueArchiver
{
  public static void main(String[] args) throws Exception
  {
    if ( args.length < 2 )
    {
      System.out.println( "usage : IssueArchiver <suspect-dir> <suspect-archive>" );
      System.exit(1);
    }

    File suspectDir = new File( args[0] );
    if ( !suspectDir.isDirectory() )
    {
      throw new IllegalArgumentException( "not a directory: " + suspectDir );
    } 
    File suspectArchive = new File( args[1] );
    if ( !suspectArchive.isDirectory() )
    {
      throw new IllegalArgumentException( "not a directory: " + suspectArchive );
    }
    
    File[] files = suspectDir.listFiles();
    for ( File f : files )
    {
      String name = f.getName();
      if ( name.startsWith( "suspects_" ) && name.endsWith( ".txt" ) )
      {
        BufferedReader br = new BufferedReader( new FileReader( f ) );
        for(;;)
        {
          String line = br.readLine();
          if ( line == null ) break;
          StringTokenizer tk = new StringTokenizer( line );
          long id = Long.parseLong( tk.nextToken() );
          int prio = Integer.parseInt( tk.nextToken() );

          File archiveEntry = new File( suspectArchive, "" + id );
          if ( !archiveEntry.exists() )
          {
            archiveEntry.createNewFile();
          }
        }
        br.close();
      }
    }
  }
}
