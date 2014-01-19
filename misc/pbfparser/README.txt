The pbf-parse is not included in the regular source tree
to avoid the library dependencies to "osmosis" and "protobuf"

In order to run the mapcreator from a pbf-file (as it is
done in the process_pbf_planet.sh script included in
the git-repo), you have to build yourself the "pbfparser.jar"
by doing the following:

-> get osmosis.jar
-> get protobuf.jar
-> copy the brouter...with_dependencies.jar and name it brouter.jar
-> compile the PBF-Parser using:
   javac -d . -cp protobuf.jar;osmosis.jar;brouter.jar *.java
-> pack protobuf.jar + osmosis.jar + btools/**.class alltogether in a jar "pbfparser.jar"

Alternativly, you can run the Mapcreator against a *xml.bz2 Database-Extract,
then you don't need the pbf-parser.
