package btools.memrouter;

import java.io.File;
import java.net.URL;

import org.junit.Assert;
import org.junit.Test;

import btools.expressions.BExpressionContextWay;
import btools.expressions.BExpressionMetaData;

public class MemrouterTest
{
  @Test
  public void memrouterTest() throws Exception
  {
    URL dummyurl = this.getClass().getResource( "/dummy.txt" );
    Assert.assertTrue( "dummy.txt not found", dummyurl != null );
    File workingDir = new File(dummyurl.getFile()).getParentFile();
    File profileDir = new File( workingDir, "/../../../misc/profiles2" );
    File dataDir = new File( workingDir, "/../../../brouter-map-creator/target/test-classes/tmp" );
    File lookupFile = new File( profileDir, "lookups.dat" );
    File profileFile = new File( profileDir, "trekking.brf" );
    File waytiles55 = new File( dataDir, "waytiles55" );
    File unodes55 = new File( dataDir, "unodes55" );
    File[] fahrplanFiles = new File[1];
    fahrplanFiles[0] = new File( workingDir, "fahrplan.txt" );
    
    // read lookup + profile for lookup-version + access-filter
    BExpressionMetaData meta = new BExpressionMetaData();
    BExpressionContextWay expctxWay = new BExpressionContextWay(meta);
    meta.readMetaData( lookupFile );
    expctxWay.parseFile( profileFile, "global" );


    // run GraphLoader
    new GraphLoader().process( unodes55, waytiles55, fahrplanFiles, expctxWay );
  }
}
