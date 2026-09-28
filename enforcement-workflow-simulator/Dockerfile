# Image for the :enforcement-workflow-simulator subproject.
#
# Mirrors the root service Dockerfile's conventions (BASE_IMAGE arg, non-root "app" user)
# but ships the simulator's boot jar instead of the service jar. The build context is the
# REPO ROOT, not this directory, so CI can reuse the jar Gradle already produced:
#
#   docker build -f enforcement-workflow-simulator/Dockerfile .
#
# This image is for non-live environments only. The application itself refuses to start if
# a live profile is active — see LiveEnvironmentGuard.
ARG BASE_IMAGE
FROM ${BASE_IMAGE:-eclipse-temurin:25-jre}

# curl is used by the HEALTHCHECK below (and is handy for debugging, as in the root image).
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd -r app && useradd -r -g app app
WORKDIR /app

# Overridable so CI can pass an exact filename rather than relying on the glob.
ARG JAR_FILE=enforcement-workflow-simulator/build/libs/*.jar
COPY ${JAR_FILE} /app/app.jar

USER app
EXPOSE 8091

# This module has no Spring Boot Actuator and every endpoint is POST, so there is no GET
# that returns 2xx to probe. curl WITHOUT -f exits 0 on any HTTP response (404 included)
# and non-zero when the connection is refused — so this checks that the listener is up,
# which is exactly what a liveness probe should check.
#
# Deliberately NOT /auth/token: that would mint a bearer token on every probe, and issued
# tokens are only evicted when they are looked up (ADR-004), so probe tokens would
# accumulate for the lifetime of the container.
HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
  CMD curl -s -o /dev/null "http://localhost:${SERVER_PORT:-8091}/" || exit 1

# Exec form so the JVM is PID 1 and receives SIGTERM directly on container stop.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
