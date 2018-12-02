The pbf-parse is not included in the regular source tree
to avoid the library dependencies to "osmosis" and "protobuf"

In order to run the mapcreator from a pbf-file (as it is
done in the process_pbf_planet.sh script included in
the git-repo), you have to build yourself the "pbfparser.jar"
by doing the following:

-> get osmosis from https://bretth.dev.openstreetmap.org/osmosis-build/osmosis-latest.zip
-> copy lib/default/osmosis-osm-binary-*.jar in the archive to osmosis.jar in
this folder
-> copy lib/default/protobuf-java-*.jar in the archive to protobuf.jar in this
folder
-> copy the brouter-server/target/brouter-server...with-dependencies.jar to
brouter.jar in this folder
-> compile the PBF-Parser using:
   javac -d . -cp protobuf.jar:osmosis.jar:brouter.jar *.java
-> pack all the compiled class files together in a jar
"pbfparser.jar" with "jar cf pbfparser.jar btools/**/*.class"

Alternatively, just for testing you can run the Mapcreator against a *xml.bz2 Database-Extract,
then you don't need the pbf-parser. However, the XML-Parser does not (yet) parse
Turn-Restrictions, so really just for testing...
