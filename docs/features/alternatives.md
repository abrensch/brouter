---
parent: Features
---

# Alternative route calculations

Sometimes the routing result is not what you want, and you are looking for some
other way, following the same routing preferences, but not following the way of
the first choice routing result.

Maybe you don't like the first choice, or you are planning a roundtrip and don't
want to go back the same way. For this purpose, BRouter can calculate
alternatives.

This feature is well known from handheld-navigation systems, but not found in
online services. This is because these online services need to use
pre-calculations for fast processing and cannot handle individual routing
criteria. BRouter does not do any precalculations for specific routing profiles,
so it can do whatever you ask for.

When using a second choice route, you may want to recalculate the route using
[via or nogo points](vianogo.md) in order to define the overall routing
according to your preferences but have a route that is first choice except for
these explicit constraints.
