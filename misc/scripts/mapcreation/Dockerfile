# Step 1: Build BRouter and dependencies (Osmosis, PbfParser)

FROM maven:3-jdk-8 as build
RUN mkdir -p /brouter-build
WORKDIR /brouter-build
COPY . .
RUN mvn package -pl brouter-server -am -Dmaven.javadoc.skip=true

RUN apt-get update \
    && apt-get install -y ca-certificates curl \
    && mkdir -p /osmosis-src && curl --location --output /osmosis-src/osmosis-0.48.3.tgz https://github.com/openstreetmap/osmosis/releases/download/0.48.3/osmosis-0.48.3.tgz \
    && tar -xvzf /osmosis-src/osmosis-0.48.3.tgz -C /osmosis-src

WORKDIR /brouter-build/misc/pbfparser
RUN javac -d . -cp "/brouter-build/brouter-server/target/brouter-server-1.6.1-jar-with-dependencies.jar:/osmosis-src/lib/default/protobuf-java-3.12.2.jar:/osmosis-src/lib/default/osmosis-osm-binary-0.48.3.jar" *.java \
    && jar cf pbfparser.jar btools/**/*.class

# Step 2: collect needed JARs + processing script and run script

FROM openjdk:8-jdk-buster

RUN apt-get update \
    && apt-get install -y osmctools

WORKDIR /brouter
COPY misc/profiles2/* /brouter/
COPY misc/pbfparser /brouter/pbfparser
COPY --from=build /brouter-build/brouter-server/target/brouter-server-1.6.1-jar-with-dependencies.jar brouter.jar
COPY --from=build /osmosis-src/lib/default/protobuf-java-3.12.2.jar /brouter/pbfparser/protobuf.jar
COPY --from=build /osmosis-src/lib/default/osmosis-osm-binary-0.48.3.jar /brouter/pbfparser/osmosis.jar
COPY --from=build /brouter-build/misc/pbfparser/pbfparser.jar /brouter/pbfparser/pbfparser.jar
COPY misc/scripts/mapcreation/process_pbf_planet_docker.sh /brouter/process_pbf_planet_docker.sh

VOLUME ["/brouter-tmp"]
VOLUME ["/planet"]
VOLUME ["/srtm"]
VOLUME ["/segments"]

WORKDIR /brouter
ENTRYPOINT ["/bin/bash", "/brouter/process_pbf_planet_docker.sh"]
