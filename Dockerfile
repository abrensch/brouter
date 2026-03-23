# =============================================================================
# BRouter Server — Multi-arch Docker image (amd64 + arm64)
# Builds BRouter from source using Gradle, runs the standalone HTTP server.
# Uses Bellsoft Liberica to share base layers with the Spring Boot app image.
# =============================================================================
FROM bellsoft/liberica-openjdk-alpine:21@sha256:d939f0118532acc680d10dd0c0438cbffab5f028eaa0537ebb2bd97537329c74 AS build

WORKDIR /tmp/brouter
COPY . .
RUN chmod +x gradlew && \
    ./gradlew clean fatJar -x test --no-daemon

# --- Runtime stage ---
FROM bellsoft/liberica-openjre-alpine:25@sha256:87025d11840c8e873019b59f2d64a6b3da4bc5e126bb6d51aa3cd86f1b8b27be

RUN addgroup -S brouter && adduser -S brouter -G brouter

WORKDIR /brouter

# Copy the fat JAR
COPY --from=build /tmp/brouter/brouter-server/build/libs/brouter-*-all.jar /brouter/brouter-server.jar

# Copy default profiles (lookups.dat + *.brf)
COPY --from=build /tmp/brouter/misc/profiles2 /brouter/profiles2

# Segments directory — mount your .rd5 files here
# customprofiles is required as 3rd arg to RouteServer (user-defined profiles)
RUN mkdir -p /brouter/segments4 /brouter/customprofiles && chown -R brouter:brouter /brouter

VOLUME ["/brouter/segments4"]

USER brouter

# BRouter server listens on port 17777 by default
EXPOSE 17777

ENTRYPOINT ["java"]
CMD ["-Xmx512m", "-Xms256m", "-cp", "/brouter/brouter-server.jar", \
     "btools.server.RouteServer", "/brouter/segments4", "/brouter/profiles2", \
     "customprofiles", "17777", "1"]

