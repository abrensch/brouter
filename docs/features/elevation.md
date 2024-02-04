---
parent: Features
---

# Elevation awareness

Elevation awareness is the most important issue in bike routing if not routing
in a flat country. But in most routing software, elevation is either not handled
at all or in a way that is not really useful.

Even worse, most tools do not even report on elevation in a useful manner. The
far-too-high *total climbs* usually reported do not only accumulate real, small
height-variations that are averaged-out by the bikers momentum - they accumulate
also noise and grid-interpolation errors from the elevation data source.

For most regions, BRouter uses elevation data from the [Shuttle Radar Topography
Mission (SRTM)](http://srtm.usgs.gov/), precisely the hole-filled Version 4.1
Data provided by [srtm.csi.cgiar.org](http://srtm.csi.cgiar.org/), which is the
data that is displayed e.g. in Google Earth and which is used in most
elevation-enabled routing tools. However, the routing algorithm is able to
extract the information on real ascends and descends and ignores the noise.

For latitudes above 60 degree in northern Europe, BRouter uses Lidar data, that
were [compiled and resampled by Sonny](https://sonny.4lima.de/)

On the reporting side, BRouter uses a similar concept to compute the *filtered
ascend*, which is the ascend without the noise and the small hills and which
turned out to be a valid concept to estimate the real elevation penalty.
