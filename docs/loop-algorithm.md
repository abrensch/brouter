---
layout: default
title: Loop generation algorithm
---

# Loop Generation Algorithm (in depth)

This document explains how BRouter's loop-route feature works internally: how candidate loops are
discovered, scored, and turned into finished tracks. It covers only the **new** loop code —
`RoutingEngine.doLoop()`, `LoopGenerator`, the loop fields on `RoutingContext`, the loop params in
`RoutingParamCollector`, and `FormatJson`'s multi-loop output. It assumes familiarity with the base
BRouter routing engine (the weighted graph, `createPath`, profiles/`expctxWay`, nogo penalties);
those are used as black boxes here.

For the user-facing HTTP surface see [loop-api.md](loop-api.md).

---

## 1. Goal and where it plugs in

**Input:** one start point and a target distance `D` (meters). **Output:** up to `loopMaxResults`
ranked routes that start and end at the same node and whose total length is within `±loopTolerance`
of `D`, following good roads/paths per the active profile.

Loop mode is engine mode `BROUTER_ENGINEMODE_LOOP = 5`. `RoutingEngine.doRun` dispatches it to
`doLoop()`, which:

1. Reads `targetDistance` = `loopDistance` (falls back to `roundTripDistance*2`, else `5000`).
2. Sets `useDynamicDistance = true` and a default `waypointCatchingRange` of 250 m.
3. **Snaps the start** to the street network with the same `matchWaypointsToNodes` the normal
   router uses (honouring catching range / dynamic range; throws the same "not mapped" error if the
   point is off-network). It then anchors the loop at whichever end of the matched way segment
   (`node1`/`node2`) is closer to the projected `crosspoint`, so start = end sits on the nearest
   street node.
4. Loads the graph around the start (`resetCache(true)`, `cleanupMode = 0`) and hands off to
   `new LoopGenerator(...).generate()`.
5. For each returned loop: `recalcTrack` (elevation/time/distance), assigns a name/message, writes
   per-alternative files when an `outfileBase` is set, and sets `foundTrack` = best plus
   `foundTracks` = full ranked list. For GeoJSON it serializes the whole list as one
   `FeatureCollection`; otherwise the best loop.

Everything below happens inside `LoopGenerator`.

---

## 2. Why a bespoke search (design constraints)

The base engine can't answer "find a good loop of length D" directly:

- Its cost is a **weighted penalty**, not meters — `OsmPath.cost` "is a modified distance". Using
  cost to compare against `D` would make the tolerance band meaningless. So the loop search must
  track **real length in meters** separately from cost.
- Its graph is a **lazily-loaded "hollow node" graph** keyed by a packed lon/lat id
  (`OsmNode.getIdFromPos()` = `ilon<<32 | ilat`), not a dense integer-indexed array. So the
  per-node "arrays" the algorithm needs are `HashMap<Long, …>` keyed by that id.

The generator reuses the engine's cost model (`rc.createPath`, `expctxWay`) and nogo mechanism
verbatim, but drives its own Dijkstra so it can track meters, prune by geography, and collect
candidates.

### Core data structures

- `PathInfo { OsmPath path; int cost; int len; }` — the best path reaching a node: its cost (search
  cost, see §4), its real length in meters, and the `OsmPath` (whose `originElement` chain is the
  implicit predecessor array).
- `QEntry { OsmPath path; int cost; int len; int prio; }` — an open-set entry, ordered by `prio`
  (= cost, plus an optional A\* heuristic).
- `Candidate` — a discovered loop: forward + return paths, their costs/lengths, and the metrics
  filled in during scoring.
- `backInfo : Map<Long, PathInfo>` — phase-1 output: the return-leg **cost and length** for every
  node (the `OsmPath` is dropped to save memory; return paths are rebuilt on demand — see §8/§16).

---

## 3. Four-phase pipeline

`generate()` runs:

```
backInfo  = reverseDijkstra()          // Phase 1: return-leg field
candidates = forwardSearch()           // Phase 2: discover loops
finalists = selectFinalists(candidates)// cheap pre-rank → small pool
for c in finalists: resolveOverlap(c)  // Phase 3: de-overlap the return leg
return rank(finalists)                 // Phase 4: score, diversify, build tracks
```

The split between "collect many candidates cheaply" (phases 1–2) and "do expensive work on a few
finalists" (phases 3–4) is what keeps it fast in dense cities (see §11).

---

## 4. The bounded Dijkstra core (`dijkstra` + `expand`)

Both exploratory searches and the return re-search share one routine. It's a textbook Dijkstra over
the hollow-node graph, with several loop-specific behaviors.

**Node identity & relaxation.** Nodes are keyed by `getIdFromPos()`. `best` holds the current best
`PathInfo` per node; `settled` marks finalized nodes. The `PriorityQueue<QEntry>` is ordered by
`prio`. Because Java's `PriorityQueue` has no decrease-key, relaxations push a new `QEntry` and stale
ones are skipped on pop via `settled.contains(uid)` and an identity check `bi.path != e.path`.

**Expansion (`expand`).** For the popped node `u` it ensures `u` is non-hollow
(`obtainNonHollowNode`) and iterates `u.firstlink`. For each neighbour `v` it skips border nodes,
dead ends, the node we came from, and — crucially — **any node outside the reachable disk**
(`v.calcDistance(start) > airBound`, see §7). It then builds the child path and relaxes:

```
OsmPath np = rc.createPath(bi.path, link, null, false);   // detailMode = false
if (np.cost < 0) continue;                                // forbidden edge
int searchCost = np.cost + surfacePenalty(...);           // §8
int nlen = bi.len + np.linkDist;                          // real meters, §5
if (best[v] == null || searchCost < best[v].cost) relax(v, np, searchCost, nlen);
```

**Termination / pruning.** On pop: if a `targetId` was given and reached, stop (single-pair mode);
otherwise, if `bi.len > lenBound` the node is not expanded (it can't be part of a valid loop). A
hard `MAX_POPS` cap (4,000,000) and a cooperative `engine.isTerminated()` watchdog check bound
runaway searches.

### 5. Real length without extra work (`linkDist`)

`OsmPath` never stored travelled meters, and the loop search runs `createPath` with
`detailMode = false` for speed (no `MessageData`, no transfer-node element chain, no kinematics —
and, as a bonus, turn restrictions *are* evaluated, since the core skips them only in detailMode).

The base cost pass already computes each link's true metric length internally (`linkdisttotal`). We
expose it as a new field, `OsmPath.linkDist`, set at the end of `addAddionalPenalty`. The search
reads `np.linkDist` directly — exact, geometry-aware length with **no second geometry decode**. This
is the only change to the core cost model, and it is inert for normal routing (the field is written
but never read by the base engine).

### 6. A\* option (`prio` / `searchGoal`)

When `searchGoal` is non-null the open-set key becomes `cost + searchGoal.calcDistance(node)` — a
straight-line-distance heuristic. It's admissible for typical profiles (cost ≈ meters × costfactor,
costfactor ≳ 1), so it preserves optimality while focusing the search into a corridor. Only the
**return re-search** (§9, candidate → start) uses it; the two disk sweeps set `searchGoal = null`.
Searches never nest, so a single field is safe.

---

## 7. The reachable-disk bound (`reachFactor` / `airBound`)

The single biggest scaling win. For any node on a closed loop of length ≈ `D`, both its outbound and
return legs are at least its straight-line distance from the start, and they sum to ≈ `D`. Hence

```
airDist(node, start) ≤ D·(1+tol) / 2
```

So the entire search can be confined to a disk of that radius. Because area grows with the square of
the radius, this is a large, correct pruning of both Dijkstra phases.

`0.5·D` is the rigorous worst case (a degenerate out-and-back). Real, desirable loops are roundish —
the farthest point of a circular loop of perimeter `D` is only ≈ `0.32·D` away — so `reachFactor()`
adaptively **tightens** the radius for large loops, where dense-city searches are expensive:

| `D` | reach factor |
|---|---|
| ≤ 5 km | 0.50 (full, safe) |
| 5–20 km | linear 0.50 → 0.38 |
| ≥ 20 km | 0.38 |

`airBound = reachFactor · (1+tol) · D + 100 m`, overridable via the `loopReachFactor` param. Tighter
= faster but may miss very elongated loops.

---

## 8. Phase 1 — reverse precompute (`reverseDijkstra`)

A single Dijkstra from the start with `rc.inverseDirection = true`, which makes the cost model
evaluate every edge in its **reverse** orientation. The result at each node `v` is therefore the
optimal cost and real length of the **return** leg `v → start`. Only the best cost+length per node
is retained (the `OsmPath` objects are dropped once the sweep finishes — see §16 for why; the actual
return path is rebuilt for finalists in §11). It's bounded by `airBound` (§7) and by
`lenBound = D·(1+tol)` (a return longer than the whole band can never be part of a valid loop).
Output: `backInfo`.

## 9. Phase 2 — forward search & candidate discovery (`forwardSearch`)

A Dijkstra from the start in the normal direction, tracking `len` (meters) and `cost` separately. On
each pop of node `u` with forward length `fLen`:

- **Candidate test.** If `backInfo[u]` exists, compute `total = fLen + backInfo[u].len`. If
  `total ∈ [D·(1−tol), D·(1+tol)]`, record a `Candidate` (forward path/cost/len from this search,
  return path/cost/len from `backInfo`, plus a cheap air-bearing sector for later diversity).
- **Free closing bound.** Skip expanding `u` when `fLen > D·(1+tol)` or
  `fLen + backInfo[u].len > D·(1+tol)` — such paths can never close within the band. This is the
  `f(node) = |D − (fLen + lenBack)|` idea from the spec, applied as a prune.

**Adaptive early-stop.** Short loops finish the whole (small) disk. Only once a search becomes
genuinely expensive — more than `SOFT_POP_BUDGET` (150,000) node expansions — do we stop early, and
only after collecting a sufficient, directionally-diverse pool:
`candidates ≥ max(loopMaxResults·8, 64)` spanning ≥ 6 compass sectors (or a large absolute pool).
This bounds dense-city cost while never truncating cheap searches. Hard caps `MAX_CANDIDATES` (4000)
and `MAX_POPS` also apply.

## 10. Finalist pre-ranking (`selectFinalists`)

Phase 3 (path reconstruction) and the overlap re-search are expensive, so we don't run them on every
candidate. `selectFinalists` scores all candidates on **cheap, already-computed** metrics only —
closeness to `D` and average quality (cost per meter) from the pre-overlap fwd+back numbers — sorts
by that, then greedily takes a small pool (`max(loopMaxResults·3, 8)`) preferring new air-bearing
sectors first, then filling. This turns phase 3 from O(candidates) into O(finalists), which is the
dominant cost saving for large loops.

## 11. Phase 3 — overlap detection & de-overlapping (`resolveOverlap`)

A naïve loop can run out and back on the same road. For each finalist:

0. **Rebuild the return leg.** Since `backInfo` kept only cost+length, the optimal return path is
   re-created here with `returnLeg` (a goal-directed A\* from the candidate to the start). This runs
   once per finalist, not per candidate.
1. **Reconstruct edge sets.** `walkChain` follows the `OsmPath.originElement` chain to list the
   forward and return elements; `edgeSet` turns each into a set of undirected edge keys
   (`min(id)_max(id)`).
2. **Overlap fraction** = Jaccard overlap `|shared| / |union|`.
3. **Re-search if overlapping.** If any edge is shared, `penalizedReturn` re-routes the return leg
   *around* the outbound one, reusing the base **nogo** mechanism: it drops soft nogo circles
   (radius 15 m, weight `loopOverlapPenalty`) along a bounded sample (≤ `MAX_OVERLAP_NOGOS` = 150) of
   the outbound path, temporarily adds them to `rc.nogopoints`, and runs a **goal-directed A\***
   Dijkstra (§6) from the candidate to the start. The new return leg is accepted only if it actually
   reduces overlap *and* keeps the loop total within the band.

**Orientation matters.** The precompute's return path walks candidate→start, but the re-search
(source = candidate) walks start→candidate. Track assembly (§13) therefore orients each leg
explicitly by endpoint id; getting this wrong once produced a "straight line from mid-route back to
the start" artifact.

Finally `resolveOverlap` stores `totalLen`, `costPerMeter`, `overlapFraction`, and the precise
initial-outbound-bearing `sector` for this finalist.

> Standard Dijkstra keeps one best path per node, so if two forward paths reach the same candidate,
> the lower-cost one wins. That's an accepted approximation: a path that heavily overlaps its own
> return leg is a poor loop anyway, and the re-search repairs the cases that matter.

**Out-and-back filter.** After overlap resolution, finalists whose leg overlap still exceeds
`MAX_OVERLAP_FRACTION` (0.6) are dropped before ranking — these are out-and-backs, not loops (e.g. a
candidate whose far point sits on a long dead-end edge, reachable and returnable only by retracing
it). They're kept only if nothing cleaner remains, so a loop is always returned when one exists.

## 12. Phase 4 — scoring & ranking (`rank`)

First every finalist's **detailed track** is built (§13) so hill and surface factors read from the
real routed geometry/tags.

**Band re-validation.** Candidate selection (§9) tested the band on the `fwd+back` length, but that
double-counts any edge the two legs share, and spur trimming (§13) removes retracing. So the band is
re-checked here on the **actual built length**, and finalists outside `[D·(1−tol), D·(1+tol)]` are
dropped. This is what prevents a much-too-short result: a surface-biased near-out-and-back can have
an in-band `fwd+back` length yet collapse well below the band once its retrace is trimmed. If no
finalist is in band, the generator returns nothing and `doLoop` reports "no loop found" — better
than returning a route far off the requested distance. (`selectFinalists` keeps extra headroom —
`max(loopMaxResults·4, 12)` — precisely because some finalists are dropped here.)

Then a composite score is computed over the surviving in-band loops, with min–max normalization
across that pool:

```
score = wCloseness·closeness      // 1 − |D − total| / (tol·D), clamped
      + wQuality  ·quality         // 1 − normalized(cost per meter)      (lower cost = better)
      + wOverlap  ·(1 − overlap)
      + wHills    ·hillTerm        // normalized ascent/km; inverted unless loopHillPreference=prefer
      + wSurface  ·surfaceFraction // share of length whose tags match loopSurface (§14)
      + wDiversity·diversity       // added greedily below
```

Weights are the `loopWeight*` params (defaults 0.4 / 0.3 / 0.2 / 0.1, hills & surface 0 = off); they
need not sum to 1 since ranking is relative. **Directional diversity** is applied greedily: after
sorting by the base score, the first (best) candidate seen in each precise initial-bearing sector
gets a `+wDiversity` bonus, so distinct directions are rewarded over near-duplicates.

**Randomness (`jitter`).** So repeated calls with identical settings return *different* good loops,
a non-negative random perturbation in `[0, loopRandomness)` (default 0.2) is added to each
candidate's score. It is applied in **two** places: in `selectFinalists` (so the finalist *pool*
varies across calls, not just the final order) and again in `rank`. Because scores live in roughly
`[0, 1]`, jitter only reshuffles candidates whose scores are within `loopRandomness` of each other —
i.e. it varies among the high-quality options and never promotes clearly-worse loops; every returned
loop still respects the distance band and all weighted factors. The `Random` is seeded from
`loopSeed` when provided (reproducible) and freshly seeded otherwise (varies each call);
`loopRandomness=0` restores deterministic best-first selection.

**Selection** then takes the top `loopMaxResults`: a first pass takes the best loop per sector; a
second pass fills any remaining slots from the leftovers, best-first.

## 13. Turning a candidate into a finished track (`buildTrack`)

Exploration ran detailMode-free, so the stored paths are junction-to-junction only (no intra-link
curve geometry). To produce a track that matches the roads exactly, the winner is **replayed once**
through the normal detailMode path builder:

1. **Orient & concatenate** the forward `[start..candidate]` and return `[candidate..start]` element
   chains into one ordered element list.
2. **Recover real node references.** We can't look resolved nodes up in `nodesMap` by coordinate —
   once loaded, a node's segment data is consumed (`getAndClear`), so the map no longer returns it.
   Instead `linkTargetAt` walks the **live link graph** from the start node, matching each successive
   element's coordinates to a link target. Link objects still reference the real node instances, so
   this reliably rebuilds the node sequence.
3. **Replay in detailMode** (`replayDetailed`): route start → … → start one link at a time with
   `createPath(..., true)`, producing an `OsmPath` whose chain carries full geometry, elevation, and
   way tags. The track is materialized from copied elements (fresh objects, to avoid aliasing shared
   chains) with the true profile cost.
4. **Fallback** (`buildCoarse`): if any link can't be resolved, fall back to the straight
   junction-to-junction polyline so a loop is still returned.

Before step 2, **spur trimming** (`trimSpurs`) removes immediate out-and-backs from the element
list: wherever the sequence reads `A, X, A` (an excursion to `X` and straight back to `A`) the tip
`X` and the returning `A` are dropped, leaving `A` adjacent to what followed (still graph-adjacent,
since `A` bordered both). It runs to a fixed point so nested spurs peel away, guarded so it never
collapses below a minimal loop. This is what removes the common turnaround artifact: near the far
point the shortest forward and return paths coincide, so the reverse-precompute's return leg often
begins by retracing the last forward edge, producing an `A → X → A` spike at the turnaround (§11's
node-penalty re-search is too weak to reliably prevent it). Trimming shortens the loop slightly, so
`buildTrack` recomputes the true length from the trimmed geometry.

The reported `cost` comes from the detailMode replay (the true profile cost of the actual track);
the surface search bias from §8 never leaks into it.

## 14. Hills & surface factors

These bias **which loop wins** and, for surface, also **which roads the search prefers**:

- **`ascentPerKm(track)`** sums positive elevation deltas (`selev` is in ¼-meter units) over the
  finished track and divides by km. `loopHillPreference=avoid` ranks flatter loops higher;
  `prefer` ranks hillier ones higher (the only way to *seek* elevation, since the cost model can
  only penalize it).
- **`surfaceFraction(track, keywords)`** is the share of the track's length whose way-tag string
  contains any target keyword — the ranking term.
- **`expandSurface(spec)`** turns the comma-separated `loopSurface` into lower-case tag substrings.
  Named classes map to specific OSM tags so `sidewalk` (`footway=sidewalk`), `footway`
  (`highway=footway`), `has_sidewalk` (`sidewalk=both/left/right/yes`), `trail`
  (`highway=path/track/bridleway`, no footway) and `path` (broad, incl. footway) are all distinct;
  anything else is a raw substring.
- **Pathfinding bias (`surfaceMatches`, in `expand`).** When `loopSurface` + `loopWeightSurface` are
  set, every edge whose decoded tags don't match gets an extra search cost of
  `linkDist · loopWeightSurface`, steering the search onto matching ways. This reads each edge's tag
  string (`expctxWay.getKeyValueDescription`) — the one per-edge string build the fast path avoids —
  so it's strictly opt-in. The penalty rides on the *search* cost only; the true profile cost is
  preserved for the reported track.

## 15. Output (`FormatJson`)

`FormatJson.format(List<OsmTrack>)` emits one GeoJSON `FeatureCollection` with a `LineString`
`Feature` per loop, ordered best-first with a `rank` property. Each feature also carries diagnostic
**tag-breakdown** properties (`appendTagMix`): `way-types` (by `highway=*`), `way-detail` (by
`footway=*`), and `surfaces` (by `surface=*`), each as percent-of-length of the top values — so a
caller can see how their area is tagged and verify targeting. gpx/kml return the single best loop.

---

## 16. Complexity & performance

Let `V` be the number of graph nodes inside the reachable disk (§7).

- **Phase 1 + 2:** two Dijkstra sweeps, `O(V log V)` each, each node expanded with a light
  detailMode-free `createPath`. The disk bound makes `V` scale with `(reachFactor·D)²` rather than
  the whole map.
- **Phase 3:** overlap check + at most one goal-directed A\* re-search per **finalist**
  (`O(loopMaxResults)` of them, not per candidate), each A\* exploring a narrow corridor.
- **Phase 4/track build:** one detailMode replay per finalist.

**Memory.** The dominant heap cost is the per-node state of the two disk sweeps (`best`/`settled`
maps plus one `OsmPath` per node). Two measures keep it bounded:

- The reverse precompute returns **only cost+length per node** (`backInfo`), not the `OsmPath`
  objects, so the reverse sweep's paths are freed before the forward sweep runs — otherwise two
  full-disk sets of paths coexist. Return legs are rebuilt on demand for the few finalists
  (`returnLeg`, a goal-directed A\*), which is cheap.
- A **node budget** scaled to the JVM heap caps how many nodes one sweep may settle
  (`maxSettledNodes`). A large loop in a very dense area that would exceed it aborts with a clean
  error instead of `OutOfMemoryError`; `doLoop` also catches OOM as a backstop, frees references,
  and reports the error, so the server never crashes. More heap (`-Xmx`) or a smaller
  `loopReachFactor` raises the ceiling.

The design evolved through several optimizations, each targeting the then-dominant cost:

1. Air-distance disk bound + adaptive `reachFactor` (shrinks both sweeps quadratically).
2. Work-gated adaptive early-stop (full search when cheap, cut off when expensive).
3. Overlap resolution restricted to finalists + goal-directed A\* return (was O(candidates) full
   Dijkstras).
4. DetailMode-free exploration with `linkDist` (removes per-edge allocation on the hot path);
   detailMode used only to replay the ~`loopMaxResults` winners.

Net effect: fast at all distances, including large loops in dense cities.

---

## 17. Approximations & limitations

- **One best path per node** (standard Dijkstra) — see the note in §11.
- **Return-leg symmetry via `inverseDirection`** gives exact directional costs for the return leg;
  the re-search corrects the overlap cases.
- **Surface matching is substring-based** against the profile's decoded tag string, so it only sees
  tags present in `lookups.dat`. Named classes are best-effort expansions; raw `key=value` gives
  exact control. The tag-breakdown output helps pick the right target.
- **The profile still governs what's routable.** Surface bias nudges within what the profile allows;
  it can't route onto ways the profile forbids (e.g. footways under a bike profile). Choose a profile
  suited to the target.
- **Non-deterministic by default.** With `loopRandomness > 0` (the default) and no `loopSeed`,
  repeated calls return different loops. Set `loopRandomness=0` or a fixed `loopSeed` for
  reproducible output.
- **CH not used.** The reverse precompute runs on the raw graph. Plugging the `len_back`/`cost_back`
  lookups into BRouter's contraction-hierarchy infrastructure is a possible future optimization; it
  is intentionally not attempted here.

---

## 18. Where the code lives

| Concern | Location |
|---|---|
| Engine-mode dispatch, start snapping, output | `RoutingEngine.doLoop()` / `formatTrack()` |
| The algorithm (all phases) | `LoopGenerator` |
| Real per-link length | `OsmPath.linkDist` (set in `addAddionalPenalty`) |
| Loop params & scoring weights | `RoutingContext` (`loop*` fields) |
| HTTP/CLI param parsing | `RoutingParamCollector` (`loop*`, `hills`) |
| Multi-loop GeoJSON + tag breakdown | `FormatJson.format(List)`, `appendTagMix` |
| Server wiring | `RouteServer` (LOOP output branch) |

See [loop-api.md](loop-api.md) for the parameter reference and examples.
