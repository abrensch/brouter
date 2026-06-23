package btools.router;

import java.util.List;

/**
 * Road-CHARACTER appeal of a finished route — "would a real cyclist want to ride this?" — measured
 * from the per-segment OSM tags ({@link MessageData#wayKeyValues}), NOT from the generating profile's
 * cost. That independence is the whole point: a route built by a residential-penalising profile must
 * be judged on the roads it actually uses (more tracks/through-roads, less village wiggle), not on the
 * inflated cost that profile assigned — otherwise the comparison is circular.
 *
 * <p>{@link RouteChoiceScore} (RCS) measures loop GEOMETRY (distance ratio, reuse, self-crossings,
 * closure) and is blind to road character; that is the gap a rider's eye sees and RCS does not. This
 * class fills it with a length-weighted desirability per {@code highway} class for the gravel family:
 * tracks/paths/quiet lanes score high, busy arterials and residential-interior touring score low.
 */
public final class RoadCharacterScore {

  /** Length-weighted desirability in [0,1]; higher = more appealing to ride. */
  public final double appeal;
  /** Fraction of route length on residential/living_street (village-interior character). */
  public final double residentialFrac;
  /** Fraction on track/path/cycleway/bridleway (ideal gravel). */
  public final double trackFrac;
  /** Fraction on primary/secondary/trunk (busy arterials). */
  public final double arterialFrac;
  /** Fraction on untagged/null edges (links, render artefacts). */
  public final double untaggedFrac;

  private RoadCharacterScore(double appeal, double residentialFrac, double trackFrac,
                             double arterialFrac, double untaggedFrac) {
    this.appeal = appeal;
    this.residentialFrac = residentialFrac;
    this.trackFrac = trackFrac;
    this.arterialFrac = arterialFrac;
    this.untaggedFrac = untaggedFrac;
  }

  private static final int FAM_GRAVEL = 0, FAM_ROAD = 1, FAM_MTB = 2, FAM_TREKKING = 3;

  /** Resolve a profile name to a desirability family (mirrors {@link RouteChoiceScore#costMBand}). */
  static int family(String profileName) {
    if (profileName == null) return FAM_TREKKING;
    String n = profileName.toLowerCase(java.util.Locale.ROOT);
    if (n.contains("fastbike") || n.matches(".*\\broad\\b.*") || n.contains("racing")) return FAM_ROAD;
    if (n.contains("gravel")) return FAM_GRAVEL;
    if (n.contains("mtb")) return FAM_MTB;
    if (n.contains("trekking")) return FAM_TREKKING;
    return FAM_TREKKING;
  }

  /**
   * Per-family rider desirability of a {@code highway} class, judged from the tag (profile-cost
   * independent). Families differ sharply: a road/fastbike rider WANTS smooth paved arterials and
   * avoids rough tracks; a gravel/mtb rider is the opposite. Applying one family's preference to
   * another would mis-score the route, so RCS must pass the active profile name through.
   */
  private static double desirability(int fam, String highway) {
    boolean none = (highway == null || highway.isEmpty());
    switch (fam) {
      case FAM_ROAD:     return desirabilityRoad(highway, none);
      case FAM_MTB:      return desirabilityMtb(highway, none);
      case FAM_TREKKING: return desirabilityTrekking(highway, none);
      default:           return desirabilityGravel(highway, none); // FAM_GRAVEL
    }
  }

  // smooth-paved-loving: arterials good, tracks bad
  private static double desirabilityRoad(String highway, boolean none) {
    if (none) return 0.55;
    switch (highway) {
      case "cycleway": return 1.00;
      case "tertiary": case "tertiary_link": return 0.92;
      case "secondary": case "secondary_link": return 0.85;
      case "unclassified": return 0.72;
      case "primary": case "primary_link": return 0.62;
      case "residential": return 0.60;
      case "living_street": return 0.52;
      case "service": return 0.50;
      case "trunk": case "trunk_link": return 0.25;
      case "track": return 0.25;
      case "path": case "bridleway": return 0.12;
      case "pedestrian": case "steps": return 0.10;
      case "motorway": case "motorway_link": return 0.00;
      default: return 0.55;
    }
  }

  // path/track-loving, even more than gravel
  private static double desirabilityMtb(String highway, boolean none) {
    if (none) return 0.50;
    switch (highway) {
      case "path": case "track": case "bridleway": return 1.00;
      case "cycleway": return 0.80;
      case "unclassified": return 0.70;
      case "service": return 0.60;
      case "tertiary": case "tertiary_link": return 0.55;
      case "living_street": return 0.45;
      case "residential": return 0.40;
      case "footway": return 0.55;
      case "secondary": case "secondary_link": return 0.30;
      case "pedestrian": case "steps": return 0.30;
      case "primary": case "primary_link": return 0.15;
      case "trunk": case "trunk_link": return 0.05;
      case "motorway": case "motorway_link": return 0.00;
      default: return 0.55;
    }
  }

  // balanced
  private static double desirabilityTrekking(String highway, boolean none) {
    if (none) return 0.55;
    switch (highway) {
      case "cycleway": case "path": return 0.95;
      case "track": return 0.85;
      case "unclassified": return 0.80;
      case "tertiary": case "tertiary_link": return 0.75;
      case "service": return 0.62;
      case "residential": return 0.60;
      case "living_street": return 0.60;
      case "secondary": case "secondary_link": return 0.55;
      case "bridleway": case "footway": return 0.65;
      case "pedestrian": case "steps": return 0.40;
      case "primary": case "primary_link": return 0.35;
      case "trunk": case "trunk_link": return 0.10;
      case "motorway": case "motorway_link": return 0.00;
      default: return 0.60;
    }
  }

  // track/quiet-loving, dislikes arterials + village-interior touring
  private static double desirabilityGravel(String highway, boolean none) {
    if (none) return 0.50;
    switch (highway) {
      case "track": case "path": case "bridleway": case "footway": return 1.00;
      case "cycleway": return 0.95;
      case "unclassified": return 0.85;
      case "service": return 0.72;
      case "tertiary": case "tertiary_link": return 0.68;
      case "living_street": return 0.58;
      case "residential": return 0.45;   // rideable, but touring village interiors is the dislike
      case "secondary": case "secondary_link": return 0.38;
      case "pedestrian": case "steps": return 0.30;
      case "primary": case "primary_link": return 0.20;
      case "trunk": case "trunk_link": return 0.05;
      case "motorway": case "motorway_link": return 0.00;
      default: return 0.55;
    }
  }

  private static boolean isTrackFamily(String h) {
    return "track".equals(h) || "path".equals(h) || "cycleway".equals(h) || "bridleway".equals(h) || "footway".equals(h);
  }

  private static boolean isArterial(String h) {
    return "primary".equals(h) || "primary_link".equals(h) || "secondary".equals(h)
      || "secondary_link".equals(h) || "trunk".equals(h) || "trunk_link".equals(h)
      || "motorway".equals(h) || "motorway_link".equals(h);
  }

  private static boolean isResidential(String h) {
    return "residential".equals(h) || "living_street".equals(h);
  }

  /** Extract the {@code highway} value from a space-separated {@code key=value} tag string. */
  static String highwayOf(String wayKeyValues) {
    if (wayKeyValues == null) return null;
    int i = wayKeyValues.indexOf("highway=");
    if (i < 0) return null;
    int s = i + "highway=".length();
    int e = s;
    while (e < wayKeyValues.length() && wayKeyValues.charAt(e) != ' ') e++;
    return wayKeyValues.substring(s, e);
  }

  public static RoadCharacterScore compute(OsmTrack track, String profileName) {
    if (track == null || track.nodes == null || track.nodes.size() < 2) {
      return new RoadCharacterScore(0, 0, 0, 0, 1);
    }
    int fam = family(profileName);
    List<OsmPathElement> nodes = track.nodes;
    double total = 0, weighted = 0, resid = 0, trk = 0, art = 0, untag = 0;
    for (int i = 1; i < nodes.size(); i++) {
      OsmPathElement prev = nodes.get(i - 1), curr = nodes.get(i);
      int segLen = prev.calcDistance(curr);
      if (segLen <= 0) continue;
      String tags = (curr.message != null) ? curr.message.wayKeyValues : null;
      String h = highwayOf(tags);
      total += segLen;
      weighted += segLen * desirability(fam, h);
      if (h == null) untag += segLen;
      else if (isResidential(h)) resid += segLen;
      else if (isTrackFamily(h)) trk += segLen;
      else if (isArterial(h)) art += segLen;
    }
    if (total <= 0) return new RoadCharacterScore(0, 0, 0, 0, 1);
    return new RoadCharacterScore(weighted / total, resid / total, trk / total, art / total, untag / total);
  }

  @Override
  public String toString() {
    return String.format(java.util.Locale.US,
      "appeal=%.2f resid=%.0f%% track=%.0f%% arterial=%.0f%% untag=%.0f%%",
      appeal, residentialFrac * 100, trackFrac * 100, arterialFrac * 100, untaggedFrac * 100);
  }
}
