/**
 * Container for link between two Osm nodes
 *
 * @author ab
 */
package btools.mapaccess;

import btools.util.ByteDataReader;


public class OsmLink
{
 /**
   * The description bitmap is mainly the way description
   * used to calculate the costfactor
   */
  public byte[] descriptionBitmap;

  public OsmNode targetNode;

  public OsmLink next;

  public OsmLinkHolder firstlinkholder = null;

  public byte[] geometry;

  public boolean counterLinkWritten;

  public byte state;

  public void setGeometry( byte[] geometry )
  {
    this.geometry = geometry;
  }

  final public OsmTransferNode decodeFirsttransfer( OsmNode sourceNode )
  {
      if ( geometry == null ) return null;

      OsmTransferNode firstTransferNode = null;
      OsmTransferNode lastTransferNode = null;
      OsmNode startnode =  counterLinkWritten ? targetNode : sourceNode;
      ByteDataReader r = new ByteDataReader( geometry );
      int olon = startnode.ilon;
      int olat = startnode.ilat;
      int oselev = startnode.selev;
      while ( r.hasMoreData() )
      {
        OsmTransferNode trans = new OsmTransferNode();
        trans.ilon = olon + r.readVarLengthSigned();
        trans.ilat = olat + r.readVarLengthSigned();
        trans.selev = (short)(oselev + r.readVarLengthSigned());
        olon = trans.ilon;
        olat = trans.ilat;
        oselev = trans.selev;
        if ( counterLinkWritten ) // reverse chaining
        {
          trans.next = firstTransferNode;
          firstTransferNode = trans;
        }
        else
        {
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
      }
      return firstTransferNode;
  }

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
