---
title: "Routing algorithm"
parent: Developers
---

# Routing algorithm: 2-pass routing with adaptive cost-cutoff

There's not so much to say about the routing algorithm, because the basic ideas
like [Dijkstra's algorithm](http://en.wikipedia.org/wiki/Dijkstra%27s_algorithm)
or the [A-Star algorithm](http://en.wikipedia.org/wiki/A*_search_algorithm) are
well documented.

Since Dijkstra uses only the path-cost-function `g(x)`, while A-Star add's the
remaining air-distance to the destination as a *future-cost-function* `h(x)`,
you can consider both algorithms to be equivalent if you introduce a *heuristic
coefficient* `c`:

```
cost-function = g(x) + c*h(x)
```

It is known that you get a correct result if `c` is in the range 0..1, and if
`g(x)` and `h(x)` satisfy some conditions.

For `c>1` you do not get a correct result. However, if c is larger than the
average ratio of the path cost-function g(x) and the air-distance, you get a
quick heuristic search which is heading towards the destination with a
processing time that scales linear with distance, not quadratic as for a correct
(`c<1`) search.

BRouter uses a 2-pass algorithm as a mixed approach with e.g. `c=1.5` for the
first pass and `c=0` for the second pass. The trick here is that the second pass
can use a cost-cutoff from the maximum-cost-estimate that the first pass
delivers to limit the search area, because any path with a remaining
air-distance larger than the difference of the current cost and the maximum cost
estimate can be dropped. And this cost-cutoff is adaptive: if during the second
pass a match is found with the first pass result, the maximum cost estimate is
lowered on-the-fly if this match gives a combined path with a lower cost.

For recalculations, where you still know the result from the last calculation
for the same destination, the first pass can be skipped, by looking for a match
with the last calculations result. You can expect to find such a match and thus
a maximum cost estimate soon enough, so you get an effective limit on the search
area. If a recalculation does not finish within a given timeout, it's usually
o.k. to publish a merged track from the best match between the new and the old
calculation, because the far-end of a route usually does not change by an
immediate recalculation in case you get off the track.

The reason that `c=0` (=Dijkstra) is used in the second pass and not `c=1`
(=A-Star) is simply that for `c=0` the open-set is smaller, because many paths
run into the cutoff at an early time and do not have to be managed in the
open-set anymore. And because the size of the open-set has an impact on
performance and memory consumption, c=0 is choosen for the second pass. The
open-set is what's displayed in the graphical app-animation of the brouter-app.

However, you can change the coefficients of both passes in the routing-profile
via the variables `pass1coefficient` and `pass2coefficient`, as you can see in
the car-test profile. A negative coefficient disables a pass, so you can e.g.
force BRouter to use plain A-Star with:

```
assign pass1coefficient=1
assign pass2coefficient=-1
```

or do some super-fast dirty trick with *non-optimal* results (there are routers
out there doing that!!):

```
assign pass1coefficient=2
assign pass2coefficient=-1
```

Some more words on the conditions that the path-cost-funtion g(x) has to
fullfill. Mathematically it reads that you need *non-negative edge costs*, but
the meaning is that at the time you reach a node you must be sure that no other
path reaching this node at a later time can lead to a better result over all.

If you have *turn-costs* in your cost function, this is obviously not the case,
because the overall result depends and the angle at which the next edge is
leaving this node.... However, there's a straight forward solution for that
problem by redefining edges and nodes: in BRouter, *nodes* in the Dijkstra-sense
are not OSM-Nodes, but the links between them, and the edges in the Dijkstra
sense are the transitions between the links at the OSM-Nodes. With that
redefinition, *turn-cost* and also *initial-costs* become valid terms in the
path-cost-function.

However, there's still a problem with the elevation term in the cost-function,
because this includes a low-pass-filter applied on the SRTM-altitudes that
internally is implemented as an *elevation-hysteresis-buffer* that is managed
parallel to the path's cost. So the path's cost is no longer the *true cost* of
a path, because the hysteresis-buffer contains potential costs that maybe
realized later, or maybe not.

Strictly speaking, neither Dijkstra nor A-Star can handle that. And in BRouter,
there's no real solution. There's a mechanism to delay the node-decision one
step further and so to reduce the probablity of glitches from that dirtyness,
but mainly the solution is *don't care*.
