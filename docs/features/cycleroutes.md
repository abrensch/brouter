---
parent: Features
---

# Following long distance cycle routes

The long distance cycle network (see
[www.opencyclemap.org](http://www.opencyclemap.org)) is the first thing to
consider when planning a cycle trip. BRouter can do that, and the `trekking`
profile makes use of it. It does that implicitly by just making them cheap in
the cost-function, so that the routing sometimes *snaps in* to long distance
cycle routes.

That's a good choice for long distance cycling, because these routes are a *safe
harbor* almost free of bad surprises. However, when really looking for the
*optimal* route between A and B to use it more than once (e.g. your daily
commute) you may want to ignore the long-distance network, to put more focus on
*hard-facts* like distance, hills and surface quality (use the
`trekking-ignore-cr` profile for that purpose).
