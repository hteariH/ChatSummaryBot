# --- Build Stage ---
FROM gradle:jdk25 AS build
WORKDIR /app
COPY build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle
COPY src ./src
RUN gradle bootJar --no-daemon

# --- Runtime Stage ---
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app
# ffmpeg strips the video track from Telegram video notes, keeping only audio for Gemini.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
