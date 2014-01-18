/**
 * cache for a single square
 *
 * @author ab
 */
package btools.mapaccess;

import java.io.IOException;
import java.io.RandomAccessFile;

final class OsmFile
{
  private RandomAccessFile is = null;
  private long fileOffset;

  private int[] posIdx;
  public MicroCache[] microCaches;

  public int lonDegree;
  public int latDegree;

  public String filename;

  public OsmFile( RandomAccessFile rafile, long startPos, byte[] iobuffer ) throws Exception
  {
    fileOffset = startPos;
    if ( rafile != null )
    {
      is = rafile;
      posIdx = new int[6400];
      microCaches = new MicroCache[6400];
      is.seek( fileOffset );
      is.readFully( iobuffer, 0, 25600 );
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
         is.readFully( iobuffer );
       }
     }
     return size;
  }

  public void close()
  {
    try { is.close(); } catch( IOException e ) { throw new RuntimeException( e ); }
  }
}
