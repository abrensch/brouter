/**
 * Container for an osm node (pre-pocessor version)
 *
 * @author ab
 */
package btools.mapcreator;

import java.io.IOException;

import btools.util.ByteDataWriter;

public class OsmNodeP implements Comparable<OsmNodeP>
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
   * The links to other nodes
   */
  public OsmLinkP firstlink = null;


 /**
   * The elevation
   */
  public short selev;

  public boolean isBorder = false;

  public final static int NO_BRIDGE_BIT = 1;
  public final static int NO_TUNNEL_BIT = 2;
  public final static int LCN_BIT = 4;
  public final static int CR_BIT = 8;
  
  public byte wayBits = 0;

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
    return ( wayBits & NO_BRIDGE_BIT ) == 0 || ( wayBits & NO_TUNNEL_BIT ) == 0 ? Short.MIN_VALUE : selev;
  }

  public double getElev()
  {
    return selev / 4.;
  }



   public void addLink( OsmLinkP link )
   {
     if ( firstlink != null ) link.next = firstlink;
     firstlink = link;
   }

   public byte[] getNodeDecsription()
   {
     return null;
   }

   public void writeNodeData( ByteDataWriter os, boolean writeVarLength, byte[] abBuf ) throws IOException
   {
     int lonIdx = ilon/62500;
     int latIdx = ilat/62500;

     // buffer the body to first calc size
     ByteDataWriter os2 = new ByteDataWriter( abBuf );
     os2.writeShort( getSElev() );
     
     // hack: write node-desc as link tag (copy cycleway-bits)
     byte[] nodeDescription = getNodeDecsription();

     for( OsmLinkP link0 = firstlink; link0 != null; link0 = link0.next )
     {
       int ilonref = ilon;
       int ilatref = ilat;
    	 
       OsmLinkP link = link0;
       OsmNodeP origin = this;
       int skipDetailBit = link0.counterLinkWritten() ? SKIPDETAILS_BITMASK : 0;

       // first pass just to see if that link is consistent
       while( link != null )
       {
         OsmNodeP target = link.targetNode;
         if ( !target.isTransferNode() )
         {
           break;
         }
         // next link is the one (of two), does does'nt point back
         for( link = target.firstlink; link != null; link = link.next )
         {
           if ( link.targetNode != origin ) break;
         }
         origin = target;
       }
       if ( link == null ) continue; // dead end

       if ( skipDetailBit == 0)
       {
         link = link0;
       }
       origin = this;
       byte[] lastDescription = null;
       while( link != null )
       {
    	 if ( link.descriptionBitmap == null && skipDetailBit == 0 ) throw new IllegalArgumentException( "missing way description...");
    	   
         OsmNodeP target = link.targetNode;
         int tranferbit = target.isTransferNode() ? TRANSFERNODE_BITMASK : 0;
         int nodedescbit = nodeDescription != null ? NODEDESC_BITMASK : 0;

         int writedescbit = 0;
         if ( skipDetailBit == 0 ) // check if description changed
         {
        	 int inverseBitByteIndex =  writeVarLength ? 0 : 7;
        	 boolean inverseDirection = link instanceof OsmLinkPReverse;
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
           if ( writeVarLength ) os2.writeByte( lastDescription.length );
           os2.write( lastDescription );
         }
         if ( nodedescbit != 0 )
         {
           if ( writeVarLength ) os2.writeByte( nodeDescription.length );
           os2.write( nodeDescription );
           nodeDescription = null;
         }

         if ( tranferbit == 0)
         {
           target.markLinkWritten( origin );
           break;
         }
         os2.writeVarLengthSigned( target.getSElev() -getSElev() );
         // next link is the one (of two), does does'nt point back
         for( link = target.firstlink; link != null; link = link.next )
         {
           if ( link.targetNode != origin ) break;
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

  public boolean isTransferNode()
  {
    return (!isBorder) && _linkCnt() == 2;
  }

  private int _linkCnt()
  {
    int cnt = 0;

    for( OsmLinkP link = firstlink; link != null; link = link.next )
    {
      cnt++;
    }
    return cnt;
  }

   // mark the link to the given node as written,
   // don't want to write the counter-direction
   // in full details
   public void markLinkWritten( OsmNodeP t )
   {
     for( OsmLinkP link = firstlink; link != null; link = link.next )
     {
       if ( link.targetNode == t) link.descriptionBitmap = null;
     }
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
