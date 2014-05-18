/**
 * Container for link between two Osm nodes (pre-pocessor version)
 *
 * @author ab
 */
package btools.mapcreator;


public class OsmLinkP
{
 /**
   * The description bitmap is mainly the way description
   * used to calculate the costfactor
   */
  public byte[] descriptionBitmap;

 /**
   * The target is either the next link or the target node
   */
  public OsmNodeP targetNode;

  public OsmLinkP next;

  
  public final boolean counterLinkWritten( )
  {
	  return descriptionBitmap == null;
  }
}
