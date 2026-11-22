// context for simple expression
// context means:
// - the local variables
// - the local variable names
// - the lookup-input variables

package btools.expressions;


public final class BExpressionContextNode extends BExpressionContext {
  private static String[] buildInVariables =
    {"initialcost"};

  protected String[] getBuildInVariableNames() {
    return buildInVariables;
  }

  public float getInitialcost() {
    return getBuildInVariable(0);
  }


  /**
   * Create an Expression-Context for node context.
   * This is always without result caching (hashSize=0)
   * because node-evaluation is not context free (it depends on the way-context)
   */
  public BExpressionContextNode(BExpressionMetaData meta) {
    super("node", 0, meta);
  }
}
