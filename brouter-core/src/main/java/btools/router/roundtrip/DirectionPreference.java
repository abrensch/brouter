package btools.router.roundtrip;

import java.util.Locale;

/**
 * Eight-point compass direction the round-trip heads out toward, plus
 * {@link #ANY} for "no preference". Each carries its {@link #bearing} in degrees
 * ({@code [0, 360)}; {@code -1} for {@code ANY}). The planner maps a requested
 * start-direction to the nearest constant, and
 * {@link CandidateScorer#directionScore} penalises misaligned candidate
 * bearings, keeping a coherent outward heading instead of self-crossings.
 */
enum DirectionPreference {
  N(0), NE(45), E(90), SE(135), S(180), SW(225), W(270), NW(315), ANY(-1);

  final double bearing;

  DirectionPreference(double bearing) {
    this.bearing = bearing;
  }

  static DirectionPreference fromString(String s) {
    if (s == null) return ANY;
    try {
      return valueOf(s.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return ANY;
    }
  }
}
