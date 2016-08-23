package btools.util;

import org.junit.Assert;
import org.junit.Test;

public class BitCoderContextTest
{
  @Test
  public void varBitsEncodeDecodeTest()
  {
    byte[] ab = new byte[4000];
    BitCoderContext ctx = new BitCoderContext( ab );
    for ( int i = 0; i < 1000; i++ )
    {
      ctx.encodeVarBits( i );
    }
    ctx = new BitCoderContext( ab );

    for ( int i = 0; i < 1000; i++ )
    {
      int value = ctx.decodeVarBits();
      Assert.assertTrue( "distance value mismatch i=" + i + "v=" + value, value == i );
    }
  }

  @Test
  public void boundedEncodeDecodeTest()
  {
    byte[] ab = new byte[581969];
    BitCoderContext ctx = new BitCoderContext( ab );
    for ( int max = 1; max < 1000; max++ )
    {
      for ( int val = 0; val <= max; val++ )
      {
        ctx.encodeBounded( max, val );
      }
    }

    ctx = new BitCoderContext( ab );

    for ( int max = 1; max < 1000; max++ )
    {
      for ( int val = 0; val <= max; val++ )
      {
        int valDecoded = ctx.decodeBounded( max );
        if ( valDecoded != val )
        {
          Assert.fail( "mismatch at max=" + max + " " + valDecoded + "<>" + val );
        }
      }
    }
  }
}
