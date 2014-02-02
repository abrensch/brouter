/**
 * cache for a single square
 *
 * @author ab
 */
package btools.mapaccess;

import java.io.IOException;
import java.io.RandomAccessFile;
import btools.util.Crc32;

final class OsmFile
{
  private RandomAccessFile is = null;
  private long fileOffset;

  private int[] posIdx;
  public MicroCache[] microCaches;

  public int lonDegree;
  public int latDegree;

  public String filename;

  public boolean ghost = false;

  public OsmFile( PhysicalFile rafile, int tileIndex, byte[] iobuffer ) throws Exception
  {
    if ( rafile != null )
    {
      filename = rafile.fileName;

      long[] index = rafile.fileIndex;
      fileOffset = tileIndex > 0 ? index[ tileIndex-1 ] : 200L;
      if ( fileOffset == index[ tileIndex] ) return; // empty
    	
      is = rafile.ra;
      posIdx = new int[6400];
      microCaches = new MicroCache[6400];
      is.seek( fileOffset );
      is.readFully( iobuffer, 0, 25600 );
      
      if ( rafile.fileHeaderCrcs != null )
      {
        int headerCrc = Crc32.crc( iobuffer, 0, 25600 );
        if ( rafile.fileHeaderCrcs[tileIndex] != headerCrc )
        {
          throw new IOException( "sub index checksum error" );
        }
      }
      
      ByteDataReader dis = new ByteDataReader( iobuffer );
      for( int i=0; i<6400; i++ )
      {
        posIdx[i] = dis.readInt();
      }
    }
  }

  private int getPosIdx( int idx )
  {
    return  idx == -1 ? 25600 : posIdx[idx];
  }

  public int getDataInputForSubIdx( int subIdx, byte[] iobuffer ) throws Exception
  {
     int startPos = getPosIdx(subIdx-1);
     int endPos = getPosIdx(subIdx);
     int size = endPos-startPos;
     if ( size > 0 )
     {
       is.seek( fileOffset + startPos );
       if ( size <= iobuffer.length )
       {
         is.readFully( iobuffer, 0, size );
       }
     }
     return size;
  }

  // set this OsmFile to ghost-state:
  long setGhostState()
  {
    long sum = 0;
    ghost = true;
    for( int i=0; i< microCaches.length; i++ )
    {
      MicroCache mc = microCaches[i];
      if ( mc == null ) continue;
      if ( mc.virgin )
      {
        mc.ghost = true;
        sum += mc.getDataSize();
      }
      else
      {
        microCaches[i] = null;
      }
    }
    return sum;
  }

  void cleanAll()
  {
    for( int i=0; i< microCaches.length; i++ )
    {
      MicroCache mc = microCaches[i];
      if ( mc == null ) continue;
      if ( mc.ghost )
      {
        microCaches[i] = null;
      }
      else
      {
        mc.collect();
      }
    }
  }

}
