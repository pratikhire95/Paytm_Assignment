# syntax=docker/dockerfile:1
#
# Multi-stage build: Maven + JDK only in the build stage; the runtime image carries just a JRE and the jar.
# Build context is this directory (seatManagement/):   docker build -t seat-reservation seatManagement
#
# Secrets are NEVER baked into the image: ADMIN_TOKEN, TOKEN_SECRET and the database settings are read from
# environment variables at start-up (see ../docker-compose.yml, ../render.yaml and ../.env.example).
# For reproducible production builds pin both base images by digest (image@sha256:...).

# ----------------------------------------------------------------------------------------------- build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies first: this layer is rebuilt only when pom.xml changes.
COPY pom.xml .
RUN mvn -B -ntp -q dependency:go-offline

COPY src ./src
# Unit tests run in CI and on developer machines (`make test`, `make test-db`); the image build must not need a database.
RUN mvn -B -ntp -q -DskipTests package \
 && cp target/seat-management.jar /build/app.jar

# --------------------------------------------------------------------------------------------- runtime
FROM eclipse-temurin:21-jre-alpine

# Unprivileged user; nothing in the image is writable by it except /tmp (Tomcat's scratch directory).
RUN addgroup -S -g 10001 app \
 && adduser -S -u 10001 -G app -h /app -s /sbin/nologin app

WORKDIR /app
COPY --from=build --chown=10001:10001 /build/app.jar /app/app.jar

# SerialGC: smallest footprint for small containers (512 MB / fractional CPU), still plenty for this workload.
# Override JAVA_OPTS on bigger hosts, e.g. "-XX:+UseG1GC -XX:MaxRAMPercentage=75".
ENV PORT=8080
ENV JAVA_OPTS="-XX:+UseSerialGC -XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8"

USER 10001:10001
EXPOSE 8080

# Liveness only (does not touch the database): the orchestrator restarts a hung process, but a database
# blip does not turn into a restart loop. Readiness (which does check the database) is GET /readyz.
# start-period is generous because a cold JVM on a fractional CPU can take a minute to come up.
HEALTHCHECK --interval=15s --timeout=4s --start-period=150s --retries=4 \
  CMD wget -q -O /dev/null "http://127.0.0.1:${PORT}/healthz" || exit 1

# exec: the JVM becomes PID 1 and receives SIGTERM directly, so graceful shutdown (drain, then exit) works.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
