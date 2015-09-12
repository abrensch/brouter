/**
 * Container for link between two Osm nodes
 *
 * @author ab
 */
package btools.mapaccess;


public class OsmLink
{
 /**
   * The description bitmap is mainly the way description
   * used to calculate the costfactor
   */
  public byte[] descriptionBitmap;

 /**
   * The target is either the next link or the target node
   */
  public OsmNode targetNode;

  public OsmLink next;

  public byte[] firsttransferBytes;

  final public OsmTransferNode decodeFirsttransfer()
  {
    return firsttransferBytes == null ? null : OsmTransferNode.decode( firsttransferBytes );
  }

  final public void encodeFirsttransfer( OsmTransferNode firsttransfer )
  {
    if ( firsttransfer == null ) firsttransferBytes = null;
    else firsttransferBytes = OsmTransferNode.encode( firsttransfer );
  }

  public boolean counterLinkWritten;

  public byte state;

   public OsmLinkHolder firstlinkholder = null;

   final public void addLinkHolder( OsmLinkHolder holder )
   {
     if ( firstlinkholder != null ) { holder.setNextForLink( firstlinkholder ); }
     firstlinkholder = holder;
   }

   public String toString()
   {
     return "Link(target=" + targetNode.getIdFromPos() + " counterLinkWritten=" + counterLinkWritten + " state=" + state + ")";
   }
}
