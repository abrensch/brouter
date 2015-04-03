// context for simple expression
// context means:
// - the local variables
// - the local variable names
// - the lookup-input variables

package btools.expressions;



public final class BExpressionContextNode extends BExpressionContext
{
  private static String[] buildInVariables =
	  { "initialcost" };
	  
  protected String[] getBuildInVariableNames()
  {
    return buildInVariables;
  }

  public float getInitialcost() { return getBuildInVariable(0); }


  public BExpressionContextNode( BExpressionMetaData meta )
  {
    super( "node", meta );
  }

  /**
   * Create an Expression-Context for way context
   *
   * @param hashSize  size of hashmap for result caching
   */
  public BExpressionContextNode( int hashSize, BExpressionMetaData meta )
  {
    super( "node", hashSize, meta );
  }
}
