package btools.server;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;


public class IssueFilter
{
  public static void main(String[] args) throws Exception
  {
    if ( args.length != 2 )
    {
      System.out.println( "usage : IssueFilter <in-file> <out-file> " );
      System.exit(1);
    }
    
    BufferedReader br = new BufferedReader( new FileReader( new File( args[0] ) ) );
    BufferedWriter bw = new BufferedWriter( new FileWriter( new File( args[1] ) ) );
    for(;;)
    {
      String line = br.readLine();
      if ( line == null ) break;
      
      if ( line.startsWith( "bad TR candidate: " ) )
      {
        bw.write( line.substring( "bad TR candidate: ".length() ) );
        bw.write( "\r\n" );
      }
    }
    br.close(); 
    bw.close(); 
  }
}
