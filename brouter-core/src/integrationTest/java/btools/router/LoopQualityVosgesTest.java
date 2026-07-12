package btools.router;

import java.util.Collection;

import org.junit.runners.Parameterized;

/** Loop-quality shard for {@link LoopTestRegion#VOSGES_LA_BRESSE}. See {@link LoopQualityTestBase}. */
public class LoopQualityVosgesTest extends LoopQualityTestBase {
  @Parameterized.Parameters(name = "{5}")
  public static Collection<Object[]> data() {
    return dataForRegion(LoopTestRegion.VOSGES_LA_BRESSE);
  }
}
