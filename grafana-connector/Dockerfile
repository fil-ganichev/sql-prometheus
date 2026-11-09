FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /src
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY src src
COPY demo demo
COPY grafana-connector grafana-connector
RUN chmod +x gradlew && ./gradlew :grafana-connector:installDist --no-daemon

FROM eclipse-temurin:17-jre-jammy
WORKDIR /opt/connector
COPY --from=build /src/grafana-connector/build/install/grafana-connector /opt/connector
EXPOSE 8081
ENTRYPOINT ["/opt/connector/bin/grafana-connector"]
