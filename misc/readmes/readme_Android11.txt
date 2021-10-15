BRouter - Version 1.6.2 - Setting up the Android App
====================================================

Choosing and Installing a Map-Tool
----------------------------------


BRouter just calculates tracks as GPX- or Geojson-output, it
does not display any map or give any navigation
instuctions. Therefore you need a map-tool in
order for BRouter to be useful.

Currently, BRouter cooperates with any map tool that can use the BRouter 
interface without file access. So you need to install some, and get familiar with,
at least one of them:

- "OsmAnd": See http://www.osmand.net Get It from Google-Play
   or get it as an APK from the release-build archive:
   http://download.osmand.net/releases/

- "Locus": See http://www.locusmap.eu There's a "Pro"
  Version which is ad-free and a free version with ads. 
  You can get it from Google-Play, but for the free-version
  there's also an APK-Download.

- "Oruxmaps": See http://www.oruxmaps.com Oruxmaps is
  Donation-Ware, which means it's free and you're supposed
  to donate to the project if you want to support it.


Installing the BRouter App
--------------------------

You can install the BRouter-App either from Google's Play Store
or directly from the APK-File contained within the "brouter-1.6.2.zip"
distribution zip-file.

Choosing a SD-Card Base Directory
---------------------------------

When first starting BRouter (or after deleting/moving
the brouter folder on the sd-card), it asks for a
sd-card base directory and gives you proposals plus
the option to enter any other base directory.

Most phones (namely those with Android 4.x) have 2 logical
"SD-Cards", where the first one is internal and not an actual
Card, and the second one is a an optional "external" micro-sd-card
that can be taken out of the device.

Navigation needs big data files that usually should go on an
external, big sd-card. You should accept the external card, which
is usually the one with the most space available.

Since Android 11 BRouter app uses only its local storage on 
.../Android/media/btools.routingapp/

That means it can't access the folders from other apps like OsmAnd, OruxMaps or Locus.


Completing your installation
----------------------------

After accepting a base-directory proposal, "BRouter" creates a subfolders
relative to this base directory, so you end up with e.g. the following structure:
(depending on base dir and your map-tool choice):

/mnt/sdcard/Android/media/btools.routingapp/brouter
/mnt/sdcard/Android/media/btools.routingapp/brouter/segments4  <- ** put routing data files (*.rd5) here **
/mnt/sdcard/Android/media/btools.routingapp/brouter/profiles2  <- lookup-table and routing profiles
/mnt/sdcard/Android/media/btools.routingapp/brouter/modes      <- routing-mode/profile mapping
/mnt/sdcard/Android/media/btools.routingapp/import             <- allow a small file exchange with other apps
/mnt/sdcard/Android/media/btools.routingapp/import/tracks      <- place the nogo* files here


The "profiles2" and the "modes" directory get some reasonable default-configuration
from the installation procedure, but the "segments4" directory is basically empty
(except for the storageconfig.txt file) so you have to get routing-datafiles in
order to complete your installation.

After accepting the base directory, the download manager starts automatically to
help you with this download. Or you can download
them manually from the following location:

  http://brouter.de/brouter/segments4

Routing data files are organised as 5*5 degree files,
with the Filename containing the south-west corner
of the square, which means:

- You want to route near West48/North37 -> get W50_N35.rd5
- You want to route near East7/North47 -> get E5_N45.rd5

From the above link you find routing data for all places in the world where OSM
data is available. The carsubset datafiles are needed only if you want to
calculate car-routes over long distances, otherwise you are fine with just the
normal (full) rd5's.

The minimum files BRouter needs to work are e.g.

/mnt/sdcard/Android/media/btools.routingapp/brouter/segments4/E5_N45.rd5
/mnt/sdcard/Android/media/btools.routingapp/brouter/profiles2/lookups.dat
/mnt/sdcard/Android/media/btools.routingapp/brouter/profiles2/trekking.brf

But of course you can put as many routing data files
and routing profiles as you like.

Since folders on other apps are not longer available you could use the 
import folder to place a favourites.gpx with waypoints or
in subfolder 'tracks' your nogo*.gpx files.


Routing via the service interface
=================================

BRouter is best used via it's "service interface". No need to start the BRouter-App
in order to do that, it's just a services that sits in the background and can be
called by the map-tools very much like on online routing service.

To do that, you have to choose BRouter as a navigation service in your map-tool.
This is supported by OsmAnd, Locus-Maps and OruxMaps (In OsmAnd starting with version 1.7,
you see BRouter as a navigation service if BRouter is installed. You do not see the
option if BRouter is not installed).

There's a mapping between the "routing-mode" asked for by the map-tool
(on out of 6: car/bike/foot * fast/slow) and BRouter's routing-profiles.
This mapping is stored in the file brouter/modes/serviceconfig.dat and is
pre-configured by the installation process to the following mapping:

  motorcar_fast  -> car-test
  motorcar_short -> moped
  bicycle_fast   -> fastbike
  bicycle_short  -> trekking
  foot_fast      -> shortest
  foot_short     -> shortest

This mapping, however, can be changed any time by starting the BRouter-APP and using
the "Server Mode" button (or by editing the serviceconfig.dat manually). You can also
change gthe profiles themselves or create new ones. Please refer to the
"profile_developers_guide.txt" (contained in the distribution-zip) if you plan to
adapt routing profiles to your preferences.

Note that if called via the service-interface, BRouter uses a timeout of 60 seconds,
which sets a limit on the distances you can calculate.


Calculate routes using the file interface
=========================================

The other option is using the BRouter-App to calculate a route. This is the prefered option
when calculating long-distance-routes that would not finish within the 60 seconds timout
if calculated via the service-interface.

To do this, start the BRouter-App, select two or more waypoints from the favorite waypoint-database
in your import folder and then start the route calculation. These waypoints are called "Favorites"
in OsmAnd, "POI"s in Locus or "Waypoints" in Oruxmaps and allow to store a location
on the map and give it a name. Export them from the app to the import folder.

No need anymore to create special "to", "from", "via1..via9" points, but they are still supported
and if a "from" and a "to" wayppoint is found in the database, you will not be prompted
to select waypoints from the database.

If a route is calculated, it is stored as "brouter0.gpx" in the BRouter import/tracks directory.
If started once more with identical input, BRouter will store a second route broute1.gpx
for the first alternative and so on.


Using nogo-areas
================

There's a special naming-convention to specify nogo-areas:

  "nogo[radius] [name]" defines a nogo-area, where radius (in Meter)
  is optional and defaults to 20m, and the name is also optional.
  So "nogo", "nogo1000", "nogo roadblock", "nogo200 badferry" are all valid
  names for nogo-waypoints.

The effect is that BRouter searches a route that does not touch the disc
defined by the position and the radius of the nogo-area.

Nogo-Areas are effective in the service-interface and in the BRouter-App.
In the BRouter-App, you will get a nogo-dialog allowing to de-select them
if nogo-waypoints are found in the waypoint-database. This de-selection
can also be bound to a service mode using the "Server Mode" button to make
it effective in the service-interface as well, but initially, every nogo-area
is effective in the service-interface.

Nogo areas can be used either to account for real obstacles or to enforce
personal routing preferences.

Please note that nogo values can transfer also by new interface 
parameter: polylines, polygons
see 'IBRouterService.aidl' for more information.


Mixed operation: "timeout-free recalculations"
==============================================

You can combine both operation modes (service-interface + BRouter-App) to
become able to calculate very long distances, but make use of the advantages of
the service interface as well, especially the dynamic recalculations if you get
off the track, without running into the 60 seconds timeout.

To support this, BRouter can do "timeout free recalculations". It works by
initially calculating a track to your destination and binding it to one or
more routing-modes using the "Server Mode" button. This way, BRouter stores
a "reference track" in the "brouter/modes" subdirectory.

If afterwards a route to the exact same destination is calculated via the service interface,
BRouter uses a special calculation mode that makes use of the reference track for
faster processing that is guaranteed to give a result within 60 seconds.
"Exact same" destination means withing 5m, so best use the same waypoint for
re-calculating that you used for the initial calculation.

This way you can follow a long distance route via the service interface, enjoying
automatic recalculations if you get off the track.


Issues and bugs:
================
<https://github.com/abrensch/brouter/issues>
