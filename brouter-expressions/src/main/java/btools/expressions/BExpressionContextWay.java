// context for simple expression
// context means:
// - the local variables
// - the local variable names
// - the lookup-input variables

package btools.expressions;

import btools.codec.TagValueValidator;

public final class BExpressionContextWay extends BExpressionContext implements TagValueValidator {
  private boolean decodeForbidden = true;

  private static String[] buildInVariables =
    {"costfactor", "turncost", "uphillcostfactor", "downhillcostfactor", "initialcost", "nodeaccessgranted", "initialclassifier", "trafficsourcedensity", "istrafficbackbone", "priorityclassifier", "classifiermask", "maxspeed", "uphillcost", "downhillcost", "uphillcutoff", "downhillcutoff", "uphillmaxslope", "downhillmaxslope", "uphillmaxslopecost", "downhillmaxslopecost"};

  protected String[] getBuildInVariableNames() {
    return buildInVariables;
  }

  public float getCostfactor() {
    return getBuildInVariable(0);
  }

  public float getTurncost() {
    return getBuildInVariable(1);
  }

  public float getUphillCostfactor() {
    return getBuildInVariable(2);
  }

  public float getDownhillCostfactor() {
    return getBuildInVariable(3);
  }

  public float getInitialcost() {
    return getBuildInVariable(4);
  }

  public float getNodeAccessGranted() {
    return getBuildInVariable(5);
  }

  public float getInitialClassifier() {
    return getBuildInVariable(6);
  }

  public float getTrafficSourceDensity() {
    return getBuildInVariable(7);
  }

  public float getIsTrafficBackbone() {
    return getBuildInVariable(8);
  }

  public float getPriorityClassifier() {
    return getBuildInVariable(9);
  }

  public float getClassifierMask() {
    return getBuildInVariable(10);
  }

  public float getMaxspeed() {
    return getBuildInVariable(11);
  }

  public float getUphillcost() {
    return getBuildInVariable(12);
  }

  public float getDownhillcost() {
    return getBuildInVariable(13);
  }

  public float getUphillcutoff() {
    return getBuildInVariable(14);
  }

  public float getDownhillcutoff() {
    return getBuildInVariable(15);
  }

  public float getUphillmaxslope() {
    return getBuildInVariable(16);
  }

  public float getDownhillmaxslope() {
    return getBuildInVariable(17);
  }

  public float getUphillmaxslopecost() {
    return getBuildInVariable(18);
  }

  public float getDownhillmaxslopecost() {
    return getBuildInVariable(19);
  }

  public BExpressionContextWay(BExpressionMetaData meta) {
    super("way", meta);
  }

  /**
   * Create an Expression-Context for way context
   *
   * @param hashSize size of hashmap for result caching
   */
  public BExpressionContextWay(int hashSize, BExpressionMetaData meta) {
    super("way", hashSize, meta);
  }

  @Override
  public int accessType(byte[] description) {
    evaluate(false, description);
    float minCostFactor = getCostfactor();
    if (minCostFactor >= 9999.f) {
      setInverseVars();
      float reverseCostFactor = getCostfactor();
      if (reverseCostFactor < minCostFactor) {
        minCostFactor = reverseCostFactor;
      }
    }
    return minCostFactor < 9999.f ? 2 : decodeForbidden ? (minCostFactor < 10000.f ? 1 : 0) : 0;
  }

  @Override
  public void setDecodeForbidden(boolean decodeForbidden) {
    this.decodeForbidden = decodeForbidden;
  }
}
