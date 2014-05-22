package btools.util;

import java.util.Random;
import java.util.HashSet;

import org.junit.Assert;
import org.junit.Test;

public class BitCoderContextTest
{
  @Test
  public void distanceEncodeDecodeTest()
  {
  	byte[] ab = new byte[4000];
  	BitCoderContext ctx = new BitCoderContext( ab );
  	for( int i=0; i<1000; i++ )
  	{
  		ctx.encodeDistance( i );
    }
  	ctx = new BitCoderContext( ab );
    
  	for( int i=0; i<1000; i++ )
  	{
      int value = ctx.decodeDistance();
      Assert.assertTrue( "distance value mismatch", value == i );
    }
  }
}
