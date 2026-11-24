#!/bin/bash
set -e

rm -rf planet-old.osm.pbf
rm -rf planet-new.osm.pbf
touch mapsnapshpttime.txt
./osmupdate --verbose --drop-author --compression-level=1 planet-latest.osm.pbf planet-new.osm.pbf &

rm -rf tmp

mkdir tmp
cd tmp

java -Xmx6144M -Xms6144M -Xmn256M -cp ../brouter.jar btools.mapcreator.OsmParser ../lookups.dat . ../all.brf ../trekking.brf ../softaccess.brf ../planet-new.osm.pbf

mv ../planet-latest.osm.pbf ../planet-old.osm.pbf
mv ../planet-new.osm.pbf ../planet-latest.osm.pbf

mkdir unodes55
java -Xmx6144M -Xms6144M -Xmn256M -cp ../brouter.jar btools.mapcreator.NodeEnhancer . ../../srtm3_bef

mkdir segments
java  -Xmx6144M -Xms6144M -Xmn256M -cp ../brouter.jar btools.mapcreator.WayLinker . ../lookups.dat ../all.brf rd5

cd ..

rm -rf segments
mv tmp/segments segments
touch -r mapsnapshpttime.txt segments/*.rd5
rsh -l webrouter brouter.de "rm -rf segments; mkdir segments"
scp -p segments/* webrouter@brouter.de:segments
rsh -l webrouter brouter.de ./updateRd5.sh
