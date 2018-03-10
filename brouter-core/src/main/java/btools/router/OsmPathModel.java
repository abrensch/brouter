/**
 * Container for link between two Osm nodes
 *
 * @author ab
 */
package btools.router;

import java.util.Map;

import btools.expressions.BExpressionContextNode;
import btools.expressions.BExpressionContextWay;


abstract class OsmPathModel
{
  public abstract OsmPrePath createPrePath();

  public abstract OsmPath createPath();

  public abstract void init( BExpressionContextWay expctxWay, BExpressionContextNode expctxNode, Map<String,String> keyValues );
}
