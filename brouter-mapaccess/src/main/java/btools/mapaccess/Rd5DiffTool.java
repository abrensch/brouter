/**
 * Proof of concept for delta rd5's
 *
 * @author ab
 */
package btools.mapaccess;

import java.io.File;

import btools.codec.DataBuffers;
import btools.codec.MicroCache;
import btools.codec.MicroCache2;
import btools.codec.StatCoderContext;

final public class Rd5DiffTool
{
  public static void main( String[] args ) throws Exception
  {
    diff2files( new File( args[0] ),new File( args[1] ) );
  }

  /**
   * Compute the delta between 2 RD5 files and
   * show statistics on the expected size of the delta file
   */
  public static void diff2files( File f1, File f2 ) throws Exception
  {
    byte[] abBuf1 = new byte[10 * 1024 * 1024];
    byte[] abBuf2 = new byte[10 * 1024 * 1024];
    
    int nodesTotal = 0;
    int nodesDiff = 0;
    int diffedTiles = 0;
    
    long bytesDiff = 0L;
    long diffedTileSize = 0L;

    PhysicalFile pf1 = null;
    PhysicalFile pf2 = null;
    try
    {
      DataBuffers dataBuffers = new DataBuffers();
      pf1 = new PhysicalFile( f1, dataBuffers, -1, -1 );
      pf2 = new PhysicalFile( f2, dataBuffers, -1, -1 );
      int div = pf1.divisor;
      for ( int lonDegree = 0; lonDegree < 5; lonDegree++ ) // does'nt really matter..
      {
        for ( int latDegree = 0; latDegree < 5; latDegree++ ) // ..where on earth we are
        {
          OsmFile osmf1 = new OsmFile( pf1, lonDegree, latDegree, dataBuffers );
          OsmFile osmf2 = new OsmFile( pf2, lonDegree, latDegree, dataBuffers );
          for ( int lonIdx = 0; lonIdx < div; lonIdx++ )
          {
            for ( int latIdx = 0; latIdx < div; latIdx++ )
            {
              int lonIdxDiv = lonDegree * div + lonIdx;
              int latIdxDiv = latDegree * div + latIdx;
              
            
              MicroCache mc1 = osmf1.hasData() ? 
                osmf1.createMicroCache( lonIdxDiv, latIdxDiv, dataBuffers, null, null, true, null )
              : MicroCache.emptyCache();
              MicroCache mc2 = osmf2.hasData() ? 
                osmf2.createMicroCache( lonIdxDiv, latIdxDiv, dataBuffers, null, null, true, null )
              : MicroCache.emptyCache();
              
              MicroCache mc = new MicroCache2( mc1.getSize() + mc2.getSize(), abBuf2, lonIdxDiv, latIdxDiv, div );
              mc.calcDelta( mc1, mc2 );
              
              nodesTotal += mc2.getSize();
              
              if ( mc.getSize() > 0 )
              {
                 int len = mc.encodeMicroCache( abBuf1 );
                 byte[] bytes = new byte[len];
                 System.arraycopy( abBuf1, 0, bytes, 0, len );

                 bytesDiff += len;
                 nodesDiff += mc.getSize();
                 diffedTiles++;
                 diffedTileSize += mc2.size();
                 
                 // cross-check the encoding: decode again
                 MicroCache mcCheck = new MicroCache2( new StatCoderContext( bytes ), new DataBuffers( null ), lonIdxDiv, latIdxDiv, div, null, null );
                 
                 // due to link-order ambiguity, for decoded we can only compare node-count and datasize
                 if ( mc.size() != mcCheck.size() )
                 {
                   throw new IllegalArgumentException( "re-decoded data-size mismatch!" );
                 }
                 if ( mc.getSize() != mcCheck.getSize() )
                 {
                   throw new IllegalArgumentException( "re-decoded node-count mismatch!" );
                 }
                 
                 // .... so re-encode again
                 int len2 = mcCheck.encodeMicroCache( abBuf1 );
                 byte[] bytes2 = new byte[len2];
                 System.arraycopy( abBuf1, 0, bytes2, 0, len2 );
                 
                 // and here we can compare byte-by-byte
                 if ( len != len2 )
                 {
                   throw new IllegalArgumentException( "decoded size mismatch!" );
                 }
                 for( int i=0; i<len; i++ )
                 {
                   if ( bytes[i] != bytes2[i] )
                   {
                     throw new IllegalArgumentException( "decoded data mismatch at i=" + i );
                   }
                 }
              }
            }
          }
        }
      }
      System.out.println( "nodesTotal=" + nodesTotal + " nodesDiff=" + nodesDiff + " bytesDiff=" + bytesDiff + " diffedTiles=" + diffedTiles + " diffedTileSize=" + diffedTileSize );
    }
    finally
    {
      if ( pf1 != null )
      {
        try
        {
          pf1.ra.close();
        }
        catch (Exception ee)
        {
        }
      }
      if ( pf2 != null )
      {
        try
        {
          pf2.ra.close();
        }
        catch (Exception ee)
        {
        }
      }
    }
  }
}
