---
layout: default
title: Loop routes (HTTP API)
---

# BRouter Loop-Route HTTP API

Generate ranked circular routes ("loops") that start and end at the same point and are
approximately a target distance long. Loops are produced by engine mode **5**
(`BROUTER_ENGINEMODE_LOOP`) on the standard `/brouter` endpoint.

> For how the generator works internally (the search, scoring, and track construction), see the
> [Loop generation algorithm](loop-algorithm.md) deep-dive.

## Endpoint

```
GET http://<host>:17777/brouter?lonlats=<lon>,<lat>&profile=<name>&engineMode=5&loopDistance=<meters>&format=geojson
```

- Requires `lonlats` (a **single** `lon,lat` start point; extra points are ignored) and `profile`.
- With `format=geojson` (or `json`) the response is **one GeoJSON `FeatureCollection` containing
  all ranked loops** (one `LineString` Feature each, `rank` property, `0` = best).
- With `format=gpx` or `kml` the response is the **single best** loop.

Example — a 5 km trekking loop, all candidates:

```
http://localhost:17777/brouter?lonlats=8.7209,50.0025&profile=trekking&engineMode=5&loopDistance=5000&format=geojson
```

## How hills & surface are handled

There are two independent layers, mirroring how normal navigation works:

1. **Which roads a loop may use** is decided by the **profile and its parameters** — exactly the
   same cost model BRouter uses for point-to-point routing. Loop generation runs through the same
   engine, so any profile parameter affects it identically. Set these with `profile:<name>=<value>`
   (or the `hills` convenience param below).
2. **Which of the found loops win** is decided by a composite **ranking score**. On top of the
   built-in factors (closeness to the target distance, average quality, self-overlap, directional
   diversity) you can add **hill** and **surface** preferences that re-rank the candidate loops.

For the strongest effect, combine both: use the profile layer to steer the pathfinding and the
ranking layer to pick the best matching loop.

> **Profile parameter values are numeric.** Booleans are `1`/`0` (e.g.
> `profile:consider_elevation=1`), **not** `true`/`false`.

## Core parameters

| Param | Default | Meaning |
|---|---|---|
| `lonlats` | — (required) | Start/finish point `lon,lat` |
| `profile` | — (required) | Routing profile; pick one suited to the surface you want (e.g. `trekking`, `hiking-mountain`, `gravel`) |
| `engineMode` | `0` | Must be `5` for loop mode |
| `loopDistance` | `5000` | Target loop length **D**, meters |
| `loopTolerance` | `0.10` | Acceptance band around D, as a fraction (`0.10` = ±10%) |
| `loopMaxResults` | `2` | How many ranked loops to return |
| `format` | `gpx` | `geojson`/`json` → all loops; `gpx`/`kml` → best loop |

## Hills

### Pathfinding (affects the actual route)

| Param | Meaning |
|---|---|
| `hills=avoid` | Penalize climbs and descents (sets `consider_elevation=1`, `uphillcost=downhillcost=60`) |
| `hills=ignore` | Turn elevation cost off (`consider_elevation=0`) — shortest/flattest-cost wins, terrain ignored |
| `profile:consider_elevation=1` | Enable elevation cost (profile-specific) |
| `profile:uphillcost=<n>` / `profile:downhillcost=<n>` | Fine-grained climb/descent penalties |

`hills` only affects profiles that expose those parameters (trekking, hiking, gravel, …).

### Ranking (picks the best of the found loops)

| Param | Default | Meaning |
|---|---|---|
| `loopWeightHills` | `0` | Weight of the hill factor in the score (0 = off) |
| `loopHillPreference` | `avoid` | `avoid` ranks flatter loops higher; `prefer` ranks hillier loops higher |

`loopHillPreference=prefer` is the way to **seek** elevation: the routing cost model can only
*penalize* hills, so "target hills" is expressed as a ranking preference over loops (measured as
ascent per km on the finished route).

## Surface & way type

Surface/way-type targeting is driven by `loopSurface` + `loopWeightSurface`, which steer **both**
layers (pathfinding and ranking) at once — so you get matching loops without needing to know a
profile's parameter names. The **profile** still sets hard constraints (what's even routable) and
its own base costs, so pick a profile that can use your target (e.g. a foot/hiking profile like
`hiking-mountain` for trails), and, where supported, combine with `profile:` params
(e.g. `profile:avoid_unsafe=1` on `trekking` to avoid main roads).

| Param | Default | Meaning |
|---|---|---|
| `loopSurface` | — | Comma-separated target surfaces / way types (see table below) |
| `loopWeightSurface` | `0` | Strength (0 = off). Used as **both** the ranking weight **and** the per-meter cost added to non-matching edges during the search |

- **Pathfinding bias:** while searching, every edge whose tags don't match `loopSurface` gets an
  extra cost of `loopWeightSurface` per meter, nudging the loop onto matching ways. Higher values
  steer harder (e.g. `0.4` is a gentle preference, `1.5`–`2` strongly favors matching ways). This
  is applied on top of — and does not replace — the profile's own costs and access rules.
- **Ranking bias:** among the loops found, each is also scored by the fraction of its length that
  matches `loopSurface`.

`loopSurface` accepts named classes or raw tag substrings:

| Value | Matches | Use for |
|---|---|---|
| `paved` | `surface=asphalt/paved/concrete/paving_stones` | Hard-surfaced ways |
| `unpaved` | `surface=gravel/fine_gravel/compacted/ground/dirt` | Loose/natural surfaces |
| `path` | `highway=path/footway/track/bridleway` | Any walking way (broad; **includes** footways/sidewalks) |
| `trail` | `highway=path/track/bridleway` | Off-road/natural trails **excluding** footways (so it won't collapse onto urban sidewalks) |
| `footway` | `highway=footway` | Any footway, including sidewalks mapped only as `highway=footway` |
| `sidewalk` | `footway=sidewalk` | A dedicated sidewalk **way** beside a road (`highway=footway` + `footway=sidewalk`) |
| `has_sidewalk` | `sidewalk=both/left/right/yes` | **Roads** that carry a sidewalk (sidewalk mapped as a road attribute) |
| `highway=footway`, `surface=asphalt`, … | any raw tag substring | Exact control for your data's tagging |

Multiple values combine (a segment counts if it matches **any**): `loopSurface=paved,path`.

### Sidewalks vs. trails — picking the right target

OSM maps footpaths in several ways, and "sidewalk" and "trail" are genuinely different tags:

- A **dedicated sidewalk way** beside a road is usually `highway=footway` **+** `footway=sidewalk`.
  Target it with `loopSurface=sidewalk`.
- Many sidewalks are mapped as plain `highway=footway` **without** the `footway=sidewalk` subtag.
  `sidewalk` won't match those — use `loopSurface=footway` instead.
- A **road that has a sidewalk** (no separate way, just `sidewalk=both` on the road) is
  `loopSurface=has_sidewalk`.
- A **nature trail** is typically `highway=path` (often `surface=ground/gravel`), sometimes
  `highway=track`/`bridleway`. Target it with `loopSurface=trail`, which deliberately excludes
  `highway=footway` so it doesn't route onto urban sidewalk footways.

If you're unsure how your area is tagged, look at the **tag-breakdown properties** in the GeoJSON
response (`way-types`, `way-detail`, `surfaces` — see below): they show the share of each loop's
length by tag value, so you can see whether your sidewalks appear as `highway=footway`,
`footway=sidewalk`, `sidewalk=both`, etc., and pick the matching target (or a raw tag substring).

> The **profile** must make your target routable. A bike profile (e.g. `trekking`) may forbid or
> heavily penalize `highway=footway`, so surface bias alone can't put you on sidewalks — use a
> foot-capable profile (e.g. `hiking-mountain`) when targeting footways/sidewalks.

## Variety across calls

By default, calling the API repeatedly for the **same start and settings returns different loops**
(when the area offers more than one good loop). A small random perturbation is applied to the
ranking of high-quality candidates, so you get variety without dropping to poor routes.

| Param | Default | Meaning |
|---|---|---|
| `loopRandomness` | `0.2` | Amount of ranking randomness. `0` = deterministic best-first (same result every call); higher = more variety (may include slightly lower-quality loops) |
| `loopSeed` | — | Fixed integer seed for reproducible randomness (same seed + settings ⇒ identical results). Omit for fresh randomness each call |

- For **stable/reproducible** output (e.g. tests, caching, comparisons): set `loopRandomness=0`, or
  pass a fixed `loopSeed`.
- For **more variety**: raise `loopRandomness` (e.g. `0.4`). Randomness only reshuffles among the
  candidates the search already found, so all returned loops still respect the distance band,
  quality, and any hill/surface targeting.

```
# reproducible
...&engineMode=5&loopDistance=8000&format=geojson&loopSeed=42
# extra variety
...&engineMode=5&loopDistance=8000&format=geojson&loopRandomness=0.4
```

## Ranking weights (advanced)

The composite score is a weighted sum; all weights are tunable and need not sum to 1
(ranking is relative). Defaults reproduce the original behavior.

| Param | Default | Factor |
|---|---|---|
| `loopWeightCloseness` | `0.4` | Closeness of total length to D |
| `loopWeightQuality` | `0.3` | Average routing quality (cost per meter) |
| `loopWeightOverlap` | `0.2` | `1 − overlap` between the outbound and return legs |
| `loopWeightDiversity` | `0.1` | Initial-bearing diversity (8 compass sectors) |
| `loopWeightHills` | `0` | Hill preference (see above) |
| `loopWeightSurface` | `0` | Surface match (see above) |

## Performance tuning (advanced)

| Param | Default | Meaning |
|---|---|---|
| `loopReachFactor` | `0` (auto) | Straight-line search radius as a fraction of D. Auto = `0.5` for short loops, tightening toward `0.38` for long ones. Lower = faster but excludes very elongated loops |
| `loopOverlapPenalty` | `4.0` | Penalty multiplier used when re-routing a return leg that overlaps the outbound leg |

> Enabling `loopWeightSurface` makes the search read each edge's tags, which is slower than the
> default fast path. It is still bounded by `loopReachFactor` and the adaptive early-stop, but
> expect large dense-city loops with surface targeting to take longer than without it.

### Memory / very large loops in dense areas

A large `loopDistance` in a very dense area (e.g. 15 km loops in a city) searches a wide disk and
can use a lot of memory. The generator scales an internal node budget to the JVM heap and aborts
cleanly if a search would exceed it, rather than crashing — you'll get HTTP 400
`loop search too large for available memory - reduce loopDistance, loosen constraints, or give the
server more heap (-Xmx)`. If you routinely request large loops, run the server with a bigger heap
(e.g. `java -Xmx2g -cp brouter.jar btools.server.RouteServer …`); a smaller `loopReachFactor` also
reduces memory. The server always stays up and keeps serving other requests.

## Worked examples (the user's scenarios)

Avoid hills and target hiking trails (use a hiking profile so trails are routable):

```
?lonlats=8.7209,50.0025&profile=hiking-mountain&engineMode=5&loopDistance=8000&format=geojson
 &hills=avoid&loopWeightHills=0.4&loopHillPreference=avoid
 &loopSurface=trail&loopWeightSurface=1.0
```

Target hills and prefer roads with sidewalks (sidewalk as a road attribute, so a road profile is fine):

```
?lonlats=8.7209,50.0025&profile=trekking&engineMode=5&loopDistance=8000&format=geojson
 &loopWeightHills=0.5&loopHillPreference=prefer
 &loopSurface=has_sidewalk&loopWeightSurface=0.8
```

Walk on dedicated sidewalk paths (needs a foot-capable profile so footways are routable):

```
?lonlats=8.7209,50.0025&profile=hiking-mountain&engineMode=5&loopDistance=5000&format=geojson
 &loopSurface=sidewalk&loopWeightSurface=1.5
```

Don't care about hills, just target paved walking paths:

```
?lonlats=8.7209,50.0025&profile=trekking&engineMode=5&loopDistance=6000&format=geojson
 &hills=ignore
 &loopSurface=paved,path&loopWeightSurface=1.0
```

## Response shape (`format=geojson`)

```json
{
  "type": "FeatureCollection",
  "features": [
    {
      "type": "Feature",
      "properties": {
        "name": "brouter_loop_trekking_0",
        "rank": 0,
        "track-length": "4980",
        "filtered ascend": "62",
        "plain-ascend": "0",
        "total-time": "3600",
        "total-energy": "0",
        "way-types": { "highway=footway": 46, "highway=residential": 30, "highway=path": 24 },
        "way-detail": { "footway=sidewalk": 41 },
        "surfaces": { "surface=asphalt": 70, "surface=paving_stones": 18 },
        "cost": "5310"
      },
      "geometry": { "type": "LineString", "coordinates": [[8.7209,50.0025,110.2], "..."] }
    }
  ]
}
```

`rank: 0` is the best loop; features are ordered best-first. Each `LineString` starts and ends at
the start point and follows the road geometry. Loops are cleaned of small "out-and-back" spurs
(a short detour down a side street and straight back), and pure out-and-back routes are excluded.
The returned `track-length` (measured after this cleanup) is always within `±loopTolerance` of the
target — a loop that trims below the band is dropped rather than returned.

The **tag-breakdown** properties are diagnostic and show the share of the loop's length carrying
each value of a tag key (top entries, integer percent):

- `way-types` — by `highway=*` (e.g. `footway`, `residential`, `path`).
- `way-detail` — by `footway=*` (e.g. `sidewalk`, `crossing`); empty if the ways carry no
  `footway` subtag.
- `surfaces` — by `surface=*` (e.g. `asphalt`, `gravel`).

Use these to see how your area is tagged and to verify targeting. In the example above the loop is
46% footway of which `footway=sidewalk` is 41% — i.e. real sidewalks — so `loopSurface=sidewalk`
is the right target here; if `way-detail` were empty you'd target `footway` instead.

## Errors

- No loop fits the band → HTTP 400 `no loop found for the given distance`. Returned loops are
  **guaranteed** to be within `±loopTolerance` of `loopDistance` (measured on the final geometry);
  if none can be found, the request fails rather than returning a much-too-short route. This can
  happen when constraints are tight — a strong `loopWeightSurface` in an area sparse in the target
  surface may leave no in-band loop. Widen `loopTolerance`, lower `loopWeightSurface`, change
  `loopDistance`, or pick a more suitable `profile`.
- A profile parameter given a non-numeric value (e.g. `profile:consider_elevation=true`) → HTTP 500
  `ParseException ...`. Use `1`/`0` for booleans.
- Search too large for the heap → HTTP 400 `loop search too large for available memory ...`. Reduce
  `loopDistance`, lower `loopReachFactor`, or start the server with more heap (`-Xmx`). The server
  recovers and keeps serving; it does not crash.
