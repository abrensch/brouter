/**
 * A train station and it's connections
 *
 * @author ab
 */
package btools.memrouter;



final class StationNode extends OsmNodeP
{
  private int instanceserial = 0;
  
  
  private static class NodeOffsets implements OffsetSetHolder
  {
    private OffsetSet offsets;

    public OffsetSet getOffsetSet()
    {
      return offsets;
    }

    public void setOffsetSet( OffsetSet offsets )
    {
      this.offsets = offsets;
    }
  }

  private NodeOffsets offsets;

  @Override
  public OffsetSet filterAndCloseNode( OffsetSet in, boolean closeGate )
  {
    if ( offsets == null || instanceserial != currentserial )
    {
      if ( closeGate )
      {
        instanceserial = currentserial;
        offsets = new NodeOffsets();
        offsets.setOffsetSet( in );
      }
      return in;
    }
    return in.filterAndClose( offsets, closeGate );
  }


  String name;

  public String getName()
  {
    return name;
  }

}
