---
parent: Using BRouter
nav_order: 2
---

# Android advanced configuration

### Directory structure

BRouter uses several subdirectories inside the base directory.

| directory                         | description                                  |
| --------------------------------- | -------------------------------------------- |
| `<basedir>/brouter`               |                                              |
| `<basedir>/brouter/modes`         | routing-mode/profile mapping and route cache |
| `<basedir>/brouter/profiles2`     | lookup-table and routing-profiles (\*.brf)   |
| `<basedir>/brouter/segments4`     | **routing data files (\*.rd5)**              |
| `<basedir>/brouter/import`        | allow a small file exchange with other apps  |
| `<basedir>/brouter/import/tracks` | place the `nogo*.gpx` files here             |

`modes` contains `serviceconfig.dat` which defines _routing-mode_ /
_routing-profile_ mapping and cached route results which are used for
recalculations.

`profiles2` contains `lookup.dat` (OSM tag access), `serverconfig.txt` and
_routing-profiles_.

`segments4` contains `storageconfig.txt` and routing data files (\*.rd5). You
can download them using the _Download Manager_ or as described in
[Download Routing Segments](download_segments.md).

### Configuration files

#### serverconfig.txt

`serverconfig.txt` is used to configure download information for routing
profiles and routing segments.

#### serviceconfig.dat

`serviceconfig.dat` is used to configure mapping between _routing-mode_ and
_routing-profile_.

#### storageconfig.txt

`storageconfig.txt` is used to specifiy additional paths which BRouter should
use.

* `secondary_segment_dir` points to an additional directory containing routing
  data files. This can be located anywhere.

  When searching for datafiles, both the download manager and the router first
  look in the primary (brouter/segments4) and then in the secondary directory.
  On the other hand, the download manager always writes new datafiles to the
  primary directory, so the secondary directory is read-only.

  You can move datafiles downloaded by the download-manager to the secondary
  directory, by using a file manager, in order to free disk space on the
  internal card.

* `additional_maptool_dir` points to a directory that should be scanned for
  maptool-installations in addition to the standard-guesses.

## Using nogo-areas

There's a special naming-convention to specify nogo-areas/lines:

`nogo[radius] [name]` defines a nogo-area, where radius (in Meter) is optional
and defaults to 20m, and the name is also optional. So `nogo`, `nogo1000`, `nogo
roadblock`, `nogo200 badferry` are all valid names for nogo-waypoints.

The effect is that BRouter searches a route that avoids the area defined by the
position and the radius of the nogo-area.

Nogo-areas are used when routing via _service interface_ and _file interface_.

When using the _file interface_ you will get a nogo-dialog allowing to de-select
them if nogo-waypoints are found in the waypoint-database. This de-selection can
also be bound to a service mode using the _Server Mode_ button to make it
effective using the _service interface_ as well, but initially, every nogo-area
is effective in the _service interface_.

Nogo-areas can be used either to account for real obstacles or to enforce
personal routing preferences.

## Routing via _file interface_

The other option is using the BRouter app to calculate a route. This is the
prefered option when calculating long-distance-routes that would not finish
within the 60 seconds timout if calculated via the _service interface_.

To do this, start the BRouter app, select two or more waypoints and then start
the route calculation. BRouter reads waypoints from the `import` folder
`favourites.gpx` file.

If your waypoint database contains a `from` and `to` waypoint the waypoint
selection will be skipped. BRouter also uses `via1`, ..., `via9` as via
waypoints.

If a route is calculated, it is stored as `brouter0.gpx`. BRouter stores the route in
`<basedir>/import/tracks` directory. If started once more with identical input,
BRouter will store a second route `brouter1.gpx` for the first alternative and so on.

## Mixed operation: _timeout-free recalculations_

You can combine both operation modes (_service interface_ + _file interface_ to
become able to calculate very long distances, but make use of the advantages of
the service interface as well, especially the dynamic recalculations if you get
off the track, without running into the 60 seconds timeout.

To support this, BRouter can do _timeout free recalculations_. It works by
initially calculating a track to your destination and binding it to one or more
routing-modes using the _Server Mode_ button. This way, BRouter stores a
_reference track_ in the `brouter/modes` subdirectory.

If afterwards a route to the exact same destination is calculated via the
service interface, BRouter uses a special calculation mode that makes use of the
reference track for faster processing that is guaranteed to give a result within
60 seconds. _Exact same_ destination means withing 5m, so best use the same
waypoint for re-calculating that you used for the initial calculation.

This way you can follow a long distance route via the _service interface_,
enjoying automatic recalculations if you get off the track.
