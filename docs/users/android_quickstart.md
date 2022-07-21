---
parent: Using BRouter
nav_order: 1
---

# Android quickstart guide

### Choosing and installing a map tool

BRouter just calculates tracks as GPX or GeoJSON files. It does not display maps
or give any navigation instuctions. Therefore you need a map-tool in order for
BRouter to be useful.

Several map tools support routing with BRouter:

- [_OsmAnd_](http://www.osmand.net): GPLv3, Free (limited features) and paid version available
- [_Locus Map_](http://www.locusmap.eu): Proprietary, Free (ads) and paid version available
- [_Oruxmaps_](http://www.oruxmaps.com): Proprietary, Free (previous version) and paid version available

Install any of those map tools using your favorite app store.

### Installing BRouter

You can install BRouter either from
[F-Droid](https://f-droid.org/packages/btools.routingapp), [Google Play
Store](https://play.google.com/store/apps/details?id=btools.routingapp) or from
the APK contained in the releases available in the [Revision
History](https://brouter.de/brouter/revisions.html).

### Choosing a base directory

When first starting BRouter (or after deleting/moving the base directory), it
asks for a directory and gives you proposals plus the option to enter any other
base directory.

Most phones have an internal and an external storage, where the external storage
is a SD card which can be removed from the device.

Navigation needs big data files that usually should go on an external storage
because it provides larger capacity. You should accept the external storage,
which is usually the one with the most space available.

Since Android 11 apps can only write to their app-specific storage so BRouter
can only use `<...>/Android/media/btools.routingapp/` as base directory. The
app-specific storage can be located on internal or external storage.


### Download routing segments

BRouter requires routing data which is independent of the displayed map of a map
tool. Routing segments (`rd5`) can be downloaded using the BRouter _Download
Manager_.

## Routing via _service interface_

BRouter is best used via it's _service interface_. It provides a service which
can be used by map tools without starting the BRouter app.

To use BRouter in your map tool you have to configure the map tool to use
BRouter as navigation service.

- [Instructions for LocusMap](https://docs.locusmap.eu/doku.php?id=manual:faq:how_to_navigate_offline)
- [Instructions for OsmAnd](osmand.md)
- [Instructions for Kurviger](https://docs.kurviger.de/start?id=app/offline_routing)
- [Orux Forum](https://oruxmaps.org/forum/)

  Note: OsmAnd only displays BRouter as navigation service if BRouter is
  installed. You have to install BRouter before configuring OsmAnd.

The _service interface_ allows specifing either a _routing-mode_ (used by OsmAnd
and OruxMaps) or a _routing-profile_ (used by LocusMap). When using a
_routing-mode_ BRouter selects the _routing-profile_ according to a mapping.

By default BRouter uses the following mapping:

| routing-mode   | routing-profile |
| -------------- | --------------- |
| motorcar_fast  | car-test        |
| motorcar_short | moped           |
| bicycle_fast   | fastbike        |
| bicycle_short  | trekking        |
| foot_fast      | shortest        |
| foot_short     | shortest        |

This mapping, however, can be changed any time by starting the BRouter and using
the _Server Mode_.

If your routing request fails due to timeout you can open BRouter and select
_Server Mode_ and recalculate the route using _\<repeat:...\>_. BRouter caches
the routing result and further requests using the _same_ destination using the
_service interface_ will be successful.
