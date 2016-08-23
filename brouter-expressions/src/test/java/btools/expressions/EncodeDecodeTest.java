package btools.expressions;

import java.util.*;
import java.io.*;
import java.net.URL;

import org.junit.Assert;
import org.junit.Test;

public class EncodeDecodeTest
{
  @Test
  public void encodeDecodeTest()
  {
    URL testpurl = this.getClass().getResource( "/dummy.txt" );
    File workingDir = new File(testpurl.getFile()).getParentFile();
    File profileDir = new File( workingDir, "/../../../misc/profiles2" );
    File lookupFile = new File( profileDir, "lookups.dat" );
  	
    // read lookup.dat + trekking.brf
    BExpressionMetaData meta = new BExpressionMetaData();
    BExpressionContextWay expctxWay = new BExpressionContextWay( meta );
    meta.readMetaData( lookupFile );
    expctxWay.parseFile( new File( profileDir, "trekking.brf" ), "global" );

    String[] tags = { "highway=residential",  "oneway=yes",  "reversedirection=yes" };

    // encode the tags into 64 bit description word
    int[] lookupData = expctxWay.createNewLookupData();
    for( String arg: tags )
    {
      int idx = arg.indexOf( '=' );
      if ( idx < 0 ) throw new IllegalArgumentException( "bad argument (should be <tag>=<value>): " + arg );
      String key = arg.substring( 0, idx );
      String value = arg.substring( idx+1 );
 
      expctxWay.addLookupValue( key, value, lookupData );
    }
    byte[] description = expctxWay.encode(lookupData);

    // calculate the cost factor from that description
    expctxWay.evaluate( true, description ); // true = "reversedirection=yes"  (not encoded in description anymore)

    float costfactor = expctxWay.getCostfactor();
    Assert.assertTrue( "costfactor mismatch", Math.abs( costfactor - 5.15 ) < 0.00001 );
  }
}
