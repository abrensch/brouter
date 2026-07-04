FROM gradle:jdk17-jammy AS build

WORKDIR /brouter
COPY . .

RUN dos2unix gradlew || sed -i 's/\r$//' gradlew
RUN ./gradlew clean build

FROM openjdk:17.0.1-jdk-slim

RUN mkdir /customprofiles

WORKDIR /app
COPY --from=build /brouter/brouter-server/build/libs/brouter-*-all.jar /app/brouter.jar
COPY --from=build /brouter/misc/scripts/standalone/server.sh /app/server.sh
COPY --from=build /brouter/misc/* /profiles2/

RUN sed -i 's/\r$//' /app/server.sh && chmod +x /app/server.sh


ENV CLASSPATH=/app/brouter.jar \
    SEGMENTSPATH=/segments4 \
    PROFILESPATH=/profiles2 \
    CUSTOMPROFILESPATH=/customprofiles

EXPOSE 17777

CMD ["sh", "-c", "/app/server.sh"]

