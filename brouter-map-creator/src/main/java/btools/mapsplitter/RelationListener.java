package btools.mapsplitter;

import java.io.File;


/**
 * Callbacklistener for Relations
 *
 * @author ab
 */
public interface RelationListener
{
  void relationFileStart( File relfile ) throws Exception;

  void nextRelation( RelationData data ) throws Exception;

  void relationFileEnd( File relfile ) throws Exception;
}
