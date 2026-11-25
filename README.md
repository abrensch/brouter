BRouter / cleanupbranch
=======================

### A simplified code base for studying flexible routing on compact graphs

This is a code cleanup of the BRouter project, targeting people with academic
interest in the code and the algorithms. It is not targeting end users mainly
interested in perfect biking or hiking routes.

It is NOT intended to be merged back into the main BRouter repository.

The refactoring is mainly focused on the entropy coding, the
map-access layer and the expression engine. Refactoring of the actual
A* Router is not yet done, and will possibly never be done.
Main intention is to inspire people to build a new router based
on the map access layer.

## Main changes and simplifications

### Entropy coding as a reusable dependency

The entropy coding, which is the technical foundation of the
compactness and fast decoding of BRouter's datafiles is now
provided as an independent library, which can be used in other projects as well.

see https://github.com/abrensch/statcoding

### New file formats for graph and elevation data

It uses different file formats for the graph data ( rd5 -> gd5 )
and the intermediate elevation data (bef -> edf). They serve the same
purpose but are technically different and not compatible.
The new formats are neither faster nor more compact, but they
have a more straight-forward structure and are easier to understand.

The lookup table (lookups.dat) and the routing profiles (*.brf)
are unchanged and can be used with the new code.

### Demo instance

The cleanup-branch is compatible with brouter-web, but does not
support all features brouter-web is offering, e.g. no map-matching,
no profile upload, no KML format, etc.

A demo instance is running at https://dr-brenschede.de/brouter-web

### Unified graph instance model for map-creation and map-access

The graph instance model, made of nodes, links, way- and node descriptions
and turn-restrictions, is now unified for map-creation and map-access.
Not two different models anymore. That allows using and unit testing
graph-navigation and -persistence independent of OSM data.
Consequently the items of that model are named GraphNode, GraphLink,
not using the Osm prefix anymore.

### Direct weaving only

Direct weaving means that the graph is created directly from the
(entropy coded) datafile, without an intermediate uncompressed
representation in memory. Direct weaving was one of the later additions
to BRouter, and is now the only way to create a graph instance
from persistent data. That removes a significant amount of code and complexity.

### Dropped special treatment of transfer nodes

From the beginning of BRouter development, transfer nodes
(simple intermediate nodes along a way, just defining its geometry)
were treated specially, in order to speed up processing and
reduce the memory footprint. That special treatment is now dropped,
and transfer nodes are treated like any other node.
This also removes a significant amount of code and complexity,
and turned out to have only little impact on speed and memory footprint.

### Dropped A* Corrections for exotic cost contributions

BRouter uses the edges of the graph as the nodes in the A* sense,
making it correct with respect to turn-costs or initial costs, but not
really correct against non-local elevation costs. BRouter tries
to handle that by looking one more step ahead, but that adds additional
complexity and was never proven to really have an impact.
That complexity is now dropped.

### Two-Node-Loops now supported

BRouter always had a problem handling two-node-loops in the graph,
due to some implicit assumptions that a link between two nodes
is unique. For that reason, the "TwoNodeLoopResolver" was introduced,
eliminating two-node-loops at map-creation time. However, that never
worked for all edge-cases, and was a source of bugs.

The new code now supports two-node-loops, and the TwoNodeLoopResolver
is dropped. That is a significant simplification of the code.

## Related Projects

* [nrenner/BRouter-web](https://github.com/nrenner/brouter-web), a web interface on
    top of the BRouter HTTP server. An online instance of the main branch is available at
    [http://brouter.de/brouter-web/](http://brouter.de/brouter-web/).

## License

BRouter is released under an [MIT License](LICENSE).
