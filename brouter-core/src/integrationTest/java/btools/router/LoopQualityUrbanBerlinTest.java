package btools.router;

import java.util.Collection;

import org.junit.runners.Parameterized;

/** Loop-quality shard for {@link LoopTestRegion#URBAN_BERLIN}. See {@link LoopQualityTestBase}. */
public class LoopQualityUrbanBerlinTest extends LoopQualityTestBase {
  @Parameterized.Parameters(name = "{5}")
  public static Collection<Object[]> data() {
    return dataForRegion(LoopTestRegion.URBAN_BERLIN);
  }
}
