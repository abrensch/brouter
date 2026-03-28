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

# Runtime configuration — override via environment variables
ENV JAVA_OPTS="-Xmx512m -Xms256m" \
    BROUTER_PORT=17777 \
    BROUTER_MAX_THREADS=4

USER brouter

EXPOSE 17777

# Shell form so env vars are expanded at runtime
ENTRYPOINT exec java $JAVA_OPTS \
    -cp /brouter/brouter-server.jar \
    btools.server.RouteServer \
    /brouter/segments4 /brouter/profiles2 customprofiles \
    $BROUTER_PORT $BROUTER_MAX_THREADS

