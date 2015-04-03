package btools.expressions;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.TreeMap;

import btools.util.BitCoderContext;
import btools.util.Crc32;
import java.util.Random;

public final class ProfileComparator
{
  public static void main( String[] args )
  {
    if ( args.length != 4 )
    {
      System.out.println( "usage: java ProfileComparator <lookup-file> <profile1> <profile2> <nsamples>" );
      return;
    }

    File lookupFile = new File( args[0] );
    File profile1File = new File( args[1] );
    File profile2File = new File( args[2] );
    int nsamples = Integer.parseInt( args[3] );
    testContext( lookupFile, profile1File, profile2File, nsamples, false );
    testContext( lookupFile, profile1File, profile2File, nsamples, true );

  }
  
  private static void testContext( File lookupFile, File profile1File, File profile2File, int nsamples, boolean nodeContext )
  {
    // read lookup.dat + profiles
    BExpressionMetaData meta1 = new BExpressionMetaData();
    BExpressionMetaData meta2 = new BExpressionMetaData();
    BExpressionContext expctx1 = nodeContext ? new BExpressionContextNode( meta1 ) : new BExpressionContextWay( meta1 );
    BExpressionContext expctx2 = nodeContext ? new BExpressionContextNode( meta2 ) : new BExpressionContextWay( meta2 );
    meta1.readMetaData( lookupFile );
    meta2.readMetaData( lookupFile );
    expctx1.parseFile( profile1File, "global" );
    expctx2.parseFile( profile2File, "global" );

    Random rnd = new Random();
    for( int i=0; i<nsamples; i++ )
    {
      int[] data = expctx1.generateRandomValues( rnd );
      expctx1.evaluate( data );
      expctx2.evaluate( data );
      
      expctx1.assertAllVariablesEqual( expctx2 );
    }
  }  
}
