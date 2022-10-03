package btools.mapcreator;


/**
 * Callbacklistener for Relations
 *
 * @author ab
 */
public interface RelationListener {
  void nextRelation(RelationData data) throws Exception;

  void nextRestriction(RelationData data, long fromWid, long toWid, long viaNid) throws Exception;
}
