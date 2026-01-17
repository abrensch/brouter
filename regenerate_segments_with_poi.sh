#!/bin/bash
# Script to regenerate test segments E10_N55.rd5 and E10_N60.rd5 with POI node data
# This requires an OSM PBF file covering the area (10-15°E, 55-60°N and 10-15°E, 60-65°N)

set -e

cd "$(dirname "$0")"

# Configuration
JAVA='java -Xmx2g -Xms2g'
BROUTER_PROFILES="misc/profiles2"
BROUTER_JAR=$(find . -name "brouter-*-all.jar" -path "*/build/libs/*" | head -1)

if [ -z "$BROUTER_JAR" ]; then
    echo "Building BRouter JAR..."
    ./gradlew :brouter-server:fatJar --no-daemon
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
echo "Regenerating segments for car route (10°E-35°E, 59°N-75°N) with POI node data"
echo "Required segments: E10_N55, E10_N60, E15_N60, E15_N65, E20_N60, E20_N65, E25_N60, E25_N65, E25_N70, E30_N60, E30_N65, E30_N70, E35_N65"
echo "This includes water points, cabins, and other POI nodes"
echo "Focus: Far northern Norway segments with full border node processing for connectivity"
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

echo "Step 2: Filtering nodes (including POI nodes)..."
mkdir -p ftiles

# Check what files OsmCutter produced
NODETILES_COUNT=$(find nodetiles -name "*.ntl" 2>/dev/null | wc -l)
if [ "$NODETILES_COUNT" -eq 0 ]; then
    echo "ERROR: OsmCutter produced no .ntl files in nodetiles!"
    echo "This might indicate the PBF file is empty or OsmCutter failed."
    exit 1
fi
echo "Found $NODETILES_COUNT node tile files (.ntl) from OsmCutter"

# NodeFilter looks for .tls files, but OsmCutter produces .ntl files
# We need to rename .ntl to .tls so NodeFilter can find them
# NodeFilter preserves filenames, so output will be .tls
echo "Renaming .ntl files to .tls for NodeFilter..."
for ntl_file in nodetiles/*.ntl; do
    if [ -f "$ntl_file" ]; then
        tls_file="${ntl_file%.ntl}.tls"
        mv "$ntl_file" "$tls_file"
    fi
done

${JAVA} -cp "../${BROUTER_JAR}" -Ddeletetmpfiles=false -DuseDenseMaps=true btools.mapcreator.NodeFilter \
    nodetiles \
    ways.dat \
    ftiles

# Verify ftiles were created
FTILES_COUNT=$(find ftiles -name "*.tls" 2>/dev/null | wc -l)
if [ "$FTILES_COUNT" -eq 0 ]; then
    echo "ERROR: NodeFilter produced no .tls files in ftiles!"
    echo "This might indicate there are no nodes in nodetiles or ways.dat is empty."
    exit 1
fi
echo "Created $FTILES_COUNT filtered node tile files (.tls)"

# WayCutter5 needs .ntl files in ftiles that match way tile names (45x30 degree)
# NodeFilter produces .tls files with the same 45x30 degree tile names
# So we create .ntl files from .tls to match what WayCutter5 expects
echo "Creating .ntl files in ftiles for WayCutter5..."
for tls_file in ftiles/*.tls; do
    if [ -f "$tls_file" ]; then
        ntl_file="${tls_file%.tls}.ntl"
        cp "$tls_file" "$ntl_file"
    fi
done
echo "Created matching .ntl files for WayCutter5"

echo "Step 3: Merging relations..."
${JAVA} -cp "../${BROUTER_JAR}" -Ddeletetmpfiles=false -DuseDenseMaps=true btools.mapcreator.RelationMerger \
    ways.dat \
    ways2.dat \
    relations.dat \
    "../${BROUTER_PROFILES}/lookups.dat" \
    "../${BROUTER_PROFILES}/trekking.brf" \
    "../${BROUTER_PROFILES}/softaccess.brf"

# Verify ways2.dat was created
if [ ! -f "ways2.dat" ]; then
    echo "ERROR: RelationMerger did not create ways2.dat!"
    exit 1
fi
echo "Created ways2.dat ($(ls -lh ways2.dat | awk '{print $5}'))"

echo "Step 4: Cutting ways..."
mkdir -p waytiles

# WayCutter expects .tlf files from ftiles
# NodeFilter produces .tls files, so we need .tlf files
# Also, WayCutter5 will later need .ntl files (it constructs filename from way filename)
# So we create both .tlf (for WayCutter) and .ntl (for WayCutter5) from .tls
echo "Creating .tlf and .ntl files from .tls for WayCutter and WayCutter5..."
for tls_file in ftiles/*.tls; do
    if [ -f "$tls_file" ]; then
        # Create .tlf for WayCutter
        tlf_file="${tls_file%.tls}.tlf"
        cp "$tls_file" "$tlf_file"
        # Create .ntl for WayCutter5 (it expects .ntl files matching way tile names)
        ntl_file="${tls_file%.tls}.ntl"
        cp "$tls_file" "$ntl_file"
    fi
done

${JAVA} -cp "../${BROUTER_JAR}" -Ddeletetmpfiles=false -DuseDenseMaps=true btools.mapcreator.WayCutter \
    ftiles \
    ways2.dat \
    waytiles

# Verify waytiles were created
WTILES_COUNT=$(find waytiles -name "*.wtl" 2>/dev/null | wc -l)
if [ "$WTILES_COUNT" -eq 0 ]; then
    echo "ERROR: WayCutter produced no .wtl files!"
    echo "This might indicate the ways2.dat file is empty or there's an issue with the data."
    exit 1
fi
echo "Created $WTILES_COUNT way tile files (.wtl)"

echo "Step 5: Creating 5x5 degree way tiles..."
mkdir -p waytiles55
${JAVA} -cp "../${BROUTER_JAR}" -Ddeletetmpfiles=false -DuseDenseMaps=true btools.mapcreator.WayCutter5 \
    ftiles \
    waytiles \
    waytiles55 \
    bordernids.dat

# Verify waytiles55 were created
WT5_COUNT=$(find waytiles55 -name "*.wt5" 2>/dev/null | wc -l)
if [ "$WT5_COUNT" -eq 0 ]; then
    echo "ERROR: WayCutter5 produced no .wt5 files!"
    echo "This might indicate there are no matching node files in ftiles or waytiles are empty."
    exit 1
fi
echo "Created $WT5_COUNT 5x5 way tile files (.wt5)"

echo "Step 6: Creating 5x5 degree node tiles (including POI nodes)..."
mkdir -p nodes55

# NodeCutter expects .tlf files from ftiles (same as WayCutter)
# We already created .tlf files in ftiles for WayCutter, so they should be available
${JAVA} -cp "../${BROUTER_JAR}" -Ddeletetmpfiles=false -DuseDenseMaps=true btools.mapcreator.NodeCutter \
    ftiles \
    nodes55

# Verify nodes55 were created
N5D_COUNT=$(find nodes55 -name "*.n5d" 2>/dev/null | wc -l)
if [ "$N5D_COUNT" -eq 0 ]; then
    echo "ERROR: NodeCutter produced no .n5d files!"
    echo "This might indicate there are no .tlf node files in ftiles."
    exit 1
fi
echo "Created $N5D_COUNT 5x5 node tile files (.n5d)"

echo "Step 7: Unifying positions (without elevation data)..."
mkdir -p unodes55
# PosUnifier requires SRTM path, use empty string if not available
SRTM_PATH="${SRTM_PATH:-}"
if [ -z "$SRTM_PATH" ]; then
    # Create a dummy directory for SRTM if not provided
    mkdir -p dummy_srtm
    SRTM_PATH="dummy_srtm"
fi
${JAVA} -cp "../${BROUTER_JAR}" -Ddeletetmpfiles=false -DuseDenseMaps=true btools.mapcreator.PosUnifier \
    nodes55 \
    unodes55 \
    bordernids.dat \
    bordernodes.dat \
    "${SRTM_PATH}"

# Verify unodes55 were created
U5D_COUNT=$(find unodes55 -name "*.u5d*" 2>/dev/null | wc -l)
if [ "$U5D_COUNT" -eq 0 ]; then
    echo "ERROR: PosUnifier produced no .u5d files!"
    echo "This might indicate there are no .n5d files in nodes55 or bordernids.dat is missing."
    exit 1
fi
echo "Created $U5D_COUNT unified node files (.u5d*)"

echo "Step 8: Linking ways and creating segments..."
mkdir -p segments
echo "WayLinker inputs:"
echo "  - waytiles55: $(find waytiles55 -name '*.wt5' 2>/dev/null | wc -l) .wt5 files"
echo "  - unodes55: $(find unodes55 -name '*.u5d*' 2>/dev/null | wc -l) .u5d* files"
echo "  - bordernodes.dat: $(ls -lh bordernodes.dat 2>/dev/null | awk '{print $5}' || echo 'missing')"
echo "  - restrictions.dat: $(ls -lh restrictions.dat 2>/dev/null | awk '{print $5}' || echo 'missing')"
echo ""

${JAVA} -cp "../${BROUTER_JAR}" -DuseDenseMaps=true btools.mapcreator.WayLinker \
    unodes55 \
    waytiles55 \
    bordernodes.dat \
    restrictions.dat \
    "../${BROUTER_PROFILES}/lookups.dat" \
    "../${BROUTER_PROFILES}/all.brf" \
    segments \
    rd5

# Verify segments were created
RD5_COUNT=$(find segments -name "*.rd5" 2>/dev/null | wc -l)
if [ "$RD5_COUNT" -eq 0 ]; then
    echo "ERROR: WayLinker produced no .rd5 files!"
    echo "This might indicate:"
    echo "  - No matching .wt5 and .u5d files"
    echo "  - File naming mismatch between way and node files"
    echo "  - WayLinker encountered an error"
    exit 1
fi
echo "Created $RD5_COUNT segment files (.rd5)"

echo ""
echo "Step 9: Copying generated segments to test_segments..."
echo "All generated segments:"
ls -lh segments/*.rd5 2>/dev/null | head -20 || echo "No .rd5 files found in segments directory"

SEGMENTS_COPIED=0

# Required segments for car route (10°E-35°E, 59°N-75°N)
# Including far northern Norway segments for proper connectivity
REQUIRED_SEGMENTS=("E10_N55.rd5" "E10_N60.rd5" "E15_N60.rd5" "E15_N65.rd5" "E20_N60.rd5" "E20_N65.rd5" "E25_N60.rd5" "E25_N65.rd5" "E25_N70.rd5" "E30_N60.rd5" "E30_N65.rd5" "E30_N70.rd5" "E35_N65.rd5")

for segment_file in "${REQUIRED_SEGMENTS[@]}"; do
    if [ -f "segments/${segment_file}" ]; then
        cp "segments/${segment_file}" "../test_segments/${segment_file}"
        echo "Successfully regenerated ../test_segments/${segment_file}"
        ls -lh "../test_segments/${segment_file}"
        SEGMENTS_COPIED=$((SEGMENTS_COPIED + 1))
    fi
done

# Also copy any other segments that might be useful (E5_N60, E25_N70, E30_N70, E35_N65, etc.)
for extra_seg in "E5_N60.rd5" "E25_N70.rd5" "E30_N70.rd5" "E35_N65.rd5"; do
    if [ -f "segments/${extra_seg}" ]; then
        cp "segments/${extra_seg}" "../test_segments/${extra_seg}"
        echo "Successfully regenerated ../test_segments/${extra_seg}"
        ls -lh "../test_segments/${extra_seg}"
        SEGMENTS_COPIED=$((SEGMENTS_COPIED + 1))
    fi
done

if [ $SEGMENTS_COPIED -eq 0 ]; then
    echo ""
    echo "Warning: No target segments found in generated segments."
    echo "This might be because:"
    echo "  1. The Norway extract doesn't cover those exact 5x5 degree tiles"
    echo "  2. The segments were generated with different names"
    echo ""
    echo "To find which segments cover your route area, check the coordinates:"
    echo "  E10_N55 covers: 10-15°E, 55-60°N"
    echo "  E10_N60 covers: 10-15°E, 60-65°N"
    echo "  E15_N60 covers: 15-20°E, 60-65°N"
    echo "  E15_N65 covers: 15-20°E, 65-70°N"
    echo "  E20_N60 covers: 20-25°E, 60-65°N"
    echo "  E20_N65 covers: 20-25°E, 65-70°N"
    echo "  E25_N60 covers: 25-30°E, 60-65°N"
    echo "  E25_N65 covers: 25-30°E, 65-70°N"
    echo "  E25_N70 covers: 25-30°E, 70-75°N"
    echo "  E30_N60 covers: 30-35°E, 60-65°N"
    echo "  E30_N65 covers: 30-35°E, 65-70°N"
    echo "  E30_N70 covers: 30-35°E, 70-75°N"
    echo "  E35_N65 covers: 35-40°E, 65-70°N"
    echo ""
    echo "Keeping tmp directory for inspection: $TMP_DIR"
    echo "You can check segments/ directory manually"
    echo "Available segments:"
    ls -lh segments/*.rd5 2>/dev/null | head -20 || echo "No .rd5 files found"
    exit 1
fi

cd ..
rm -rf "$TMP_DIR"

echo ""
echo "Done! The segments have been regenerated with POI node data."
echo "POI nodes (water points, cabins, etc.) are now included in the segments."

