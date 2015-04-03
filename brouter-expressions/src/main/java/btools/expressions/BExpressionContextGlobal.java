// context for simple expression
// context means:
// - the local variables
// - the local variable names
// - the lookup-input variables

package btools.expressions;



public final class BExpressionContextGlobal extends BExpressionContext
{
  private static String[] buildInVariables =
	  {};
	  
  protected String[] getBuildInVariableNames()
  {
    return buildInVariables;
  }

  public BExpressionContextGlobal( BExpressionMetaData meta )
  {
    super( "global", meta );
  }

  /**
   * Create an Expression-Context for way context
   *
   * @param hashSize  size of hashmap for result caching
   */
  public BExpressionContextGlobal( int hashSize, BExpressionMetaData meta )
  {
    super( "global", hashSize, meta );
  }
}
