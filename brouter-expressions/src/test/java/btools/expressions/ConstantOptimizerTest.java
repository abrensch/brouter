package btools.expressions;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.util.Random;
import java.util.Map;
import java.util.HashMap;

public class ConstantOptimizerTest {
  @Test
  public void compareOptimizerModesTest() {

    File lookupFile = new File(getClass().getResource("/lookups_test.dat").getPath());
    File profileFile = new File(getClass().getResource("/profile_test.brf").getPath());

    BExpressionMetaData meta1 = new BExpressionMetaData();
    BExpressionMetaData meta2 = new BExpressionMetaData();
    BExpressionContext expctx1 = new BExpressionContextWay(meta1);
    BExpressionContext expctx2 = new BExpressionContextWay(meta2);
    expctx2.skipConstantExpressionOptimizations = true;

    Map<String, String> keyValue = new HashMap<>();
    keyValue.put("global_inject1", "5");
    keyValue.put("global_inject2", "6");
    keyValue.put("global_inject3", "7");

    meta1.readMetaData(lookupFile);
    meta2.readMetaData(lookupFile);
    expctx1.parseFile(profileFile, "global", keyValue);
    expctx2.parseFile(profileFile, "global", keyValue);

    float d = 0.0001f;
    Assert.assertEquals(5f, expctx1.getVariableValue("global_inject1", 0f), d);
    Assert.assertEquals(9f, expctx1.getVariableValue("global_inject2", 0f), d); // should be modified in 2. assign!
    Assert.assertEquals(7f, expctx1.getVariableValue("global_inject3", 0f), d);
    Assert.assertEquals(3f, expctx1.getVariableValue("global_inject4", 3f), d); // un-assigned

    Assert.assertTrue("expected far less exporessions nodes if optimized",  expctx2.expressionNodeCount - expctx1.expressionNodeCount >= 311-144);

    Random rnd = new Random(17464); // fixed seed for unit test...
    for (int i = 0; i < 10000; i++) {
      int[] data = expctx1.generateRandomValues(rnd);
      expctx1.evaluate(data);
      expctx2.evaluate(data);
      expctx1.assertAllVariablesEqual(expctx2);
    }
  }
}
