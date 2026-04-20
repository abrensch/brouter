---
parent: Developers
title: Pseudo Nodes
---

# 1 - The use cases considered (corresponding to the pictures above):

Case 1: “unsecured crossing”
A biker is riding on a highway such as track, path, residential… and have to cross a primary or secondary highway with (very) high car-traffic and WITHOUT any securing equipment (such as traffic-signal, zebra...).
The risk and wait time of course depends on the traffic on the Primary / secondary.
An option to avoid (whenever possible) such nodes should exist!

Case 2: “unsecured left-turn”
A biker is riding on a highway type primary or secondary and intend to turn left into a lowprio highway (track, cycleway, residential…):
WITHOUT any securing equipment (such as traffic-signal, zebra...) this turn is at risk for bikers.
An option to avoid (whenever possible) such turns should exist!

The challenge:
Of course, all junctions are known in OSM, a “node” is mandatory at each intersection.
But nodes without traffic-signal or zebra have generally not tag!
And without any tag, no information at all is available for routing, the Brouter is not able to consider and penalise such nodes.

Remark: on the contrary, when tags exist on a node, a check is possible in the node context:
Examples: barrier=block|bollard…, railway=crossing….
By crossing=traffic_signals, a check is also possible but ONLY when the traffic_signal is exactly mapped at the intersection... most of the times, traffic_signals are some meters away from the intersection, and not always direct on the route.


# 2 - Approach with a new Pseudo-tag:

2 years ago we introduced s.c. “pseudo-tags” to be able to consider noise, river, forest, town and traffic during the route calculation.
(using a Postgis database, “classes” are precalculated for each OSM segment. The OSM map is then enriched with these “new tags” and the resulting map is used during route calculation in Brouter)
With a similar processing we can now add the new pseudo-tag “estimated_crossing_class” to the nodes that are not consider “secured”.

For that we consider first the junctions (=nodes) where an highway of type residential, cycleway, track, path,etc… (=lowprio highways) is crossing a primary or secondary highway (=highprio highways).
Remark: Currently “tertiary” are also in the “lowprio” group, but (due to possible unexpected detours in the resulting routing) we do not recommend to penalise such crossing.

The junctions that are secured by traffic-signal, zebra, roundabout ... get the pseudo-tag “estimated_crossing_class=0”.
The remaining junctions  get the pseudo-tag “estimated_crossing_class” with a value between 1 (low risk) to 6 (high risk).

Class calculation:
As explained above, traffic_signals are generally not mapped exactly on the intersection:
A real challenge here is to estimate whether a traffic_signal secures a node or not:
Used parameter for this estimation:

- the distance to the node
- the type of signal (button_operated!)
- the position of the signal (away from the intersection, but on the lowprio highway)
- bicycle=use_sidepath (on lowprio)
- bicycle=designated (in old versions only)

By “island” or “railway_crossing” or near a roundabout the nodes are remaining, but as the risk is lower, the calculated risk is lowered.

3 parameters of the crossed primary/secondary are used to calculate the class:

- The traffic (as used to calculate the  pseudo-tag “estimated_traffic_class”)
- The maxspeed
- The number of lanes

The class mainly depends on the estimated_taffic on the primary/secondary. Maxspeed and lanes number have relative impacts.

A first version of the pseudo-tag is available and can be visualized using this link:
https://brouter.de/brouter-web/PseudoTags.html
(enter a town name + select “crossing” in the class_type, and submit)


# 3 - How to use the new pseudo-tag for routing / prerequisites

Some new code for the profiles.

```
--- global context ---

assign   consider_crossing     =  true        # %consider_crossing% | set to true to add penalty by unsecured junctions | boolean

--- way context ---

assign coming_from_lowprio switch highway=track|cycleway|footway|path|residential|service|unclassified true false

--- node context ---
assign crossing_penalty
  switch consider_crossing
  switch way:coming_from_lowprio
   switch estimated_crossing_class=   0
      switch estimated_crossing_class=1  111
      switch estimated_crossing_class=2  211
      switch estimated_crossing_class=3  311
      switch estimated_crossing_class=4  511
      switch estimated_crossing_class=5  711
      switch estimated_crossing_class=6  911
0 0 0

assign initialcost
       add crossing_penalty
...
```

# 4 - left-turns

The “left-turn” cost is added to the turn costs when the biker is on the primary/secondary and turns left into an highway with lowpriority classifier.

The turn-left costs are defined with new /special “global” parameters

The code for the profiles:

```
--- global context ---

assign crossing_Prio_H = 22  # min priorityclassifier to consider the HW as "Highprio" HW
assign crossing_Prio_L = 21  # max priorityclassifier to consider the HW as "Lowprio" HW

# now define the turn costs depending on the estimated_crossing_class of the junction
# on "right-hand traffic", when turning left from Highprio to Lowprio
# Note: on "left-hand traffic" replace cost_ToLeft_from_H_classiN with cost_ToRight_from_H_classN

assign cost_ToLeft_from_H_class1 82
assign cost_ToLeft_from_H_class2 112
assign cost_ToLeft_from_H_class3 212
assign cost_ToLeft_from_H_class4 312
assign cost_ToLeft_from_H_class5 512
assign cost_ToLeft_from_H_class6 712
```
