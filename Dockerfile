FROM gradle:jdk17-jammy AS build

RUN mkdir /tmp/brouter
WORKDIR /tmp/brouter
COPY [".", "."]
RUN dos2unix gradlew || sed -i 's/\r$//' gradlew
RUN ./gradlew clean build

FROM openjdk:17.0.1-jdk-slim
COPY --from=build /tmp/brouter/brouter-server/build/libs/brouter-*-all.jar /brouter.jar
COPY --from=build /tmp/brouter/misc/scripts/standalone/server.sh /bin/
COPY --from=build /tmp/brouter/misc/* /profiles2

CMD ["/bin/server.sh"]

