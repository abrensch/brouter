package btools.mapdecoder;

import java.util.Map;

/**
 * Base class of Nodes, Ways and Relations
 */
public class OsmObject
{
  public int id;
  public Map<String,String> tags;
}
