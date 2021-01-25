#!/bin/sh

cd "$(dirname "$0")"

# If path is unset, search in this location
SEGMENTSPATH=${SEGMENTSPATH:-"../segments4"}

# Otherwise try to locoate the directory inside the source checkout
if [ ! -e "$SEGMENTSPATH" ]; then
    SEGMENTSPATH="../../segments4"
fi

if [ ! -e "$SEGMENTSPATH" ]; then
    echo '$SEGMENTSPATH not found, aborting...'
    exit 1
fi

SEGMENTSPATH=$(realpath "$SEGMENTSPATH")

# Segment files required for navigation inside Poland
SEGMENTS="E10_N45.rd5 E10_N50.rd5 E15_N45.rd5 E15_N50.rd5 E20_N45.rd5 E20_N50.rd5"

# Download each file, overwriting existing ones
echo "Downloading files to $SEGMENTSPATH ..."

for SEGMENT in ${SEGMENTS}
do
    wget brouter.de/brouter/segments4/$SEGMENT -O $SEGMENTSPATH/$SEGMENT
done

echo "Done"
exit 0
