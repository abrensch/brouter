# Testing the round-trip subsystem

Three tiers, ordered by feedback speed. Use the fastest tier that can catch
the class of change you are making; the full matrix stays the gate before a
push.

## Tier 0 — unit suite (~35 s)

```
./gradlew :brouter-core:test
```

~750 tests: planner/gate/scoring logic, seam contracts, and engine-backed
loops on the bundled Dreieich fixture (no downloads).

## Tier 1 — smoke gate (~minutes)

```
./gradlew :brouter-core:integrationSmoke
```

Every sentinel (undershoot contraction, weak-cell regressions, performance
budget, ref-track membership), the golden route signatures, and three
representative loop regions — Freiburg (all-profile gravel), Annecy
(alpine), Berlin (urban) — running the two GATED planners only (greedy,
iso_greedy). This trips on every historically-observed failure class at a
fraction of the full matrix cost. The AUTO competition and the report-only
variants are skipped.

## Tier 2 — full matrix (~25–40 min)

```
./gradlew :brouter-core:integrationTest
```

All 16 regions × profiles × radii × directions (~930 cells). Per cell the
SHIPPED route is gated: the AUTO competition runs lenient (production
default) and its result is held to the regional quality bands — except a
disclosed best-effort (a "Warning:" the production gate itself attached),
which is logged, not failed. Forced per-planner runs live in the smoke
tier (attribution) and in report mode. Required green before pushing to a
PR branch.

Options:

- `-Dloop.reportVariants=true` — the full five-variant shape: probe
  (WAYPOINT) and ISOCHRONE comparison variants (report-only, ungated) plus
  the forced greedy/iso_greedy planners (gated, full attribution).
- `-Dgolden.write=true` — recapture the golden signatures (only on
  known-good code).
- `-Dloop.forks=N` / `-Dloop.heap=Ng` — parallelism tuning.

## Segment tiles

Suites download missing `.rd5` tiles on demand and (by default,
`loop.segments.noupdate=true`) never refresh an existing one, so a run's
inputs cannot change under it. The golden-signature test additionally
provisions its boundary-neighbour tiles pinned, so signatures cannot differ
across machines. After deliberately refreshing tiles (upstream publishes
weekly), expect quality cells and goldens to shift: rerun the full matrix
and recapture goldens on a green state.
