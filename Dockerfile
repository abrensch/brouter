FROM maven:3-jdk-7-alpine as build
WORKDIR /build
COPY . .
RUN mvn clean install -pl '!brouter-routing-app' '-Dmaven.javadoc.skip=true' -DskipTests

FROM openjdk:7-jre-alpine
WORKDIR /app
RUN mkdir segments profiles customprofiles
COPY ./brouter-routing-app/assets/profiles2.zip profiles.zip
RUN unzip profiles.zip -d profiles && rm profiles.zip

COPY --from=build /build/brouter-server/target/brouter*with-dependencies.jar brouter-server.jar

EXPOSE 17777

CMD java -cp brouter-server.jar btools.server.RouteServer segments profiles customprofiles 17777 1
