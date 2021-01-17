FROM maven:3-jdk-7-alpine as build
WORKDIR /build
COPY brouter-codec brouter-codec
COPY brouter-core brouter-core
COPY brouter-expressions brouter-expressions
COPY brouter-map-creator brouter-map-creator
COPY brouter-mapaccess brouter-mapaccess
COPY brouter-routing-app brouter-routing-app
COPY brouter-server brouter-server
COPY brouter-util brouter-util
COPY settings.xml .
COPY pom.xml .
RUN mvn clean install -pl '!brouter-routing-app' '-Dmaven.javadoc.skip=true' -DskipTests

FROM openjdk:7-jre-alpine
WORKDIR /app
RUN mkdir segments4 customprofiles
COPY ./misc/profiles2 .
ENV CLASSPATH="./brouter-server.jar"
ENV SEGMENTSPATH="./segments4"
ENV PROFILESPATH="./profiles2"
ENV CUSTOMPROFILESPATH="./customprofiles"

COPY ./misc/scripts/standalone/server.sh .

COPY --from=build /build/brouter-server/target/brouter*with-dependencies.jar brouter-server.jar

EXPOSE 17777

CMD ./server.sh
