package btools.router;

import java.util.Collection;

import org.junit.runners.Parameterized;

/** Loop-quality shard for {@link LoopTestRegion#FINALE_LIGURE}. See {@link LoopQualityTestBase}. */
public class LoopQualityFinaleLigureTest extends LoopQualityTestBase {
  @Parameterized.Parameters(name = "{5}")
  public static Collection<Object[]> data() {
    return dataForRegion(LoopTestRegion.FINALE_LIGURE);
  }
}
