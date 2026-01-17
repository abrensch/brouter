#!/bin/bash
# Download online segments from brouter.de if they don't exist locally
# or if local segments are too small (missing data)

SEGMENT_DIR="${1:-test_segments}"
BASE_URL="https://brouter.de/brouter/segments4"

# Segments needed for routes
SEGMENTS=("E10_N55" "E10_N60" "E15_N60" "E15_N65" "E20_N60" "E20_N65" "E25_N60" "E25_N65" "E25_N70" "E30_N60" "E30_N65" "E30_N70" "E5_N60")

mkdir -p "$SEGMENT_DIR"

for seg in "${SEGMENTS[@]}"; do
    local_file="${SEGMENT_DIR}/${seg}.rd5"
    local_size=0
    
    if [ -f "$local_file" ]; then
        local_size=$(stat -f%z "$local_file" 2>/dev/null || stat -c%s "$local_file" 2>/dev/null || echo "0")
    fi
    
    # Download if missing or if local is suspiciously small (< 1MB for most segments, except very sparse areas)
    if [ ! -f "$local_file" ] || [ "$local_size" -lt 1000000 ]; then
        echo "Downloading ${seg}.rd5..."
        curl -s -f "${BASE_URL}/${seg}.rd5" -o "${local_file}.tmp"
        if [ -f "${local_file}.tmp" ] && [ -s "${local_file}.tmp" ]; then
            mv "${local_file}.tmp" "$local_file"
            size=$(ls -lh "$local_file" | awk '{print $5}')
            echo "  ✓ ${seg}.rd5 ($size)"
        else
            echo "  ✗ Failed to download ${seg}.rd5"
            rm -f "${local_file}.tmp"
        fi
    else
        echo "  ✓ ${seg}.rd5 already exists ($(ls -lh "$local_file" | awk '{print $5}'))"
    fi
done
