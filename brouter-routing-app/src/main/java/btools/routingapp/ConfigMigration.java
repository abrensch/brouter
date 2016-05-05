package btools.routingapp;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

public class ConfigMigration
{
  public static void tryMigrateStorageConfig( File srcFile, File dstFile )
  {
    if ( !srcFile.exists() ) return;
    
    String ssd = null;
    String amd = null;

    BufferedReader br = null;
    BufferedWriter bw = null;
    try
    {
      br = new BufferedReader( new FileReader( srcFile ) );
      for ( ;; )
      {
        String line = br.readLine();
        if ( line == null ) break;
        if ( line.trim().startsWith( "secondary_segment_dir=" ) )
        {
          if ( !"secondary_segment_dir=../segments2".equals( line ) )
          {
            ssd = line;
          }
        }
        if ( line.trim().startsWith( "additional_maptool_dir=" ) )
        {
          amd = line;
        }
      }
      br.close();

      List<String> lines = new ArrayList<String>();
      br = new BufferedReader( new FileReader( dstFile ) );
      for ( ;; )
      {
        String line = br.readLine();
        if ( line == null ) break;
        if ( ssd != null && line.trim().startsWith( "secondary_segment_dir=" ) )
        {
          line = ssd;
        }
        if ( amd != null && line.trim().startsWith( "#additional_maptool_dir=" ) )
        {
          line = amd;
        }
        lines.add( line );
      }
      br.close();
      br = null;

      bw = new BufferedWriter( new FileWriter( dstFile ) );
      for( String line: lines )
      {
        bw.write( line + "\n" );
      }        
    }
    catch (Exception e) { /* ignore */ }
    finally
    {
      if ( br != null )
      {
        try
        {
          br.close();
        }
        catch (Exception ee) { /* ignore */ }
      }
      if ( bw != null )
      {
        try
        {
          bw.close();
        }
        catch (Exception ee) { /* ignore */ }
      }
    }
  }

  public static File saveAdditionalMaptoolDir( String segmentDir, String value )
  {
    return saveStorageLocation( segmentDir, "additional_maptool_dir=", value );
  }

  private static File saveStorageLocation( String segmentDir, String tag, String value )
  {
    File res = null;
    BufferedReader br = null;
    BufferedWriter bw = null;
    String configFile = segmentDir + "/storageconfig.txt";
    List<String> lines = new ArrayList<String>();
    try
    {
      br = new BufferedReader( new FileReader( configFile ) );
      for ( ;; )
      {
        String line = br.readLine();
        if ( line == null ) break;
        if ( !line.trim().startsWith( tag ) )
        {
          lines.add( line );
        }
      }
      lines.add( tag + value );
      br.close();
      br = null;
      bw = new BufferedWriter( new FileWriter( configFile ) );
      for( String line : lines )
      {
        bw.write( line + "\r\n" );
      }
    }
    catch (Exception e) { /* ignore */ }
    finally
    {
      if ( br != null )
      {
        try
        {
          br.close();
        }
        catch (Exception ee) { /* ignore */ }
      }
      if ( bw != null )
      {
        try
        {
          bw.close();
        }
        catch (Exception ee) { /* ignore */ }
      }
    }
    return res;
  }

}
