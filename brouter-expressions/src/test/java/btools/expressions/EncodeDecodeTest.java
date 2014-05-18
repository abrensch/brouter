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
    URL lookupurl = this.getClass().getResource( "/lookups.dat" );
    Assert.assertTrue( "lookup file lookup.dat not found", lookupurl != null );
    File lookupFile = new File(lookupurl.getFile());
    File workingDir = lookupFile.getParentFile();
  	
    // read lookup.dat + trekking.brf
    BExpressionContext expctxWay = new BExpressionContext("way");
    expctxWay.readMetaData( lookupFile );
    expctxWay.parseFile( new File( workingDir, "trekking.brf" ), "global" );

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
    expctxWay.evaluate( false, description, null );

    float costfactor = expctxWay.getCostfactor();
    Assert.assertTrue( "costfactor mismatch", Math.abs( costfactor - 5.1 ) > 0.00001 );
  }
}
