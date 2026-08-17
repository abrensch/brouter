#!/bin/sh

cd "$(dirname "$0")"
# BRouter standalone server
# java -cp brouter.jar btools.brouter.RouteServer <segmentdir> <profile-map> <customprofiledir> <port> <maxthreads> [bindaddress]

# maxRunningTime is the request timeout in seconds, set to 0 to disable timeout.
# maxRequestLength is the maximum accepted request body size in bytes. The default
# is sized to allow PUT/POST bodies slightly above 5 MiB.
BROUTER_JAVA_XMX=${BROUTER_JAVA_XMX:-"256M"}
BROUTER_JAVA_XMS=${BROUTER_JAVA_XMS:-$BROUTER_JAVA_XMX}
BROUTER_JAVA_XMN=${BROUTER_JAVA_XMN:-"16M"}
BROUTER_MAX_RUNNING_TIME=${BROUTER_MAX_RUNNING_TIME:-"300"}
BROUTER_MAX_REQUEST_LENGTH=${BROUTER_MAX_REQUEST_LENGTH:-"6291456"}
BROUTER_USE_RFC_MIME_TYPE=${BROUTER_USE_RFC_MIME_TYPE:-"false"}

DEFAULT_JAVA_OPTS="-Xmx$BROUTER_JAVA_XMX -Xms$BROUTER_JAVA_XMS -Xmn$BROUTER_JAVA_XMN -DmaxRunningTime=$BROUTER_MAX_RUNNING_TIME -DmaxRequestLength=$BROUTER_MAX_REQUEST_LENGTH -DuseRFCMimeType=$BROUTER_USE_RFC_MIME_TYPE"
JAVA_OPTS=${JAVA_OPTS:-$DEFAULT_JAVA_OPTS}

# If paths are unset, first search in locations matching the directory structure
# as found in the official BRouter zip archive
CLASSPATH=${CLASSPATH:-"../brouter.jar"}
SEGMENTSPATH=${SEGMENTSPATH:-"../segments4"}
PROFILESPATH=${PROFILESPATH:-"../profiles2"}
CUSTOMPROFILESPATH=${CUSTOMPROFILESPATH:-"../customprofiles"}

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
if [ ! -e "$CUSTOMPROFILESPATH" ]; then
    CUSTOMPROFILESPATH="../customprofiles"
fi

exec java $JAVA_OPTS -cp $CLASSPATH btools.server.RouteServer "$SEGMENTSPATH" "$PROFILESPATH" "$CUSTOMPROFILESPATH" 17777 1 $BINDADDRESS
