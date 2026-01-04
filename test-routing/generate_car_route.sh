#!/bin/bash
# Script to generate car test route
# Route: 59.589069, 10.2079291 to 61.9978187, 10.8879825 via 61.6468135, 10.462849

set -e

cd "$(dirname "$0")/.."

SEGMENT_DIR="test_segments"
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
echo "Generating car route..."
# BRouter writes to testtrack0.gpx (or testtrack.gpx) in current directory
# Redirect stderr to suppress debug output, but keep stdout for any errors
# Enable car breaks via profile parameter
java -jar "${BROUTER_JAR}" \
    "${SEGMENT_DIR}" \
    "${PROFILE_DIR}" \
    0 \
    car-vario \
    "10.2079291,59.589069|10.462849,61.6468135|10.8879825,61.9978187" \
    "alternativeidx=0&format=gpx" \
    "enable_car_breaks=1.0&enable_camping_rules=1.0" \
    2>/dev/null

if [ $? -eq 0 ]; then
    # BRouter creates testtrack0.gpx for alternative 0, or testtrack.gpx for default
    if [ -f "testtrack0.gpx" ]; then
        mv testtrack0.gpx test-routing/car-test-route.gpx
        echo "Route generated successfully: test-routing/car-test-route.gpx"
    elif [ -f "testtrack.gpx" ]; then
        mv testtrack.gpx test-routing/car-test-route.gpx
        echo "Route generated successfully: test-routing/car-test-route.gpx"
    else
        echo "Error: GPX file not found. BRouter may have failed."
        exit 1
    fi
    
    # Check if it's a valid GPX file
    if grep -q "<gpx" test-routing/car-test-route.gpx 2>/dev/null; then
        echo "GPX file appears valid"
        # Count track points
        TRACKPOINTS=$(grep -c "<trkpt" test-routing/car-test-route.gpx 2>/dev/null || echo "0")
        echo "Track points: ${TRACKPOINTS}"
    else
        echo "Warning: Output may contain errors. Check the file contents."
    fi
else
    echo "Error generating route"
    exit 1
fi

