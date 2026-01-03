#!/bin/bash
# Script to regenerate test segment E5_N60.rd5 with updated lookup table
# This requires an OSM PBF file covering the area (5-10°E, 60-65°N)

set -e

cd "$(dirname "$0")"

# Configuration
JAVA='java -Xmx2g -Xms2g'
BROUTER_PROFILES="misc/profiles2"
BROUTER_JAR=$(find . -name "brouter-*-all.jar" -path "*/build/libs/*" | head -1)

if [ -z "$BROUTER_JAR" ]; then
    echo "Building BRouter JAR..."
    ./gradlew :brouter-server:build --no-daemon
    BROUTER_JAR=$(find . -name "brouter-*-all.jar" -path "*/build/libs/*" | head -1)
fi

if [ -z "$BROUTER_JAR" ]; then
    echo "Error: Could not find brouter JAR file"
    exit 1
fi

echo "Using JAR: $BROUTER_JAR"
echo "Using profiles: $BROUTER_PROFILES"

# Check if we have a PBF file
PBF_FILE=""
if [ -n "$1" ]; then
    PBF_FILE="$1"
elif [ -f "norway-latest.osm.pbf" ]; then
    PBF_FILE="norway-latest.osm.pbf"
elif [ -f "test_segments/norway-latest.osm.pbf" ]; then
    PBF_FILE="test_segments/norway-latest.osm.pbf"
else
    echo "Error: No PBF file specified and no norway-latest.osm.pbf found"
    echo "Usage: $0 [path-to-pbf-file]"
    echo ""
    echo "You can download Norway extract from:"
    echo "  https://download.geofabrik.de/europe/norway-latest.osm.pbf"
    exit 1
fi

if [ ! -f "$PBF_FILE" ]; then
    echo "Error: PBF file not found: $PBF_FILE"
    exit 1
fi

echo "Using PBF file: $PBF_FILE"
echo "Regenerating segment E5_N60.rd5 with updated lookup table (includes fixme tag)"
echo ""

# Create temporary directory
TMP_DIR="tmp_segment_regen_$$"
mkdir -p "$TMP_DIR"
cd "$TMP_DIR"

echo "Step 1: Cutting OSM data..."
mkdir nodetiles
${JAVA} -cp "../${BROUTER_JAR}" -DavoidMapPolling=true btools.mapcreator.OsmCutter \
    "../${BROUTER_PROFILES}/lookups.dat" \
    nodetiles \
    ways.dat \
    relations.dat \
    restrictions.dat \
    "../${BROUTER_PROFILES}/all.brf" \
    "../${PBF_FILE}"

echo "Step 2: Filtering nodes..."
mkdir ftiles
${JAVA} -cp "../${BROUTER_JAR}" -Ddeletetmpfiles=true -DuseDenseMaps=true btools.mapcreator.NodeFilter \
    nodetiles \
    ways.dat \
    ftiles

echo "Step 3: Merging relations..."
${JAVA} -cp "../${BROUTER_JAR}" -Ddeletetmpfiles=true -DuseDenseMaps=true btools.mapcreator.RelationMerger \
    ways.dat \
    ways2.dat \
    relations.dat \
    "../${BROUTER_PROFILES}/lookups.dat" \
    "../${BROUTER_PROFILES}/trekking.brf" \
    "../${BROUTER_PROFILES}/softaccess.brf"

echo "Step 4: Cutting ways..."
mkdir waytiles
${JAVA} -cp "../${BROUTER_JAR}" -Ddeletetmpfiles=true -DuseDenseMaps=true btools.mapcreator.WayCutter \
    ftiles \
    ways2.dat \
    waytiles

echo "Step 5: Creating 5x5 degree way tiles..."
mkdir waytiles55
${JAVA} -cp "../${BROUTER_JAR}" -Ddeletetmpfiles=true -DuseDenseMaps=true btools.mapcreator.WayCutter5 \
    ftiles \
    waytiles \
    waytiles55 \
    bordernids.dat

echo "Step 6: Creating 5x5 degree node tiles..."
mkdir nodes55
${JAVA} -cp "../${BROUTER_JAR}" -Ddeletetmpfiles=true -DuseDenseMaps=true btools.mapcreator.NodeCutter \
    ftiles \
    nodes55

echo "Step 7: Unifying positions (without elevation data)..."
mkdir unodes55
${JAVA} -cp "../${BROUTER_JAR}" -Ddeletetmpfiles=true -DuseDenseMaps=true btools.mapcreator.PosUnifier \
    nodes55 \
    unodes55 \
    bordernids.dat \
    bordernodes.dat

echo "Step 8: Linking ways and creating segments..."
mkdir segments
${JAVA} -cp "../${BROUTER_JAR}" -DuseDenseMaps=true btools.mapcreator.WayLinker \
    unodes55 \
    waytiles55 \
    bordernodes.dat \
    restrictions.dat \
    "../${BROUTER_PROFILES}/lookups.dat" \
    "../${BROUTER_PROFILES}/all.brf" \
    segments \
    rd5

echo ""
echo "Step 9: Copying generated segment to test_segments..."
if [ -f "segments/E5_N60.rd5" ]; then
    cp "segments/E5_N60.rd5" "../test_segments/E5_N60.rd5"
    echo "Successfully regenerated test_segments/E5_N60.rd5"
    ls -lh "../test_segments/E5_N60.rd5"
else
    echo "Warning: E5_N60.rd5 not found in generated segments"
    echo "Generated segments:"
    ls -lh segments/*.rd5 2>/dev/null || echo "No .rd5 files found"
fi

cd ..
rm -rf "$TMP_DIR"

echo ""
echo "Done! The segment has been regenerated with the updated lookup table."
echo "The fixme tag is now included and will be encoded in the segment data."

