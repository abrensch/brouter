#!/bin/bash
set -e
/java/bin/javac -d . BRouterTests.java
rm -rf troutes
mkdir troutes
cd troutes
mkdir gpxold
mkdir gpxnew
mkdir csvold
mkdir csvnew
/java/bin/javac -d . ../BRouterTests.java
/java/bin/java -cp . BRouterTests ../examples.txt > dotests.sh
chmod +x dotests.sh
ln -s gpxnew gpx
ln -s csvnew csv
ln -s /var/www/brouter/segments2_lastrun segments
cp /usr/lib/cgi-bin/brouter64.jar brouter.jar
./dotests.sh
rm gpx
rm csv
rm segments
ln -s gpxold gpx
ln -s csvold csv
ln -s /var/www/brouter/segments2 segments
./dotests.sh
rm gpx
rm csv
rm segments
rm *.class
