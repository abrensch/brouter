/**
 * Container for an osm node with tags (pre-pocessor version)
 *
 * @author ab
 */
package btools.memrouter;


public class OsmNodePT extends OsmNodeP
{
    public byte[] descriptionBits;

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
    }
}
