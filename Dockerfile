# --- Build Stage ---
FROM gradle:jdk21 AS build
WORKDIR /app
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY src ./src
RUN gradle build -Dquarkus.package.jar.type=uber-jar --no-daemon -x test

# --- Runtime Stage ---
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
COPY --from=build /app/build/quarkus-app/lib/ ./lib/
COPY --from=build /app/build/quarkus-app/*.jar ./
COPY --from=build /app/build/quarkus-app/app/ ./app/
COPY --from=build /app/build/quarkus-app/quarkus/ ./quarkus/

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "quarkus-run.jar"]
