/**
 * cache for a single square
 *
 * @author ab
 */
package btools.mapaccess;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import btools.util.ByteDataReader;
import btools.util.Crc32;

final class MicroCache extends ByteDataReader
{
  private int[] faid;
  private int[] fapos;
  private int size = 0;
  private int delcount = 0;
  private int delbytes = 0;
  private int p2size; // next power of 2 of size
  
  // the object parsing position and length
  private int aboffsetEnd;

  private int lonIdxBase;
  private int latIdxBase;

  // cache control: a virgin cache can be
  // put to ghost state for later recovery
  boolean virgin = true;
  boolean ghost = false;

  public MicroCache( OsmFile segfile, int lonIdx80, int latIdx80, byte[] iobuffer ) throws Exception
  {
	 super( null );
     int lonDegree = lonIdx80/80;
     int latDegree = latIdx80/80;

     lonIdxBase = (lonIdx80/5)*62500 + 31250;
     latIdxBase = (latIdx80/5)*62500 + 31250;

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
         aboffset += 4;
         int bodySize = readVarLengthUnsigned();

         aboffset += bodySize;
         nbytes += bodySize;
       }

       int crc = Crc32.crc( ab, 0, aboffset );
       if ( crc != readInt() )
       {
         throw new IOException( "checkum error" );
       }

       // new array with only net data
       byte[] nab = new byte[nbytes];
       aboffset = 4;
       int noffset = 0;
       faid = new int[size];
       fapos = new int[size];
       p2size = 0x40000000;
       while( p2size > size ) p2size >>= 1;

       for(int i = 0; i<size; i++)
       {
         faid[i] = readInt() ^ 0x8000; // flip lat-sign for correct ordering

         int bodySize = readVarLengthUnsigned();
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
  private boolean getAndClear( long id64 )
  {
    if ( size == 0 )
    {
      return false;
    }
    int id = shrinkId( id64 );
    int[] a = faid;
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
        int ablength = ( n+1 < size ? fapos[n+1] & 0x7fffffff : ab.length ) - aboffset;
        aboffsetEnd = aboffset + ablength;
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
      node.parseNodeBody( this, nodesMap, dc );
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
        int[] nfaid = new int[nsize];
        int[] nfapos = new int[nsize];
        int idx = 0;

        byte[] nab = new byte[ab.length - delbytes];
        int nab_off = 0;
        for( int i=0; i<size; i++ )
        {
      	  int pos = fapos[i];
          if ( ( pos & 0x80000000 ) == 0 )
          {
            int ablength = ( i+1 < size ? fapos[i+1] & 0x7fffffff : ab.length ) - pos;
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
      int id32 = faid[i];
      long id64 = expandId( id32 );
      OsmNode n = new OsmNode( id64 );
      n.setHollow();
      nodesMap.put( n );
      positions.add( n );
    }
    return positions;
  }

  private long expandId( int id32 )
  {
    int lon32 = lonIdxBase + (short)(id32 >> 16);
    int lat32 = latIdxBase + (short)((id32 & 0xffff) ^ 0x8000);
    return ((long)lon32)<<32 | lat32;
  }

  private int shrinkId( long id64 )
  {
    int lon32 = (int)(id64 >> 32);
    int lat32 = (int)(id64 & 0xffffffff);
    return (lon32 - lonIdxBase)<<16 | ( ( (lat32 - latIdxBase) & 0xffff) ^ 0x8000);
  }

  public boolean hasMoreData()
  {
    return aboffset < aboffsetEnd;
  }
}
