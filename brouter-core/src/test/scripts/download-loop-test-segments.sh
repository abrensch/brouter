#!/bin/bash
#
# Downloads .rd5 segment tiles needed for the loop quality test suite.
# Non-interactive — suitable for CI. Skips tiles already present.
#
# Usage:
#   ./download-loop-test-segments.sh [output-dir]
#   Default output-dir: ./segments4

set -e

BASE_URL="http://brouter.de/brouter/segments4/"
DEFAULT_DIR="./segments4"
DOWNLOAD_DIR="${1:-$DEFAULT_DIR}"

# Tiles needed for the 5 loop quality test regions:
#   Dreieich (8.72E, 50.0N)         -> E5_N50.rd5
#   Berlin   (13.4E, 52.5N)         -> E10_N50.rd5
#   Innsbruck (11.4E, 47.3N)        -> E10_N45.rd5
#   Nice     (7.27E, 43.7N)         -> E5_N40.rd5
#   Lozere   (3.5E, 44.5N)          -> E0_N40.rd5
TILES=(
  "E5_N50.rd5"
  "E10_N50.rd5"
  "E10_N45.rd5"
  "E5_N40.rd5"
  "E0_N40.rd5"
)

mkdir -p "$DOWNLOAD_DIR"

fetched=0
skipped=0
failed=0

for tile in "${TILES[@]}"; do
  filepath="${DOWNLOAD_DIR}/${tile}"
  if [ -f "$filepath" ]; then
    echo "SKIP  $tile (already present)"
    skipped=$((skipped + 1))
    continue
  fi

  echo -n "FETCH $tile ... "
  if curl -fSL --progress-bar -o "$filepath" "${BASE_URL}${tile}"; then
    echo "OK"
    fetched=$((fetched + 1))
  else
    echo "FAILED"
    rm -f "$filepath"
    failed=$((failed + 1))
  fi
done

echo ""
echo "Summary: $fetched fetched, $skipped skipped, $failed failed"
echo "Segments dir: $DOWNLOAD_DIR"

if [ "$failed" -gt 0 ]; then
  exit 1
fi
