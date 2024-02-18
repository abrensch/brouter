package btools.mapcreator;

import java.io.File;

/**
 * Callbacklistener for OSM items
 *
 * @author ab
 */
public interface ItemListener {
  default boolean itemFileStart(File file) throws Exception {
    throw new RuntimeException( "itemFileStart not implemented");
  }

  default void nextNode(NodeData data) throws Exception {
    throw new RuntimeException( "nextNode not implemented");
  }

  default void nextNodeId(long nid) throws Exception {
    throw new RuntimeException( "nextNodeId not implemented");
  }
  default void nextWay(WayData data) throws Exception {
    throw new RuntimeException( "nextWay not implemented");
  }

  default void nextRelation(RelationData data) throws Exception {
    throw new RuntimeException( "nextRelation not implemented");
  }

  default void nextRestriction(RelationData data, long fromWid, long toWid, long viaNid) throws Exception {
    throw new RuntimeException( "nextRestriction not implemented");
  }

  default void itemFileEnd(File file) throws Exception  {
    throw new RuntimeException( "itemFileEnd not implemented");
  }
}
