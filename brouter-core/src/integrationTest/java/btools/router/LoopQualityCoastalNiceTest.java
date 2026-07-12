package btools.router;

import java.util.Collection;

import org.junit.runners.Parameterized;

/** Loop-quality shard for {@link LoopTestRegion#COASTAL_NICE}. See {@link LoopQualityTestBase}. */
public class LoopQualityCoastalNiceTest extends LoopQualityTestBase {
  @Parameterized.Parameters(name = "{5}")
  public static Collection<Object[]> data() {
    return dataForRegion(LoopTestRegion.COASTAL_NICE);
  }
}
