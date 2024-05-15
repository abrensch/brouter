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
docker run --rm -v ./misc/scripts/segments4:/segments4 brouter
```

This will start brouter with a set of default routing profiles.

If you want to provide your own routing profiles, you can also mount the folder containing the custom profiles:

```
docker run --rm -v ./misc/scripts/segments4:/segments4 -v /path/to/custom/profiles:/profiles2 brouter
```

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
