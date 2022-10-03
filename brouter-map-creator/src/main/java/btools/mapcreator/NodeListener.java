package btools.mapcreator;

import java.io.File;

/**
 * Callbacklistener for NodeIterator
 *
 * @author ab
 */
public interface NodeListener {
  void nodeFileStart(File nodefile) throws Exception;

  void nextNode(NodeData data) throws Exception;

  void nodeFileEnd(File nodefile) throws Exception;
}
