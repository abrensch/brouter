#!/bin/sh

SEGMENTS="E10_N45.rd5 E10_N50.rd5 E15_N45.rd5 E15_N50.rd5 E20_N45.rd5 E20_N50.rd5"

for SEGMENT in ${SEGMENTS}
do
    wget brouter.de/brouter/segments4/$SEGMENT -O $SEGMENTSPATH/$SEGMENT
done