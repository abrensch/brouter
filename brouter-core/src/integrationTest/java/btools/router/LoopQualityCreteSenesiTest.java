package btools.router;

import java.util.Collection;

import org.junit.runners.Parameterized;

/** Loop-quality shard for {@link LoopTestRegion#CRETE_SENESI}. See {@link LoopQualityTestBase}. */
public class LoopQualityCreteSenesiTest extends LoopQualityTestBase {
  @Parameterized.Parameters(name = "{5}")
  public static Collection<Object[]> data() {
    return dataForRegion(LoopTestRegion.CRETE_SENESI);
  }
}
