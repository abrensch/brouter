package btools.mapsplitter;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import btools.util.DiffCoderDataInputStream;
import btools.util.DiffCoderDataOutputStream;

/**
 * Container a tile during encoding
 */
public class TileData extends MapCreatorBase
{
  public int zoom;
  public int x;
  public int y;
  public List<NodeData> nodeList;
  public TileData parent;
}
