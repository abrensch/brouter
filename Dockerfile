FROM gradle:jdk17-jammy as build

RUN mkdir /tmp/brouter
WORKDIR /tmp/brouter
COPY . .
RUN ./gradlew clean build

FROM openjdk:17.0.1-jdk-slim
COPY --from=build /tmp/brouter/brouter-server/build/libs/brouter-*-all.jar /brouter.jar
COPY --from=build /tmp/brouter/misc/scripts/standalone/server.sh /bin/
COPY --from=build /tmp/brouter/misc/* /profiles2

CMD /bin/server.sh

