---
parent: Features
---

# Freely configurable routing profile

A major reason for the limited usefulness of most bike routing software is that
routing preferences are a personal issue. Not even will MTB and racing cyclist
never agree on common routing preferences, also the same people will have
different preferences for day and night, dry and wet weather, etc. The only
solution for that problem is the use of a freely configurable cost function that
can easily be adapted to personal needs. This is far more flexible compared to a
set of static functions with a fixed set of configuration variables.

To make that point somewhat clearer: there are other routers that are highly
configurable, look at [routino](http://www.routino.org/uk/router.html) for an
example. However, these are one-dimensional parameter sets that do not allow to
handle arbitrary correlation. BRouter's configuration is different. It is more a
scripting language that allows you to program the cost function instead of just
configuring it. E.g. a non-graded track is generally not a good track. But if it
has a surface=asphalt tag, it probably is. On the other hand, a grade5-track
with surface=asphalt is primarily a grade5-track and you should not ignore that.
Such logic is not implementable in one-dimensional parameter sets.

See some [sample profiles](https://brouter.de/brouter/profiles2/) provided for the online router.

See the trekking-profile [`trekking.brf`](https://brouter.de/brouter/profiles2/trekking.brf) as the
reference-profile with some explanations on the meaning of this script.

See the [Profile Developers Guide](../developers/profile_developers_guide.md)
for a technical reference.
