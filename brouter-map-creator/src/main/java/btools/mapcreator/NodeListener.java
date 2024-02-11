package btools.mapcreator;

import java.io.File;

/**
 * Callbacklistener for NodeIterator
 *
 * @author ab
 */
public interface NodeListener {
  default void nodeFileStart(File nodefile) throws Exception {
  }

  void nextNode(NodeData data) throws Exception;

  default void nodeFileEnd(File nodefile) throws Exception {
  }
}
