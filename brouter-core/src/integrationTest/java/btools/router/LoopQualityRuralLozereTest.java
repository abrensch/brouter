package btools.router;

import java.util.Collection;

import org.junit.runners.Parameterized;

/** Loop-quality shard for {@link LoopTestRegion#RURAL_LOZERE}. See {@link LoopQualityTestBase}. */
public class LoopQualityRuralLozereTest extends LoopQualityTestBase {
  @Parameterized.Parameters(name = "{5}")
  public static Collection<Object[]> data() {
    return dataForRegion(LoopTestRegion.RURAL_LOZERE);
  }
}
