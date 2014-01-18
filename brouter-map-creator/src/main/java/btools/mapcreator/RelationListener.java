package btools.mapcreator;

import java.io.File;

/**
 * Callbacklistener for Relations
 *
 * @author ab
 */
public interface RelationListener
{
  void nextRelation( RelationData data ) throws Exception;
}
