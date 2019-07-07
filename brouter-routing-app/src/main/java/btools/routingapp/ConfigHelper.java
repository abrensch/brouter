package btools.routingapp;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;

import android.content.Context;

public class ConfigHelper
{
  public static String getBaseDir( Context ctx )
  {
    // get base dir from private file
    InputStream configInput = null;
    try
    {
      configInput = ctx.openFileInput( "config15.dat" );
      BufferedReader br = new BufferedReader( new InputStreamReader( configInput ) );
      return br.readLine();
    }
    catch (Exception e)
    {
      return null;
    }
    finally
    {
      if ( configInput != null )
      {
        try
        {
          configInput.close();
        }
        catch (Exception ee)
        {
        }
      }
    }
  }

  public static void writeBaseDir( Context ctx, String baseDir )
  {
    BufferedWriter bw = null;
    try
    {
      OutputStream configOutput = ctx.openFileOutput( "config15.dat", Context.MODE_PRIVATE );
      bw = new BufferedWriter( new OutputStreamWriter( configOutput ) );
      bw.write( baseDir );
      bw.write( '\n' );
    }
    catch (Exception e){ /* ignore */ }
    finally
    {
      if ( bw != null )
        try
        {
          bw.close();
        }
        catch (Exception ee) { /* ignore */ }
    }
  }
}
