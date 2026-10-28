# syntax=docker/dockerfile:1.7

# --- Stage 1: build the shaded jar on the native build platform -------------------------------------------------
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build

COPY pom.xml mvnw ./
COPY .mvn .mvn
# Downloads Maven itself and the dependencies; retried, since a network blip on repo.maven.apache.org otherwise fails
# the whole build.
RUN --mount=type=cache,target=/root/.m2 \
    for attempt in 1 2 3; do \
      ./mvnw -B -q dependency:go-offline && exit 0; \
      [ "$attempt" = 3 ] || { echo "Maven download failed (attempt $attempt of 3); retrying" >&2; sleep $((attempt * 10)); }; \
    done; \
    exit 1

COPY src ./src
# Packaged into the jar root (pom.xml <resources>).
COPY LICENSE.md ./
# Tests run in CI before the image is built (see .github/workflows).
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -DskipTests \
    && cp target/fhir-validation-service-*.jar /build/app.jar

# License and third-party notices, taken from the jar so the image carries exactly what the jar carries
# (specs/002-polyform-noncommercial-license/contracts/distribution-layout.md).
COPY docker/licenses/IMAGE-COMPONENTS.md /build/notices/app/licenses/
WORKDIR /build/notices/jar
RUN jar xf /build/app.jar LICENSE.md NOTICE META-INF/NOTICE META-INF/THIRD-PARTY.txt \
        META-INF/THIRD-PARTY-CLARIFICATIONS.md META-INF/third-party-licenses \
    && mv LICENSE.md /build/notices/app/ \
    && mv NOTICE /build/notices/app/licenses/NOTICE-ROOT \
    && mv META-INF/* /build/notices/app/licenses/ \
    && chmod -R a-w,a+rX /build/notices/app

# --- Stage 2: runtime — the service JVM only (no database, no shell supervisor) ---------------------------------
FROM eclipse-temurin:17-jre-jammy AS runtime

ARG VERSION=dev
ARG REVISION=unknown
LABEL org.opencontainers.image.title="fhir-validation-service" \
      org.opencontainers.image.description="Vert.x-based FHIR validation service" \
      org.opencontainers.image.source="https://github.com/nazrulworld/fhir-validation-service" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.licenses="PolyForm-Noncommercial-1.0.0" \
      org.opencontainers.image.authors="Md Nazrul Islam <email2nazrul@gmail.com>"

# tini: PID 1 that forwards signals to the JVM; curl: container health check.
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends tini curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 fhir \
    && useradd --system --uid 10001 --gid fhir --no-create-home --shell /usr/sbin/nologin fhir

WORKDIR /app
COPY --from=builder --chown=root:root --chmod=0444 /build/app.jar /app/app.jar
# /app/LICENSE.md and /app/licenses/ (files 0444, directories 0555, set in the builder).
COPY --from=builder --chown=root:root /build/notices/app/ /app/

ENV FV_HTTP_PORT=8880 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

USER 10001:10001
EXPOSE 8880

HEALTHCHECK --interval=15s --timeout=3s --start-period=120s --retries=4 \
    CMD curl -fsS "http://127.0.0.1:${FV_HTTP_PORT}/health/readiness" > /dev/null || exit 1

ENTRYPOINT ["/usr/bin/tini", "--"]
# exec: the JVM replaces the shell, so SIGTERM from tini reaches it directly.
CMD ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
