/**
 * Container for an osm node with tags (pre-pocessor version)
 *
 * @author ab
 */
package btools.mapcreator;


public class OsmNodePT extends OsmNodeP
{
    public long descriptionBits;

    public byte wayOrBits = 0; // used to propagate bike networks to nodes

    public OsmNodePT()
    {
    }

    public OsmNodePT( long descriptionBits )
    {
      this.descriptionBits = descriptionBits;
    }

    @Override
    public long getNodeDecsription()
    {
      return descriptionBits | (long)( (wayOrBits & 6) >> 1 );
    }

    @Override
    public boolean isTransferNode()
    {
      return false; // always have descriptionBits so never transfernode
    }

}
