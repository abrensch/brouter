package btools.util;

import java.util.Random;
import java.io.*;

import org.junit.Assert;
import org.junit.Test;

public class ReducedMedianFilterTest
{
  @Test
  public void reducedMedianFilterTest() throws IOException
  {
    ReducedMedianFilter f = new ReducedMedianFilter( 10 );
    f.reset();
    f.addSample( .2, 10 );
    f.addSample( .2, 10 );
    f.addSample( .2, 10 );
    f.addSample( .2, 15 );
    f.addSample( .2, 20 );
    
    double m = f.calcEdgeReducedMedian( 0.5 );
    Assert.assertTrue( "median1 mismatch m=" + m + " expected 11.5", doubleEquals( m, 11.5 ) );

    f.reset();
    f.addSample( .2, 10 );
    f.addSample( .2, 10 );
    f.addSample( .2, 10 );
    f.addSample( .2, 10 );
    f.addSample( .2, 20 );

    m = f.calcEdgeReducedMedian( 1. );
    Assert.assertTrue( "median1 mismatch m=" + m + " expected 12", doubleEquals( m, 12. ) );

    f.reset();
    f.addSample( .5, -10 );
    f.addSample( .5, 10 );
    m = f.calcEdgeReducedMedian( 0.5 );
    Assert.assertTrue( "median2 mismatch m=" + m + " expected 0", doubleEquals( m, 0. ) );
  }

  private boolean doubleEquals( double d1, double d2 )
  {
    double d = d1 - d2;
    return d < 1e-9 && d > -1e-9;
  }
}
