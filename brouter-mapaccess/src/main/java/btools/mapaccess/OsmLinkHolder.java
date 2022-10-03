/**
 * Container for routig configs
 *
 * @author ab
 */
package btools.mapaccess;

public interface OsmLinkHolder {
  void setNextForLink(OsmLinkHolder holder);

  OsmLinkHolder getNextForLink();
}
