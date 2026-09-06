# The build stage runs on the build host for every target platform, because
# the jar is architecture independent. Only the runtime stage runs per
# platform.
FROM --platform=$BUILDPLATFORM eclipse-temurin:17-jdk-jammy AS build

WORKDIR /brouter
COPY . .

RUN sed -i 's/\r$//' gradlew && chmod +x gradlew
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon clean build

FROM eclipse-temurin:17-jre-jammy

RUN groupadd --gid 1000 brouter \
    && useradd --uid 1000 --gid brouter --home-dir /app --no-create-home brouter \
    && mkdir -p /app /segments4 /customprofiles \
    && chown brouter:brouter /app /segments4 /customprofiles

WORKDIR /app
COPY --from=build /brouter/brouter-server/build/libs/brouter-*-all.jar /app/brouter.jar
COPY --from=build --chmod=755 /brouter/misc/scripts/standalone/server.sh /app/server.sh
COPY --from=build /brouter/misc/profiles2/ /profiles2/

ENV CLASSPATH=/app/brouter.jar \
    SEGMENTSPATH=/segments4 \
    PROFILESPATH=/profiles2 \
    CUSTOMPROFILESPATH=/customprofiles

USER brouter

EXPOSE 17777

CMD ["/app/server.sh"]
