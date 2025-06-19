# syntax=docker/dockerfile:1.4

# Stage 1: Build with Maven (multi-platform compatible)
FROM --platform=$BUILDPLATFORM maven:3.8.6-eclipse-temurin-17 AS builder

# Build arguments
ARG JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

WORKDIR /app

# Cache dependencies more efficiently
COPY pom.xml mvnw* ./
COPY .mvn .mvn
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw dependency:go-offline -B

# Copy source and build
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw clean package -DskipTests
#    && \
#    ls -l target/ && \
#    mkdir -p target/dependency && \
#    (cd target/dependency; jar -xf ../*.jar)
# Stage 2: Runtime image
FROM --platform=$TARGETPLATFORM eclipse-temurin:17-jre-jammy as runtime

# Create non-root user
RUN groupadd -r fhiruser && useradd -r -s /bin/false -g fhiruser fhiruser

# Install necessary tools with version pinning
RUN set -ex && \
    apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        curl  \
        netcat-openbsd \
        tzdata && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/* && \
    # Verify installations
    curl --version && \
    nc -h

# Copy application files with improved layering
# COPY --from=builder /app/target/dependency/BOOT-INF/lib /app/lib
# COPY --from=builder /app/target/dependency/META-INF /app/META-INF
# COPY --from=builder /app/target/dependency/BOOT-INF/classes /app/classes
COPY --from=builder /app/target/fhir-validation-service-1.0-SNAPSHOT.jar /app/app.jar
COPY --chown=fhiruser:appuser src/main/resources/config.json /app/config/config.json

# Then update the environment to point to the correct config location
ENV CONFIG_PATH=/app/config/config.json
# Copy configuration and scripts
COPY --chown=fhiruser:fhiruser docker/init-scripts/ ./init-scripts/
RUN chmod +x ./init-scripts/*.sh
COPY --chown=fhiruser:appuser src/main/resources/application-docker.properties /app/application-docker.properties

# Switch to non-root user
USER fhiruser

# Add these health-related environment variables
ENV WAIT_TIMEOUT=60
ENV WAIT_SLEEP_INTERVAL=5
ENV HEALTH_CHECK_INTERVAL=30

# Uncomment and modify the HEALTHCHECK
HEALTHCHECK --interval=30s \
           --timeout=3s \
           --start-period=40s \
           --retries=3 \
    CMD curl -f http://localhost:${FHIR_VALIDATOR_PORT:-8880}/health || exit 1

EXPOSE ${FHIR_VALIDATOR_PORT:-8880}

# Use improved Java options
ENTRYPOINT ["/bin/sh", "-c", "\
    ./init-scripts/wait-for-services.sh && \
    java -XX:+UseContainerSupport \
         -XX:MaxRAMPercentage=75.0 \
         -Djava.security.egd=file:/dev/./urandom \
         -Dvertx.environment=docker \
         -Dapplication.config.path=/app/application-docker.properties \
         -Dvertx.config.path=${CONFIG_PATH} \
         -Dvertx.preferNativeTransport=true \
         -Dvertx.disableDnsResolver=false \
         -Dvertx.addressResolverOptions.servers=[\"8.8.8.8\",\"8.8.4.4\"] \
         -jar /app/app.jar"]