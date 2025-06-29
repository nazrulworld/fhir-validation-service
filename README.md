# FHIR Validation Service

A high-performance FHIR resource validation service built with Vert.x, PostgreSQL, and Redis.

## Overview

This FHIR Validation Service provides a RESTful API for validating FHIR resources against standard profiles and implementation guides. It supports both FHIR R4 and R5 versions and allows for custom profile validation.

Key features:
- Validate FHIR resources against standard and custom profiles
- Manage and use Implementation Guides (IGs)
- High-performance validation with Redis caching
- Persistent storage with PostgreSQL
- Monitoring with Prometheus
- Cross-platform compatibility (x86 and ARM architectures)
## // Will automatically fetch and cache `hl7.fhir.r4.core` and any others you load
npmSupport.loadPackageFromPackage("hl7.fhir.dk.core", "3.4.0");
## Prerequisites

- Java 11 or higher
- Docker and Docker Compose
- Maven (for local development)

## Installation

### Using Docker Compose (Recommended)

1. Clone the repository:
   ```bash
   git clone https://github.com/nazrulworld/fhir-validation-service.git
   cd fhir-validation-service
   ```

2. Start the services using Docker Compose:
   ```bash
   docker-compose up -d
   ```

   This will start:
   - The FHIR validation service on port 8080
   - PostgresSQL database
   - Prometheus monitoring (optional)
   - PostgresSQL exporters for monitoring

3. Verify the service is running:
   ```bash
   curl http://localhost:8880/health
   ```

### Manual Setup

1. Start PostgreSQL:
   ```bash
   make run-postgres
   ```

2. Build and run the application:
   ```bash
   mvn clean package
   java -jar target/fhir-validation-service-*.jar
   ```

## How to Use

### Validating a FHIR Resource

Send a POST request to the `/validate` endpoint with a JSON body containing the FHIR resource:

```bash
curl -X POST http://localhost:8880/R4/validate \
  -H "Content-Type: application/json" \
  -d '{
      "resourceType": "Patient",
      "id": "example",
      "name": [
        {
          "family": "Smith",
          "given": ["John"]
        }
      ],
      "gender": "male",
      "birthDate": "1970-01-01"
    }
  }'
```

### Managing Implementation Guides

Upload an Implementation Guide:
```bash
curl -X POST http://localhost:8080/ig \
  -H "Content-Type: application/json" \
  -d '{
    "url": "https://example.com/path/to/implementation-guide.tgz"
  }'
```

List available Implementation Guides:
```bash
curl http://localhost:8080/ig
```

## API Documentation

The service runs on `http://localhost:8880` by default and provides the following endpoints:

### Health Endpoints

#### Get Health Status
- **GET `/health`**
  - Checks the health status of the service including PostgreSQL connection
  - Responses:
    - `200` OK: Service is healthy
    - `503` Service Unavailable: Service is unhealthy
  - Response format:
    ```json
    {
      "status": "UP|DOWN",
      "timestamp": 1624536789,
      "postgres": {
        "status": "UP|DOWN",
        "responseTime": 123,
        "error": "error message (if any)"
      }
    }
    ```

#### Kubernetes Liveness Probe
- **GET `/health/liveness`**
  - Simple liveness check for Kubernetes health monitoring
  - Response: `200` OK with status "UP" if service is live

### FHIR Validation Endpoints

#### Validate FHIR Resource
- **POST `/{version}/validate`**
  - Validates a FHIR resource against specified profiles
  - Parameters:
    - `version` (path): FHIR version (STU3, R4, R4B, R5)
    - Content-Type (header): Supported formats:
      - `application/json` (default)
      - `application/xml`
      - `application/fhir+json`
      - `application/fhir+xml`
  - Request: FHIR resource in JSON/XML format
  - Responses:
    - `200` OK: Validation results
    - `400` Bad Request: Invalid request or validation error
  - Example response:
    ```json
    {
      "valid": true,
      "messages": [
        {
          "severity": "information",
          "location": "Patient.name",
          "message": "..."
        }
      ]
    }
    ```

### Implementation Guide Management

#### Include IG for Validation
- **POST `/{version}/include-ig`**
  - Includes an Implementation Guide for validation
  - Parameters:
    - `version` (path): FHIR version
  - Request body:
    ```json
    {
      "igPackageId": "package.id",
      "igPackageVersion": "latest"
    }
    ```

#### Upload IG Package
- **POST `/igs/upload`**
  - Upload and register an IG package file
  - Request: `multipart/form-data` with file (max 20MB)

#### Register IG Package
- **POST `/igs/register`**
  - Register an IG package from URL
  - Request body:
    ```json
    {
      "downloadUrl": "https://example.com/ig-package",
      "name": "package-name",
      "version": "latest",
      "includeDependency": true
    }
    ```

#### Get IG Dependencies
- **GET `/igs/{name}/{version}/dependencies`**
  - Retrieves dependency graph for specified IG package

#### Generate Conformance Report
- **GET `/igs/{name}/{version}/conformance`**
  - Generates conformance report for specified IG package

### Profile Management

#### Register FHIR Profile
- **POST `/{version}/register-profile`**
  - Registers a FHIR profile for validation
  - Parameters:
    - `version` (path): FHIR version
  - Request body:
    ```json
    {
      "url": "http://example.com/fhir/StructureDefinition/profile"
    }
    ```
  - Response:
    ```json
    {
      "status": "success",
      "profileUrl": "http://example.com/fhir/StructureDefinition/profile"
    }
    ```

### Error Responses

All error responses follow a standard format:
## Configuration

The application can be configured through environment variables:

- `PG_HOST` - PostgresSQL host (default: localhost)
- `PG_PORT` - PostgresSQL port (default: 5432)
- `PG_DATABASE` - PostgresSQL database name (default: fhir_validator)
- `PG_USER` - PostgresSQL username (default: postgres)
- `PG_PASSWORD` - PostgresSQL password (default: password)
- `HTTP_PORT` - HTTP server port (default: 8080)
- `FHIR_VERSION` - Default FHIR version (default: R4)

## Development

### Building the Project

```bash
mvn clean package
```

### Running Tests

```bash
mvn test
```

## References

- [FHIR Official Website](https://www.hl7.org/fhir/)
- [HAPI FHIR Library](https://hapifhir.io/)
- [Vert.x Framework](https://vertx.io/)
- [Official FHIR Validator](https://validator.fhir.org/)

{
"name" : "hl7.fhir.dk.core",
"version" : "3.4.0",
"tools-version" : 3,
"type" : "IG",
"date" : "20250201191800",
"license" : "CC0-1.0",
"canonical" : "http://hl7.dk/fhir/core",
"url" : "http://hl7.dk/fhir/core/3.4.0",
"title" : "HL7 FHIR Implementation Guide: DK Core",
"description" : "A FHIR Implementation Guide for the Danish common needs across healthcare sectors (built Sat, Feb 1, 2025 19:18+0100+01:00)",
"fhirVersions" : ["4.0.1"],
"dependencies" : {
"hl7.fhir.r4.core" : "4.0.1",
"hl7.terminology.r4" : "6.2.0",
"hl7.fhir.uv.extensions.r4" : "5.1.0",
"hl7.fhir.uv.phd" : "1.1.0",
"hl7.fhir.uv.ipa" : "1.0.0"
},
"author" : "HL7 Denmark",
"maintainers" : [
{
"name" : "HL7 Denmark",
"email" : "jenskristianvilladsen@gmail.com",
"url" : "http://www.hl7.dk"
}
],
"directories" : {
"lib" : "package",
"example" : "example"
},
"jurisdiction" : "urn:iso:std:iso:3166#DK"
}