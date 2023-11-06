---
parent: Developers
title: Build segment files
---

Build your own segments files
=============================

BRouter uses its own data format (`.rd5` files), split in tiles of 5 x 5
in latitude and longitude. You can download the official segment files (weekly
built) from [brouter.de](http://brouter.de/brouter/segments4/) but you can
also build them yourself from an OSM dump (e.g. planet or [GeoFabrik
extract](https://download.geofabrik.de/))


## Run the map creation script

If you want to have elevation information in the generated segments files, you
should download the required [SRTM
files](https://cgiarcsi.community/data/srtm-90m-digital-elevation-database-v4-1/)
and set the `SRTM_PATH` variable when running the `process_pbf_planet.sh`
script.

Any flavor of the 90m SRTM database should be working, but the one used by the
official BRouter segments files are the ones provided by
[CGIAR](https://cgiarcsi.community/data/srtm-90m-digital-elevation-database-v4-1/).
If you are working with rather small geographical extracts, you can download
tiles manually using [this
interface](https://srtm.csi.cgiar.org/srtmdata/) (use the
"ArcInfo ASCII" format), instead of having to ask for an access for bulk
download of data. There is no need to unzip the downloaded files, the
`process_pbf_planet.sh` script expects a folder with all the ZIP files inside
and will manage it.

Note that if you don't have the SRTM data available, the segments files will
still be generated without any issue (but they will miss the elevation data).
If you are not sure which SRTM files you have to download, you can run the
script once and it will log all the SRTM files it is looking for.

You can now run the `misc/scripts/mapcreation/process_pbf_planet.sh` script to
build the segments. Have a look at the variables defined at the beginning of
the files and overwrite them according to your needs. By default, the script
will download the latest full planet dump from
[planet.osm.org](https://planet.osm.org/). You can also download a
geographical extract provided by [Geofabrik](https://download.geofabrik.de/)
and set the `PLANET_FILE` variable to point to it.

_Note:_ It is possible that you encounter an error complaining about not being
able to run `bash^M` on Linux/Mac OS. You can fix this one by running
`sed -i -e 's/\r$//' process_pbf_planet.sh`.


## Run a generation for elevation data tiles

To match the 5x5 OSM data grid (*.rd5) files from BRouter, there are elevation
data in a 5x5 degree format (*.bef). At the moment (end of 2023) the naming of
this elevation tiles follows the konvention used by srtm.csi.cgiar.org: srtm_x_y

As the srtm files are only available between 60N and 60S the filenames above 60N
contains negative values. e.g. srtm_34_-1 as a tile above srtm_34_00.

Please see OSM wiki for more info on [srtm](https://wiki.openstreetmap.org/wiki/SRTM).

The converter generates bef tiles from `hgt` files, `zipped hgt` files and `zipped 'ESRI' asc` files.

Converter call with arguments for a single tile generation:

`ElevationRasterTileConverter <srtm-filename | all> <hgt-data-dir> <srtm-output-dir> [arc seconds (1 or 3,default=3)] [hgt-fallback-data-dir]
Samples:
$ ... ElevationRasterTileConverter srtm_34_-1 ./srtm/hgt3sec ./srtm/srtm3_bef
$ ... ElevationRasterTileConverter srtm_34_-1 ./srtm/hgt1sec ./srtm/srtm1_bef 1
$ ... ElevationRasterTileConverter srtm_34_-1 ./srtm/hgt1sec ./srtm/srtm1_bef 1 ./srtm/hgt3sec
`
Arguments for multi file generation (world wide):

`$ ... ElevationRasterTileConverter all ./srtm/hgt3sec ./srtm/srtm3_bef
$ ... ElevationRasterTileConverter all ./srtm/hgt1sec ./srtm/srtm1_bef 1
$ ... ElevationRasterTileConverter all ./srtm/hgt1sec ./srtm/srtm1_bef 1 ./srtm/hgt3sec
`

To use 1sec and 3sec bef tiles at rd5 generation time you need an extra parameter to the fallback folder.
E.g.
`$ ... PosUnifier nodes55 unodes55 bordernids.dat bordernodes.dat ../srtm/srtm1_bef ../srtm/srtm3_bef
`
