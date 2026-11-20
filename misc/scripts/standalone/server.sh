#!/bin/sh

cd "$(dirname "$0")"
# BRouter standalone server
# java -cp brouter.jar btools.brouter.RouteServer <segmentdir> <profile-map> <port> <maxthreads> [bindaddress]

# maxRunningTime is the request timeout in seconds, set to 0 to disable timeout
JAVA_OPTS="-Xmx128M -Xms128M -Xmn8M -DmaxRunningTime=300"

# If paths are unset, first search in locations matching the directory structure
# as found in the official BRouter zip archive
CLASSPATH=${CLASSPATH:-"../brouter.jar"}
SEGMENTSPATH=${SEGMENTSPATH:-"../segments4"}
PROFILESPATH=${PROFILESPATH:-"../profiles2"}

# Otherwise try to locate files inside the source checkout
if [ ! -e "$CLASSPATH" ]; then
    CLASSPATH="$(ls ../../../brouter-server/build/libs/brouter-*-all.jar | sort --reverse --version-sort | head --lines 1)"
fi
if [ ! -e "$SEGMENTSPATH" ]; then
    SEGMENTSPATH="../../segments4"
fi
if [ ! -e "$PROFILESPATH" ]; then
    PROFILESPATH="../../profiles2"
fi
java $JAVA_OPTS -cp $CLASSPATH btools.server.RouteServer "$SEGMENTSPATH" "$PROFILESPATH" 17777 1 $BINDADDRESS
