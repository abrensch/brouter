package btools.mapcreator;

import java.io.File;

/**
 * Callbacklistener for WayIterator
 *
 * @author ab
 */
public interface WayListener {
  default boolean wayFileStart(File wayfile) throws Exception {
    return false;
  }

  void nextWay(WayData data) throws Exception;

  default void wayFileEnd(File wayfile) throws Exception {
  }
}
