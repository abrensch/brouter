#!/bin/bash
set -e
wget -N http://planet.openstreetmap.org/pbf/planet-latest.osm.pbf

if test lastmaprun.date -nt planet-latest.osm.pbf; then
   echo "no osm update, exiting"
   exit 0
fi

touch lastmaprun.date

rm -rf /var/www/brouter/segments3_lastrun

mkdir tmp
cd tmp
mkdir nodetiles
/java/bin/java -Xmx256m -Xms256m -Xmn32m -cp ../pbfparser.jar:../brouter.jar btools.mapcreator.OsmCutter ../lookups.dat nodetiles ways.dat relations.dat ../all.brf ../planet-latest.osm.pbf

mkdir ftiles
/java/bin/java -Xmx512M -Xms512M -Xmn32M -cp ../brouter.jar -Ddeletetmpfiles=true -DuseDenseMaps=true btools.mapcreator.NodeFilter nodetiles ways.dat ftiles

/java/bin/java -Xmx2600M -Xms2600M -Xmn32M -cp ../brouter.jar -Ddeletetmpfiles=true -DuseDenseMaps=true btools.mapcreator.RelationMerger ways.dat ways2.dat relations.dat ../lookups.dat ../trekking.brf ../softaccess.brf

mkdir waytiles
/java/bin/java -Xmx2600M -Xms2600M -Xmn32M -cp ../brouter.jar -Ddeletetmpfiles=true -DuseDenseMaps=true btools.mapcreator.WayCutter ftiles ways2.dat waytiles

mkdir waytiles55
/java/bin/java -Xmx2600M -Xms2600M -Xmn32M -cp ../brouter.jar -Ddeletetmpfiles=true -DuseDenseMaps=true btools.mapcreator.WayCutter5 ftiles waytiles waytiles55 bordernids.dat

mkdir nodes55
/java/bin/java -Xmx128M -Xms128M -Xmn32M -cp ../brouter.jar -Ddeletetmpfiles=true -DuseDenseMaps=true btools.mapcreator.NodeCutter ftiles nodes55

mkdir unodes55
/java/bin/java -Xmx2600M -Xms2600M -Xmn32M -cp ../brouter.jar -Ddeletetmpfiles=true -DuseDenseMaps=true btools.mapcreator.PosUnifier nodes55 unodes55 bordernids.dat bordernodes.dat /private-backup/srtm

mkdir segments
mkdir segments/carsubset

/java/bin/java -Xmx2600M -Xms2600M -Xmn32M -cp ../brouter.jar -DuseDenseMaps=true btools.mapcreator.WayLinker unodes55 waytiles55 bordernodes.dat ../lookups.dat ../car-test.brf segments/carsubset cd5
/java/bin/java -Xmx2600M -Xms2600M -Xmn32M -cp ../brouter.jar -Ddeletetmpfiles=true -DuseDenseMaps=true btools.mapcreator.WayLinker unodes55 waytiles55 bordernodes.dat ../lookups.dat ../all.brf segments rd5

cd ..
rm -rf segments
mv tmp/segments segments
rm -rf tmp
cp /var/www/brouter/segments3/.htaccess segments
mv /var/www/brouter/segments3 /var/www/brouter/segments3_lastrun
mv segments /var/www/brouter/segments3
