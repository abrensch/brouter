/**
 * Loop-route suggestion generator (BROUTER_ENGINEMODE_LOOP).
 *
 * <p>Given a start node and a target distance D (meters), produce a ranked list of
 * high-quality loops that start and end at the start node and are approximately D
 * meters long. The algorithm has four phases:
 *
 * <ol>
 *   <li><b>Reverse Dijkstra precomputation.</b> A Dijkstra from the start node,
 *       evaluating every edge in its reverse orientation (BRouter's {@code inverseDirection}
 *       flag), yields for each reachable node the optimal weighted cost and real track
 *       length of the <i>return</i> leg back to start. We keep the best path object per
 *       node (its {@link OsmPathElement} chain is the predecessor array), not full edge
 *       sets, so memory stays O(V).</li>
 *   <li><b>Forward search.</b> A quality-weighted Dijkstra from the start node using the
 *       same profile weights. Real track length ({@code lenForward}) and weighted cost
 *       ({@code costForward}) are tracked separately, because BRouter cost units are not
 *       meters and using cost to estimate distance would make the tolerance band
 *       meaningless. When a node is dequeued we compute
 *       {@code f(node) = |D - (lenForward + lenBack)|}; nodes within the tolerance band
 *       become candidates. The free early-stop bound
 *       {@code lenForward + lenBack > D*(1+tol)} prunes paths that can no longer close.</li>
 *   <li><b>Overlap detection (lazy).</b> For each candidate we reconstruct the forward and
 *       return edge sets and intersect them. No overlap: use the precomputed return leg
 *       directly. Overlap: re-run the return search from the candidate to start with heavy
 *       penalties (BRouter's existing nogo/"avoid" mechanism) on the outbound edges, which
 *       actively finds a better return route around the outbound leg.</li>
 *   <li><b>Ranking.</b> Composite score over closeness-to-D, average quality, overlap
 *       fraction and directional diversity (8 compass sectors). Return the top results.</li>
 * </ol>
 *
 * <p>Adaptation note: BRouter's graph is a lazily-loaded "hollow node" graph keyed by a
 * packed lon/lat id ({@link OsmNode#getIdFromPos()}), not a dense integer-indexed array,
 * and {@link OsmPath} tracks only weighted {@code cost} (never real meters) along a path.
 * We therefore key the per-node arrays of the spec by {@code getIdFromPos()} and accumulate
 * real meters ourselves from the detail-mode geometry, while reusing BRouter's weight
 * system ({@link RoutingContext#createPath}) and nogo penalty mechanism verbatim.
 */
package btools.router;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Random;
import java.util.Set;

import btools.mapaccess.NodesCache;
import btools.mapaccess.OsmLink;
import btools.mapaccess.OsmNode;
import btools.util.CheapAngleMeter;

public class LoopGenerator {

  private final RoutingEngine engine;
  private final RoutingContext rc;
  private final NodesCache nodesCache;
  private final OsmNode start;
  private final int targetDistance;      // D, meters
  private final double tol;              // tolerance fraction (e.g. 0.10)
  private final long startId;
  // No point on a closed loop of real length ~D can lie farther than D/2 straight-line from
  // the start (fwdLen >= airDist and backLen >= airDist, and fwdLen+backLen ~= D). Pruning the
  // search to this disk is the key scaling win for large loops (10+ mi): the explored area
  // shrinks with the square of the radius. For long loops we tighten the radius below the
  // worst-case D/2 (see reachFactor) since desirable loops are roundish (farthest point
  // ~0.32*D), not near out-and-back.
  private final int airBound;

  // safety caps to keep a single request bounded
  private static final int MAX_POPS = 4_000_000;
  private static final int MAX_CANDIDATES = 4000;
  private static final int MAX_OVERLAP_NOGOS = 150;
  // a candidate sharing more than this fraction of its edges between the outbound and return legs
  // is an out-and-back, not a loop — drop it (unless nothing cleaner exists).
  private static final double MAX_OVERLAP_FRACTION = 0.6;

  // Adaptive early-stop: while a search is cheap (fewer than SOFT_POP_BUDGET nodes expanded)
  // we never stop early, so short loops get a full, best-quality search. Once a search has
  // grown past the budget — which only happens for large loops in dense graphs — we stop as
  // soon as a sufficient, directionally-diverse candidate pool has been collected.
  private static final long SOFT_POP_BUDGET = 150_000;

  // Phase-1 output: return-leg cost / length / path per node id.
  private Map<Long, PathInfo> backInfo;

  // When non-null, the current search is goal-directed (A*): open-set ordering adds a
  // straight-line-distance heuristic toward this node. Used for the candidate->start return
  // re-search so it explores a narrow corridor instead of a full disk. Searches never nest,
  // so a single field is safe.
  private OsmNode searchGoal;

  // Surface targeting for PATHFINDING (opt-in). When active, exploration adds a per-meter cost
  // to edges whose way tags do NOT match loopSurface, nudging the search toward matching ways —
  // so the pathfinding and ranking layers reinforce each other. Off by default (zero overhead:
  // reading a link's tags builds a string, the very cost the detailMode-free search avoids).
  private final List<String> surfaceKeywords;
  private final boolean surfaceActive;
  private final double surfaceBiasPerMeter;

  // Ranking randomness: seeded so results are reproducible with a fixed loopSeed but vary across
  // calls by default. Used to jitter scores among high-quality candidates (see jitter()).
  private final Random random;

  // Upper bound on nodes settled by a single Dijkstra sweep, scaled to the JVM heap. A large loop
  // in a very dense area can otherwise explore enough nodes to exhaust the heap; hitting this cap
  // aborts the search cleanly (reported as an error) instead of throwing OutOfMemoryError.
  private final int maxSettledNodes;

  static class PathInfo {
    final OsmPath path;   // best path object reaching this node (carries the element chain)
    final int cost;       // accumulated weighted BRouter cost
    final int len;        // accumulated real track length in meters

    PathInfo(OsmPath path, int cost, int len) {
      this.path = path;
      this.cost = cost;
      this.len = len;
    }
  }

  static class QEntry {
    final OsmPath path;
    final int cost;
    final int len;
    final int prio;   // ordering key = cost + A* heuristic (== cost when no goal)

    QEntry(OsmPath path, int cost, int len, int prio) {
      this.path = path;
      this.cost = cost;
      this.len = len;
      this.prio = prio;
    }
  }

  static class Candidate {
    long nodeId;
    OsmPath forwardPath;
    int forwardCost;
    int forwardLen;
    OsmPath returnPath;
    int returnCost;
    int returnLen;
    int totalLen;
    double overlapFraction;
    double costPerMeter;
    double hilliness;     // elevation gain (m) per km, from the built track
    int airSectorVal;     // 0..7 straight-line bearing bucket (cheap, for pre-ranking)
    int sector;           // 0..7 initial-outbound-bearing bucket (precise, for final ranking)
    double baseScore;     // closeness + quality + overlap terms
    double score;         // final score incl. diversity
    OsmTrack track;       // built lazily for the winners
  }

  public LoopGenerator(RoutingEngine engine, RoutingContext rc, NodesCache nodesCache,
                       OsmNode start, int targetDistance) {
    this.engine = engine;
    this.rc = rc;
    this.nodesCache = nodesCache;
    this.start = start;
    this.targetDistance = targetDistance;
    this.tol = rc.loopTolerance <= 0 ? 0.10 : rc.loopTolerance;
    this.startId = start.getIdFromPos();
    this.airBound = (int) (reachFactor() * (1.0 + tol) * targetDistance) + 100;
    this.surfaceKeywords = expandSurface(rc.loopSurface);
    this.surfaceBiasPerMeter = rc.loopWeightSurface;
    this.surfaceActive = surfaceBiasPerMeter > 0 && !surfaceKeywords.isEmpty();
    this.random = rc.loopSeed != null ? new Random(rc.loopSeed) : new Random();
    // ~800 bytes/node covers the path object, its element, and the best/settled/open entries;
    // budget ~60% of the heap for one sweep. Clamped to a sane range.
    long budget = Runtime.getRuntime().maxMemory() * 6L / 10L;
    this.maxSettledNodes = (int) Math.max(200_000L, Math.min(3_000_000L, budget / 800L));
  }

  private void checkNodeBudget(int settledCount) {
    if (settledCount > maxSettledNodes) {
      throw new IllegalArgumentException(
        "loop search too large for available memory - reduce loopDistance, loosen constraints, or give the server more heap (-Xmx)");
    }
  }

  // A non-negative random perturbation in [0, loopRandomness). Added to a candidate's ranking
  // score so that candidates whose scores are within loopRandomness of each other get shuffled —
  // giving different (still high-quality) loops on repeated calls. Deterministic given loopSeed.
  private double jitter() {
    return rc.loopRandomness <= 0 ? 0.0 : random.nextDouble() * rc.loopRandomness;
  }

  // Straight-line search radius as a fraction of D. 0.5 is the rigorous worst-case bound
  // (out-and-back). Short loops keep the full bound; long loops tighten toward ~0.38 so the
  // searched disk — and thus both Dijkstra phases — shrink with the square of the radius.
  private double reachFactor() {
    if (rc.loopReachFactor > 0) {
      return rc.loopReachFactor;
    }
    double km = targetDistance / 1000.0;
    if (km <= 5.0) return 0.50;
    if (km >= 20.0) return 0.38;
    return 0.50 + (0.38 - 0.50) * (km - 5.0) / (20.0 - 5.0);
  }

  public List<OsmTrack> generate() {
    // Phase 1 — return-leg cost/length field
    backInfo = reverseDijkstra();
    // Phase 2 — forward search, collect candidates (cheap metrics only)
    List<Candidate> candidates = forwardSearch();
    if (candidates.isEmpty()) {
      return new ArrayList<>();
    }
    // Pre-rank on cheap metrics (closeness + quality + air-bearing diversity) and keep only a
    // small finalist pool. The expensive per-candidate work in phase 3 (path reconstruction +
    // the penalized return re-search) then runs O(finalists) instead of O(candidates), which
    // is the dominant cost for large loops in dense areas.
    List<Candidate> finalists = selectFinalists(candidates);
    // Phase 3 — overlap detection + penalized return re-search, finalists only
    for (Candidate c : finalists) {
      resolveOverlap(c);
    }
    // Keep candidates that have a usable (rebuilt) return leg and aren't out-and-backs (heavy leg
    // overlap). If the overlap filter leaves nothing, relax it but still require a return path.
    List<Candidate> loops = new ArrayList<>();
    for (Candidate c : finalists) {
      if (c.returnPath != null && c.overlapFraction <= MAX_OVERLAP_FRACTION) {
        loops.add(c);
      }
    }
    if (loops.isEmpty()) {
      for (Candidate c : finalists) {
        if (c.returnPath != null) {
          loops.add(c);
        }
      }
    }
    // Phase 4 — full composite ranking
    return rank(loops);
  }

  // ------------------------------------------------------------------
  // Phase 1 — reverse Dijkstra precomputation
  // ------------------------------------------------------------------
  private Map<Long, PathInfo> reverseDijkstra() {
    boolean savedInverse = rc.inverseDirection;
    rc.inverseDirection = true;
    searchGoal = null; // full multi-target sweep, no A* heuristic
    try {
      // return-leg longer than the whole target band can never be part of a valid loop.
      double lenBound = targetDistance * (1.0 + tol);
      Map<Long, PathInfo> full = dijkstra(start, -1L, lenBound);
      // Retain only cost+length per node, dropping the OsmPath objects, so the reverse sweep's
      // paths can be garbage-collected before the forward sweep runs. Otherwise two full-disk sets
      // of paths coexist and large dense areas exhaust the heap. Return legs are cheap to rebuild
      // on demand for the handful of finalists (see returnLeg()).
      Map<Long, PathInfo> lean = new HashMap<>(full.size() * 4 / 3 + 1);
      for (Map.Entry<Long, PathInfo> en : full.entrySet()) {
        PathInfo p = en.getValue();
        lean.put(en.getKey(), new PathInfo(null, p.cost, p.len));
      }
      return lean;
    } finally {
      rc.inverseDirection = savedInverse;
    }
  }

  // Open-set ordering key: cost, plus a straight-line heuristic toward the goal when the
  // search is goal-directed. The heuristic (meters) never exceeds the true remaining cost for
  // typical profiles (cost ~= meters * costfactor, costfactor ~>= 1), so it stays admissible.
  private int prio(int cost, OsmNode node) {
    return searchGoal == null ? cost : cost + searchGoal.calcDistance(node);
  }

  // ------------------------------------------------------------------
  // Phase 2 — forward quality-weighted search with candidate collection
  // ------------------------------------------------------------------
  private List<Candidate> forwardSearch() {
    List<Candidate> candidates = new ArrayList<>();
    double bandLo = targetDistance * (1.0 - tol);
    double bandHi = targetDistance * (1.0 + tol);

    Map<Long, PathInfo> best = new HashMap<>();
    Set<Long> settled = new HashSet<>();
    Queue<QEntry> open = new PriorityQueue<>(Comparator.comparingInt(e -> e.prio));

    OsmPath startPath = rc.createPath(new OsmLink(null, start));
    best.put(startId, new PathInfo(startPath, 0, 0));
    open.add(new QEntry(startPath, 0, 0, 0));

    Set<Integer> airSectors = new HashSet<>();
    int pops = 0;
    boolean savedInverse = rc.inverseDirection;
    rc.inverseDirection = false;
    searchGoal = null; // outward sweep, no A* heuristic
    try {
      while (!open.isEmpty()) {
        if (engine.isTerminated()) {
          throw new IllegalArgumentException("loop search killed by watchdog");
        }
        if (++pops > MAX_POPS) {
          break;
        }
        QEntry e = open.poll();
        OsmNode u = e.path.getTargetNode();
        long uid = u.getIdFromPos();
        if (settled.contains(uid)) continue;
        PathInfo bi = best.get(uid);
        if (bi == null || bi.path != e.path) continue; // stale heap entry
        settled.add(uid);
        checkNodeBudget(settled.size());

        int fLen = bi.len;
        PathInfo back = backInfo.get(uid);

        // candidate check: total real length within the tolerance band and a return leg exists
        if (back != null && uid != startId) {
          int total = fLen + back.len;
          if (total >= bandLo && total <= bandHi) {
            Candidate c = new Candidate();
            c.nodeId = uid;
            c.forwardPath = bi.path;
            c.forwardCost = bi.cost;
            c.forwardLen = fLen;
            // returnPath is rebuilt in resolveOverlap; backInfo kept only cost/length to save memory
            c.returnCost = back.cost;
            c.returnLen = back.len;
            c.airSectorVal = airSector(u);
            candidates.add(c);
            airSectors.add(c.airSectorVal);
            if (candidates.size() >= MAX_CANDIDATES) {
              break;
            }
            // Adaptive early-stop: only once the search has become expensive (past the soft
            // pop budget), and only when we already hold a diverse, sufficient pool. Cheap
            // (short-loop) searches never hit the budget, so they run to completion.
            int minPool = Math.max(rc.loopMaxResults * 8, 64);
            if (pops > SOFT_POP_BUDGET && candidates.size() >= minPool
                && (airSectors.size() >= 6 || candidates.size() >= minPool * 4)) {
              break;
            }
          }
        }

        // free early-stop bound: cannot close within the band any more
        if (fLen > bandHi) continue;
        if (back != null && fLen + back.len > bandHi) continue;

        expand(u, bi, best, open);
      }
    } finally {
      rc.inverseDirection = savedInverse;
    }
    return candidates;
  }

  // Pre-rank candidates on cheap metrics only (no path reconstruction, no re-search) and
  // return a small, directionally-diverse finalist pool. Uses pre-penalized fwd+back
  // cost/length and the straight-line bearing sector.
  private List<Candidate> selectFinalists(List<Candidate> candidates) {
    double maxDev = targetDistance * tol;
    double minCpm = Double.MAX_VALUE, maxCpm = -Double.MAX_VALUE;
    for (Candidate c : candidates) {
      int len = c.forwardLen + c.returnLen;
      double cpm = len == 0 ? 0.0 : (double) (c.forwardCost + c.returnCost) / len;
      minCpm = Math.min(minCpm, cpm);
      maxCpm = Math.max(maxCpm, cpm);
    }
    double cpmRange = maxCpm - minCpm;
    for (Candidate c : candidates) {
      int len = c.forwardLen + c.returnLen;
      double cpm = len == 0 ? 0.0 : (double) (c.forwardCost + c.returnCost) / len;
      double closeness = 1.0 - Math.min(1.0, Math.abs(targetDistance - len) / Math.max(1.0, maxDev));
      double quality = cpmRange <= 0 ? 1.0 : 1.0 - (cpm - minCpm) / cpmRange;
      // jitter here varies the finalist POOL across calls, not just the final order
      c.baseScore = rc.loopWeightCloseness * closeness + rc.loopWeightQuality * quality + jitter();
    }
    candidates.sort(Comparator.comparingDouble((Candidate c) -> c.baseScore).reversed());

    // Want several per requested result for headroom (and spread across bearing sectors). The
    // extra headroom matters because some finalists are dropped later by the band re-validation
    // (retrace-inflated near-out-and-backs collapse when trimmed), so we need spares.
    int want = Math.max(rc.loopMaxResults * 4, 12);
    List<Candidate> finalists = new ArrayList<>();
    Set<Integer> sectorsSeen = new HashSet<>();
    List<Candidate> overflow = new ArrayList<>();
    for (Candidate c : candidates) {
      if (finalists.size() >= want) break;
      if (sectorsSeen.add(c.airSectorVal)) {
        finalists.add(c);
      } else {
        overflow.add(c);
      }
    }
    for (Candidate c : overflow) {
      if (finalists.size() >= want) break;
      finalists.add(c);
    }
    return finalists;
  }

  // ------------------------------------------------------------------
  // Phase 3 — lazy overlap detection + penalized return re-search
  // ------------------------------------------------------------------
  private void resolveOverlap(Candidate c) {
    OsmNode candidateNode = c.forwardPath.getTargetNode();

    // backInfo kept only the return-leg cost/length (to save memory), so rebuild the actual
    // optimal return path here for this finalist (a cheap goal-directed A* candidate -> start).
    PathInfo base = returnLeg(candidateNode, null);
    if (base == null) {
      c.overlapFraction = 1.0; // unusable — will be dropped
      return;
    }
    c.returnPath = base.path;
    c.returnCost = base.cost;
    c.returnLen = base.len;

    List<OsmPathElement> fwdChain = walkChain(c.forwardPath); // [candidate..start]
    List<OsmPathElement> retChain = walkChain(c.returnPath);  // [candidate..start]

    Set<String> fwdEdges = edgeSet(fwdChain);
    Set<String> retEdges = edgeSet(retChain);

    int shared = 0;
    for (String edge : retEdges) {
      if (fwdEdges.contains(edge)) shared++;
    }
    int union = fwdEdges.size() + retEdges.size() - shared;
    double overlap = union == 0 ? 0.0 : (double) shared / union;

    if (shared > 0) {
      // overlap: actively route the return leg around the outbound edges using the
      // existing nogo penalty mechanism, then keep whichever return leg is better.
      PathInfo penalized = returnLeg(candidateNode, fwdChain);
      if (penalized != null) {
        List<OsmPathElement> newRet = walkChain(penalized.path);
        Set<String> newRetEdges = edgeSet(newRet);
        int newShared = 0;
        for (String edge : newRetEdges) {
          if (fwdEdges.contains(edge)) newShared++;
        }
        int newUnion = fwdEdges.size() + newRetEdges.size() - newShared;
        double newOverlap = newUnion == 0 ? 0.0 : (double) newShared / newUnion;
        // accept the re-search if it reduces overlap; its total length must still be sane.
        int newTotal = c.forwardLen + penalized.len;
        if (newOverlap < overlap && newTotal <= targetDistance * (1.0 + tol)
            && newTotal >= targetDistance * (1.0 - tol)) {
          c.returnPath = penalized.path;
          c.returnCost = penalized.cost;
          c.returnLen = penalized.len;
          overlap = newOverlap;
        }
      }
    }

    c.overlapFraction = overlap;
    c.totalLen = c.forwardLen + c.returnLen;
    int totalCost = c.forwardCost + c.returnCost;
    c.costPerMeter = c.totalLen == 0 ? 0.0 : (double) totalCost / c.totalLen;
    c.sector = initialSector(fwdChain);
  }

  // Route the return leg candidate -> start as a goal-directed (A*) search. When fwdChain is given,
  // soft-nogos along a bounded sample of the outbound edges push the route around it (the overlap
  // re-search); when null, it's the plain optimal return. Returns the path at start, or null.
  private PathInfo returnLeg(OsmNode candidateNode, List<OsmPathElement> fwdChain) {
    List<OsmNodeNamed> saved = rc.nogopoints;
    if (fwdChain != null) {
      List<OsmNodeNamed> nogos = new ArrayList<>();
      int step = Math.max(1, fwdChain.size() / MAX_OVERLAP_NOGOS);
      for (int i = 0; i < fwdChain.size(); i += step) {
        OsmPathElement pe = fwdChain.get(i);
        OsmNodeNamed nogo = new OsmNodeNamed(new OsmNode(pe.getILon(), pe.getILat()));
        nogo.name = "loop_overlap";
        nogo.isNogo = true;
        nogo.radius = 15.0;
        nogo.nogoWeight = rc.loopOverlapPenalty; // plain multiplier applied within the radius
        nogos.add(nogo);
      }
      List<OsmNodeNamed> merged = new ArrayList<>();
      if (saved != null) merged.addAll(saved);
      merged.addAll(nogos);
      rc.nogopoints = merged;
    }

    boolean savedInverse = rc.inverseDirection;
    rc.inverseDirection = false; // candidate -> start in the normal travel direction
    searchGoal = start;          // goal-directed (A*): explore a corridor toward the start
    try {
      double lenBound = targetDistance * (1.0 + tol);
      // The re-search runs candidate->start in the normal travel direction; its path element
      // chain already reads candidate..start when walked via origin — exactly the return leg.
      Map<Long, PathInfo> res = dijkstra(candidateNode, startId, lenBound);
      return res.get(startId);
    } finally {
      rc.inverseDirection = savedInverse;
      rc.nogopoints = saved;
      searchGoal = null;
    }
  }

  // ------------------------------------------------------------------
  // Phase 4 — composite scoring & ranking with directional diversity
  // ------------------------------------------------------------------
  private List<OsmTrack> rank(List<Candidate> candidates) {
    // Build every finalist's detailed track up front: the hills and surface factors are read
    // from the actual routed geometry/tags. Finalists are few, so this is cheap.
    for (Candidate c : candidates) {
      buildTrack(c);
      c.hilliness = ascentPerKm(c.track);
    }

    // Re-validate the tolerance band on the ACTUAL built length. Candidate selection used the
    // fwd+back length, but spur trimming (and, rarely, replay) can shorten a loop below the band —
    // e.g. a surface-biased near-out-and-back whose retracing inflates its fwd+back length but
    // collapses when trimmed. The band is a hard contract: keep only in-band loops. If none
    // qualify, return nothing (doLoop reports "no loop found") rather than a much-too-short route.
    double bandLo = targetDistance * (1.0 - tol);
    double bandHi = targetDistance * (1.0 + tol);
    List<Candidate> inBand = new ArrayList<>();
    for (Candidate c : candidates) {
      if (c.totalLen >= bandLo && c.totalLen <= bandHi) {
        inBand.add(c);
      }
    }
    if (inBand.isEmpty()) {
      return new ArrayList<>();
    }
    candidates = inBand;

    // normalize closeness, quality, overlap, hilliness into [0,1] (higher = better)
    double maxCloseDev = targetDistance * tol; // worst allowed |D - total|
    double minCpm = Double.MAX_VALUE, maxCpm = -Double.MAX_VALUE;
    double minHill = Double.MAX_VALUE, maxHill = -Double.MAX_VALUE;
    for (Candidate c : candidates) {
      minCpm = Math.min(minCpm, c.costPerMeter);
      maxCpm = Math.max(maxCpm, c.costPerMeter);
      minHill = Math.min(minHill, c.hilliness);
      maxHill = Math.max(maxHill, c.hilliness);
    }
    double cpmRange = maxCpm - minCpm;
    double hillRange = maxHill - minHill;

    List<String> surfaceKeywords = expandSurface(rc.loopSurface);
    boolean preferHills = "prefer".equalsIgnoreCase(rc.loopHillPreference);

    for (Candidate c : candidates) {
      double closeness = 1.0 - Math.min(1.0, Math.abs(targetDistance - c.totalLen) / Math.max(1.0, maxCloseDev));
      double quality = cpmRange <= 0 ? 1.0 : 1.0 - (c.costPerMeter - minCpm) / cpmRange;
      double overlapTerm = 1.0 - c.overlapFraction;
      double hillNorm = hillRange <= 0 ? 0.5 : (c.hilliness - minHill) / hillRange;
      double hillTerm = preferHills ? hillNorm : 1.0 - hillNorm;
      double surfaceTerm = surfaceKeywords.isEmpty() ? 0.0 : surfaceFraction(c.track, surfaceKeywords);
      c.baseScore = rc.loopWeightCloseness * closeness
        + rc.loopWeightQuality * quality
        + rc.loopWeightOverlap * overlapTerm
        + rc.loopWeightHills * hillTerm
        + rc.loopWeightSurface * surfaceTerm
        + jitter();
    }

    // greedy directional diversity: reward the first (best) candidate seen in each sector
    candidates.sort(Comparator.comparingDouble((Candidate c) -> c.baseScore).reversed());
    Set<Integer> sectorsSeen = new HashSet<>();
    for (Candidate c : candidates) {
      double diversity = sectorsSeen.add(c.sector) ? 1.0 : 0.0;
      c.score = c.baseScore + rc.loopWeightDiversity * diversity;
    }
    candidates.sort(Comparator.comparingDouble((Candidate c) -> c.score).reversed());

    int want = Math.max(1, rc.loopMaxResults);
    List<OsmTrack> out = new ArrayList<>();
    Set<Integer> usedSectors = new HashSet<>();
    List<Candidate> overflow = new ArrayList<>();

    // first pass: one loop per sector, best first
    for (Candidate c : candidates) {
      if (out.size() >= want) break;
      if (usedSectors.add(c.sector)) {
        out.add(buildTrack(c));
      } else {
        overflow.add(c);
      }
    }
    // second pass: fill remaining slots from the leftovers, best first
    for (Candidate c : overflow) {
      if (out.size() >= want) break;
      out.add(buildTrack(c));
    }
    return out;
  }

  // Total elevation gain (meters) per km along the built track. selev is in 1/4-meter units.
  private double ascentPerKm(OsmTrack t) {
    double ascend = 0;
    short prev = Short.MIN_VALUE;
    for (OsmPathElement n : t.nodes) {
      short e = n.getSElev();
      if (e != Short.MIN_VALUE) {
        if (prev != Short.MIN_VALUE && e > prev) {
          ascend += (e - prev) / 4.0;
        }
        prev = e;
      }
    }
    double km = t.distance / 1000.0;
    return km <= 0 ? 0.0 : ascend / km;
  }

  // Fraction of the track's length whose OSM way tags contain any of the target keywords.
  private double surfaceFraction(OsmTrack t, List<String> keywords) {
    long total = 0;
    long matched = 0;
    for (int i = 1; i < t.nodes.size(); i++) {
      OsmPathElement n = t.nodes.get(i);
      int d = n.calcDistance(t.nodes.get(i - 1));
      total += d;
      String tags = (n.message != null && n.message.wayKeyValues != null)
        ? n.message.wayKeyValues.toLowerCase() : "";
      for (String kw : keywords) {
        if (tags.contains(kw)) {
          matched += d;
          break;
        }
      }
    }
    return total <= 0 ? 0.0 : (double) matched / total;
  }

  // Does the link's way carry any of the target surface tags? Decodes the link description into
  // its tag string (same source detailMode uses) and substring-matches the target keywords.
  private boolean surfaceMatches(OsmLink link, OsmNode source) {
    String tags = rc.expctxWay.getKeyValueDescription(link.isReverse(source), link.descriptionBitmap);
    if (tags == null) return false;
    tags = tags.toLowerCase();
    for (String kw : surfaceKeywords) {
      if (tags.contains(kw)) return true;
    }
    return false;
  }

  // Expand the loopSurface parameter (comma-separated) into lower-case tag substrings matched
  // against each way's tag description. Named classes expand to common OSM tags; anything else
  // is used verbatim (e.g. "highway=footway", "surface=asphalt").
  private List<String> expandSurface(String spec) {
    List<String> out = new ArrayList<>();
    if (spec == null || spec.trim().isEmpty()) {
      return out;
    }
    for (String raw : spec.split(",")) {
      String tok = raw.trim().toLowerCase();
      if (tok.isEmpty()) {
        continue;
      }
      switch (tok) {
        case "paved":
          out.add("surface=asphalt");
          out.add("surface=paved");
          out.add("surface=concrete");
          out.add("surface=paving_stones");
          break;
        case "unpaved":
          out.add("surface=gravel");
          out.add("surface=fine_gravel");
          out.add("surface=compacted");
          out.add("surface=ground");
          out.add("surface=dirt");
          break;
        case "path":
          // broad walking ways, including footways (which cover urban sidewalks)
          out.add("highway=path");
          out.add("highway=footway");
          out.add("highway=track");
          out.add("highway=bridleway");
          break;
        case "trail":
          // natural/off-road trails, deliberately EXCLUDING footway so it does not collapse
          // onto urban sidewalk ways (which are highway=footway)
          out.add("highway=path");
          out.add("highway=track");
          out.add("highway=bridleway");
          break;
        case "footway":
          // any footway (includes sidewalks that are tagged only highway=footway)
          out.add("highway=footway");
          break;
        case "sidewalk":
          // a dedicated sidewalk WAY running beside a road: highway=footway + footway=sidewalk
          out.add("footway=sidewalk");
          break;
        case "has_sidewalk":
          // a ROAD that carries a sidewalk (sidewalk mapped as an attribute of the road)
          out.add("sidewalk=both");
          out.add("sidewalk=left");
          out.add("sidewalk=right");
          out.add("sidewalk=yes");
          break;
        default:
          out.add(tok);
          break;
      }
    }
    return out;
  }

  // ------------------------------------------------------------------
  // Core bounded Dijkstra over the hollow-node graph
  // ------------------------------------------------------------------
  // Returns best path/cost/len per reached node id. If targetId >= 0 the search stops as
  // soon as that node is settled. Nodes whose accumulated real length exceeds lenBound are
  // not expanded further. inverseDirection / nogopoints must be set by the caller.
  private Map<Long, PathInfo> dijkstra(OsmNode source, long targetId, double lenBound) {
    Map<Long, PathInfo> best = new HashMap<>();
    Set<Long> settled = new HashSet<>();
    Queue<QEntry> open = new PriorityQueue<>(Comparator.comparingInt(e -> e.prio));

    OsmPath startPath = rc.createPath(new OsmLink(null, source));
    long srcId = source.getIdFromPos();
    best.put(srcId, new PathInfo(startPath, 0, 0));
    open.add(new QEntry(startPath, 0, 0, prio(0, source)));

    int pops = 0;
    while (!open.isEmpty()) {
      if (engine.isTerminated()) {
        throw new IllegalArgumentException("loop search killed by watchdog");
      }
      if (++pops > MAX_POPS) break;

      QEntry e = open.poll();
      OsmNode u = e.path.getTargetNode();
      long uid = u.getIdFromPos();
      if (settled.contains(uid)) continue;
      PathInfo bi = best.get(uid);
      if (bi == null || bi.path != e.path) continue;
      settled.add(uid);
      checkNodeBudget(settled.size());

      if (targetId >= 0 && uid == targetId) {
        break; // reached the requested target
      }
      if (bi.len > lenBound) continue; // cannot be useful any more

      expand(u, bi, best, open);
    }
    return best;
  }

  private void expand(OsmNode u, PathInfo bi, Map<Long, PathInfo> best,
                      Queue<QEntry> open) {
    if (!nodesCache.obtainNonHollowNode(u)) return;
    OsmNode source = bi.path.getSourceNode();
    for (OsmLink link = u.firstlink; link != null; link = link.getNext(u)) {
      OsmNode v = link.getTarget(u);
      if (v == u) continue;
      if (!nodesCache.obtainNonHollowNode(v)) continue; // border node
      if (v.firstlink == null) continue;                 // dead end
      if (source != null && v == source) continue;        // don't turn straight back
      if (v.calcDistance(start) > airBound) continue;     // outside the reachable loop disk

      // detailMode=false: much lighter per-edge work (no MessageData / transfer-node element
      // chain / kinematic detail) and, as a bonus, turn restrictions ARE evaluated (the core
      // skips them only in detailMode). Real link length comes from np.linkDist, which the
      // cost pass already computed — no second geometry decode. Detailed geometry for the few
      // winners is rebuilt later by replaying them in detailMode (see buildTrack).
      OsmPath np = rc.createPath(bi.path, link, null, false);
      if (np.cost < 0) continue; // forbidden (turn restriction / hard nogo / access)

      // Optional surface nudge: penalize edges whose tags don't match the target. The true
      // profile cost (np.cost) stays on the path object for the final track; only this
      // search cost carries the bias, so it steers path choice without faking the reported cost.
      int searchCost = np.cost;
      if (surfaceActive && link.descriptionBitmap != null && !surfaceMatches(link, u)) {
        searchCost += (int) (np.linkDist * surfaceBiasPerMeter);
      }

      int nlen = bi.len + np.linkDist;
      long vid = v.getIdFromPos();
      PathInfo cur = best.get(vid);
      if (cur == null || searchCost < cur.cost) {
        best.put(vid, new PathInfo(np, searchCost, nlen));
        open.add(new QEntry(np, searchCost, nlen, prio(searchCost, v)));
      }
    }
  }

  // Reconstruct the ordered element chain [thisNode .. start] by walking origin pointers.
  private List<OsmPathElement> walkChain(OsmPath path) {
    List<OsmPathElement> chain = new ArrayList<>();
    OsmPathElement e = OsmPathElement.create(path);
    while (e != null) {
      chain.add(e);
      e = e.origin;
    }
    return chain;
  }

  // Undirected edge keys for an element chain.
  private Set<String> edgeSet(List<OsmPathElement> chain) {
    Set<String> edges = new HashSet<>();
    for (int i = 1; i < chain.size(); i++) {
      long a = chain.get(i - 1).getIdFromPos();
      long b = chain.get(i).getIdFromPos();
      long lo = Math.min(a, b);
      long hi = Math.max(a, b);
      edges.add(lo + "_" + hi);
    }
    return edges;
  }

  // Orient a reconstructed chain so its first element is the node with id firstId.
  private List<OsmPathElement> orient(List<OsmPathElement> chain, long firstId) {
    if (!chain.isEmpty() && chain.get(0).getIdFromPos() != firstId) {
      Collections.reverse(chain);
    }
    return chain;
  }

  // Cheap straight-line bearing sector (0..7) from start to a node — used only as an
  // early-stop diversity heuristic during the forward search.
  private int airSector(OsmNode n) {
    double dir = CheapAngleMeter.getDirection(start.getILon(), start.getILat(), n.getILon(), n.getILat());
    return ((int) Math.floor((dir + 22.5) / 45.0)) % 8;
  }

  // Initial bearing sector (0..7) of the outbound leg, measured from the start node.
  private int initialSector(List<OsmPathElement> fwdChain) {
    // fwdChain is [candidate .. start]; the last two elements are the first outbound step.
    int n = fwdChain.size();
    if (n < 2) return 0;
    OsmPathElement s = fwdChain.get(n - 1); // start
    OsmPathElement next = fwdChain.get(n - 2);
    double dir = CheapAngleMeter.getDirection(s.getILon(), s.getILat(), next.getILon(), next.getILat());
    return ((int) Math.floor((dir + 22.5) / 45.0)) % 8;
  }

  // ------------------------------------------------------------------
  // Build a final OsmTrack: start -> outbound -> candidate -> return -> start.
  // Exploration ran in detailMode=false (junction-only, for speed), so here we replay the
  // winner's junction sequence once in detailMode=true to recover smooth geometry, elevation
  // and turn/message data — identical to a normal routed track. Falls back to a coarse
  // junction polyline if a link can't be resolved.
  // ------------------------------------------------------------------
  private OsmTrack buildTrack(Candidate c) {
    if (c.track != null) return c.track;

    // Orient each leg explicitly by endpoint node id rather than assuming a direction: the
    // return path may come from the reverse precompute (walks candidate->start) OR from the
    // penalized overlap re-search (walks start->candidate). Getting this wrong stitches the
    // candidate straight to a node next to the start — the "straight line to near start" bug.
    List<OsmPathElement> fwd = orient(walkChain(c.forwardPath), startId);   // [start .. candidate]
    List<OsmPathElement> ret = orient(walkChain(c.returnPath), c.nodeId);   // [candidate .. start]

    // Ordered element list for the whole loop: start .. candidate .. start.
    List<OsmPathElement> all = new ArrayList<>(fwd);
    for (int i = 1; i < ret.size(); i++) {
      all.add(ret.get(i));
    }

    // Remove immediate out-and-back spurs (A -> X -> A). These arise at the turnaround: the
    // reverse-Dijkstra's shortest return from the far point often begins by retracing the last
    // forward edge (near the far point the shortest forward and return paths coincide). Trimming
    // yields a clean loop; the excursion removed is small, and recalcTrack recomputes the length.
    all = trimSpurs(all);

    // Recover the real graph-node references by walking the live link graph from the start
    // node, matching each successive element's coordinates to a link target. Resolved
    // (non-hollow) nodes are NOT retrievable from nodesMap by coordinate after the search
    // (they are consumed), but the link objects still reference the real node instances, so
    // this walk is reliable. Consecutive elements are graph-adjacent junctions by construction.
    List<OsmNode> seq = new ArrayList<>();
    seq.add(start);
    OsmNode cur = start;
    boolean ok = true;
    for (int i = 1; i < all.size(); i++) {
      OsmNode nx = linkTargetAt(cur, all.get(i).getILon(), all.get(i).getILat());
      if (nx == null) {
        ok = false;
        break;
      }
      seq.add(nx);
      cur = nx;
    }

    OsmTrack track = ok ? replayDetailed(seq) : null;
    if (track == null) {
      track = buildCoarse(all); // fallback: straight junction-to-junction polyline
    }
    // real (post-trim) length; also what the hill factor reads before doLoop's recalcTrack runs
    int dist = 0;
    for (int i = 1; i < track.nodes.size(); i++) {
      dist += track.nodes.get(i).calcDistance(track.nodes.get(i - 1));
    }
    track.distance = dist;
    c.totalLen = dist;
    c.track = track;
    return track;
  }

  // The neighbour of `from` whose coordinates are (lon,lat), using the live link graph.
  private OsmNode linkTargetAt(OsmNode from, int lon, int lat) {
    if (!nodesCache.obtainNonHollowNode(from)) return null;
    for (OsmLink link = from.firstlink; link != null; link = link.getNext(from)) {
      OsmNode t = link.getTarget(from);
      if (t != null && t.ilon == lon && t.ilat == lat) {
        return t;
      }
    }
    return null;
  }

  // Replay the full loop as one normal (detailMode) path so geometry/elevation/messages match
  // a routed track. Returns null if any link can't be resolved or the path is rejected.
  private OsmTrack replayDetailed(List<OsmNode> seq) {
    if (seq.size() < 2) return null;
    boolean savedInverse = rc.inverseDirection;
    rc.inverseDirection = false;
    searchGoal = null;
    try {
      OsmNode s0 = seq.get(0);
      if (s0 == null || !nodesCache.obtainNonHollowNode(s0)) return null;
      OsmPath p = rc.createPath(new OsmLink(null, s0));
      for (int i = 1; i < seq.size(); i++) {
        OsmNode u = seq.get(i - 1);
        OsmNode v = seq.get(i);
        if (u == null || v == null) return null;
        OsmLink link = linkBetween(u, v);
        if (link == null) return null;
        p = rc.createPath(p, link, null, true);
        if (p.cost < 0) return null;
      }
      List<OsmPathElement> chain = walkChain(p); // [last(start) .. first(start)] with geometry
      Collections.reverse(chain);                // ordered start .. start
      OsmTrack track = new OsmTrack();
      OsmPathElement origin = null;
      for (OsmPathElement src : chain) {
        origin = copyElement(src, origin);
        track.nodes.add(origin);
      }
      track.cost = p.cost;
      track.buildMap();
      return track;
    } finally {
      rc.inverseDirection = savedInverse;
    }
  }

  // Find a link u->v (by target node id). u is made non-hollow first.
  private OsmLink linkBetween(OsmNode u, OsmNode v) {
    if (!nodesCache.obtainNonHollowNode(u)) return null;
    long vid = v.getIdFromPos();
    for (OsmLink link = u.firstlink; link != null; link = link.getNext(u)) {
      OsmNode t = link.getTarget(u);
      if (t != null && t.getIdFromPos() == vid) {
        return link;
      }
    }
    return null;
  }

  // Fallback track: straight junction-to-junction polyline over the (spur-trimmed) element list.
  private OsmTrack buildCoarse(List<OsmPathElement> all) {
    OsmTrack track = new OsmTrack();
    OsmPathElement origin = null;
    for (OsmPathElement pe : all) {
      origin = copyElement(pe, origin);
      track.nodes.add(origin);
    }
    track.buildMap();
    return track;
  }

  // Remove immediate out-and-back spurs (elements A, X, A) from an ordered element chain, where
  // the excursion tip X sits between two occurrences of the same node A. Removing X and the second
  // A leaves A adjacent to what followed — still graph-adjacent, since A was adjacent to both. Runs
  // to a fixed point so nested spurs peel away, but never collapses below a minimal loop.
  private List<OsmPathElement> trimSpurs(List<OsmPathElement> chain) {
    boolean changed = true;
    while (changed && chain.size() > 4) {
      changed = false;
      for (int i = 1; i + 1 < chain.size(); i++) {
        if (chain.get(i - 1).getIdFromPos() == chain.get(i + 1).getIdFromPos()) {
          chain.remove(i + 1); // the return-to-A
          chain.remove(i);     // the excursion tip X
          changed = true;
          break;
        }
      }
    }
    return chain;
  }

  private OsmPathElement copyElement(OsmPathElement src, OsmPathElement origin) {
    OsmPathElement e = OsmPathElement.create(src.getILon(), src.getILat(), src.getSElev(), origin);
    if (src.message != null) {
      e.message = src.message.copy();
    }
    return e;
  }
}
