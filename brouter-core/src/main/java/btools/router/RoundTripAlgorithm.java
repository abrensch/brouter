package btools.router;

public enum RoundTripAlgorithm {
  AUTO,
  WAYPOINT,
  ISOCHRONE,
  GREEDY;

  public static RoundTripAlgorithm fromString(String s) {
    if (s == null) return AUTO;
    try {
      return valueOf(s.toUpperCase());
    } catch (IllegalArgumentException e) {
      return AUTO;
    }
  }
}
