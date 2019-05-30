BRouter
=======

BRouter is a configurable OSM offline router with elevation awareness, Java + Android. Designed to be multi-modal with a particular emphasis on bicycle routing.

For more infos see http://brouter.de/brouter

<a href="https://f-droid.org/packages/btools.routingapp" target="_blank">
<img src="https://f-droid.org/badge/get-it-on.png" alt="Get it on F-Droid" height="90"/></a>
<a href="https://play.google.com/store/apps/details?id=btools.routingapp" target="_blank">
<img src="https://play.google.com/intl/en_us/badges/images/generic/en-play-badge.png" alt="Get it on Google Play" height="90"/></a>


## Build and Install

Compile with (Java 6!):

> mvn clean install -Dandroid.sdk.path=<your-sdk-path>

To skip building for Android, add ``-pl '!brouter-routing-app'``.

Next, download one or more [data file(s)](http://brouter.de/brouter/segments4/) (rd5) into the ``misc/segments4`` directory.

## Run

On Linux:
> ./misc/scripts/standalone/server.sh


Related Projects
================

* https://github.com/nrenner/brouter-web
* https://github.com/poutnikl/Brouter-profiles/wiki
