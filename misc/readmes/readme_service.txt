BRouter - Beta Version 0.9.5 - Using the service interface
==========================================================

Starting with version 0.9.3, BRouter contains a *NEW* service interface.

The HTTP-Interface introduced in 0.9.1-0.9.2 was dropped

The new service interface is an Android service that can be called
from other apps. See the file IBRouterService.aidl for the
interface definition.

Following map-tools implement this interface:

- OruxMaps starting with version 5.5.3
- Locus in the current version
- (for OsmAnd, pull-request is pending: https://github.com/osmandapp/Osmand/pull/537 )

The service interface defines a default timeout of 60 seconds
(if not modified by the caller), so you can only calculate
medium distance routes via the service interface. For long
distance calculations, you would run into the timeout, so
you have to use BRouter's classical operation mode at least
for an initial calculation. But read below on how timeout-free
recalculations work even for long-distance applications.

The configuration concept in the service interface
--------------------------------------------------

BRouter is fully configurable via the use of profile
definition files and a list of nogo-areas, while
the service interface is usually accessed by just
choosing one o the 6 "standard routing modes" made
of a combination of the car/bike/foot and the
shortest/fastest selection.

Therefore, you need a mapping between the standard routing mode
and BRouter's configuration. There is no default mapping
deployed with the BRouter distribution file, so you have
to configure this yourself. You can do that by starting
BRouter is the "normal" way and at the end of the routing
process, press the "Server Mode" button. Then you get
a list of standard routing mode with a preselection,
where you can choose for which of the 6 standard modes
you want to store the configuration just used.
(profile + nogo-list). Please note that the profile
is stored by reference (so modifications at the profile
file afterwards will have effect), while the nogo-areas
are stored by value (so modifying the nogo-waypoints
afterwards will have no effect)

Timeout-free recalculations
---------------------------

A new feature in zje service-interface of version 0.95 is that
you can  follow a long-distance route and have (nearly) correct
recalculations when getting of the track, without running
into the 60-seconds timeout.

For that, a valid route to the destination must be known
for the current routing-mode. This can be achieved either
by calculating the route via the brouter-app and storing
it by pressing the "server-mode" button when done.

Or it is done implicitly by the service-mode itself once
it was able to do the calcultion within the timeout -
in that case, subsequent recalcs for the same destination
also make use of the known valid track for faster
processing.

The destination must be identical in a digital sense
(+- 20 microdegrees), so make sure to use the same waypoint,
do not re-enter it as a map-location.