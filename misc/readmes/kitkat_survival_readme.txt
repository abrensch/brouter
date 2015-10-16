Surviving with Android 4.4 (KitKat) or 5.x (Lollipop) using latest BRouter
=======================================================

BRouter has some basic support to reclaim your external SD card.

Brouter must be installed on internal drive ("SD card"), but maps could be moved to external SD card.

What's new is a configuration file located at:

  brouter/segments4/storageconfig.txt

which has 2 configuration items:

  "secondary_segment_dir" points to an additional directory containing
  routing data files. This can be located anywhere.

  "additional_maptool_dir" points to a base-directory that should
  be scanned for maptool-installations in addition to the standard-guesses.

Initially, the value for "secondary_segment_dir" is "../segments3" to support the
file-format transition from 1.2 to 1.3, so that, after upgrading, your existing
datafiles are found via the secondary directory.

However, for surviving KitKatn and later, you are supposed to change that to the
absolute path to a directory on the external card, e.g.:

secondary_segment_dir=/storage/external_SD/brouter_segments4

When searching for datafiles, both the download manager and the router first look in the primary (brouter/segments4) and then in the secondary directory.
On the other hand, the download manager always writes new datafiles to the primary directory, so the secondary directory is read-only.

So you can move datafiles downloaded by the download-manager to the secondary directory, by using a file manager, in order to free disk space on the internal card. Or you ca download datafiles directly to the secondary directory by doing manual http downloads
from http://brouter.de/brouter/segments4

Depending on how your maptool handles the file-system structure, you are done.

However, e.g. for OsmAnd it is likely that BRouter still has no access to OsmAnd's waypoint database. The reason is:

When, after installing OsmAnd, you choose to move it's resources to the external SD Card,
it moves it to a special directory where it has write-access even with Android 4.4, e.g.:

 /storage/external_SD/Android/data/net.osmand/files

The package name slightly differs for OsmAnd+.

This directory is not found automatically by BRouter, so you have to configure
it as "additional_maptool_dir".

However, you are still not done, because if BRouter finds a wayoint-database file
under:

 /storage/external_SD/Android/data/net.osmand/files/osmand/favourites.gpx

then it decides to write it's tracks to:

 /storage/external_SD/Android/data/net.osmand/files/osmand/tracks

But this directory is not writable by BRouter. So what you have to do is to create
a redirection-file (create the tracks folder if it does not exist!)

 /storage/external_SD/Android/data/net.osmand/files/osmand/tracks/brouter.redirect

and that should contain a single line with the absolute path to the folder where
the tracks should be written (e.g. /mnt/sdcard/brouter ). Redirection file is normal file named brouter.redirect and containing single line pointing to folder writable by BRouter and readable by OsmAnd.

THEN you are done.
