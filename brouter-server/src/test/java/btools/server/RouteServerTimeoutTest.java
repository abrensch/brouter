package btools.server;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * Unit tests for the per-request {@code timeout} parameter clamping in
 * {@link RouteServer#getMaxRunningTime(Map)}. A client may request a smaller
 * or larger CPU-time budget than the default, but never one exceeding the
 * operator-configured {@code maxRunningTime} ceiling (a longer budget is a DoS
 * lever, so the server cap always wins).
 */
public class RouteServerTimeoutTest {

  private String savedCeiling;

  @Before
  public void saveProperty() {
    savedCeiling = System.getProperty("maxRunningTime");
  }

  @After
  public void restoreProperty() {
    if (savedCeiling == null) {
      System.clearProperty("maxRunningTime");
    } else {
      System.setProperty("maxRunningTime", savedCeiling);
    }
  }

  private static Map<String, String> params(String timeout) {
    Map<String, String> p = new HashMap<>();
    if (timeout != null) {
      p.put("timeout", timeout);
    }
    return p;
  }

  @Test
  public void defaultCeilingWhenNoParam() {
    System.clearProperty("maxRunningTime");
    // Default ceiling is 60s; no timeout param -> the ceiling applies.
    Assert.assertEquals(60_000L, RouteServer.getMaxRunningTime(params(null)));
    Assert.assertEquals(60_000L, RouteServer.getMaxRunningTime(null));
  }

  @Test
  public void requestCanLowerBudgetBelowCeiling() {
    System.setProperty("maxRunningTime", "60");
    // A client asking for less than the ceiling gets exactly that.
    Assert.assertEquals(15_000L, RouteServer.getMaxRunningTime(params("15")));
  }

  @Test
  public void requestCanRaiseBudgetUpToCeiling() {
    System.setProperty("maxRunningTime", "120");
    // Ceiling raised to 120s by the operator; a client asking for 120s gets it
    // (this is what makes the >200km opt-in gate client-actionable).
    Assert.assertEquals(120_000L, RouteServer.getMaxRunningTime(params("120")));
  }

  @Test
  public void requestCannotExceedCeiling() {
    System.setProperty("maxRunningTime", "60");
    // Client asks for 300s but the operator ceiling is 60s -> clamped to 60s.
    Assert.assertEquals(60_000L, RouteServer.getMaxRunningTime(params("300")));
  }

  @Test
  public void fractionalSecondsAccepted() {
    System.setProperty("maxRunningTime", "60");
    Assert.assertEquals(2_500L, RouteServer.getMaxRunningTime(params("2.5")));
  }

  @Test
  public void malformedOrNonPositiveTimeoutFallsBackToCeiling() {
    System.setProperty("maxRunningTime", "45");
    Assert.assertEquals(45_000L, RouteServer.getMaxRunningTime(params("not-a-number")));
    Assert.assertEquals(45_000L, RouteServer.getMaxRunningTime(params("0")));
    Assert.assertEquals(45_000L, RouteServer.getMaxRunningTime(params("-5")));
  }
}
