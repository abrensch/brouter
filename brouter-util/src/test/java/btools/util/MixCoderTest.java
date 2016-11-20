package btools.util;

import java.util.Random;
import java.io.*;

import org.junit.Assert;
import org.junit.Test;

public class MixCoderTest
{
  @Test
  public void mixEncodeDecodeTest() throws IOException
  {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    MixCoderDataOutputStream mco = new MixCoderDataOutputStream( baos );
    MixCoderDataInputStream mci = null;

    for(;;)
    {
      Random rnd = new Random( 1234 );
      for( int i=0; i<1500; i++ )
      {
        checkEncodeDecode( rnd.nextInt( 3800 ), mco, mci );
      }
      for( int i=0; i<1500; i++ )
      {
        checkEncodeDecode( rnd.nextInt( 35 ), mco, mci );
      }
      for( int i=0; i<1500; i++ )
      {
        checkEncodeDecode( 0, mco, mci );
      }
      for( int i=0; i<1500; i++ )
      {
        checkEncodeDecode( 1000, mco, mci );
      }

      if ( mco != null )
      {
        mco.close();
        mco = null;
        mci = new MixCoderDataInputStream( new ByteArrayInputStream( baos.toByteArray() ) );
      }
      else break; 
    }
  }

  private void checkEncodeDecode( int v, MixCoderDataOutputStream mco, MixCoderDataInputStream mci ) throws IOException
  {
    if ( mco != null )
    {
      mco.writeMixed( v );
    }
    if ( mci != null )
    {
      long vv = mci.readMixed();
      if ( vv != v )
      {
        Assert.assertTrue( "value mismatch: v=" + v + " vv=" + vv, false );
      }
    }
  }
}
