#!/bin/bash
set -e
wget -N http://planet.openstreetmap.org/pbf/planet-latest.osm.pbf

if test lastmaprun.date -nt planet-latest.osm.pbf; then
   echo "no osm update, exiting"
   exit 0
fi

touch lastmaprun.date

rm -rf /var/www/brouter/segments4_lastrun

mkdir tmp
cd tmp
mkdir nodetiles
/java/bin/java -Xmx2600m -Xms2600m -Xmn32m -cp ../pbfparser.jar:../brouter.jar btools.mapcreator.OsmCutter ../lookups.dat nodetiles ways.dat relations.dat ../all.brf ../planet-latest.osm.pbf

mkdir ftiles
/java/bin/java -Xmx2600M -Xms2600M -Xmn32M -cp ../brouter.jar -Ddeletetmpfiles=true -DuseDenseMaps=true btools.mapcreator.NodeFilter nodetiles ways.dat ftiles

/java/bin/java -Xmx2600M -Xms2600M -Xmn32M -cp ../brouter.jar -Ddeletetmpfiles=true -DuseDenseMaps=true btools.mapcreator.RelationMerger ways.dat ways2.dat relations.dat ../lookups.dat ../trekking.brf ../softaccess.brf

mkdir waytiles
/java/bin/java -Xmx2600M -Xms2600M -Xmn32M -cp ../brouter.jar -Ddeletetmpfiles=true -DuseDenseMaps=true btools.mapcreator.WayCutter ftiles ways2.dat waytiles

mkdir waytiles55
/java/bin/java -Xmx2600M -Xms2600M -Xmn32M -cp ../brouter.jar -Ddeletetmpfiles=true -DuseDenseMaps=true btools.mapcreator.WayCutter5 ftiles waytiles waytiles55 bordernids.dat

mkdir nodes55
/java/bin/java -Xmx2600M -Xms2600M -Xmn32M -cp ../brouter.jar -Ddeletetmpfiles=true -DuseDenseMaps=true btools.mapcreator.NodeCutter ftiles nodes55

mkdir unodes55
/java/bin/java -Xmx2600M -Xms2600M -Xmn32M -cp ../brouter.jar -Ddeletetmpfiles=true -DuseDenseMaps=true btools.mapcreator.PosUnifier nodes55 unodes55 bordernids.dat bordernodes.dat /private-backup/srtm

mkdir segments

/java/bin/java -Xmx2600M -Xms2600M -Xmn32M -cp ../brouter.jar -DuseDenseMaps=true btools.mapcreator.WayLinker unodes55 waytiles55 bordernodes.dat restrictions.dat ../lookups.dat ../all.brf segments rd5

mkdir traffic

/java/bin/java -Xmx2600m -Xms2600m -Xmn32m -jar ../brouter.jar segments 8.593025 49.724868 seed 0 ../car-traffic_analysis.brf

/java/bin/java -Xmx2600m -Xms2600m -Xmn32m -jar ../brouter.jar segments 8.609011 50.527861 seed 0 ../car-traffic_analysis.brf

/java/bin/java -Xmx2600m -Xms2600m -Xmn32m -jar ../brouter.jar segments 12.867994 51.239889 seed 0 ../car-traffic_analysis.brf

/java/bin/java -Xmx2600m -Xms2600m -Xmn32m -jar ../brouter.jar segments 11.128099 49.501845 seed 0 ../car-traffic_analysis.brf

/java/bin/java -Xmx2600m -Xms2600m -Xmn32m -jar ../brouter.jar segments 16.532815 49.169541 seed 0 ../car-traffic_analysis.brf

/java/bin/java -Xmx2600m -Xms2600m -Xmn32m -jar ../brouter.jar segments 16.917636 51.040949 seed 0 ../car-traffic_analysis.brf

/java/bin/java -Xmx2600M -Xms2600M -Xmn32M -cp ../brouter.jar -DuseDenseMaps=true btools.mapcreator.WayLinker unodes55 waytiles55 bordernodes.dat restrictions.dat ../lookups.dat ../all.brf segments rd5

cd ..
rm -rf segments
mv tmp/segments segments
cp /var/www/brouter/segments4/.htaccess segments
cp /var/www/brouter/segments4/storageconfig.txt segments
mv /var/www/brouter/segments4 /var/www/brouter/segments4_lastrun
mv segments /var/www/brouter/segments4
rm -rf tmp
