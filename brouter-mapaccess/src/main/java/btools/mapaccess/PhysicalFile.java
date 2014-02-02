/**
 * cache for a single square
 *
 * @author ab
 */
package btools.mapaccess;

import java.io.*;
import btools.util.Crc32;

final class PhysicalFile
{
  RandomAccessFile ra = null;
  long[] fileIndex = new long[25];
  int[] fileHeaderCrcs;
   
  private int fileIndexCrc;
  public long creationTime;

  String fileName;
  
  public PhysicalFile( File f, byte[] iobuffer, int lookupVersion ) throws Exception
  {
    fileName = f.getName();

    ra = new RandomAccessFile( f, "r" );
    ra.readFully( iobuffer, 0, 200 );
    fileIndexCrc = Crc32.crc( iobuffer, 0, 200 );
    ByteDataReader dis = new ByteDataReader( iobuffer );
    for( int i=0; i<25; i++ )
    {
      long lv = dis.readLong();
      short readVersion = (short)(lv >> 48);
      if ( readVersion != lookupVersion )
      {
        throw new IllegalArgumentException( "lookup version mismatch (old rd5?) lookups.dat="
                 + lookupVersion + " " + f. getAbsolutePath() + "=" + readVersion );
      }
      fileIndex[i] = lv & 0xffffffffffffL;
    }

    // read some extra info from the end of the file, if present
    long len = ra.length();

    long pos = fileIndex[24];
    int extraLen = 8 + 26*4;

    if ( len == pos ) return; // old format o.k.

    if ( len < pos+extraLen ) // > is o.k. for future extensions!
    {
      throw new IOException( "file of size " + len + " + too short, should be " + (pos+extraLen) );
    }
    
    ra.seek( pos );
    ra.readFully( iobuffer, 0, extraLen );
    dis = new ByteDataReader( iobuffer );
    creationTime = dis.readLong();
    if ( dis.readInt() != fileIndexCrc )
    {
      throw new IOException( "top index checksum error" );
    }
    fileHeaderCrcs = new int[25];
    for( int i=0; i<25; i++ )
    {
      fileHeaderCrcs[i] = dis.readInt();
    }
  }
}
