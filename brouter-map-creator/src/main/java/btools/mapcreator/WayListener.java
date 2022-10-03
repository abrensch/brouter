package btools.mapcreator;

import java.io.File;

/**
 * Callbacklistener for WayIterator
 *
 * @author ab
 */
public interface WayListener {
  boolean wayFileStart(File wayfile) throws Exception;

  void nextWay(WayData data) throws Exception;

  void wayFileEnd(File wayfile) throws Exception;
}
