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
RUN groupadd -r appuser && useradd -r -s /bin/false -g appuser appuser

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
# Copy configuration and scripts
COPY --chown=appuser:appuser src/main/resources/config.json ./config/
COPY --chown=appuser:appuser docker/init-scripts/ ./init-scripts/
COPY --chown=appuser:appuser src/main/resources/application-docker.properties ./application.properties

# Switch to non-root user
USER appuser

# Add these health-related environment variables
ENV WAIT_TIMEOUT=60
ENV WAIT_SLEEP_INTERVAL=5
ENV HEALTH_CHECK_INTERVAL=30

# Uncomment and modify the HEALTHCHECK
HEALTHCHECK --interval=30s \
           --timeout=3s \
           --start-period=40s \
           --retries=3 \
    CMD curl -f http://localhost:8880/health || exit 1

EXPOSE 8880

# Use improved Java options
ENTRYPOINT ["/bin/sh", "-c", "\
    ./init-scripts/wait-for-services.sh && \
    java -XX:+UseContainerSupport \
         -XX:MaxRAMPercentage=75.0 \
         -Djava.security.egd=file:/dev/./urandom \
         -Dvertx.environment=docker \
         -jar /app/app.jar"]