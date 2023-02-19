---
parent: Features
---

# Remarks on Voice Hints

BRouter calculates voice hints but they are not present in all export formats. And within formats,
how they are presented will vary.

There are gpx formats for
* OsmAnd
* Locus
* Comment-style
* Gpsies
* Orux

The calculation starts with angles and comparing with the 'bad ways' (ways that are not
used on this junction). So e.g. an almost 90 degree hint can become "sharp right turn" if another
way is at 110 degrees.

And there are other rules
* show 'continue' only if the way crosses a higher priority way
* roundabouts have an exit marker
* u-turn between -179 and +179 degree
* merge two hints when near to each other - e.g. left, left to u-turn left
* marker when highway exit and continue nearly same direction
* beeline goes direct from via to via point

There are some variables in the profiles that affect on the voice hint generation:
* considerTurnRestrictions -
* turnInstructionCatchingRange - check distance to merge voice hints
* turnInstructionRoundabouts - use voice hints on roundabouts

Voice hint variables

| short    | description |
| :-----     | :----- |
| C        | continue (go straight) |
| TL       | turn left |
| TSLL     | turn slightly left |
| TSHL     | turn sharply left |
| TR       | turn right |
| TSLR     | turn slightly right |
| TSHR     | turn sharply right |
| KL       | keep left |
| KR       | keep right |
| TLU      | u-turn left |
| TU       | 180 degree u-turn |
| TRU      | u-turn right |
| OFFR     | off route |
| RNDB     | roundabout |
| RNLB     | roundabout left |
| BL       | beeline routing |

