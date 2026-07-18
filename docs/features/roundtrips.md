---
parent: Features
---

# Round-trip and loop routing

Most route planning answers the question *how do I get from A to B?*. Cyclists
and hikers, however, often have a different one: *I have an afternoon and want a
nice loop of about 40km that brings me back to where I started.* There is no
destination, only a starting point and a rough idea of how far you want to go.
BRouter can plan such round-trips.

Given a single start point and a desired length, BRouter places a ring of
intermediate waypoints around the start and routes a closed loop through them,
following the same [configurable cost function](costfunctions.md) as any other
route. The loop therefore respects your personal preferences on surface, hills
and road type just like a normal A-to-B route does.

You control the loop with a few request parameters:

| parameter | meaning |
| :----- | :----- |
| `roundTripLength` | desired total loop length in meters (takes precedence over `roundTripDistance`) |
| `roundTripDistance` | search radius in meters; the loop length is roughly `2π × radius` |
| `roundTripPoints` | waypoint count for the geometric placements (`FAST`/`WAYPOINT`/`ISOCHRONE`, and the `BALANCED` fallback); accepted range 3–20 (out-of-range values fall back to 5). The greedy planners derive their own count and ignore it |
| `direction` / `heading` | compass bearing the loop heads out toward (`heading` additionally forces it for the opening leg); without it the start bearing is drawn randomly, so fix it whenever the loop should be reproducible. Same keys as upstream point-to-point routing |
| `alternativeidx` | deterministic loop variety seed (`0` = default loop, any value ≥ 0 gives a reproducible variant — see [note below](#loop-quality)) |
| `roundTripDirectionAdd` | angle offset added to an auto-detected start bearing |
| `roundTripAlgorithm` | the speed/quality ladder `FAST` (the default: one placement, one routing pass — the historic round-trip speed), `BALANCED` (~8 s per slice, worst case two slices — the recommended interactive/mobile quality tier), `AUTO` (effort resolved from request context), `QUALITY` (max effort — both planners always, wider search, doubled budget); the internal engine names `WAYPOINT`, `GREEDY`, `ISO_GREEDY`, `ISOCHRONE` are also accepted for forced selection — see below. Deployments change the default with the system property `roundtrip.default.algorithm`; the request parameter always wins |
| `roundTripStrictQuality` | `1` hard-rejects loops that fail the quality checks; default `0` is lenient — a failing loop is still returned, tagged with a `Warning:` advisory (see [Loop quality](#loop-quality)) |
| `allowSamewayback` | `1` lets the return leg reuse ways from the outward leg; default `0` keeps the way out and the way back distinct |

If you instead supply more than one waypoint, BRouter treats those as explicit
[via-points](vianogo.md) the loop must pass through in order, and the generated
ring is not used. The same length settings then act only as guidance.

## Planning strategies

Generating a good loop is harder than routing between two fixed points: the
waypoints are not given, so the planner has to *invent* a set of intermediate
targets and then check whether the resulting route is actually a pleasant,
closed loop. Four modes are recommended, forming a speed/quality ladder:

- **FAST** — places the waypoints geometrically and scores them by
  straight-line distance only, with no routed-leg evaluation. Roughly 10× faster
  (sub-second) at noticeably lower quality — useful as a quick preview on limited
  mobile hardware. The loop is placed as a **directional lobe** heading out
  toward the resolved bearing (`direction`/`heading`, or the automatic
  draw) — the same arc geometry as the pre-1.7.9 round-trip; it never
  encircles the start as the primary shape. When the lobe cannot be placed or
  routed (sparse terrain), it is automatically retried as an encircling ring.
- **BALANCED** — one graph-aware planning run under a ~8 s wall-clock slice
  with a reduced per-step search width, no retry ladders beyond the budget,
  and a geometric fallback under a **fresh ~8 s slice** when no acceptable
  loop closes (planner produced nothing, or its best effort fails the quality
  gate outright) — so the worst case is two slices, ~16 s, and the common case
  one. Returns the best loop it found inside the budget — with a `Warning:`
  advisory when quality is degraded. This is the recommended default for interactive use on phones:
  predictable latency, visibly better loops than FAST. (Measured on the test
  machine: ~40% below AUTO's time on a 180 km request at nearly identical
  length adherence; phones should expect the budget to be the limit instead.)
- **AUTO** — runs the planner competition and keeps the best loop,
  with its effort resolved from the request context (see below). This is what
  you want when calculation time is not a major concern.
- **QUALITY** — the full competition at maximum effort: both planners always
  run, a wider per-step search (top-K 4/6 instead of 3/5) and a doubled
  planning budget. "Best loop, take your time." Deliberately *not* a forced
  single-planner mode: across the test matrix the radial planner still wins
  about a quarter of the cells, so forcing `ISO_GREEDY` would ship worse loops
  than the competition at the same cost.

Client guidance: `FAST` for a live preview while the user drags sliders,
`BALANCED` for the normal calculate-a-loop action on a phone, `AUTO` as the
general default, `QUALITY` when the user explicitly asks for the best possible
loop and accepts the wait.

**Context-aware AUTO.** AUTO resolves its effort from the request context and
logs the decision (`round trip effort: …`):

- **Resources** — a request budget too short to fund the full competition
  (≤ 10 s `maxRunningTime`) or a memory-constrained device
  (`memoryclass` ≤ 48) resolves to BALANCED-grade bounded effort instead of
  the competition. Unconstrained requests keep the standard competition.
- **Profile class** — read from the profile's own `validForFoot` /
  `validForBikes` / `validForCars` globals (name-independent). Motorized
  profiles route but their loop quality is unvalidated — the log carries a
  provisional-quality advisory.
- **Length class** — small / standard / long / XL, recorded in the effort log.
  Length-specific tuning lands as evidence accumulates; the >200 km opt-in
  gate (raise the timeout explicitly) is unchanged.

Under the hood the competition has two iterative strategies — `GREEDY` (radial
candidate placement) and `ISO_GREEDY` (candidates drawn from a bounded isochrone
expansion). AUTO runs `ISO_GREEDY` first and adds a plain-`GREEDY` comparison
run only when the ISO result does not clearly win (an ISO_GREEDY run that
already fell back to graph-native candidates internally has used the same
source truth, so a second run would be duplicate work); under `QUALITY` both
always run. The legacy `WAYPOINT`/probe generator enters only as a separately
scored fallback when greedy produces no accepted route. Measured across the
test matrix the two planners cost the same (median ~3 s) and score almost
identically, so they are *not* exposed as separate speed/quality tiers.
You can still force a specific planner by name for testing or
comparison: the parser accepts `WAYPOINT` (= `FAST`), `GREEDY`, `ISO_GREEDY`, and
`ISOCHRONE` (direct isochrone-frontier placement). Matching is case-insensitive;
any unrecognised value falls back to `AUTO`.

## Migrating from the old round-trip

The old round-trip routine (a fixed ring of points, one routing pass) lives on
as the `FAST` tier — and `FAST` is the default, so existing callers keep the
speed they know without changing anything. The quality tiers are an opt-in:
per request with `roundTripAlgorithm` (`AUTO`, `BALANCED`, `QUALITY`), or per
deployment with the system property `roundtrip.default.algorithm`.

What stays the same with `FAST`:

- **Direction and shape.** The generated points fan out toward the requested
  bearing (`direction`), the same way the old routine placed them. The loop
  heads out in that direction and comes back — it is a lobe pointing the way
  you asked, not a circle around the start point.
- **Speed.** One placement, one routing pass. Same speed class as before.

What is different:

- **Loops are shorter for the same `roundTripDistance`.** The old routine
  reached the full `2π × radius` length partly by force-routing through
  unreachable points — the same behaviour that caused stacked waypoints and
  failing loops near rivers and islands. `FAST` drops such points instead, so
  its loops come out shorter in constrained terrain (roughly 75% of the old
  length in our A/B measurements). If you need a loop of a certain length,
  ask for it with `roundTripLength` — or use `AUTO`, which corrects the
  distance and is the reason it takes longer.
- **The result is checked.** Every loop passes the quality gate. A loop with
  problems is still returned, but tagged with a `Warning:` message.

## Loop quality

A round-trip should look like a loop, not like an out-and-back with a detour.
BRouter applies several quality checks while planning:

- **Closure** — the route must return close to the start; grossly open routes
  are rejected.
- **No retracing** — a loop that travels back along roads it already used is
  penalised, so the way out and the way back differ.
- **Clean shape** — self-intersections in the developing loop are penalised to
  favour simple, non-tangled geometry.
- **Real loops only** — a valid loop encloses some area, so it needs the start
  plus at least two intermediate waypoints.

By default the distance, shape, surface and retrace checks are **advisory**: a
loop that fails them is still returned, tagged with a `Warning:` message that
describes the flaw, so you can decide whether to ride it. Set
`roundTripStrictQuality=1` to make those failures **hard rejections** instead, so
the request returns no route rather than a flawed loop.

These checks make round-trip planning reliable enough to use without manually
tweaking the result, while still leaving the actual road choices entirely to
your routing profile.

## Calculation budget

Round-trip generation is bounded by a wall-clock budget. The server's
`maxRunningTime` system property (default 60 s) is the **operator ceiling**; a
request may ask for a different budget via the `timeout` URL parameter
(seconds), clamped to that ceiling — a client can lower it, or raise it up to
the ceiling, but never beyond (a longer budget is a DoS lever, so the server
cap always wins).

The per-plan budget scales with the requested loop length: the standard
40–100 km class keeps the calibrated 30 s, scaling linearly to 2× at 200 km.
**Loops above 200 km must opt in** by supplying a `timeout` of at least 120 s
(and an operator ceiling that permits it); otherwise the request is rejected
with a message pointing at the `timeout` / `maxRunningTime` knob rather than
shipping a guaranteed-degraded loop.

The budget is enforced with **minimum-slice floors**: a nearly-spent request
still funds exactly one bounded attempt (a ~3 s planner rung, a ~5 s AUTO
child) instead of a guaranteed instant timeout, so the effective ceiling can
overshoot by that floor — a deliberate, bounded overrun, not an unbounded one.

Because a plan that exhausts its budget returns its best gate-graded loop
(possibly a disclosed distance-miss) rather than an error, a client that gets a
degraded result can **resend the same request with a larger `timeout`** to buy a
deeper search. Each plan exit records a `budget: used …ms of …ms, headroom …ms`
diagnostic so operators can see how often the budget actually binds.

### Behaviour under load

Routing is CPU-bound, and the wall-clock budget only translates into useful
search if the request actually gets a core. `AUTO` runs its candidates
**sequentially** (ISO_GREEDY first, plain GREEDY only when still needed — see
above), so one request occupies one core. Terminating a request (server
pre-emption, client cancel) cascades to its child engines, so a cancelled
round trip frees its core within ~one search step.

The server's overall concurrency is governed by the `maxthreads` launch
argument; for a hard "an admitted request gets a core for its whole timeout"
guarantee, keep `maxthreads` at or below the core count (the server's default
admission policy favours new-request latency and will pre-empt the oldest
in-flight request when the pool is full).

## Design notes

A couple of decisions are worth recording for anyone tuning the planner:

- **`alternativeidx` is a loop *variety seed*, not an enumerated alternative.**
  In point-to-point routing `alternativeidx` (0–3) enumerates successive
  alternatives by penalising the previous route. Round-trip mode reuses the same
  parameter with seed semantics instead: any integer ≥ 0 deterministically selects
  one loop variant, the values carry no quality ordering, and seed 0 (or absent)
  is bit-identical to the unperturbed baseline. Enumeration semantics were rejected
  because they cost one full round-trip plan per index — round trips already run a
  wall-clock budget, so `idx=3` could quadruple the work, whereas a seed is a single
  generation pass. The seed never influences the start-direction draw; variety comes
  from seeded score jitter (greedy family) and bounded geometry knobs
  (WAYPOINT/ISOCHRONE), so reproducibility needs both `direction` and the seed.

- **`ISO_GREEDY` monitors its own candidate pool and falls back internally.**
  The isochrone-fed planner scores the trustworthiness of its start-centered
  candidate pool while the loop is being built (distinct sectors, angular span,
  contour spread, return-oracle coverage, and in-plan evidence such as
  graph-native candidates repeatedly winning the routed comparison). A degraded
  pool loses its prior-based scoring advantages and cedes routed slots to fresh
  per-step graph-native candidates; an unhealthy pool switches the remaining
  steps to graph-native candidates entirely — the same local truth plain
  `GREEDY` uses. A *re-expansion* refresh was deliberately not implemented: the
  pool's staleness is positional (the loop has moved away from the start), so
  re-running the same start-centered expansion would rebuild the same pool,
  while the per-step graph-native expansion already re-samples the loop's
  current lobe fresh on every step. Each accepted leg records a
  `leg N source: …` diagnostic (source, quota injection, oracle vs EMA return
  estimate, heuristic-vs-routed rank, pool health) so the long-term goal —
  retiring `AUTO`'s separate plain-`GREEDY` comparison run once attribution
  shows it no longer wins — can be decided from measurements rather than
  guesswork ([issue #26](https://github.com/jonnybbb/brouter/issues/26)).

- **The near-revisit (teardrop) detectors have no sub-600 m floor.** The
  teardrop/near-revisit detectors share a 600 m minimum-arc floor
  (`NEAR_REVISIT_MIN_ARC_M`). Lowering it was measured and rejected: the
  [100, 300) m band is universal road-network micro-geometry (junction loops,
  hairpins, dual-carriageway turns) present in nearly every loop — penalising it
  would degrade selection corpus-wide — while the [300, 600) m band is essentially
  empty, so there is no defect population below 600 m to find. The 600 m floor sits
  on the signal-to-noise cliff. Small severe detours are instead *repaired* by
  `removeMicroDetours` (arcs ≤ 1500 m, 50 m proximity, ratio > 3), not penalised.
  Revisit only with labelled sub-600 m positives in hand, and prefer a non-geometric
  signal (e.g. graph avoidability) over lowering the floor.
