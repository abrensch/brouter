BRouter - Beta Version 0.9.8 - Setting up the Android App
=========================================================

Choosing and Installing a Map-Tool
----------------------------------

BRouter just calculates tracks as GPX-Files, it
does not display any map or give any navigation
instuctions. Therefore you need a map-tool in
order for BRouter to be useful.

Currently, BRouter cooperates with three different
maptools, so you need to install, and get familiar with,
at least one of them:

- "OsmAnd": See http://www.osmand.net Get It from Google-Play
   or get it as an APK from the release-build archive:
   http://download.osmand.net/releases/
   I tested versions up to 1.6

- "Locus": See http://www.locusmap.eu There's a "Pro"
  Version which is ad-free and a free version with ads. 
  You can get it from Google-Play, but for the free-version
  there's also an APK-Download.

- "Oruxmaps": See http://www.oruxmaps.com Oruxmaps is
  Donation-Ware, which means it's free and you're supposed
  to donate to the project if you want to support it.

Which one to use is a matter of taste. Maybe OsmAnd is
more plug&play and has a reasonable voice-guidig. Locus
and Oruxmaps are more powerful and better for outdoor
use. Locus for example has elevation profile diagrams
which OsmAnd has not.

Locus and Oruxmaps are best used with third-party vector
maps, check http://www.openandromaps.org if you consider
to go for Locus or OruxMaps.


Choosing an SD-Card Base Directory
----------------------------------

Some phones (namely those with Android 4.x) have 2 logical
"SD-Cards", where the first one is internal and not an actual
Card, and the second one is a an optional "external" micro-sd-card
that can be taken out of the device.

If you have such a setup, try to make sure your map-tool uses
the external sd-card to store the offline maps and other stuff.

In OsmAnd, this works by choosing an "SD-Card base directory". Typically,
the first card in mounted on "/mnt/sdcard", while the external
one maybe mounted at "/mnt/sdcard/external_sd", but depending
on your phone it can be some other path.

In OruxMaps, path configuration is only possible for the actual
map data, but the configuration database file that BRouter tries
to access is hardwired to the /mnt/sdcard/oruxmaps directory.
As a workaround for this specific setup, you can place a
redirection file in the directory where BRouter would normally
place the gpx-files (e.g. /mnt/sdcard/oruxmaps/tracklogs).
The first line of that redirection file called "brouter.redirect"
must contain the absolute path of the directory where you want
the gpx-files to go (e.g. /storage0/oruxmaps/tracklogs).

Make sure you understand the concept of the SD-Card base-directory,
because the communication between BRouter and the Map-Tools
requires that both are using either the same base-directory,
or the maptools are using the standard base directory (/mnt/sdcard).


Selecting Waypoints in your Maptool
-----------------------------------

In order to calculate a route, BRouter needs to know
at least a starting point and an endpoint. You specify
them by creating waypoints in your map-tool.

These are called "Favorites" in OsmAnd, "POI"s in Locus
or "Wayoints" in Oruxmaps and allow to store a location
on the map and give it a name.

Pleae specify at least a waypoint called "from" for
the starting-point and another called "to" for the
endpoint (lowercase! names are case-sensitive) You
can use any category, as only the name is read by BRouter.

Optionally, you can specify more waypoints:

 "via1" ... "via9" to specify stop-overs

 "nogo[radius] [name]" to specify a nogo-area,
  where radius (in Meter) is optional and defaults to 20m,
  and the name is also optional. So "nogo", "nogo1000",
  "nogo roadblock", "nogo200 badferry" are all valid
  nogo-waypoints.

Make sure to not create duplicates for the from, to and via-waypoints,
as BRouter would complain about duplicates. For nogo-areas,
duplicates are allowed.

Starting from version 0.97, instead of following the from/to/via
naming convention, you can choose any names and select them from
withing BRouter.


Installing the BRouter App
--------------------------

Download the file "brouter_0_8.zip" and unpack in a directory
"brouter" on the SD-Card of your Android Device. Most convenient
is to attach the device (or just the sd-card) to a desktop-computer
and do the unpacking there, but doing that on the device itself
is also possible, provided you know the appropriate tools.

Install the BRouter-App by installing the APK-File "BRouter.apk"
For instructions how to install from an APK (in contrast to
installing from Google Play), search the internet for tips.
You may need to change system configuration, some setting like
"Applications->Unknown sources" depending on Android version.

The BRouter App asks for permissions to access the SD-Card
and to de-activate the screen saver. Being an offline app,
it does NOT ask for internet access. The drawback is that
you have to install the additional resources manually.


BRouter's SD-Card Directory Structure
-------------------------------------

BRouter guesses a reasonable sd-card base directory and on
first start prompts you for a base directory with it's
guess as a default. You should choose the same base
directory that is used by your map-tool.

On first start, BRouter will create a "brouter" subdirectory
relative to that base-directory if it's not already there
(becaused you created it by unpacking the zip-file, see above)

If later on you want to change the base directory, you can delete
or rename the 'brouter' subfolder, so it will prompt again for
a base directory. You should choose the same base directory that
is used by your map-tool (OsmAnd or Locus).

So you may end up with e.g. the following directory structure
(depending on base dir and your map-tool choice):

/mnt/sdcard/brouter
/mnt/sdcard/brouter/segments2  <- put routing data files (*.rd5) here
/mnt/sdcard/brouter/profiles2  <- put lookup-table and routing profiles here

/mnt/sdcard/osmand             <- OsmAnd's sd-card dir
/mnt/sdcard/osmand/track       <- OsmAnd's track storage

/mnt/sdcard/Locus              <- Locus's sd-card dir
/mnt/sdcard/Locus/mapitems     <- Locus's track storage

/mnt/sdcard/oruxmaps           <- Oruxmaps's sd-card dir
/mnt/sdcard/oruxmaps/tracklogs <- Oruxmaps's track storage

Starting with version 0.94, if a non-standard base directory
is choosen (e.g. /mnt/sdcard/external_sd) BRouter tries to
additionally to access the configuraion files relative
to the standard base directory ( /mnt/sdcard )

The minimum files BRouter needs to work are e.g.

/mnt/sdcard/brouter/segments2/E5_N45.rd5
/mnt/sdcard/brouter/profiles2/lookups.dat
/mnt/sdcard/brouter/profiles2/trekking.brf

But of course you can put as many routing data files
and routing profiles as you like.

Get the profiles (*.brf) and the lookup.dat from
the zip-file or from:

  http://www.dr-brenschede.de/brouter/profiles2

And the routing data files from:

  http://h2096617.stratoserver.net/brouter/segments2

Routing data files are organised as 5*5 degree files,
with the Filename containing the south-west corner
of the square, which means:

- You want to route near West48/North37 -> get W50_N35.rd5
- You want to route near East7/North47 -> get E5_N45.rd5

From the above link you find routing data for all
places in the world where OSM data is available.

Starting the BRouter Android-APP
--------------------------------

Make sure you selected "from" and "to" waypoints
in your maptool as decsribed above.

Then you can start BRouter. It will read the waypoints
from the map-tools database, calculate the route and
store the result as "brouter0.gpx" in the map-tools
track directory.

BRouter shows a graphical animation of the routing
algorithm, and shows some messages on distances
and ascends. The "filtered ascend" is a measure
for the real hill-climbing pain, with small
variations filtered out.

Then you can use your maptool to view or navigate the
route.

If started once more with identical input,
BRouter will store a second route broute1.gpx
for the first alternative and so on.
