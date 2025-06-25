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

# Stage 2: Runtime image
FROM --platform=$TARGETPLATFORM eclipse-temurin:17-jre-jammy as runtime

# Create non-root user
RUN groupadd -r fhiruser && useradd -r -s /bin/false -g fhiruser fhiruser

# Install necessary tools with version pinning
RUN set -ex && \
    apt-get update && \
    # Add PostgresSQL repository
    sh -c 'echo "deb https://apt.postgresql.org/pub/repos/apt jammy-pgdg main" > /etc/apt/sources.list.d/pgdg.list' && \
    curl -fsSL https://www.postgresql.org/media/keys/ACCC4CF8.asc | gpg --dearmor -o /etc/apt/trusted.gpg.d/postgresql.gpg && \
    apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        curl \
        netcat-openbsd \
        tzdata \
        postgresql-16 \
        postgresql-contrib-16 \
        gosu && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/* && \
    # Verify installations
    curl --version && \
    nc -h && \
    psql --version

# Add PostgreSQL environment variables
ENV PGDATA=/var/lib/postgresql/16/main
ENV PG_HOST=127.0.0.1
ENV PG_DATABASE=fhir_validator
ENV PG_USER=postgres
ENV PG_PASSWORD=password
ENV PG_PORT=5432

# Create PostgreSQL directories, set permissions and initialize
VOLUME ${PGDATA}
COPY --chown=postgres:postgres docker/postgres-init/init-database.sh /docker-entrypoint-initdb.d/
RUN chmod +x /docker-entrypoint-initdb.d/init-database.sh

# FHIR Validator Settings
ENV FV_SERVER_PORT=8880
ENV FV_JAR_NAME=fhir-validation-service-1.0-SNAPSHOT.jar

COPY --from=builder /app/target/${FV_JAR_NAME} /app/app.jar
COPY --chown=fhiruser:appuser src/main/resources/config.json /app/config/config.json

# Then update the environment to point to the correct config location
ENV CONFIG_PATH=/app/config/config.json
# Copy configuration and scripts
COPY --chown=fhiruser:fhiruser docker/init-scripts/ ./init-scripts/
RUN chmod +x ./init-scripts/*.sh
COPY --chown=fhiruser:fhiruser src/main/resources/application-docker.properties /app/application-docker.properties

COPY docker/docker-entrypoint.sh /docker-entrypoint.sh
RUN chmod +x ./docker-entrypoint.sh

# Add these health-related environment variables
ENV WAIT_TIMEOUT=60
ENV WAIT_SLEEP_INTERVAL=5
ENV HEALTH_CHECK_INTERVAL=30

# Uncomment and modify the HEALTHCHECK
HEALTHCHECK --interval=30s \
           --timeout=3s \
           --start-period=40s \
           --retries=3 \
    CMD curl -f http://localhost:${FV_SERVER_PORT:-8880}/health || exit 1

# Expose PostgreSQL port
EXPOSE ${FV_SERVER_PORT:-8880} 5432

# Update ENTRYPOINT to handle services properly
ENTRYPOINT ["/docker-entrypoint.sh"]