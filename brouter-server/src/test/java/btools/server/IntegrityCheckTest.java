package btools.server;

import java.io.File;
import java.net.URL;

import org.junit.Assert;
import org.junit.Test;

import btools.mapaccess.PhysicalFile;

public class IntegrityCheckTest
{
  private File workingDir;

  @Test
  public void integrityTest() throws Exception
  {
    URL resulturl = this.getClass().getResource( "/testtrack0.gpx" );
    Assert.assertTrue( "reference result not found: ", resulturl != null );
    File resultfile = new File( resulturl.getFile() );
    workingDir = resultfile.getParentFile();

    File segmentDir = new File( workingDir, "/../../../brouter-map-creator/target/test-classes/tmp/segments" );
    File[] files = segmentDir.listFiles();

    for ( File f : files )
    {
      PhysicalFile.checkFileIntegrity( f );
    }
  }

}
