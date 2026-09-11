---
parent: Users
title: Mountain hiking and via ferratas
---

# Mountain hiking and via ferratas

The `hiking-mountain.brf` profile has a `via_ferrata_scale_limit` parameter.
It limits the difficulty of **recognisable via ferrata sections** independently
of the existing SAC hiking preferences. Via ferratas are excluded by default;
allowing a grade is an explicit opt-in, not a recommendation to attempt it.

## Selecting a limit

Change the existing assignment in the profile, or set the corresponding profile
parameter in a client that exposes BRouter profile parameters:

```text
assign via_ferrata_scale_limit -1
```

`-1` excludes all recognisable ferratas, including grade 0. `0` permits only
OSM grade 0 (very easy). Values `1` through `6` allow OSM grades up to that
number. For example, use `2` for approximately A/B and `4` for approximately
A through D. These are **OSM/Huesler grades**, not a precise conversion of
regional letter scales. See the [OSM tag documentation](https://wiki.openstreetmap.org/wiki/Key:via_ferrata_scale).

An allowed ferrata is eligible for routing; the parameter does not force the
route to include one. Other existing costs and access checks still apply.
This change does not alter the profile's existing SAC penalties or direction
handling, and it does not add arbitrary profile-parameter controls to OsmAnd.

## Tag handling

A way is recognised as a ferrata if it has `highway=via_ferrata` **or any
non-empty `via_ferrata_scale` value**. The second condition also covers ferratas
mapped as `highway=path`, `footway`, or another highway type.

The lookup preserves grades `0` through `6`, including each `-` and `+` suffix.
For whole-grade limits, a minus grade is conservatively treated as its whole
grade, while a plus grade requires a higher limit. For example, limit `2`
permits `2-` and `2`, but excludes `2+` and `3-`. Internally plus grades are
represented by a half step; this is an ordering convention, not a measurement
of difficulty. `6+` is excluded by all whole-grade limits offered by the selector.

A recognised ferrata with a missing or unrecognised grade is excluded even at
limit `6`. Nonstandard values such as `B/C`, `2;3`, `2.5`, or `7` are not guessed
or silently downgraded. A normal path with no ferrata tag retains its existing
routing behaviour.

The limit is a hard exclusion (`costfactor=100000`), not merely a preference.
The exclusion also applies to `uphillcostfactor` and `downhillcostfactor`, and
cannot be bypassed by `shortest_way`, elevation settings, or route preferences.

## Data requirements and rollout

Both the profile and `lookups.dat` must be updated. Routing data must then be
**regenerated from OSM with the extended lookup**, and clients must download
these new `.rd5` files. Updating only the profile or the lookup cannot restore
a tag that was discarded while producing older routing data.

This lookup change appends `via_ferrata_scale` at the end of the way context
and increases lookup version 11's minor version from 2 to 3. No existing tag or
value indices are reordered. See [lookup-table evolution](../developers/profile_developers_guide.md#lookup-table-evolution-and-the-the-major-and-minor-versions).

An older `.rd5` remains structurally readable, but it is **not sufficient for
reliable grade filtering**. With such data, `highway=via_ferrata` is ungraded
and therefore excluded. A ferrata originally mapped as `highway=path` with only
`via_ferrata_scale` identifying it may appear to be an ordinary path because
the grade tag was not encoded. Do not use old tiles to assess this limit.

Uploading the profile to a public BRouter-Web instance does not update that
server's lookup or routing data. Its operator must deploy the extended lookup
and rebuild the data first. Local/Android installations have the same data
requirement.

The filter uses way tags. It does not propagate a grade present only on a route
relation, infer missing grades, verify mapping accuracy, or check current
conditions, closures, equipment requirements, or suitability for an individual.
Check the route against current local information and the ferrata's topo.
