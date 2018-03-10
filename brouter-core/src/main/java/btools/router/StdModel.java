/**
 * Container for link between two Osm nodes
 *
 * @author ab
 */
package btools.router;

import java.util.Map;

import btools.expressions.BExpressionContext;
import btools.expressions.BExpressionContextNode;
import btools.expressions.BExpressionContextWay;


final class StdModel extends OsmPathModel
{
  public OsmPrePath createPrePath()
  {
    return null;
  }

  public OsmPath createPath()
  {
    return new StdPath();
  }

  protected BExpressionContextWay ctxWay;
  protected BExpressionContextNode ctxNode;


  @Override
  public void init( BExpressionContextWay expctxWay, BExpressionContextNode expctxNode, Map<String,String> keyValues )
  {
    ctxWay = expctxWay;
    ctxNode = expctxNode;
  
    BExpressionContext expctxGlobal = expctxWay; // just one of them...

  }
}
