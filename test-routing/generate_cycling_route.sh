#!/bin/bash
# Script to generate cycling test route
# Route: 59.9063856, 10.7696719 to 63.4268907, 10.3955186 
#        via 60.7927994, 11.0431314 and 61.5484373, 9.966244

set -e

cd "$(dirname "$0")/.."

# Use online segments from brouter.de for better coverage
# Note: Online segments may not include POI nodes, but have complete road network
SEGMENT_DIR="test_segments"

# Download online segments if needed (they have better road network coverage)
if [ -x "../download_online_segments.sh" ]; then
    echo "Checking/updating online segments for better coverage..."
    ../download_online_segments.sh "$SEGMENT_DIR" > /dev/null 2>&1
fi
PROFILE_DIR="misc/profiles2"
BROUTER_JAR="brouter-server/build/libs/brouter-1.7.8-all.jar"

# Check if required segments exist
REQUIRED_SEGMENTS=("E10_N55.rd5" "E10_N60.rd5")
MISSING_SEGMENTS=()

for segment in "${REQUIRED_SEGMENTS[@]}"; do
    if [ ! -f "${SEGMENT_DIR}/${segment}" ]; then
        MISSING_SEGMENTS+=("${segment}")
    fi
done

if [ ${#MISSING_SEGMENTS[@]} -gt 0 ]; then
    echo "Missing segments: ${MISSING_SEGMENTS[*]}"
    echo ""
    echo "Download from: https://brouter.de/brouter/segments4/"
    echo "Or use the regenerate script to generate from OSM data"
    echo ""
    echo "Required segments:"
    for seg in "${MISSING_SEGMENTS[@]}"; do
        echo "  - ${seg}"
    done
    exit 1
fi

# Generate route
echo "Generating cycling route..."
# BRouter writes to testtrack0.gpx (or testtrack.gpx) in current directory
# Redirect stderr to suppress debug output, but keep stdout for any errors
java -jar "${BROUTER_JAR}" \
    "${SEGMENT_DIR}" \
    "${PROFILE_DIR}" \
    0 \
    fastbike \
    "10.7696719,59.9063856|11.0431314,60.7927994|9.966244,61.5484373|10.3955186,63.4268907" \
    "alternativeidx=0&format=gpx&exportWaypoints=1" \
    "enable_cycling_rest=1.0&enable_hiking_rest=1.0&enable_water_point_filter=1.0&enable_camping_rules=1.0" \
    2>/dev/null

if [ $? -eq 0 ]; then
    # BRouter creates testtrack0.gpx for alternative 0, or testtrack.gpx for default
    if [ -f "testtrack0.gpx" ]; then
        mv testtrack0.gpx test-routing/cycling-test-route.gpx
        echo "Route generated successfully: test-routing/cycling-test-route.gpx"
    elif [ -f "testtrack.gpx" ]; then
        mv testtrack.gpx test-routing/cycling-test-route.gpx
        echo "Route generated successfully: test-routing/cycling-test-route.gpx"
    else
        echo "Error: GPX file not found. BRouter may have failed."
        exit 1
    fi
    
    # Check if it's a valid GPX file
    if grep -q "<gpx" test-routing/cycling-test-route.gpx 2>/dev/null; then
        echo "GPX file appears valid"
        # Count track points
        TRACKPOINTS=$(grep -c "<trkpt" test-routing/cycling-test-route.gpx 2>/dev/null || echo "0")
        echo "Track points: ${TRACKPOINTS}"
    else
        echo "Warning: Output may contain errors. Check the file contents."
    fi
else
    echo "Error generating route"
    exit 1
fi

