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
| `roundTripPoints` | target number of intermediate waypoints (3–20, default 5) |
| `startDirection` / `heading` | compass bearing to bias the direction the loop heads out |
| `roundTripDirectionAdd` | angle offset added to an auto-detected start bearing |
| `roundTripAlgorithm` | `AUTO` (default — best loop) or `FAST` (quick preview); the internal engine names `WAYPOINT`, `GREEDY`, `ISO_GREEDY`, `ISOCHRONE` are also accepted for forced selection — see below |
| `roundTripStrictQuality` | `1` hard-rejects loops that fail the quality checks; default `0` is lenient — a failing loop is still returned, tagged with a `Warning:` advisory (see [Loop quality](#loop-quality)) |
| `allowSamewayback` | `1` lets the return leg reuse ways from the outward leg; default `0` keeps the way out and the way back distinct |

If you instead supply more than one waypoint, BRouter treats those as explicit
[via-points](vianogo.md) the loop must pass through in order, and the generated
ring is not used. The same length settings then act only as guidance. On
non-paved profiles, `roundTripDensify=1` opts into bulging the arcs between your
via-points outward so the loop better honours the requested length.

## Planning strategies

Generating a good loop is harder than routing between two fixed points: the
waypoints are not given, so the planner has to *invent* a set of intermediate
targets and then check whether the resulting route is actually a pleasant,
closed loop. Two modes are recommended:

- **AUTO** (default) — runs the iterative planners and keeps the best loop. This
  is what you want almost always.
- **FAST** — places the ring of waypoints geometrically and scores them by
  straight-line distance only, with no routed-leg evaluation. Roughly 10× faster
  (sub-second) at noticeably lower quality — useful as a quick preview on limited
  mobile hardware.

Under the hood AUTO competes two iterative strategies — `GREEDY` (radial
candidate placement) and `ISO_GREEDY` (candidates drawn from a bounded isochrone
expansion) — and adopts whichever scores better. Measured across the test matrix
they cost the same (median ~3 s) and score almost identically, so they are *not*
exposed as separate speed/quality tiers; AUTO just picks the better one per
request. You can still force a specific planner by name for testing or
comparison: the parser accepts `WAYPOINT` (= `FAST`), `GREEDY`, `ISO_GREEDY`, and
`ISOCHRONE` (direct isochrone-frontier placement, also selectable with
`roundTripIsochrone=1`). Matching is case-insensitive; any unrecognised value —
including the former `BALANCED`/`QUALITY` names — falls back to `AUTO`.

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

## Experimental parameters

A few opt-in flags expose work-in-progress planning experiments. They all
default to off, are honoured only by the iterative `GREEDY` / `ISO_GREEDY`
planners, and may change or disappear without notice — they are **not** part of
the stable round-trip interface:

| parameter | meaning |
| :----- | :----- |
| `roundTripSteerVias` | keep generated waypoints out of dense town/city cores (costs one extra isochrone pass) |
| `roundTripDesirability` | bias waypoint placement toward profile-preferred terrain via a desirability heatmap ([issue #15](https://github.com/abrensch/brouter/issues/15)) |
| `roundTripCapsule` | urban "capsule" loop-planning prototype |

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
  (WAYPOINT/ISOCHRONE), so reproducibility needs both `startDirection` and the seed.

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
