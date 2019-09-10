#!/bin/bash
set -e
cd "$(dirname "$0")"

# Fetch OSM planet dump if no planet file is specified
if [ -z "$PLANET_FILE" ]; then
    if [ -x "$(command -v osmupdate)" ] && [[ -f "./planet-latest.osm.pbf" ]]; then
        # Prefer running osmupdate to update the planet file if available
        mv "./planet-latest.osm.pbf" "./planet-latest.old.osm.pbf"
        osmupdate "planet-latest.old.osm.pbf" "./planet-latest.osm.pbf"
        rm "./planet-latest.old.osm.pbf"
    else
        # Otherwise, download it again
        wget -N http://planet.openstreetmap.org/pbf/planet-latest.osm.pbf
    fi
fi

if test lastmaprun.date -nt planet-latest.osm.pbf; then
   echo "no osm update, exiting"
   exit 0
fi

touch lastmaprun.date

rm -rf /var/www/brouter/segments4_lastrun

JAVA='/java/bin/java -Xmx2600m -Xms2600m -Xmn32m'

BROUTER_PROFILES=$(realpath "../../profiles2")

BROUTER_JAR=$(realpath $(ls ../../../brouter-server/target/brouter-server-*-jar-with-dependencies.jar))
OSMOSIS_JAR=$(realpath "../../pbfparser/osmosis.jar")
PROTOBUF_JAR=$(realpath "../../pbfparser/protobuf.jar")
PBFPARSER_JAR=$(realpath "../../pbfparser/pbfparser.jar")

PLANET_FILE=${PLANET_FILE:-$(realpath "./planet-latest.osm.pbf")}
# Download SRTM zip files from
# https://cgiarcsi.community/data/srtm-90m-digital-elevation-database-v4-1/
# (use the "ArcInfo ASCII" version) and put the ZIP files directly in this
# folder:
SRTM_PATH="/private-backup/srtm"

mkdir tmp
cd tmp
mkdir nodetiles
${JAVA} -cp "${OSMOSIS_JAR}:${PROTOBUF_JAR}:${PBFPARSER_JAR}:${BROUTER_JAR}" btools.mapcreator.OsmCutter ${BROUTER_PROFILES}/lookups.dat nodetiles ways.dat relations.dat restrictions.dat ${BROUTER_PROFILES}/all.brf ${PLANET_FILE}

mkdir ftiles
${JAVA} -cp ${BROUTER_JAR} -Ddeletetmpfiles=true -DuseDenseMaps=true btools.mapcreator.NodeFilter nodetiles ways.dat ftiles

${JAVA} -cp ${BROUTER_JAR} -Ddeletetmpfiles=true -DuseDenseMaps=true btools.mapcreator.RelationMerger ways.dat ways2.dat relations.dat ${BROUTER_PROFILES}/lookups.dat ${BROUTER_PROFILES}/trekking.brf ${BROUTER_PROFILES}/softaccess.brf

mkdir waytiles
${JAVA} -cp ${BROUTER_JAR} -Ddeletetmpfiles=true -DuseDenseMaps=true btools.mapcreator.WayCutter ftiles ways2.dat waytiles

mkdir waytiles55
${JAVA} -cp ${BROUTER_JAR} -Ddeletetmpfiles=true -DuseDenseMaps=true btools.mapcreator.WayCutter5 ftiles waytiles waytiles55 bordernids.dat

mkdir nodes55
${JAVA} -cp ${BROUTER_JAR} -Ddeletetmpfiles=true -DuseDenseMaps=true btools.mapcreator.NodeCutter ftiles nodes55

mkdir unodes55
${JAVA} -cp ${BROUTER_JAR} -Ddeletetmpfiles=true -DuseDenseMaps=true btools.mapcreator.PosUnifier nodes55 unodes55 bordernids.dat bordernodes.dat ${SRTM_PATH}

mkdir segments
${JAVA} -cp ${BROUTER_JAR} -DuseDenseMaps=true btools.mapcreator.WayLinker unodes55 waytiles55 bordernodes.dat restrictions.dat ${BROUTER_PROFILES}/lookups.dat ${BROUTER_PROFILES}/all.brf segments rd5

mkdir traffic

${JAVA} -jar ${BROUTER_JAR} segments 8.593025 49.724868 seed 0 ${BROUTER_PROFILES}/car-traffic_analysis.brf

${JAVA} -jar ${BROUTER_JAR} segments 8.609011 50.527861 seed 0 ${BROUTER_PROFILES}/car-traffic_analysis.brf

${JAVA} -jar ${BROUTER_JAR} segments 12.867994 51.239889 seed 0 ${BROUTER_PROFILES}/car-traffic_analysis.brf

${JAVA} -jar ${BROUTER_JAR} segments 11.128099 49.501845 seed 0 ${BROUTER_PROFILES}/car-traffic_analysis.brf

${JAVA} -jar ${BROUTER_JAR} segments 16.532815 49.169541 seed 0 ${BROUTER_PROFILES}/car-traffic_analysis.brf

${JAVA} -jar ${BROUTER_JAR} segments 16.917636 51.040949 seed 0 ${BROUTER_PROFILES}/car-traffic_analysis.brf

${JAVA} -cp ${BROUTER_JAR} -DuseDenseMaps=true btools.mapcreator.WayLinker unodes55 waytiles55 bordernodes.dat restrictions.dat ${BROUTER_PROFILES}/lookups.dat ${BROUTER_PROFILES}/all.brf segments rd5

cd ..
rm -rf segments
mv tmp/segments segments
cp /var/www/brouter/segments4/.htaccess segments
cp /var/www/brouter/segments4/storageconfig.txt segments
mv /var/www/brouter/segments4 /var/www/brouter/segments4_lastrun
mv segments /var/www/brouter/segments4
rm -rf tmp
