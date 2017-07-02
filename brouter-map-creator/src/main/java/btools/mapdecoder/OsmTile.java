package btools.mapdecoder;

import java.util.Collections;
import java.util.List;

/**
 * Container for waydata on the preprocessor level
 *
 * @author ab
 */
public class OsmTile
{
  public OsmTile parent;
  public long sourceId;

  public int zoom;
  public int x;
  public int y;

  private static List<OsmNode> emptyNodes = Collections.EMPTY_LIST;
  private static List<OsmWay> emptyWays = Collections.EMPTY_LIST;
  private static List<OsmRelation> emptyRelations = Collections.EMPTY_LIST;

  public List<OsmNode> nodes = emptyNodes;
  public List<OsmWay> ways = emptyWays;
  public List<OsmRelation> relations = emptyRelations;
  
  public long[] nodePositions;
  
  
  public String toString()
  {
    return "z=" + zoom+ " x=" + x + " y=" + y + " nodes=" + nodes.size() + " ways=" + ways.size() + " rels=" + relations.size();
  }
}
