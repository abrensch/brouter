/**
 * Container for link between two Osm nodes
 *
 * @author ab
 */
package btools.mapaccess;

import java.util.*;

public final class OsmLink
{
 /**
   * The description bitmap is mainly the way description
   * used to calculate the costfactor
   */
  public long descriptionBitmap;

 /**
   * The target is either the next link or the target node
   */
  public OsmNode targetNode;

  /**
   * The origin position
   */
  public int ilatOrigin;
  public int ilonOrigin;

  public OsmLink next;

  public byte[] firsttransferBytes;

  public OsmTransferNode decodeFirsttransfer()
  {
    return firsttransferBytes == null ? null : OsmTransferNode.decode( firsttransferBytes );
  }

  public void encodeFirsttransfer( OsmTransferNode firsttransfer )
  {
    if ( firsttransfer == null ) firsttransferBytes = null;
    else firsttransferBytes = OsmTransferNode.encode( firsttransfer );
  }

  public boolean counterLinkWritten;

   public OsmLinkHolder firstlinkholder = null;

   public void addLinkHolder( OsmLinkHolder holder )
   {
     if ( firstlinkholder != null ) { holder.setNextForLink( firstlinkholder ); }
     firstlinkholder = holder;
   }
}
