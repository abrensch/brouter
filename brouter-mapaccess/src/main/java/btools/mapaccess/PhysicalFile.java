/**
 * cache for a single square
 *
 * @author ab
 */
package btools.mapaccess;

import java.io.*;

import btools.util.ByteDataReader;
import btools.util.Crc32;

final public class PhysicalFile
{
  RandomAccessFile ra = null;
  long[] fileIndex = new long[25];
  int[] fileHeaderCrcs;
   
  private int fileIndexCrc;
  public long creationTime;

  String fileName;
  
  /**
   * Checks the integrity of the file using the build-in checksums
   *
   * @return the error message if file corrupt, else null
   */
  public static String checkFileIntegrity( File f )
  {
      PhysicalFile pf = null;
	  try
	  {
        byte[] iobuffer = new byte[65636];
        pf = new PhysicalFile( f, new byte[65636], -1, -1 );
      	for( int tileIndex=0; tileIndex<25; tileIndex++ )
      	{
		  OsmFile osmf = new OsmFile( pf, tileIndex, iobuffer );
		  if ( osmf.microCaches != null )
		    for( int lonIdx80=0; lonIdx80<80; lonIdx80++ )
			  for( int latIdx80=0; latIdx80<80; latIdx80++ )
                new MicroCache( osmf, lonIdx80, latIdx80, iobuffer );
      	}
	  }
	  catch( IllegalArgumentException iae )
	  {
	    return iae.getMessage();
	  }
	  catch( Exception e )
	  {
	    return e.toString();
	  }
	  finally
	  {
        if ( pf != null ) try{ pf.ra.close(); } catch( Exception ee ) {}
	  }
	  return null;
  }

  public PhysicalFile( File f, byte[] iobuffer, int lookupVersion, int lookupMinorVersion ) throws Exception
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
      if ( i == 0 && lookupVersion != -1 && readVersion != lookupVersion )
      {
        throw new IllegalArgumentException( "lookup version mismatch (old rd5?) lookups.dat="
                 + lookupVersion + " " + f. getAbsolutePath() + "=" + readVersion );
      }
      if ( i == 1 && lookupMinorVersion != -1 && readVersion < lookupMinorVersion )
      {
        throw new IllegalArgumentException( "lookup minor version mismatch (old rd5?) lookups.dat="
                 + lookupMinorVersion + " " + f. getAbsolutePath() + "=" + readVersion );
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
