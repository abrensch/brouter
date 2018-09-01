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
    if ( args.length < 3 )
    {
      System.out.println( "usage : IssueArchiver <new-suspect-dir> <old-suspect-dir> <suspect-archive>" );
      System.exit(1);
    }

    File newSuspectDir = new File( args[0] );
    if ( !newSuspectDir.isDirectory() )
    {
      throw new IllegalArgumentException( "not a directory: " + newSuspectDir );
    } 
    File oldSuspectDir = new File( args[1] );
    if ( !oldSuspectDir.isDirectory() )
    {
      throw new IllegalArgumentException( "not a directory: " + oldSuspectDir );
    } 
    File suspectArchive = new File( args[2] );
    if ( !suspectArchive.isDirectory() )
    {
      throw new IllegalArgumentException( "not a directory: " + suspectArchive );
    }
    
    File[] newFiles = newSuspectDir.listFiles();
    for ( File newFile : newFiles )
    {
      String name = newFile.getName();
      if ( name.startsWith( "suspects_" ) && name.endsWith( ".txt" ) )
      {
        File oldFile = new File( oldSuspectDir, name );
        if ( !oldFile.exists() ) continue;

        System.out.println( "archiving " + oldFile );

        BufferedReader br = new BufferedReader( new FileReader( oldFile ) );
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
