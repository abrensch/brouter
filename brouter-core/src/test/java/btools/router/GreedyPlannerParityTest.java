package btools.router;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import btools.router.roundtrip.RoundTripAlgorithm;
import btools.router.roundtrip.RoundTripResult;

/**
 * Refactor-parity pin for {@code GreedyRoundTripPlanner.plan()} (code-review
 * finding #2: splitting the 880-line method into session / candidate-round /
 * loop-assembler / outcome-evaluator pieces). Fingerprints the planner's FULL
 * observable output — routed polyline, loop waypoints, decision telemetry —
 * across a scenario grid on the bundled Dreieich fixture, and asserts it is
 * bit-identical to goldens captured on the pre-refactor code. Any structural
 * extraction that reorders, drops, or duplicates a state update in the plan
 * loop changes at least one fingerprint and fails here immediately, without
 * waiting for the (slow, real-map) loop-quality matrix.
 *
 * <p>Every scenario also runs TWICE with fresh engines and asserts the two
 * fingerprints are equal — the determinism precondition that makes golden
 * comparison meaningful (the same property ADR-0001's variety-seed sentinel
 * pins at loop granularity; here it is pinned at full-telemetry granularity).
 *
 * <p>These goldens are CHANGE DETECTORS, not correctness claims: any
 * deliberate behavior change to the planner (scoring weights, budgets,
 * candidate policy) is EXPECTED to break them. Recapture in that case:
 * run with {@code -Dgreedy.parity.print=true}, which prints the current
 * fingerprints (and skips the golden assertions), then paste the printed
 * block over the {@code GOLDENS} entries below.
 *
 * <p>Wall-clock-dependent fields (runtimeMillis, the diagnostics list — its
 * budget line embeds elapsed ms) are deliberately excluded from the
 * fingerprint. Deadline branches never fire on the fixture (plans finish in
 * well under a second against a 30s budget), so the remaining fields are
 * decision-determined, not time-determined.
 */
public class GreedyPlannerParityTest {

  private static final boolean PRINT_MODE = Boolean.getBoolean("greedy.parity.print");

  /** Scenario grid: algo|profile|direction|radius|seed → golden fingerprint. */
  private static final Map<String, String> GOLDENS = new LinkedHashMap<>();

  static {
    // Captured on the pre-refactor baseline (2026-07-18, branch
    // roundtrip-upstream-v2 after review findings #1/#3/#4). See class doc
    // for the recapture procedure.
    GOLDENS.put("GREEDY|gravel|0|1000|0", "err=-;n=116;d=5545;h=b5ee02c7dbcf55a9;pd=6017;tol=true;fb=-;wp=bcb54cfbad2e2db;cg=85;cr=13;rk=2;ri=0;rn=13;ai=0;an=3;aq=0;ps=-1;ph=NaN;fc=false;gc=false");
    GOLDENS.put("GREEDY|gravel|90|1000|0", "err=-;n=228;d=5998;h=92632646657e97df;pd=6098;tol=true;fb=-;wp=920a7b18e83e455;cg=97;cr=13;rk=3;ri=0;rn=13;ai=0;an=3;aq=0;ps=-1;ph=NaN;fc=false;gc=false");
    GOLDENS.put("GREEDY|gravel|180|1000|0", "err=-;n=230;d=6242;h=283c9ac6933f4b7;pd=6335;tol=true;fb=-;wp=3b95d653a8945793;cg=57;cr=8;rk=2;ri=0;rn=8;ai=0;an=2;aq=0;ps=-1;ph=NaN;fc=false;gc=false");
    GOLDENS.put("GREEDY|gravel|270|1000|0", "err=-;n=230;d=6242;h=283c9ac6933f4b7;pd=6335;tol=true;fb=-;wp=3b95d653a8945793;cg=57;cr=8;rk=2;ri=0;rn=8;ai=0;an=2;aq=0;ps=-1;ph=NaN;fc=false;gc=false");
    GOLDENS.put("ISO_GREEDY|gravel|90|1000|0", "err=-;n=206;d=6005;h=167f4cfe7584deb6;pd=6339;tol=true;fb=-;wp=1fbb9c544f82d68c;cg=132;cr=13;rk=3;ri=2;rn=11;ai=0;an=3;aq=0;ps=-1;ph=0.6900;fc=false;gc=true");
    GOLDENS.put("ISO_GREEDY|gravel|270|1000|0", "err=-;n=230;d=6242;h=283c9ac6933f4b7;pd=6335;tol=true;fb=-;wp=3b95d653a8945793;cg=85;cr=8;rk=2;ri=1;rn=7;ai=0;an=2;aq=0;ps=-1;ph=0.7500;fc=false;gc=false");
    GOLDENS.put("GREEDY|trekking|90|1000|0", "err=-;n=200;d=4955;h=2ea525ab57e7f0f;pd=5989;tol=true;fb=-;wp=4318eefcaebea881;cg=139;cr=18;rk=8;ri=0;rn=18;ai=0;an=3;aq=0;ps=-1;ph=NaN;fc=false;gc=false");
    GOLDENS.put("ISO_GREEDY|trekking|270|1000|0", "err=-;n=149;d=3752;h=f7042709e1b6e3d;pd=5828;tol=false;fb=best error=6.3%;wp=f98a009fac4657b9;cg=112;cr=18;rk=7;ri=0;rn=18;ai=0;an=3;aq=0;ps=-1;ph=NaN;fc=false;gc=true");
    GOLDENS.put("GREEDY|fastbike|90|1000|0", "err=greedy round trip planner produced no acceptable loop: could not build any loop;track=-;pd=0;tol=false;fb=could not build any loop;wp=0;cg=0;cr=0;rk=0;ri=0;rn=0;ai=0;an=0;aq=0;ps=-1;ph=NaN;fc=false;gc=false");
    GOLDENS.put("ISO_GREEDY|fastbike|90|1000|0", "err=greedy round trip planner produced no acceptable loop: could not build any loop;track=-;pd=0;tol=false;fb=could not build any loop;wp=0;cg=0;cr=0;rk=0;ri=0;rn=0;ai=0;an=0;aq=0;ps=-1;ph=NaN;fc=false;gc=false");
    GOLDENS.put("GREEDY|gravel|90|1000|7", "err=-;n=201;d=5486;h=cf5189b49eed314a;pd=5489;tol=false;fb=best error=10.1%;wp=73013f946df5dc1d;cg=97;cr=13;rk=4;ri=0;rn=13;ai=0;an=3;aq=0;ps=-1;ph=NaN;fc=false;gc=false");
    GOLDENS.put("ISO_GREEDY|gravel|90|1000|7", "err=-;n=206;d=6005;h=167f4cfe7584deb6;pd=6339;tol=true;fb=-;wp=1fbb9c544f82d68c;cg=132;cr=13;rk=3;ri=2;rn=11;ai=0;an=3;aq=0;ps=-1;ph=0.5300;fc=false;gc=true");
  }

  @Test
  public void plannerOutputMatchesPreRefactorGoldens() {
    StringBuilder printBlock = new StringBuilder();
    StringBuilder mismatches = new StringBuilder();
    for (Map.Entry<String, String> e : GOLDENS.entrySet()) {
      String key = e.getKey();
      String fp1 = fingerprintFor(key);
      String fp2 = fingerprintFor(key);
      // Determinism is load-bearing in BOTH modes: goldens are only
      // meaningful if the same request reproduces the same plan.
      Assert.assertEquals("nondeterministic planner output for " + key, fp1, fp2);
      if (PRINT_MODE) {
        printBlock.append("    GOLDENS.put(\"").append(key).append("\", \"")
          .append(fp1).append("\");\n");
      } else if (!e.getValue().equals(fp1)) {
        mismatches.append("\n  ").append(key)
          .append("\n    golden:  ").append(e.getValue())
          .append("\n    current: ").append(fp1);
      }
    }
    if (PRINT_MODE) {
      System.out.println("=== GreedyPlannerParityTest current fingerprints ===");
      System.out.print(printBlock);
      System.out.println("=== end fingerprints ===");
      Assume.assumeTrue("print mode: fingerprints captured, assertions skipped", false);
    }
    Assert.assertEquals("planner fingerprints diverged from the pre-refactor goldens"
      + " (deliberate behavior change? recapture with -Dgreedy.parity.print=true):"
      + mismatches, "", mismatches.toString());
  }

  private static String fingerprintFor(String key) {
    String[] parts = key.split("\\|");
    RoundTripAlgorithm algo = RoundTripAlgorithm.valueOf(parts[0]);
    String profile = parts[1];
    int direction = Integer.parseInt(parts[2]);
    int radius = Integer.parseInt(parts[3]);
    int seed = Integer.parseInt(parts[4]);
    RoutingEngine re = RoundTripFixture.engine(profile, direction, radius, rc -> {
      rc.roundTripAlgorithm = algo;
      if (seed > 0) {
        rc.setAlternativeIdx(seed);
      }
    });
    return fingerprint(re);
  }

  /** Deterministic digest of everything the planner decided (no wall-clock fields). */
  private static String fingerprint(RoutingEngine re) {
    StringBuilder sb = new StringBuilder(160);
    String err = re.getErrorMessage();
    sb.append("err=").append(err == null ? "-" : err.replace('\n', ' '));
    OsmTrack track = re.getFoundTrack();
    if (track == null || track.nodes == null) {
      sb.append(";track=-");
    } else {
      sb.append(";n=").append(track.nodes.size())
        .append(";d=").append(track.distance)
        .append(";h=").append(Long.toHexString(polylineHash(track.nodes)));
    }
    RoundTripResult r = re.getLastRoundTripResult();
    if (r == null) {
      sb.append(";planner=-");
    } else {
      sb.append(";pd=").append(r.getTotalDistanceMeters())
        .append(";tol=").append(r.isWithinTolerance())
        .append(";fb=").append(r.getFallbackReason() == null ? "-" : r.getFallbackReason())
        .append(";wp=").append(Long.toHexString(waypointsHash(r.getLoopWaypoints())))
        .append(";cg=").append(r.getCandidatesGenerated())
        .append(";cr=").append(r.getCandidatesRouted())
        .append(";rk=").append(r.getReturnChecksPerformed())
        .append(";ri=").append(r.getRoutedIsoCandidates())
        .append(";rn=").append(r.getRoutedNonIsoCandidates())
        .append(";ai=").append(r.getAcceptedIsoLegs())
        .append(";an=").append(r.getAcceptedNonIsoLegs())
        .append(";aq=").append(r.getAcceptedQuotaInjectedLegs())
        .append(";ps=").append(r.getPoolDemotedAtStep())
        .append(";ph=").append(Double.isNaN(r.getIsoPoolHealthScore())
          ? "NaN" : String.format(Locale.US, "%.4f", r.getIsoPoolHealthScore()))
        .append(";fc=").append(r.isForcedCorridorAccepted())
        .append(";gc=").append(r.isInternalGraphNativeCompared());
    }
    return sb.toString();
  }

  /** Order-sensitive FNV-1a over the polyline's integer coordinates. */
  private static long polylineHash(List<OsmPathElement> nodes) {
    long h = 0xcbf29ce484222325L;
    for (OsmPathElement n : nodes) {
      h = (h ^ n.getILon()) * 0x100000001b3L;
      h = (h ^ n.getILat()) * 0x100000001b3L;
    }
    return h;
  }

  private static long waypointsHash(List<OsmNodeNamed> wps) {
    if (wps == null) return 0;
    long h = 0xcbf29ce484222325L;
    for (OsmNodeNamed w : wps) {
      h = (h ^ w.ilon) * 0x100000001b3L;
      h = (h ^ w.ilat) * 0x100000001b3L;
    }
    return h;
  }
}
