package btools.router;

import java.util.Locale;

/**
 * Eight-point compass direction the round-trip should head out toward, plus
 * {@link #ANY} for "no preference". Each constant carries its {@link #bearing}
 * in degrees ({@code [0, 360)}; {@code -1} for {@code ANY}). The planner maps a
 * requested start-direction to the nearest constant
 * ({@link GreedyRoundTripPlanner} via {@code nearestDirectionPreference}) and
 * {@link CandidateScorer#directionScore} penalises candidate bearings that
 * misalign with it, so a loop keeps a coherent outward heading instead of
 * doubling back into self-crossings.
 */
public enum DirectionPreference {
  N(0), NE(45), E(90), SE(135), S(180), SW(225), W(270), NW(315), ANY(-1);

  public final double bearing;

  DirectionPreference(double bearing) {
    this.bearing = bearing;
  }

  public static DirectionPreference fromString(String s) {
    if (s == null) return ANY;
    try {
      return valueOf(s.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return ANY;
    }
  }
}
