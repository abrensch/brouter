/**
 * cache for a single square
 *
 * @author ab
 */
package btools.mapaccess;

import java.util.*;
import java.io.*;

final class MicroCache
{
  private long[] faid;
  private int[] fapos;
  private int size = 0;
  private int delcount = 0;
  private int delbytes = 0;
  private int p2size; // next power of 2 of size

  // the object parsing position and length
  private byte[] ab;
  private int aboffset;
  private int ablength;

  public MicroCache( OsmFile segfile, int lonIdx80, int latIdx80, byte[] iobuffer ) throws Exception
  {
     int lonDegree = lonIdx80/80;
     int latDegree = latIdx80/80;

     int lonIdxBase = (lonIdx80/5)*62500 + 31250;
     int latIdxBase = (latIdx80/5)*62500 + 31250;

     int subIdx = (latIdx80-80*latDegree)*80 + (lonIdx80-80*lonDegree);

     try
     {
       ab = iobuffer;
       int asize = segfile.getDataInputForSubIdx(subIdx, ab);
       if ( asize == 0 )
       {
         return;
       }
       if ( asize > iobuffer.length )
       {
         ab = new byte[asize];
         asize = segfile.getDataInputForSubIdx(subIdx, ab);
       }
       aboffset = 0;
       size = readInt();

       // new array with only net data
       byte[] nab = new byte[asize - 4 - size*8];
       int noffset = 0;
       faid = new long[size];
       fapos = new int[size];
       p2size = 0x40000000;
       while( p2size > size ) p2size >>= 1;
       
       for(int i = 0; i<size; i++)
       {
         int ilon = readShort();
         int ilat = readShort();
         ilon += lonIdxBase;
         ilat += latIdxBase;
         long nodeId = ((long)ilon)<<32 | ilat;

         faid[i] = nodeId;
         int bodySize = readInt();
         fapos[i] = noffset;
         System.arraycopy( ab, aboffset, nab, noffset, bodySize );
         aboffset += bodySize;
         noffset += bodySize;
       }
       ab = nab;
     }
     catch( EOFException eof )
     {
     }
  }

  public int getSize()
  {
    return size;
  }
  
  /**
   * @return the value for "id",
   * Throw an exception if not contained in the map.
   */
  private boolean getAndClear( long id )
  {
    if ( size == 0 )
    {
      return false;
    }
    long[] a = faid;
    int offset = p2size;
    int n = 0;

    
    while ( offset> 0 )
    {
      int nn = n + offset;
      if ( nn < size && a[nn] <= id )
      {
        n = nn;
      }
      offset >>= 1;
    }
    if ( a[n] == id )
    {
      if ( ( fapos[n] & 0x80000000 ) == 0 )
      {
        aboffset = fapos[n];
        ablength = ( n+1 < size ? fapos[n+1] & 0x7fffffff : ab.length ) - aboffset;
        fapos[n] |= 0x80000000; // mark deleted
        delbytes+= ablength;
        delcount++;
        return true;
      }
      else
      {
        throw new RuntimeException( "MicroCache: node already consumed: id=" + id );
      }
    }
    return false;
  }

  public void fillNode( OsmNode node, OsmNodesMap nodesMap, DistanceChecker dc )
  {
    long id = node.getIdFromPos();
    if ( getAndClear( id ) )
    {
      node.parseNodeBody( this, ablength, nodesMap, dc );
    }

    if ( delcount > size / 2 ) // garbage collection
    {
      int nsize = size - delcount;
      if ( nsize == 0 )
      {
        faid = null;
        fapos = null;
      }
      else
      {
        long[] nfaid = new long[nsize];
        int[] nfapos = new int[nsize];
        int idx = 0;

        byte[] nab = new byte[ab.length - delbytes];
        int nab_off = 0;
        for( int i=0; i<size; i++ )
        {
      	  int pos = fapos[i];
          if ( ( pos & 0x80000000 ) == 0 )
          {
            ablength = ( i+1 < size ? fapos[i+1] & 0x7fffffff : ab.length ) - pos;
            System.arraycopy( ab, pos, nab, nab_off, ablength );
            nfaid[idx] = faid[i];
            nfapos[idx] = nab_off;
            nab_off += ablength;
            idx++;
          }
        }
        faid = nfaid;
        fapos = nfapos;
        ab = nab;
      }
      size = nsize;
      delcount = 0;
      delbytes = 0;
      p2size = 0x40000000;
      while( p2size > size ) p2size >>= 1;
    }
  }

  public List<OsmNode> getPositions( OsmNodesMap nodesMap )
  {
    ArrayList<OsmNode> positions = new ArrayList<OsmNode>();

    for( int i=0; i<size; i++ )
    {
      OsmNode n = new OsmNode( faid[i] );
      n.setHollow();
      nodesMap.put( faid[i], n );
      positions.add( n );
    }
    return positions;
  }

  public int readInt()
  {
      int i3 = ab[aboffset++]& 0xff;
      int i2 = ab[aboffset++]& 0xff;
      int i1 = ab[aboffset++]& 0xff;
      int i0 = ab[aboffset++]& 0xff;
      return (i3 << 24) + (i2 << 16) + (i1 << 8) + i0;
  }

  public long readLong()
  {
      long i7 = ab[aboffset++]& 0xff;
      long i6 = ab[aboffset++]& 0xff;
      long i5 = ab[aboffset++]& 0xff;
      long i4 = ab[aboffset++]& 0xff;
      long i3 = ab[aboffset++]& 0xff;
      long i2 = ab[aboffset++]& 0xff;
      long i1 = ab[aboffset++]& 0xff;
      long i0 = ab[aboffset++]& 0xff;
      return (i7 << 56) + (i6 << 48) + (i5 << 40) + (i4 << 32) + (i3 << 24) + (i2 << 16) + (i1 << 8) + i0;
  }

  public boolean readBoolean()
  {
      int i0 = ab[aboffset++]& 0xff;
      return i0 != 0;
  }

  public byte readByte()
  {
      int i0 = ab[aboffset++] & 0xff;
      return (byte)(i0);
  }

  public short readShort()
  {
      int i1 = ab[aboffset++] & 0xff;
      int i0 = ab[aboffset++] & 0xff;
      return (short)( (i1 << 8) | i0);
  }
}
