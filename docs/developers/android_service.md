---
parent: Developers
---

# Android Service

BRouter exposes an [Android
Service](https://developer.android.com/guide/components/services) which can be
used by other applications to calculate routes. See `IBRouterService.aidl` for
the interface definition.


## Some words on the input rules (app and server)

We have some parts of input:

### lonlats

The lonlats parameter is a list of positions where the routing should go along. It is recommended to use this instead of the two parameter lons and lats.

When there are more than two points the 'via' points may be off the perfect route - in lower zoom level it is not always clear if a point meets the best way.

The profile parameter 'correctMisplacedViaPoints' tries to avoid this situation.

On the other hand, it would be fatal if this point is not reached when you want to go there.
There are to choices to manage that:
- add a poi to the 'pois' list
- name the point in lonlats list

Another feature of BRouter is routing via beelines.
Define a straight starting point in the 'lonlats' list with a 'd' (direct). The second point needs no declaration.

This contradicts the naming rules in 'lonlats'. If the point is to be given a name, the router parameter 'straight' can be used instead and filled with the index of the point.

'nogos', 'polylines' and 'polygons' are also lists of positions.
Please note: when they have a parameter 'weight' the result is not an absolute nogo it is weighted to the other ways.

### routing parameter

This parameters are needed to tell BRouter what to do.

### profile parameter

Profile parameters affect the result of a profile.
For the app it is a list of params concatenated by '&'. E.g. extraParams=avoidferry=1&avoidsteps=0
The server calls profile params by a prefix 'profile:'. E.g. ...&profile:avoidferry=1&profile:avoidsteps=0


## other routing engine modes in app

### get elevation

"engineMode=2" allows a client to only request an elevation for a point. This can be restricted with "waypointCatchingRange".
