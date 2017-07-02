package btools.mapsplitter;

import java.util.Arrays;
import java.util.Random;

import org.junit.Assert;
import org.junit.Test;

import btools.mapdecoder.BitReadBuffer;
import btools.mapdecoder.CharDecoder;

public class BitCodingTest
{
  // @Test
  public void charEncodeDecodeTest()
  {
    byte[] ab = new byte[4000];
    BitWriteBuffer bwb = new BitWriteBuffer( ab );
    CharEncoder ce = new CharEncoder();
  
    for( int pass=1; pass<=3; pass++ )
    {
      ce.encodeDictionary( bwb );
      for ( char c = 'a'; c <= 'z'; c++ )
      {
        ce.encode( Character.valueOf( c ) );
      }
    }

    BitReadBuffer brb = new BitReadBuffer( ab );
    CharDecoder cd = new CharDecoder( brb );
    for ( char c = 'a'; c <= 'z'; c++ )
    {
      Character c1 = cd.decode();
      Assert.assertTrue( "char mismatch c=" + c + "c1=" + c1, c == c1.charValue() );
    }
  }

  @Test
  public void varBitsEncodeDecodeTest()
  {
    byte[] ab = new byte[4000];
    BitWriteBuffer bwb = new BitWriteBuffer( ab );
    for ( int i = 0; i < 1000; i++ )
    {
      bwb.encodeInt( i );
      bwb.encodeLong( i );
    }
    BitReadBuffer brb = new BitReadBuffer( ab );

    for ( int i = 0; i < 1000; i++ )
    {
      int value = brb.decodeInt();
      Assert.assertTrue( "int value mismatch i=" + i + "v=" + value, value == i );
      long lvalue = brb.decodeLong();
      Assert.assertTrue( "long value mismatch i=" + i + "v=" + lvalue, value == i );
    }
  }

  @Test
  public void boundedEncodeDecodeTest()
  {
    byte[] ab = new byte[581969];
    BitWriteBuffer bwb = new BitWriteBuffer( ab );
    for ( int max = 1; max < 1000; max++ )
    {
      for ( int val = 0; val <= max; val++ )
      {
        bwb.encodeBounded( max, val );
      }
    }

    BitReadBuffer brb = new BitReadBuffer( ab );

    for ( int max = 1; max < 1000; max++ )
    {
      for ( int val = 0; val <= max; val++ )
      {
        long valDecoded = brb.decodeBounded( max );
        if ( valDecoded != val )
        {
          Assert.fail( "mismatch at max=" + max + " " + valDecoded + "<>" + val );
        }
      }
    }
  }

  @Test
  public void sortedLongArrayEncodeDecodeTest()
  {
    Random rand = new Random(1234);
    int size = 20;
    long[] values = new long[size];
    for ( int i = 0; i < size; i++ )
    {
      values[i] = rand.nextInt() & 0x0fffffff;
    }
    values[5] = 175384; // force collision
    values[8] = 175384;

    values[15] = 275384; // force neighbours
    values[18] = 275385;

    encodeDecodeArray( "Test1", values );

    values = new long[1];
    values[0] = 0x134567890123456L;
    encodeDecodeArray( "Test2", values );

    values = new long[0];
    encodeDecodeArray( "Test3", values );

    values = new long[100000];
    for ( int i = 0; i < values.length; i++ )
    {
      values[i] = (((long)rand.nextInt())&0xffffffffL) << rand.nextInt(26); // 32 + 25 bits
    }
    encodeDecodeArray( "Test4", values );
  }

  private void encodeDecodeArray( String testName, long[] values )
  {
    Arrays.sort( values );

    byte[] ab = new byte[3000000];
    BitWriteBuffer bwb = new BitWriteBuffer( ab );

    bwb.encodeSortedArray( values );

    long[] decodedValues = new BitReadBuffer( ab ).decodeSortedArray();

    for ( int i = 0; i < values.length; i++ )
    {
      if ( values[i] != decodedValues[i] )
      {
        Assert.fail( "mismatch at " + testName + " i=" + i + " " + values[i] + "<>" + decodedValues[i] );
      }
    }
  }

}
