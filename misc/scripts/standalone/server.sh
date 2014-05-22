#!/bin/sh

# BRouter standalone server
# java -cp brouter.jar btools.brouter.RouteServer <segmentdir> <profile-map> <customprofiledir> <port> <maxthreads>

JAVA_OPTS="-Xmx128M -Xms128M -Xmn8M"
CLASSPATH=../brouter.jar

java $JAVA_OPTS -cp $CLASSPATH btools.server.RouteServer ../segments2 ../profiles2 ../customprofiles 17777 1
