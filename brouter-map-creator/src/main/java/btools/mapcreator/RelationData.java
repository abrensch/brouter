package btools.mapcreator;

import btools.util.LongList;

/**
 * Container for relation data on the preprocessor level
 *
 * @author ab
 */
public class RelationData extends MapCreatorBase {
  public long rid;
  public LongList ways;

  public RelationData(long id, LongList ways) {
    rid = id;
    this.ways = ways;
  }
}
