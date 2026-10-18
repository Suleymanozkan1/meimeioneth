# syntax=docker/dockerfile:1.6
# LexiFlow backend image. Build from the REPOSITORY ROOT (the server depends on :core):
#   docker build -f backend/Dockerfile -t lexiflow-server .
# or simply `docker compose up --build` inside backend/.

# ---------- build stage ----------
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /src

# Only what the server build needs (the Android :app module is not copied).
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY core ./core
COPY backend ./backend

# The Android app is not part of the server image: drop it from the build so no Android SDK is required.
RUN sed -i -E 's/"\:app"[[:space:]]*,?[[:space:]]*//' settings.gradle.kts \
 && chmod +x gradlew \
 && ./gradlew --no-daemon -Dorg.gradle.jvmargs=-Xmx1536m :backend:installDist \
 && rm -rf /src/backend/build/install/lexiflow-server/bin/*.bat

# ---------- runtime stage ----------
FROM eclipse-temurin:21-jre-jammy

RUN groupadd --system --gid 10001 lexiflow \
 && useradd --system --uid 10001 --gid lexiflow --home-dir /app --shell /usr/sbin/nologin lexiflow

WORKDIR /app
COPY --from=build --chown=root:root /src/backend/build/install/lexiflow-server /app

ENV PORT=8080 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom"

# Runs as an unprivileged user; the application files are owned by root and read-only for it.
USER 10001:10001
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/${PORT} && printf "GET /health HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3 && grep -q "\"status\":\"ok\"" <&3'

ENTRYPOINT ["/app/bin/lexiflow-server"]
