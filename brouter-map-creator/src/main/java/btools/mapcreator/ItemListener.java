package btools.mapcreator;

import java.io.File;
import java.io.IOException;

/**
 * Callbacklistener for OSM items
 *
 * @author ab
 */
public interface ItemListener {
  default boolean itemFileStart(File file) throws IOException {
    throw new RuntimeException( "itemFileStart not implemented");
  }

  default void nextNode(NodeData data) throws IOException {
    throw new RuntimeException( "nextNode not implemented");
  }

  default void nextNodeId(long nid) throws IOException {
    throw new RuntimeException( "nextNodeId not implemented");
  }
  default void nextWay(WayData data) throws IOException {
    throw new RuntimeException( "nextWay not implemented");
  }

  default void nextRelation(RelationData data) throws IOException {
    throw new RuntimeException( "nextRelation not implemented");
  }

  default void nextRestriction(RelationData data, long fromWid, long toWid, long viaNid) throws IOException {
    throw new RuntimeException( "nextRestriction not implemented");
  }

  default void itemFileEnd(File file) throws IOException  {
    throw new RuntimeException( "itemFileEnd not implemented");
  }
}
