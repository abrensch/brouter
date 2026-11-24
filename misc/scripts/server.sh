#!/bin/sh

cd "$(dirname "$0")"
# BRouter standalone server
# java -cp brouter.jar btools.brouter.RouteServer <segmentdir> <profile-map> <port> <maxthreads> [bindaddress]

# maxRunningTime is the request timeout in seconds, set to 0 to disable timeout
JAVA_OPTS="-Xmx128M -Xms128M -Xmn8M -DmaxRunningTime=300"
CLASSPATH="$(ls ../../brouter-server/build/libs/brouter-*-all.jar | sort --reverse --version-sort | head --lines 1)"
SEGMENTSPATH="../../segments"
PROFILESPATH="../profiles"
java $JAVA_OPTS -cp $CLASSPATH btools.server.RouteServer "$SEGMENTSPATH" "$PROFILESPATH" 2443 8 0.0.0.0
