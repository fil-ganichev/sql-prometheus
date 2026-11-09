FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /src
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY src src
COPY demo demo
COPY grafana-connector grafana-connector
RUN chmod +x gradlew && ./gradlew :demo:bootJar --no-daemon

FROM eclipse-temurin:17-jre-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/demo/build/libs/demo-1.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
