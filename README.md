BRouter
=======

BRouter is a configurable OSM offline router with elevation awareness, written
in Java. Designed to be multi-modal with a particular emphasis on bicycle
and energy-based car routing.

For more infos see [http://brouter.de/brouter](http://brouter.de/brouter).


## BRouter on Windows/Linux/Mac OS

### Build and Install

Build BRouter:

```
./gradlew clean build
```

Build JAR file for server and map creator with all dependent classes:

```
./gradlew clean build fatJar # places JAR file in brouter-server/build/libs/
```

Build ZIP file for distribution with readmes, profiles and JAR:

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
