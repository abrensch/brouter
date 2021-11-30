---
parent: Features
---

## Ofline routing on Android phones

BRouter is first and foremost an offline tool. It runs on any Android phone. The
online version offered here is just for a trial and for convenience. While many
smartphone routing software use online services to do the actual route
calculations, the advantages of the offline approach are evident:

- It is more reliable, data connection problems and server-side problems are no
  issue

- It works in foreign countries where data connections are either not available
  or very expensive

- It does not raise any data privacy issues

- You can use a cheap dedicated, second phone for navigation, without having to
  put your business smartphone on an untrusted bike mount and run it's battery
  low

The downside is that advanced route calculations are difficult to do on a
smartphone with limited computing and memory resources, which may lead
developers to implement simplifications into the routing algorithm that affect
the quality of the routing results. BRouter always does it's best on the
quality, but has a processing time that scales quadratic with distance, leading
to a limit at about 150km in air-distance, which is enough for a bikers daytrip.

### Installing the BETA Version of BRouter on an Android smartphone.

Before trying the Android app, you should have a look one the [online
version](/brouter-web) to see what it's doing.

What you should also do before trying the BRouter Android app is to install, an
get familiar with, one of the supported map-apps. This is either
[OsmAnd](http://www.osmand.net), which is a full offline navigation solution on
it's own, but especially it's a wonderful offline OSM map tool and is able to
give spoken directions to routes calculated either internally or externally.
Other options are [Locus](http://www.locusmap.eu/) or
[OruxMaps](http://www.oruxmaps.com/index_en.html).

The BRouter Android app assumes that at least one of OsmAnd, Locus or OruxMaps
is installed - it will not work otherwise.

If you are o.k. with all that, you can install the BRouter Android app from the
[brouter_1_6_1.zip](../brouter_bin/brouter_1_6_1.zip) installation ZIP-file
including the APK and read the [readme.txt](readme.txt) ( **READ ME !** ) for
details on how to add routing data and navigation profiles to your installation
and how the interfacing between BRouter and the supported map-tools works.

Navigation profiles and the lookup-data are [here](profiles2) Routing data files
per 5*5-degree square are [here](/brouter/segments4)

(The Map-Snapshot date is about 2 days before the timestamp of the routing data
files)
