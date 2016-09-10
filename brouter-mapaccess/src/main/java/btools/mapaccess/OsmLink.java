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
