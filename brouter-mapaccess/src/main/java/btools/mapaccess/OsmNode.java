/**
 * Container for an osm node
 *
 * @author ab
 */
package btools.mapaccess;

import btools.util.ByteArrayUnifier;



public class OsmNode implements OsmPos
{
  public static final int EXTERNAL_BITMASK        = 0x80;
  public static final int VARIABLEDESC_BITMASK    = 0x40;
  public static final int TRANSFERNODE_BITMASK    = 0x20;
  public static final int WRITEDESC_BITMASK       = 0x10;
  public static final int SKIPDETAILS_BITMASK     = 0x08;
  public static final int NODEDESC_BITMASK        = 0x04;

  public OsmNode()
  {
  }

  public OsmNode( int ilon, int ilat )
  {
    this.ilon = ilon;
    this.ilat = ilat;
  }

  public OsmNode( long id )
  {
    ilon = (int)(id >> 32);
    ilat = (int)(id & 0xffffffff);
  }

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

    public byte[] nodeDescription;

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
    return selev;
  }

  public double getElev()
  {
    return selev / 4.;
  }

 /**
   * Whether there's a traffic signal
   */

 /**
   * The links to other nodes
   */
    public OsmLink firstlink = null;

    public OsmLink firstreverse = null;

   // whether this node is completed and registerd for map-removal
   public boolean completed;

   public boolean wasProcessed;
   public int maxcost; // maximum cost to consider for that node

   public void addLink( OsmLink link )
   {
     if ( firstlink != null ) link.next = firstlink;
     firstlink = link;
   }

  public int calcDistance( OsmPos p )
  {
    double l = (ilat-90000000) * 0.00000001234134;
    double l2 = l*l;
    double l4 = l2*l2;
    double coslat = 1.- l2 + l4 / 6.;

    double dlat = (ilat - p.getILat() )/1000000.;
    double dlon = (ilon - p.getILon() )/1000000. * coslat;
    double d = Math.sqrt( dlat*dlat + dlon*dlon ) * (6378000. / 57.3);
    return (int)(d + 1.0 );
  }


   public void parseNodeBody( MicroCache is, OsmNodesMap hollowNodes, DistanceChecker dc )
   {
	 ByteArrayUnifier abUnifier = hollowNodes.getByteArrayUnifier();

	 selev = is.readShort();

     OsmLink lastlink = null;

     int lonIdx = ilon/62500;
     int latIdx = ilat/62500;

     while( is.hasMoreData() )
     {
       OsmLink link = new OsmLink();
       OsmTransferNode firstTransferNode = null;
       OsmTransferNode lastTransferNode = null;
       int linklon;
       int linklat;
       byte[] description = null;
       for(;;)
       {
         int bitField = is.readByte();
         if ( (bitField & EXTERNAL_BITMASK) != 0 )
         {
           // full position for external target
           linklon = is.readInt();
           linklat = is.readInt();
         }
         else
         {
           // reduced position for internal target
           linklon = is.readShort();
           linklat = is.readShort();
           linklon += lonIdx*62500 + 31250;
           linklat += latIdx*62500 + 31250;
         }
         // read variable length or old 8 byte fixed, and ensure that 8 bytes is only fixed
         boolean readFix8 = (bitField & VARIABLEDESC_BITMASK ) == 0; // old, fix length format
         if ( (bitField & WRITEDESC_BITMASK ) != 0 )
         {
        	 byte[] ab = new byte[readFix8 ? 8 : is.readByte()];
        	 is.readFully( ab );
        	 description = abUnifier.unify( ab );
         }
         if ( (bitField & NODEDESC_BITMASK ) != 0 )
         {
        	 byte[] ab = new byte[readFix8 ? 8 : is.readByte()];
        	 is.readFully( ab );
        	 nodeDescription = abUnifier.unify( ab );
         }
         if ( (bitField & SKIPDETAILS_BITMASK ) != 0 )
         {
           link.counterLinkWritten = true;
         }
         
         if ( description == null && !link.counterLinkWritten ) throw new IllegalArgumentException( "internal error: missing way description!" );
         
         boolean isTransfer = (bitField & TRANSFERNODE_BITMASK ) != 0;
         if ( isTransfer )
         {
           OsmTransferNode trans = new OsmTransferNode();
           trans.ilon = linklon;
           trans.ilat = linklat;
           trans.descriptionBitmap = description;
           trans.selev = is.readShort();
           if ( lastTransferNode == null )
           {
             firstTransferNode = trans;
           }
           else
           {
             lastTransferNode.next = trans;
           }
           lastTransferNode = trans;
         }
         else
         {
           link.descriptionBitmap = description;
           break;
         }
       }

       // performance shortcut: ignore link if out of reach
       if ( dc != null && !link.counterLinkWritten )
       {
           if ( !dc.isWithinRadius( ilon, ilat, firstTransferNode, linklon, linklat ) )
           {
               continue;
           }
       }

       if ( linklon == ilon && linklat == ilat )
       {
          continue; // skip self-ref
       }

       if ( lastlink == null )
       {
         firstlink = link;
       }
       else
       {
         lastlink.next = link;
       }
       lastlink = link;


       long targetNodeId = ((long)linklon)<<32 | linklat;
       OsmNode tn = hollowNodes.get( targetNodeId ); // target node

       if ( tn == null )
       {
         // node not yet known, create a hollow proxy
         tn = new OsmNode(linklon, linklat);
         tn.setHollow();
         hollowNodes.put( targetNodeId, tn );
       }
       else
       {
         if ( !( tn.isHollow() || tn.hasHollowLinks() ) )
         {
           hollowNodes.registerCompletedNode( tn );
         }
       }
       link.targetNode = tn;

       link.encodeFirsttransfer(firstTransferNode);

       // compute the reverse link
       if ( !link.counterLinkWritten )
       {
           OsmLink rlink = new OsmLink();
           byte[] linkDescriptionBitmap = link.descriptionBitmap;
           rlink.ilonOrigin = tn.ilon;
           rlink.ilatOrigin = tn.ilat;
           rlink.targetNode = this;
           rlink.descriptionBitmap = linkDescriptionBitmap; // default for no transfer-nodes
           OsmTransferNode previous = null;
           OsmTransferNode rtrans = null;
           for( OsmTransferNode trans = firstTransferNode; trans != null; trans = trans.next )
           {
             if ( previous == null )
             {
                 rlink.descriptionBitmap = trans.descriptionBitmap;
             }
             else
             {
                 previous.descriptionBitmap = trans.descriptionBitmap;
             }
             rtrans = new OsmTransferNode();
             rtrans.ilon = trans.ilon;
             rtrans.ilat = trans.ilat;
             rtrans.selev = trans.selev;
             rtrans.next = previous;
             rtrans.descriptionBitmap = linkDescriptionBitmap;
             previous = rtrans;
           }
           rlink.encodeFirsttransfer(rtrans);
           rlink.next = firstreverse;
           firstreverse = rlink;
       }

     }

     if ( !hasHollowLinks() )
     {
       hollowNodes.registerCompletedNode( this );
     }
   }

  public boolean isHollow()
  {
    return selev == -12345;
  }

  public void setHollow()
  {
    selev = -12345;
  }

  public long getIdFromPos()
  {
    return ((long)ilon)<<32 | ilat;
  }

  public boolean hasHollowLinks()
  {
    for( OsmLink link = firstlink; link != null; link = link.next )
    {
      if ( link.targetNode.isHollow() ) return true;
    }
    return false;
  }


  public int linkCnt()
  {
    int cnt = 0;

    for( OsmLink link = firstlink; link != null; link = link.next )
    {
      cnt++;
    }
    return cnt;
  }

  public void unlinkLink( OsmLink link )
  {
    if ( link == firstlink )
    {
      firstlink = link.next;
      return;
    }
    for( OsmLink l = firstlink; l != null; l = l.next )
    {
      if ( l.next == link )
      {
        l.next = link.next;
        return;
      }
    }
  }

   public OsmLink getReverseLink( int lon, int lat )
   {
     for( OsmLink rlink = firstreverse; rlink != null; rlink = rlink.next )
     {
       if ( rlink.ilonOrigin == lon && rlink.ilatOrigin == lat )
       {
         unlinkRLink( rlink );
         return rlink;
       }
     }
     return null;
   }

   public void unlinkRLink( OsmLink rlink )
   {
     if ( rlink == firstreverse )
     {
       firstreverse = rlink.next;
       return;
     }
     for( OsmLink l = firstreverse; l != null; l = l.next )
     {
       if ( l.next == rlink )
       {
         l.next = rlink.next;
         return;
       }
     }
   }

}
