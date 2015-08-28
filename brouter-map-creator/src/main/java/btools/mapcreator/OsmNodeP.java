/**
 * Container for an osm node (pre-pocessor version)
 *
 * @author ab
 */
package btools.mapcreator;

import java.io.IOException;

import btools.util.ByteDataWriter;

public class OsmNodeP extends OsmLinkP implements Comparable<OsmNodeP>
{
  public static final int SIGNLON_BITMASK         = 0x80;
  public static final int SIGNLAT_BITMASK         = 0x40;
  public static final int TRANSFERNODE_BITMASK    = 0x20;
  public static final int WRITEDESC_BITMASK       = 0x10;
  public static final int SKIPDETAILS_BITMASK     = 0x08;
  public static final int NODEDESC_BITMASK        = 0x04;

 /**
   * The latitude
   */
  public int ilat;

 /**
   * The longitude
   */
  public int ilon;


 /**
   * The elevation
   */
  public short selev;

  public final static int NO_BRIDGE_BIT = 1;
  public final static int NO_TUNNEL_BIT = 2;
  public final static int BORDER_BIT = 4;
  public final static int TRAFFIC_BIT = 8;

  public byte bits = 0;

  // interface OsmPos
  public int getILat()
  {
    return ilat;
  }

  public int getILon()
  {
    return ilon;
  }

  public short getSElev()
  {
    // if all bridge or all tunnel, elevation=no-data
    return ( bits & NO_BRIDGE_BIT ) == 0 || ( bits & NO_TUNNEL_BIT ) == 0 ? Short.MIN_VALUE : selev;
  }

  public double getElev()
  {
    return selev / 4.;
  }


   // populate and return the inherited link, if available,
   // else create a new one
   public OsmLinkP createLink( OsmNodeP source )
   {
    if ( sourceNode == null && targetNode == null )
    {
      // inherited instance is available, use this
      sourceNode = source;
      targetNode = this;
      source.addLink( this );
      return this;
    }
    OsmLinkP link = new OsmLinkP( source, this );
    addLink( link );
    source.addLink( link );
    return link;
  }


   // memory-squeezing-hack: OsmLinkP's "previous" also used as firstlink..

   public void addLink( OsmLinkP link )
   {
     link.setNext( previous, this );
     previous = link;
   }

   public OsmLinkP getFirstLink()
   {
     return sourceNode == null && targetNode == null ? previous : this;
   }

   public byte[] getNodeDecsription()
   {
     return null;
   }

   public void writeNodeData( ByteDataWriter os, byte[] abBuf ) throws IOException
   {
     int lonIdx = ilon/62500;
     int latIdx = ilat/62500;

     // buffer the body to first calc size
     ByteDataWriter os2 = new ByteDataWriter( abBuf );
     os2.writeShort( getSElev() );

     // hack: write node-desc as link tag (copy cycleway-bits)
     byte[] nodeDescription = getNodeDecsription();

     for( OsmLinkP link0 = getFirstLink(); link0 != null; link0 = link0.getNext( this ) )
     {
       int ilonref = ilon;
       int ilatref = ilat;

       OsmLinkP link = link0;
       OsmNodeP origin = this;
       int skipDetailBit = link0.descriptionBitmap == null ? SKIPDETAILS_BITMASK : 0;

       // first pass just to see if that link is consistent
       while( link != null )
       {
         OsmNodeP target = link.getTarget( origin );
         if ( !target.isTransferNode() )
         {
           break;
         }
         // next link is the one (of two), does does'nt point back
         for( link = target.getFirstLink(); link != null; link = link.getNext( target ) )
         {
           if ( link.getTarget( target ) != origin ) break;
         }
         origin = target;
       }
       if ( link == null ) continue; // dead end

       if ( skipDetailBit == 0)
       {
         link = link0;
         origin = this;
       }
       byte[] lastDescription = null;
       while( link != null )
       {
       if ( link.descriptionBitmap == null && skipDetailBit == 0 ) throw new IllegalArgumentException( "missing way description...");

         OsmNodeP target = link.getTarget( origin );
         int tranferbit = target.isTransferNode() ? TRANSFERNODE_BITMASK : 0;
         int nodedescbit = nodeDescription != null ? NODEDESC_BITMASK : 0;

         int writedescbit = 0;
         if ( skipDetailBit == 0 ) // check if description changed
         {
           int inverseBitByteIndex =  0;
           boolean inverseDirection = link.isReverse( origin );
           byte[] ab = link.descriptionBitmap;
             int abLen = ab.length;
             int lastLen = lastDescription == null ? 0 : lastDescription.length;
             boolean equalsCurrent = abLen == lastLen;
             if ( equalsCurrent )
             {
               for( int i=0; i<abLen; i++ )
               {
                 byte b = ab[i];
                 if ( i == inverseBitByteIndex && inverseDirection ) b ^= 1;
                 if ( b != lastDescription[i] ) { equalsCurrent = false; break; }
               }
             }
           if ( !equalsCurrent )
           {
             writedescbit = WRITEDESC_BITMASK;
             lastDescription = new byte[abLen];
               System.arraycopy( ab,  0,  lastDescription,  0 , abLen );
               if ( inverseDirection ) lastDescription[inverseBitByteIndex] ^= 1;
           }

         }

         int bm = tranferbit | writedescbit | nodedescbit | skipDetailBit;
         int dlon = target.ilon - ilonref;
         int dlat = target.ilat - ilatref;
         ilonref = target.ilon;
         ilatref = target.ilat;
         if ( dlon < 0 ) { bm |= SIGNLON_BITMASK; dlon = - dlon; }
         if ( dlat < 0 ) { bm |= SIGNLAT_BITMASK; dlat = - dlat; }
         os2.writeByte( bm );

         int blon = os2.writeVarLengthUnsigned( dlon );
         int blat = os2.writeVarLengthUnsigned( dlat );

         if ( writedescbit != 0 )
         {
           // write the way description, code direction into the first bit
           os2.writeByte( lastDescription.length );
           os2.write( lastDescription );
         }
         if ( nodedescbit != 0 )
         {
           os2.writeByte( nodeDescription.length );
           os2.write( nodeDescription );
           nodeDescription = null;
         }

         link.descriptionBitmap = null; // mark link as written

         if ( tranferbit == 0)
         {
           break;
         }
         os2.writeVarLengthSigned( target.getSElev() -getSElev() );
         // next link is the one (of two), does does'nt point back
         for( link = target.getFirstLink(); link != null; link = link.getNext( target ) )
         {
           if ( link.getTarget( target ) != origin ) break;
         }
         if ( link == null ) throw new RuntimeException( "follow-up link not found for transfer-node!" );
         origin = target;
       }
     }

     // calculate the body size
     int bodySize = os2.size();

     os.ensureCapacity( bodySize + 8 );

     os.writeShort( (short)(ilon - lonIdx*62500 - 31250) );
     os.writeShort( (short)(ilat - latIdx*62500 - 31250) );

     os.writeVarLengthUnsigned( bodySize );
     os.write( abBuf, 0, bodySize );
   }

  public String toString2()
  {
    return (ilon-180000000) + "_" + (ilat-90000000) + "_" + (selev/4);
  }

  public long getIdFromPos()
  {
    return ((long)ilon)<<32 | ilat;
  }

  public boolean isBorderNode()
  {
    return (bits & BORDER_BIT) != 0;
  }

  public boolean hasTraffic()
  {
    return (bits & TRAFFIC_BIT) != 0;
  }

  public boolean isTransferNode()
  {
    return (bits & BORDER_BIT) == 0 && _linkCnt() == 2;
  }

  private int _linkCnt()
  {
    int cnt = 0;

    for( OsmLinkP link = getFirstLink(); link != null; link = link.getNext( this ) )
    {
      cnt++;
    }
    return cnt;
  }

 /**
   * Compares two OsmNodes for position ordering.
   *
   * @return -1,0,1 depending an comparson result
   */
   public int compareTo( OsmNodeP n )
   {
     long id1 = getIdFromPos();
     long id2 = n.getIdFromPos();
     if ( id1 < id2 ) return -1;
     if ( id1 > id2 ) return  1;
     return 0;
   }
}
