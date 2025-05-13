# Stage 1: Build with Maven (multi-platform compatible)
FROM --platform=$BUILDPLATFORM maven:3.8.6-eclipse-temurin-11 AS builder
WORKDIR /app
COPY pom.xml .
# Cache dependencies
RUN mvn dependency:go-offline -B
COPY src ./src
# Build application (will use host platform JDK)
RUN mvn clean package -DskipTests

# Stage 2: Runtime image
FROM --platform=$TARGETPLATFORM eclipse-temurin:11-jre-jammy
WORKDIR /app

# Install necessary tools for health check and service waiting
RUN apt-get update && \
    apt-get install -y curl netcat-openbsd && \
    rm -rf /var/lib/apt/lists/*

# Copy built JAR (platform-independent)
COPY --from=builder /app/target/fhir-validation-service-*.jar ./app.jar
COPY src/main/resources/config.json ./config/
COPY docker/init-scripts/ ./init-scripts/
RUN chmod +x ./init-scripts/*.sh

# Copy Docker-specific application properties
COPY src/main/resources/application-docker.properties ./application.properties

# Health check
HEALTHCHECK --interval=30s --timeout=3s \
  CMD curl -f http://localhost:8080/health || exit 1

EXPOSE 8080
ENTRYPOINT ["/bin/sh", "-c", "./init-scripts/wait-for-services.sh && java -jar app.jar"]
