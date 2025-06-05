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
   git clone https://github.com/yourusername/fhir-validation-service.git
   cd fhir-validation-service
   ```

2. Start the services using Docker Compose:
   ```bash
   docker-compose up -d
   ```

   This will start:
   - The FHIR validation service on port 8080
   - PostgreSQL database
   - Redis cache
   - Prometheus monitoring (optional)
   - PostgreSQL and Redis exporters for monitoring

3. Verify the service is running:
   ```bash
   curl http://localhost:8080/health
   ```

### Manual Setup

1. Start PostgreSQL:
   ```bash
   make run-postgres
   ```

2. Start Redis:
   ```bash
   make run-redis
   ```

3. Build and run the application:
   ```bash
   mvn clean package
   java -jar target/fhir-validation-service-*.jar
   ```

## How to Use

### Validating a FHIR Resource

Send a POST request to the `/validate` endpoint with a JSON body containing the FHIR resource:

```bash
curl -X POST http://localhost:8080/validate \
  -H "Content-Type: application/json" \
  -d '{
    "resource": {
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
    },
    "options": {
      "profile": "http://hl7.org/fhir/StructureDefinition/Patient"
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

### Validation API
- `POST /validate` - Validate a FHIR resource
  - Request body: JSON with `resource` and optional `options`
  - Query parameters: `version` (R4 or R5, default is R4)

### Profile API
- `GET /profiles` - List available profiles
- `GET /profiles/{id}` - Get a specific profile
- `POST /profiles` - Add a new profile

### Implementation Guide API
- `GET /ig` - List available implementation guides
- `GET /ig/{id}` - Get a specific implementation guide
- `POST /ig` - Add a new implementation guide
- `DELETE /ig/{id}` - Remove an implementation guide

## Configuration

The application can be configured through environment variables:

- `PG_HOST` - PostgreSQL host (default: localhost)
- `PG_PORT` - PostgreSQL port (default: 5432)
- `PG_DATABASE` - PostgreSQL database name (default: fhir_validator)
- `PG_USER` - PostgreSQL username (default: postgres)
- `PG_PASSWORD` - PostgreSQL password (default: password)
- `REDIS_HOST` - Redis host (default: localhost)
- `REDIS_PORT` - Redis port (default: 6379)
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


