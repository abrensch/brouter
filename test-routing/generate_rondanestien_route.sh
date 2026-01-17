#!/bin/bash
# Script to generate Rondanestien route with DNT huts
# Route: Målia to Hjerkinn via Jammerdalsbu and Nedre Dørålseter
# Based on OSM relation: https://www.openstreetmap.org/api/0.6/relation/1572954

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

# Route coordinates:
# Start: Målia (node 845742898): 61.3823782, 10.7473345
# Via 1: Jammerdalsbu: 61.5857799, 10.3536473
# Via 2: Nedre Dørålseter: (will find exact coordinates)
# End: Hjerkinn (node 339607873): 62.0910527, 9.6421663

# Using approximate coordinates for Nedre Dørålseter based on route alignment
# Will use coordinates near route between Jammerdalsbu and Hjerkinn
DORAALSETER_LAT=61.85
DORAALSETER_LON=10.15

echo "Generating Rondanestien route (Målia -> Jammerdalsbu -> Nedre Dørålseter -> Hjerkinn)..."
echo "Route includes DNT huts with locks for members"

# Generate route with all filters enabled
java -jar "${BROUTER_JAR}" \
    "${SEGMENT_DIR}" \
    "${PROFILE_DIR}" \
    0 \
    trekking \
    "10.7473345,61.3823782|10.3536473,61.5857799|${DORAALSETER_LON},${DORAALSETER_LAT}|9.6421663,62.0910527" \
    "alternativeidx=0&format=gpx&exportWaypoints=1" \
    "enable_hiking_rest=1.0&enable_water_point_filter=1.0&enable_camping_rules=1.0" \
    2>/dev/null

if [ $? -eq 0 ]; then
    if [ -f testtrack0.gpx ]; then
        mv testtrack0.gpx test-routing/rondanestien-test-route.gpx
        echo "Route generated successfully: test-routing/rondanestien-test-route.gpx"
        
        # Check for rest stops and POI information
        REST_STOPS=$(grep -c "<wpt.*rest_stop" test-routing/rondanestien-test-route.gpx 2>/dev/null || echo "0")
        CABINS=$(grep -c "Cabin:" test-routing/rondanestien-test-route.gpx 2>/dev/null || echo "0")
        WATER=$(grep -c "Water:" test-routing/rondanestien-test-route.gpx 2>/dev/null || echo "0")
        
        echo "Rest stops found: $REST_STOPS"
        echo "Cabin POIs found: $CABINS"
        echo "Water POIs found: $WATER"
        
        # Show a few waypoint names to verify POI search
        echo ""
        echo "Sample waypoint names:"
        grep "<name>.*Rest Stop" test-routing/rondanestien-test-route.gpx | head -3
    else
        echo "Error: GPX file not generated"
        exit 1
    fi
else
    echo "Error: Route generation failed"
    exit 1
fi

