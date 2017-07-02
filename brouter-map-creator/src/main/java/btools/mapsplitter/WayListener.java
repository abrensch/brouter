package btools.mapsplitter;

import java.io.File;

/**
 * Callbacklistener for WayIterator
 *
 * @author ab
 */
public interface WayListener
{
  void wayFileStart( File wayfile ) throws Exception;

  void nextWay( WayData data ) throws Exception;

  void wayFileEnd( File wayfile ) throws Exception;
}
