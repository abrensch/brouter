package btools.routingapp;

import java.io.File;

public class WpDatabaseScanner extends Thread
{
  private String currentDir = "";
  private String bestGuess = "";
  private String lastError = "";

  private Object currentDirSync = new Object();

  private long maxtimestamp = 0;

  public String getCurrentDir()
  {
    synchronized (currentDirSync)
    {
      return currentDir;
    }
  }

  private void setCurrentDir( String dir )
  {
    synchronized (currentDirSync)
    {
      currentDir = dir;
    }
  }

  public String getBestGuess()
  {
    synchronized (currentDirSync)
    {
      return bestGuess;
    }
  }

  public String getLastError()
  {
    synchronized (currentDirSync)
    {
      return lastError;
    }
  }

  private void setLastError( String msg )
  {
    synchronized (currentDirSync)
    {
      lastError = msg;
    }
  }
  
  private static String[] vetos = new String[] { "dev", "sys", "system", "proc", "etc", "init", "d", "cache", "acct", "data" };

  private void scan( File dir, int level )
  {
    if ( level > 8 )
    {
      return;
    }
  
    try
    {
      if ( dir.isDirectory() )
      {
        if ( level == 1 )
        {
          String name = dir.getName();
          for( String veto: vetos )
          {
            if ( veto.equals( name ) )
            {
              return;
            }
          }
        }
      
        testPath( dir.getPath() );
        File[] childs = dir.listFiles();
        if ( childs == null )
        {
          return;
        }
        for ( File child : childs )
        {
          scan( child, level+1 );
        }
      }
    }
    catch (Exception e)
    {
      setLastError( e.toString() );
    }
  }

  private void testPath( String path ) throws Exception
  {
    setCurrentDir( path );

    testReader( new CoordinateReaderOsmAnd( path ) );
    testReader( new CoordinateReaderOsmAnd( path, true ) );
    testReader( new CoordinateReaderLocus( path ) );
    testReader( new CoordinateReaderOrux( path ) );
  }

  private void testReader( CoordinateReader cor ) throws Exception
  {
    long ts = cor.getTimeStamp();
    if ( ts > maxtimestamp )
    {
      maxtimestamp = ts;
      synchronized (currentDirSync)
      {
        bestGuess = cor.basedir;
      }
    }
    else if ( ts > 0 && ts == maxtimestamp )
    {
      synchronized (currentDirSync)
      {
        if ( cor.basedir.length() < bestGuess.length() )
        {
          bestGuess = cor.basedir;
        }
      }
    }
  }

  @Override
  public void run()
  {
    scan( new File( "/" ), 0 );
    setCurrentDir( null );
    
  }
}
