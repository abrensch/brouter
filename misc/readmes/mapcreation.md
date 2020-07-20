Build your own BRouter segments files
=====================================

BRouter uses its own data format (`.rd5` files), split in tiles of 5 x 5
in latitude and longitude. You can download the official segment files (weekly
built) from [brouter.de](http://brouter.de/brouter/segments4/) but you can
also build them yourself from an OSM dump.


## Build the pbfparser

First, there are two file formats available to download OSM data: `bzip`-ed
XML files (very large) and `.pbf`
([Protobuf](https://github.com/protocolbuffers/protobuf) format) which is much
more efficient. If you want to use the latter one, you will have to build the
`pbfparser` (located in `misc/pbfparser` first):

* Download [the latest
    version](https://github.com/openstreetmap/osmosis/releases)
    of [Osmosis](https://wiki.openstreetmap.org/wiki/Osmosis) and unzip it
    somewhere.
* Copy the `lib/default/protobuf-java-*.jar` and
    `lib/default/osmosis-osm-binary-*.jar` files from the unzipped Osmosis
    archive to `misc/pbfparser/protobuf.jar` and `misc/pbfparser/osmosis.jar`.
* Build BRouter and copy
    `brouter-server/target/brouter-server-*-jar-with-dependencies.jar` to
    `misc/pbfparser/brouter.jar`.
* You can build the `pbfparser` using, in the `misc/pbfparser/`
    folder,

```
javac -d . -cp "brouter.jar:protobuf.jar:osmosis.jar" *.java
```

* Finally, you can build a `jar` file from these files using

```
jar cf pbfparser.jar btools/**/*.class
```

_Note:_ If the `jar` file is not properly created, everything else will seem
to work normally but there will not be any data extracted from the OSM data
dump. You can check what is actually inside the built `jar` file using
`jar tf pbfparser.jar`.


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
interface](http://srtm.csi.cgiar.org/SELECTION/inputCoord.asp) (use the
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

## Docker

1. Build the Docker image: `docker build -f misc/scripts/mapcreation/Dockerfile -t brouter-mapcreation .`
2. Download a planet file
3. Download SRTM data
4. Run the mapcreation script:

``` shell script
docker run --rm \
    --user "$(id -u):$(id -g)"
    --env PLANET=slovenia-latest.osm.pbf \
    --env JAVA_OPTS="-Xmx2048M -Xms2048M -Xmn256M" \
    --volume /tank/brouter/tmp:/brouter-tmp \
    --volume /tank/brouter/planet:/planet \
    --volume /tank/brouter/srtm:/srtm:ro \
    --volume /tank/brouter/segments:/segments \
    brouter-mapcreation
```

Let's take a closer look on what happens here:

- `--rm` delete the container after the processing is done
- `--user "$(id -u):$(id -g)"`: the processing script can and should be run as a non-privileged user, the user ID and group ID for that user can be provided with the `--user` option; in the example the current user ID and its primary group ID is used. Ensure that the mounted directories (`tmp`, `planet`, `segments`) are writable by the given user.
- `--env PLANET=slovenia-latest.osm.pbf`: the downloaded planet file is located in `/tank/brouter/planet/` (see the step further down where that directory is mounted into the container) and its name is `slovenia-latest.osm.pbf` (**important:** the planet file *must* be located in the directory which is mounted at `/planet` in the container; that means for this example that `/tank/brouter/planet/slovenia-latest.osm.pbf` must exist)
- `--env JAVA_OPTS="-Xmx2048M -Xms2048M -Xmn256M"`: the memory related options for the Java VM should be set according to the specs of the docker container; the example shows values for smaller planet files – when processing larger regions (europe, world, …) the memory requirements are higher (the default value is `-Xmx6144M -Xms6144M -Xmn256M`)
- `--volume /tank/brouter/tmp:/brouter-tmp`: the process of creating the routing data uses a lot of temporary files which are written in this mounted directory
- `--volume /tank/brouter/planet:/planet`: the directory where the planet file is located; write access is needed because the planet file will be updated by the processing script before the routing data is created
- `--volume /tank/brouter/srtm:/srtm:ro`: the directory where the SRTM data is located, either in zipped ASCII format (`*.zip`) or in BEF (`*.bef`) format; it's mounted read-only as there are no changes applied to the SRTM data
- `--volume /tank/brouter/segments:/segments`: that's the target directory where the `*.rd5` files will be stored

If updating the planet file isn't desired, just provide `--env PLANET_UPDATE=0` as option. In this case the directory which contains the planet file can be mounted read-only.

### Downloading SRTM Data

SRTM data can be downloaded here: <http://srtm.csi.cgiar.org/srtmdata/>

Just select the wanted tiles and put them into the mentioned directory:

- Tile size: 5 deg x 5 deg
- Format: “Esri ASCII”

It's also possible to download the whole set at <https://drive.google.com/folderview?id=0B_J08t5spvd8RWRmYmtFa2puZEE&usp=drive_web#list>. Unpack the archive and mount the directory `SRTMv4.1/5_5x5_ascii` into the container.
