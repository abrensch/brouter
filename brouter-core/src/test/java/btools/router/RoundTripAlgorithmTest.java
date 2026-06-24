package btools.router;

import java.util.Locale;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Tests for {@link RoundTripAlgorithm#fromString}: the {@code FAST} preview
 * alias, the internal enum names, and AUTO fallback (including the dropped
 * BALANCED/QUALITY names, which now resolve to AUTO). */
public class RoundTripAlgorithmTest {

  @Test
  public void fastAliasResolvesToWaypoint() {
    assertEquals(RoundTripAlgorithm.WAYPOINT, RoundTripAlgorithm.fromString("FAST"));
  }

  @Test
  public void fastAliasIsCaseInsensitive() {
    assertEquals(RoundTripAlgorithm.WAYPOINT, RoundTripAlgorithm.fromString("fast"));
    assertEquals(RoundTripAlgorithm.WAYPOINT, RoundTripAlgorithm.fromString("Fast"));
  }

  @Test
  public void internalEnumNamesStillParse() {
    // The alias map must NOT shadow the internal enum names.
    assertEquals(RoundTripAlgorithm.WAYPOINT, RoundTripAlgorithm.fromString("WAYPOINT"));
    assertEquals(RoundTripAlgorithm.ISOCHRONE, RoundTripAlgorithm.fromString("ISOCHRONE"));
    assertEquals(RoundTripAlgorithm.GREEDY, RoundTripAlgorithm.fromString("GREEDY"));
    assertEquals(RoundTripAlgorithm.ISO_GREEDY, RoundTripAlgorithm.fromString("ISO_GREEDY"));
    assertEquals(RoundTripAlgorithm.AUTO, RoundTripAlgorithm.fromString("AUTO"));
  }

  @Test
  public void unknownAlgorithmFallsBackToAuto() {
    assertEquals(RoundTripAlgorithm.AUTO, RoundTripAlgorithm.fromString("UNKNOWN"));
    assertEquals(RoundTripAlgorithm.AUTO, RoundTripAlgorithm.fromString(""));
    // The dropped BALANCED/QUALITY aliases now resolve to AUTO (the best-loop default).
    assertEquals(RoundTripAlgorithm.AUTO, RoundTripAlgorithm.fromString("BALANCED"));
    assertEquals(RoundTripAlgorithm.AUTO, RoundTripAlgorithm.fromString("QUALITY"));
  }

  @Test
  public void nullAlgorithmFallsBackToAuto() {
    assertEquals(RoundTripAlgorithm.AUTO, RoundTripAlgorithm.fromString(null));
  }

  @Test
  public void parsingIsLocaleIndependent() {
    // Turkish/Azeri locales map 'i'.toUpperCase() to dotted-İ, not 'I'. Without
    // Locale.ROOT, "isochrone"/"iso_greedy" would silently fall back to AUTO.
    // Guard against that regression.
    Locale prev = Locale.getDefault();
    try {
      Locale.setDefault(new Locale("tr", "TR"));
      assertEquals(RoundTripAlgorithm.ISOCHRONE, RoundTripAlgorithm.fromString("isochrone"));
      assertEquals(RoundTripAlgorithm.ISO_GREEDY, RoundTripAlgorithm.fromString("iso_greedy"));
      assertEquals(RoundTripAlgorithm.WAYPOINT, RoundTripAlgorithm.fromString("waypoint"));
    } finally {
      Locale.setDefault(prev);
    }
  }
}
