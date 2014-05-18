/**
 * cache for a single square
 *
 * @author ab
 */
package btools.mapaccess;

import java.util.*;
import java.io.*;

import btools.util.Crc32;

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

  // cache control: a virgin cache can be
  // put to ghost state for later recovery
  boolean virgin = true;
  boolean ghost = false;

  public MicroCache( OsmFile segfile, int lonIdx80, int latIdx80, byte[] iobuffer ) throws Exception
  {
     int lonDegree = lonIdx80/80;
     int latDegree = latIdx80/80;

     int lonIdxBase = (lonIdx80/5)*62500 + 31250;
     int latIdxBase = (latIdx80/5)*62500 + 31250;

     int subIdx = (latIdx80-80*latDegree)*80 + (lonIdx80-80*lonDegree);

     {
       ab = iobuffer;
       int asize = segfile.getDataInputForSubIdx(subIdx, ab);

       if ( asize == 0 )
       {
         ab = null;
         return;
       }
       if ( asize > iobuffer.length )
       {
         ab = new byte[asize];
         asize = segfile.getDataInputForSubIdx(subIdx, ab);
       }
       aboffset = 0;
       size = readInt();

       // get net size
       int nbytes = 0;
       for(int i = 0; i<size; i++)
       {
         int ilon = readShort();
         int ilat = readShort();
         int bodySize = readInt();
         if ( ilon == Short.MAX_VALUE && ilat == Short.MAX_VALUE )
         {
           int crc = Crc32.crc( ab, 0, aboffset-8 );
           if ( crc != readInt() )
           {
             throw new IOException( "checkum error" );
           }
           size = i;
           break;
         }
         aboffset += bodySize;
         nbytes += bodySize;
       }

       // new array with only net data
       byte[] nab = new byte[nbytes];
       aboffset = 4;
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
  }

  public int getSize()
  {
    return size;
  }
  
  public int getDataSize()
  {
    return ab == null ? 0 : ab.length;
  }

  /**
   * Set the internal reader (aboffset, ablength)
   * to the body data for the given id
   *
   * @return true if id was found
   *
   * Throws an exception if that id was already requested
   * as an early detector for identity problems
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

  /**
   * Fill a hollow node with it's body data
   */
  public void fillNode( OsmNode node, OsmNodesMap nodesMap, DistanceChecker dc, boolean doCollect )
  {
    long id = node.getIdFromPos();
    if ( getAndClear( id ) )
    {
      node.parseNodeBody( this, ablength, nodesMap, dc );
    }

    if ( doCollect && delcount > size / 2 ) // garbage collection
    {
      collect();
    }
  }

  void collect()
  {
    if ( delcount > 0 )
    {
      virgin = false;

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

  void unGhost()
  {
    ghost = false;
    delcount = 0;
    delbytes = 0;
    for( int i=0; i<size; i++ )
    {
      fapos[i] &= 0x7fffffff; // clear deleted flags
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

  public void readFully( byte[] ta )
  {
	  System.arraycopy( ab, aboffset, ta, 0, ta.length );
	  aboffset += ta.length;
  }
}
