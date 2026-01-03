BRouter
=======

BRouter is a configurable OSM offline router with elevation awareness, Java +
Android. Designed to be multi-modal with a particular emphasis on bicycle
and energy-based car routing.

For more infos see [http://brouter.de/brouter](http://brouter.de/brouter).


## BRouter on Android

You can install the BRouter app on your Android device from
[F-Droid](https://f-droid.org/packages/btools.routingapp) or [Google Play
Store](https://play.google.com/store/apps/details?id=btools.routingapp). You
can also [build BRouter](#build-and-install) yourself. You can find detailed
documentation of the BRouter Android app in
[`docs/users/android_quickstart.md`](docs/users/android_quickstart.md).

<a href="https://f-droid.org/packages/btools.routingapp" target="_blank">
<img src="https://f-droid.org/badge/get-it-on.png" alt="Get it on F-Droid" height="90"/></a>
<a href="https://play.google.com/store/apps/details?id=btools.routingapp" target="_blank">
<img src="https://play.google.com/intl/en_us/badges/images/generic/en-play-badge.png" alt="Get it on Google Play" height="90"/></a>

### Android with Locus

You can use BRouter as the offline routing engine for [Locus
Map](https://www.locusmap.eu/) on your Android device. This is currently the
most featureful and maintained solutions for using BRouter on your Android
device.

A full documentation on how to set this up is available at
[https://www.locusmap.eu/locus-map-can-navigate-offline/](https://www.locusmap.eu/locus-map-can-navigate-offline/).


### Android with OSMAnd

Alternatively, you can also use BRouter as the offline routing engine for
[OSMAnd](https://osmand.net/) on your Android device.

A full documentation on how to set this up is available at
[`docs/users/osmand.md`](docs/users/osmand.md).


## BRouter on Windows/Linux/Mac OS

### Build and Install

To compile the BRouter Android app, the Android SDK path must first be set in a file called `local.properties` in the main folder:

```
sdk.dir=<your/android/sdk/path>
```


Build BRouter with the Android app (if Android SDK path is set):

```
./gradlew clean build
```

Build BRouter without the Android app:

```
./gradlew clean build -x :brouter-routing-app:build
```

Build JAR file for server and map creator with all dependent classes:

```
./gradlew clean build fatJar # places JAR file in brouter-server/build/libs/
```

Build ZIP file for distribution with readmes, profiles, APK and JAR:

```
./gradlew distZip # places ZIP file in brouter-server/build/distributions/
```


### Get the required segments (data) files

Routing data files are organised as 5*5 degree files,
with the filename containing the south-west corner
of the square, which means:

- You want to route near West48/North37 -> you need `W50_N35.rd5`
- You want to route near East7/North47 -> you need `E5_N45.rd5`

These data files, called "segments" across BRouter, are generated from
[OpenStreetMap](https://www.openstreetmap.org/) data and stored in a custom
binary format (rd5) for improved efficiency of BRouter routing.


#### Download them from brouter.de

Segments files from the whole planet are generated weekly at
[https://brouter.de/brouter/segments4/](http://brouter.de/brouter/segments4/).

You can download one or more segments files, covering the area of the planet
you want to route, into the `misc/segments4` directory.

#### Generate your own segments files

You can also generate the segments files you need directly from a planet dump
of OpenStreetMap data (or a [GeoFabrik extract](https://download.geofabrik.de/)).

More documentation of this is available in the
[`docs/developers/build_segments.md`](docs/developers/build_segments.md) file.


### (Optional) Generate profile variants

This repository holds examples of BRouter profiles for many different
transportation modes. Most of these can be easily customized by setting
variables in the first `global` context of the profiles files.

An helper script is available in `misc/scripts/generate_profile_variants.sh`
to help you quickly generate variants based on the default profiles, to create
a default set of profiles covering most of the basic use cases.

Have a look at the
[`docs/developers/profile_developers_guide.md`](docs/developers/profile_developers_guide.md)
for an in-depth guide on profiles edition and customization.


### Run the BRouter HTTP server

Helpers scripts are provided in `misc/scripts/standalone` to quickly spawn a
BRouter HTTP server for various platforms.

* Linux/Mac OS: `./misc/scripts/standalone/server.sh`
* Windows (using Bash): `./misc/scripts/standalone/server.sh`
* Windows (using CMD): `misc\scripts\standalone\server.cmd`

The API endpoints exposed by this HTTP server are documented in the
[`brouter-server/src/main/java/btools/server/request/ServerHandler.java`](brouter-server/src/main/java/btools/server/request/ServerHandler.java)
file.

The server emits log data for each routing request on stdout. For each routing
request a line with the following eight fields is printed. The fields are
separated by whitespace.

- timestamp, in ISO8601 format, e.g. `2024-05-14T21:11:26.499+02:00`
- current server session count (integer number 1-999) or "new" when a new
  IP address is detected
- IP address (IPv4 or IPv6), prefixed by `ip=`
- duration of routing request in ms, prefixed by `ms=`
- divider `->`
- HTTP request method
- HTTP request URL
- HTTP request version

Example log output:

```
2024-05-14T21:11:26.499+02:00 new ip=127.0.0.1 ms=189 -> GET /brouter?lonlats=13.377485,52.516247%7C13.351221,52.515004&profile=trekking&alternativeidx=0&format=geojson HTTP/1.1
2024-05-14T21:11:33.229+02:00   1 ip=127.0.0.1 ms=65 -> GET /brouter?lonlats=13.377485,52.516247%7C13.351221,52.515004&profile=trekking&alternativeidx=0&format=geojson HTTP/1.1
```

## BRouter with Docker

To build the Docker image run (in the project's top level directory):

```
docker build -t brouter .
```

Download the segment files as described in the previous chapter. The folder containing the
segment files can be mounted into the container. Run BRouter as follows:

```
docker run --rm \
  -v ./misc/scripts/segments4:/segments4 \
  -p 17777:17777 \
  --name brouter \
  brouter
```

This will start brouter with a set of default routing profiles. It will be accessible on port 17777.

If you want to provide your own routing profiles, you can also mount the folder containing the custom profiles:

```
docker run --rm \
  -v ./misc/scripts/segments4:/segments4 \
  -v /path/to/custom/profiles:/profiles2 \
  -p 17777:17777 \
  --name brouter \
  brouter
```

## Enhanced Features

### Hiking Rest Stop Waypoints

BRouter now includes automatic rest stop suggestions for hiking routes. When using the `hiking-mountain` profile (or other hiking profiles) with the `enable_hiking_rest=1.0` parameter, the router will:

- Calculate suggested rest stops along the route (every 11.295 km by default)
- Add these rest stops as waypoints in the GPX output
- Include rest stop information in the route metadata

Example usage:
```
java -jar brouter.jar <segmentdir> <profiledir> 0 hiking-mountain \
  "lon1,lat1|lon2,lat2" \
  "alternativeidx=0&format=gpx" \
  "enable_hiking_rest=1.0"
```

The rest stops will appear as `<wpt>` elements in the GPX file with names like "Rest Stop 1 (11.3 km)" and type "rest_stop", making them easy to identify in GPX readers and navigation apps.

When POI search is enabled (for hiking and cycling routes), nearby water points and cabins/huts are automatically found and included in the waypoint names:
- Water points within 2 km: `"Rest Stop 1 (11.3 km) | Water: 450m"`
- Cabins/huts within 5 km: `"Main Rest 1 (28.24 km) | Cabin: 1200m"`
- Spring warnings: `"Rest Stop 2 (22.6 km) | Water: 800m (spring)"`

See the [`test-routing/README.md`](test-routing/README.md) for example routes demonstrating these features.

**Historical Background:** The rest stop distances are based on traditional Norwegian measurement units. The default distance of 11.295 km corresponds to one old Norwegian mile (mil), while the alternative distance of 2.275 km is one quarter of an old Norwegian mile. A full day's walk was traditionally considered to be 40 km. Rest periods for hikers and cyclists are based on these historical measurements.

### Additional Routing Features

BRouter supports various routing modes and features:

#### Car Routing
- **Energy-based routing** with kinematic calculations
- **Rest period suggestions**: Every 4.5 hours driving = 45 min break, every 9 hours = 11 hour rest, every 6 days = 45 hour rest
- **Intersection parking**: Allows parking at intersections between specific road types (unclassified, service, track, rest_area, tertiary)
- **Landuse restrictions**: Automatically avoids forbidden landuse areas (farmland, residential, commercial, industrial, military, etc.)
- **Protected area checks**: Validates access through IUCN protection classes and designations

#### Truck Routing
- **Physical dimension restrictions**: Checks height, width, length, weight, and axle load against OSM restrictions
- **EU Regulation EC 561/2006 compliance**: Mandatory breaks after 4.5 hours, daily rest (11 hours), weekly rest (45 hours)
- **Time-based access restrictions**: Handles conditional tags like `hgv:conditional`, `access:conditional`, `maxweight:conditional`
- **Hazmat routing support**: Tunnel category restrictions based on hazmat class
- **Automatic rest stop insertion**: Finds and suggests rest areas, services, and motels along the route

#### Bicycle Routing
- **Elevation awareness**: Considers uphill/downhill costs
- **Rest stop suggestions**: For trekking cyclists, main rest every 28.24 km (scaled from hiking distances), alternative rest every 5.69 km
- **Daily segments**: Maximum 100 km per day for trekking cyclists
- **POI search**: Automatically searches for water points (2 km radius) and cabins/huts (5 km radius) near rest stops
- **Camping rules**: Country-specific rules for Nordic countries
- **Water point filtering**: Minimum 4 km between water points with spring warnings

#### Hiking Routing
- **Path validation**: Validates routes against forbidden highways (motorways, trunk, primary roads)
- **Rest suggestions**: Every 11.295 km (old Norwegian mile) or 2.275 km (1/4 mile) alternative
- **Daily segments**: Maximum 40 km per day
- **POI search**: Automatically searches for water points (2 km radius) and cabins/huts (5 km radius) near rest stops
- **Path priority**: Prioritizes `trailblazed=yes` and `route=hiking` paths, avoids `fixme` tagged paths
- **Path enforcement**: Automatically adds waypoints to avoid forbidden highways and enforce path usage
- **Guide requirements**: Detects when a guide is needed based on route difficulty and hazards
- **Glacier proximity warnings**: Minimum 200m camping distance from glaciers (buildings exempt)
- **Camping rules**: Country-specific rules for Nordic countries (Norway, Sweden, Denmark, Finland)
- **Water point filtering**: Minimum 4 km between water points with spring warnings
- **Protected area checks**: Validates access through IUCN protection classes and designations

#### Common Features
- **POI search**: Automatic search for water points and cabins/huts near rest stops (hiking and cycling routes)
  - Water points: Searches for `amenity=drinking_water`, `amenity=fountain`, `natural=spring` within 2 km
  - Cabins/Huts: Searches for `tourism=alpine_hut`, `tourism=wilderness_hut`, `tourism=hut`, `tourism=cabin` within 5 km
  - POI information included in GPX waypoint names with distances
  - Spring warnings included for natural springs
- **Camping rules**: Detailed country-specific information for Norway, Sweden, Denmark, and Finland
- **Water point filtering**: Distance-based filtering (minimum 4 km) with warnings for natural springs
- **Protected area checks**: IUCN protection class validation and designation checks
- **Country border detection**: Coordinate-based country detection for filtering POIs and applying country-specific rules
- **Landuse restrictions**: Automatic avoidance of forbidden landuse areas (cultivated, residential, commercial, industrial, restricted access)

#### Advanced Features
- **AutomaticRestStopInsertion**: Framework for automatically inserting rest stops as waypoints in routes
- **PathEnforcementEngine**: Framework for enforcing path usage by adding waypoints to avoid forbidden highways
- **CountryBorderFilter**: Coordinate-based country detection for major countries (can be extended with OSM boundary data)

For example routes demonstrating these features, see [`test-routing/README.md`](test-routing/README.md).

## Documentation

More documentation is available in the [`docs`](docs) folder.


## Related Projects

* [nrenner/BRouter-web](https://github.com/nrenner/brouter-web), a web interface on
    top of the BRouter HTTP server. An online instance is available at
    [http://brouter.de/brouter-web/](http://brouter.de/brouter-web/).
* [poutnikl/Brouter-profiles](https://github.com/poutnikl/Brouter-profiles/wiki),
    a collection of BRouter profiles.
* [Phyks/BRouterTesting](https://github.com/Phyks/BrouterTesting), a
    collection of test cases for helping develop new BRouter profiles.


## License

BRouter is released under an [MIT License](LICENSE).
