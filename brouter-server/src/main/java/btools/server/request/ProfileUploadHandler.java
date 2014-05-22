package btools.server.request;

import btools.server.ServiceContext;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;

/**
 * Custom profile uploads
 */
public class ProfileUploadHandler
{
  // maximum number of characters (file size limit for custom profiles)
  private static final int MAX_LENGTH = 100000;

  // prefix for custom profile id to distinguish from default profiles
  public static final String CUSTOM_PREFIX = "custom_";

	private ServiceContext serviceContext;
  
	public ProfileUploadHandler( ServiceContext serviceContext)
	{
		this.serviceContext = serviceContext;
	}

  public void handlePostRequest(String profileId, BufferedReader br, BufferedWriter response) throws IOException
  {
    BufferedWriter fileWriter = null;

    try
    {
      String id;
      if ( profileId != null )
      {
        // update existing file when id appended
        id = profileId.substring( ProfileUploadHandler.CUSTOM_PREFIX.length() );
      } else {
        id = "" + System.currentTimeMillis();
      }
      File file = new File( getOrCreateCustomProfileDir(), id + ".brf" );
      fileWriter = new BufferedWriter(new OutputStreamWriter(new FileOutputStream( file ) ) );
      //StringWriter sw = new StringWriter(); bw = new BufferedWriter(sw);

      // only profile text as content
      readPostData(br, fileWriter, id);

      fileWriter.flush();
      //System.out.println("data: |" + sw.toString() + "|");

      response.write("profileid=" + CUSTOM_PREFIX + id);
    }
    finally
    {
      if ( fileWriter != null ) try { fileWriter.close(); } catch( Exception e ) {}
    }
  }

  private File getOrCreateCustomProfileDir()
  {
    // workaround: customProfileDir relative to profileDir, because RoutingEngine doesn't know custom profiles
    File customProfileDir = new File(serviceContext.profileDir, serviceContext.customProfileDir);
    if (!customProfileDir.exists())
    {
      customProfileDir.mkdir();
    }
    return customProfileDir;
  }

  // reads HTTP POST content from input into output stream/writer
  private static void readPostData( BufferedReader ir, BufferedWriter bw, String id ) throws IOException
  {
    // Content-Type: text/plain;charset=UTF-8

    for(;;)
    {
      // headers
      String line = ir.readLine();
      if ( line == null ) break;

      // blank line before content after headers
      if ( line.length() == 0 )
      {
        int numChars = 0;

        // Content-Length header is in bytes (!= characters for UTF8),
        // but Reader reads characters, so don't know number of characters to read
        for(;;)
        {
          // read will block when false, occurs at end of stream rather than -1
          if (!ir.ready()) break;
          int c = ir.read();
          if ( c == -1) break;
          bw.write( c );
          
          numChars++;
          if (numChars > MAX_LENGTH)
            throw new IOException("Maximum number of characters exceeded (" + MAX_LENGTH + ", " + id + ")");
        }
        break;
      }
    }
  }
}
