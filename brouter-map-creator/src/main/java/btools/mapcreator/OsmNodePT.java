/**
 * Container for an osm node with tags (pre-pocessor version)
 *
 * @author ab
 */
package btools.mapcreator;


public class OsmNodePT extends OsmNodeP
{
    public byte[] descriptionBits;

    public byte wayOrBits = 0; // used to propagate bike networks to nodes

    public OsmNodePT()
    {
    }

    public OsmNodePT( byte[] descriptionBits )
    {
      this.descriptionBits = descriptionBits;
    }

    @Override
    public final byte[] getNodeDecsription()
    {
      return descriptionBits;
      // return descriptionBits | (long)( (wayOrBits & 6) >> 1 );     TODO !!!!!!!!!!1
    }

    @Override
    public boolean isTransferNode()
    {
      return false; // always have descriptionBits so never transfernode
    }

}
