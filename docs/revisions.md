# Revision history

(ZIP-Archives including APK, readme + profiles)

### next version

Android

- bug fix for repeat last route <repeat:...>

Library

- ISO8601 compatible timestamps in log
- Update MIME type for GeoJSON responses

Profiles

- update gravel profile


- Minor bug fixes

[Solved issues](https://github.com/abrensch/brouter/issues?q=is%3Aissue+milestone%3A%22Version+1.7.5%22+is%3Aclosed)


### [brouter-1.7.4.zip](../brouter_bin/brouter-1.7.4.zip) (current revision, 09.04.2024)

Library

- new "DIVIDE" command for profile calculation
- new "maxslope" and "maxslopecost" parameters
- new parameter collector
- new output logic
- rework on voice hints and roundabouts
- enabled elevation raster files with 1 asec


Android

- BRouter translations
- fallback on certificate problems


- Minor bug fixes

[Solved issues](https://github.com/abrensch/brouter/issues?q=is%3Aissue+milestone%3A%22Version+1.7.4%22+is%3Aclosed)


### [brouter-1.7.3.zip](../brouter_bin/brouter-1.7.3.zip) (19.08.2023)

- Minor bug fixes


### [brouter-1.7.2.zip](../brouter_bin/brouter-1.7.2.zip) (19.07.2023)

- Re-index Json output
  Note: This is different to releases 1.7.0 and 1.7.1. It is recommended to use the current version to avoid breaks in voice hint output for GeoJson.


### [brouter-1.7.1.zip](../brouter_bin/brouter-1.7.1.zip) (12.07.2023)

Android

- Add parameter dialog for profile
- Add portrait mode for download view
- Add silent mode for calling apps
- Fixed download lookups.dat when download only single rd5 tile.


Library

- Update matching points rules on areas with longer distances between way points
- Optimize constant expressions in profile parsing
- Rework on roundabouts (left-hand driving)
- Add new function 'get elevation'
- Minor bug fixes


### [brouter-1.7.0.zip](../brouter_bin/brouter-1.7.0.zip) (29.04.2023)

Android

-   Enable Android 13 (SDK 33)
-   Remove external coord readers (OsmAnd, Locus, Orux)
-   Remove brouter.redirect
-   Remove version for Android 19
-   Sort profiles
-   New download worker (foreground)

Library

-   U-turn command (180 degree)
-   Recalculation of ascend over all segments
-   Recalculation on elevations (bridges/tunnel)
-   Rework on turn instructions
-   Allow straight lines between 2 via points
-   Correction of misplaced via points
-   Remove double points
-   New locus export with info on trkpt

[Solved issues](https://github.com/abrensch/brouter/issues?q=is%3Aissue+milestone%3A%22Version+1.6.4%22+is%3Aclosed)

### [brouter-1.6.3.zip](../brouter_bin/brouter-1.6.3.zip) (21.12.2021)

-   Enable Android 11
-   Move storage to /Android/media/btools.routingapp
-   Coord reader in app specific folder (favourites.gpx and nogo*.gpx)
-   new Logo
-   increased download speed limit from 4 to 16 MBit/s

### [brouter_1_6_1.zip](../brouter_bin/brouter_1_6_1.zip) (01.03.2020)

-   download manager: direct jump-in zoom to workaround a problem with
    S10+Android10

### [brouter_1_6_0.zip](../brouter_bin/brouter_1_6_0.zip) (16.02.2020)

-   fixed Voice-Hint timing bug (locus+osmand)
-   BRouter-Web related enhancements
-   fixed excessive roundabout times in kinematic model
-   lookup+profile extensions
-   documentation updates
-   pre-processor: Douglas-Peucker transfer-node elimination
-   pre-processor: speedup (->daily updates)
-   pre-processor: conversion of (lidar-) hgt to bef (->Northern Europe
    coverage)
-   route server: thread-limit logic update (guarantee 2000ms victim runtime)
-   route server: extended parsed profile cache
-   route server: more appropriate HTTP status codes
-   download manager: rd5 delta updates
-   download manager: rd5 delete function
-   suspect-manager: multiple challenges

### [brouter_1_5_5.zip](../brouter_bin/brouter_1_5_5.zip) (22.07.2019)

-   bugfix in lazy crc check
-   waypount echo (brouter-web)

### [brouter_1_5_4.zip](../brouter_bin/brouter_1_5_4.zip) (20.07.2019, hot-fix 16:40 UTC+2)

-   fixed OsmAnd Turn Instruction issue (+hot-fix)
-   internal compression in service interface
-   repeat timeout -> repeat any service interface routing in brouter-app
-   forcing new basedir selection on 1.4.x - 1.5.x update
-   more careful memory allocation

### [brouter_1_5_3.zip](../brouter_bin/brouter_1_5_3.zip) (06.07.2019)

-   fixed car-profiles for correct OsmAnd Turn Instructions
-   increased Download Manager Speed Limit 2 Mbit/s -> 4 MBit/s
-   adapted download size estimates

### [brouter_1_5_2.zip](../brouter_bin/brouter_1_5_2.zip) (04.07.2019)

-   Android-Api-28 compatibility (with some loss of function)
-   easy install on external SD (but RD5s will be deleted on uninstall)
-   both Api-28 and Api-10 APKs in release-zip

### [brouter_1_5_1.zip](../brouter_bin/brouter_1_5_1.zip) (30.06.2019)

-   Android Target API back to 10 for now (too much problems on Android >= 6)

### [brouter_1_5_0.zip](../brouter_bin/brouter_1_5_0.zip) (30.06.2019)

-   MIT License
-   Android Target API 28 (to conform to Play Store Policy)
-   new internal memory management (direct-weaving+escape analysis): lower
    memory footprint and defined memory bounds = more reliable operation for
    long distances and as embedded library
-   performance improvements
-   Bicycle+Foot ETA (estimated time of arrival)
-   ETA data in GPX for Locus + OsmAnd
-   more precice distance calculation
-   weighted nogos
-   BRouter-Web related additions
-   maxspeed:forrward/backward
-   no_entry/no_exit TRs

### [brouter_1_4_11.zip](../brouter_bin/brouter_1_4_11.zip) (02.04.2018, hot-fix 12.4.2018)

-   automatically ignore network islands
-   car-fast/eco: breaking speeds from cost model + cost tuning
-   **hot-fix 12.4.2018**: fixed bug for only-TRs at start or end
    segment

### [brouter_1_4_10.zip](../brouter_bin/brouter_1_4_10.zip) (07.03.2018)

-   fixed motorcar TR exceptions
-   added vr-forum profiles to distribution
-   added suspect manager to RouteServer
-   polygon nogo pull request (QMapShack)
-   nogo encoding pull request (QMapShack)

### [brouter_1_4_9.zip](../brouter_bin/brouter_1_4_9.zip) (24.09.2017)

-   tweaked distance calculation
-   new car profiles, kinematic model based
-   basic travel-time/energy support
-   modular cost models
-   lookup extensions (+conrcete:lanes/plate code-side-hack)
-   fix for interface provided nogos
-   access to way-context vars from node-context
-   fixed same segment search problem
-   removed size limit for encoded tags
-   (**hot fix, 5pm**: fixed regression bug for TR-bike-exceptions)

### [brouter_1_4_8.zip](../brouter_bin/brouter_1_4_8.zip) (10.12.2016, hot-fix 7.1.2017)

-   added turn restrictions (default for car, use considerTurnRestrictions=true
    for bike)
-   fixed elevation interpolation in start/end segments
-   fixed error message for very old data files
-   removed sanity checks when just reading nogos from waypoint-database
-   handling url encoded parameters
-   locus codes 13/14 for u-turns left/right
-   workaround for app-startup-crash when not able to determine free disk-space
    (hot-fix 7.1.2017)

### [brouter_1_4_7.zip](../brouter_bin/brouter_1_4_7.zip) (19.10.2016)

-   added turncost as start-direction bias (locus only)
-   fixed a nullpointer bug in voice-hint-processing
-   fixed brouter-web/standalone upload path bug
-   added oneway:bicycle=true to oneway logic

### [brouter_1_4_6.zip](../brouter_bin/brouter_1_4_6.zip) (30.9.2016)

-   improved memory footprint
-   tweaked recalculation timeout logic

### [brouter_1_4_5.zip](../brouter_bin/brouter_1_4_5.zip) (10.9.2016)

-   some more performance improvements
-   filtering out unused way tags to increase profile cache efficiency
-   cache sizing depending on android memory class
-   fixed *ups* bug at very long distances
-   fixed a bug when using repeat-timeout shortcut without a cordinate source

### [brouter_1_4_4.zip](../brouter_bin/brouter_1_4_4.zip) (29.08.2016)

-   performance improvements
-   *repeat timeout* shortcut to continue a timed-out service request
-   relaxed compatibility rule for lookup-data minor version
-   added mtb:scale:uphill

### [brouter_1_4_3.zip](../brouter_bin/brouter_1_4_3.zip) (06.08.2016)

-   Option for sending profiles via service interface
-   more aggresive profile replacement at version upgrade
-   fixed a serious rounding bug when reading locus/orux waypoints

### [brouter_1_4_2.zip](../brouter_bin/brouter_1_4_2.zip) (16.05.2016)

-   turn instructions, elevation on locus waypoints
-   turn-instructions, shift to less ambigious angles
-   turn-instructions, locus transport mode cleanup

### [brouter_1_4_1.zip](../brouter_bin/brouter_1_4_1.zip) (09.05.2016

-   turn instructions, fixed locus roundabaouts
-   added xor, lesser, sub operators for profiles

### [brouter_1_4.zip](../brouter_bin/brouter_1_4.zip) (06.05.2016)

-   turn instructions, first version (locus+osmand)
-   extended scan for searching maptool-waypoint database
-   blank->underscore replacement in tag-values
-   ignoring direct duplicates in waypoint selection

### [brouter_1_3_2.zip](../brouter_bin/brouter_1_3_2.zip) (01.11.2015)

-   allow big waypoint databases (locus+orux)
-   storageconfig-migration for 1.2->1.3.2 update
-   dirty reference tracks for better 2nd try performance
-   static profile cache re-use
-   fix for osmand 2.x directory structure on 4.4+ ext-sd
-   fixed some error-handling issues

### [brouter_1_3_1.zip](../brouter_bin/brouter_1_3_1.zip) (18.10.2015)

-   target island detection
-   fixed 2-node loop problem
-   minor profile modifications
-   changed animation to show track during final pass

### [brouter_1_3.zip](../brouter_bin/brouter_1_3.zip) (16.10.2015)

-   statistical encoding for data files (->much smaller)
-   download manager update function
-   filter for routable ways on decoder-level
-   -> better memory footprint, no more OOM
-   removed carsubset files (not needed anymore)
-   waypoint matching on decoder level
-   waypoint inside nogo disables nogo
-   traffic-load pseudo-tags from traffic simulation

### [brouter_1_2.zip](../brouter_bin/brouter_1_2.zip) (4.4.2015)

-   profile syntax extensions
-   csv-fixes
-   safari-patch (brouter-web)
-   message list in geojson (brouter-web)
-   initial cost classifier
-   lookup extensions (minor version 4)
-   more error handling + debug tracing

### [brouter_1_1.zip](../brouter_bin/brouter_1_1.zip) (28.12.2014)

-   performance fixes

### [brouter_1_0_4.zip](../brouter_bin/brouter_1_0_4.zip) (28.9.2014)

-   lookup extensions
-   proposed-handling for cycle relations
-   reworked csv listing

### [brouter_1_0_3.zip](../brouter_bin/brouter_1_0_3.zip) (24.8.2014)

-   support for slope dependent cost-function

### [brouter_1_0_2.zip](../brouter_bin/brouter_1_0_2.zip) (10.8.2014)

-   fixed NullPointerException during setup
-   mime-type patch for downloading from brouter-web

### [brouter_1_0_1.zip](../brouter_bin/brouter_1_0_1.zip) (26.7.2014)

-   new file format with extended lookup table and 25% size reduction
-   special, fast handling for trivial recalculations for timeout-free
    recalculations
-   fixed the scaling for high-density screens in the download manager
-   added more [configuration options](https://brouter.de/brouter/kitkat_survival_readme.txt) to work
    around the kitkat (Android 4.4) issues

### [brouter_0_9_9.zip](../brouter_bin/brouter_0_9_9.zip) (18.4.2014, hot-fix 11.5.2014)

-   new (google-play compatible) signing key, UNINSTALL NECCESSARY!
-   added crc checksums to datafiles
-   fixed a bug in accessing the last 64k of a datafile
-   extended basedir-proposals (**Fixed Android 4.4 issue on 11.5.2014**)
-   changed RouteServer to multithreaded/nonblocking operation (for brouter-web)
-   added brouter-web start-scripts
-   added oneway:bicycle=no -> cycleway=opposite conversion to pre-processor
-   added more cache-reuse for better short-route performance

### [brouter_0_9_8.zip](../brouter_bin/brouter_0_9_8.zip) (12.1.2014)

-   fixed NullPointer for missing service-mode
-   fixed remaining issue for short routes with from/to on same way-section
-   improved reporting on OutOfMemory
-   changed *fastbike* profile to fix an issue with mandatory cycleways
-   fixes a bug in elevation reporting if startpoint has VOID elevation

### [brouter_0_9_7.zip](../brouter_bin/brouter_0_9_7.zip) (31.12.2013)

-   fixed a bug for short routes with from/to on same way-section
-   improved waypoint-matching
-   improved nogo-handling in service interface (inverse logic, routing mode
    stores veto-list)
-   added waypoint-selection dialogs when from/to not given
-   summary page after service-mode confifuration update
-   allowed configuration of BRouter's servicemodes without any supported
    maptool installed
-   added a redirection-workaround for the tracks-output directory
-   removed the beta-expiry

### [brouter_0_9_6.zip](../brouter_bin/brouter_0_9_6.zip) (27.10.2013)

-   added html-page about [routing-algorithm](developers/algorithm.md)
-   changed from 3-pass to 2-pass calculation
-   added profile-parameters for routing coefficients
-   lowered pass1-coefficient for car-test to 1.3
-   fixed a bug in nogo-handling in service interface
-   fixed a bug in command-line java version

### [brouter_0_9_5.zip](../brouter_bin/brouter_0_9_5.zip) (20.10.2013)

-   some performance improvments
-   support for car-subset datafiles
-   timeout-free partial recalcs in service-mode
-   added java-version (executable jar) to distribution zip
-   moved service-mode-mapping files to sdcard

### [brouter_0_9_4.zip](../brouter_bin/brouter_0_9_4.zip) (4.9.2013)

-   additional maptool search at /mnt/sdcard when using a non-standard base
    directory
-   fixed error handling issues
-   major source code refactoring

### [brouter_0_9_3.zip](../brouter_bin/brouter_0_9_3.zip) (30.6.2013)

-   introduced new service interface as android service
-   re-designed service-mode configuration to be more flexible

### [brouter_0_9_2.zip](../brouter_bin/brouter_0_9_2.zip) (9.6.2013)

-   fixed lifecycle issues of service interface

### [brouter_0_9_1.zip](../brouter_bin/brouter_0_9_1.zip) (2.6.2013)

-   added an experimental service interface (see readme_service.txt)

### [brouter_0_9.zip](../brouter_bin/brouter_0_9.zip) (26.5.2013)

-   line-matching + exact positions for waypoints
-   minor profile modifications
-   changed track-name from mytrack so something more useful

### [brouter_0_8.zip](../brouter_bin/brouter_0_8.zip) (4.5.2013)

-   changed beta expiry to August 2014
-   Nogo-Points next version: line-matching + radius
-   line-matching for waypoints (online version only up to now)
-   moped-profile

### [brouter_0_7.zip](../brouter_bin/brouter_0_7.zip) (7.4.2013)

-   Support for OruxMaps
-   Via-Points
-   Nogo-Points
-   fixed parsing of profiles without trailing newline
-   fixed scaling of routing animation
-   (No documentation update yet!)

### [brouter_0_6.zip](../brouter_bin/brouter_0_6.zip) (9.3.2013)

-   Extended data files (more way tags, added node tags)
-   Extended profiles (global-, way-, node-context)
-   more precise access + oneway rules
-   more evelation parameters in profiles
-   explicit configuration of base directory
-   elevation=void within bridges or tunnels
-   fixed gpx version header
-   link counter in app animation

### [brouter_0_5.zip](../brouter_bin/brouter_0_5.zip) (27.1.2013)

-   last revision before data format change - old data files not available
    anymore
