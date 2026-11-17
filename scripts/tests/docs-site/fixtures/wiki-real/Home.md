# FHIR Validation Service Wiki

A horizontally scalable FHIR resource validation service built with Vert.x, HAPI FHIR and PostgreSQL.

It validates FHIR resources (STU3, R4, R4B, R5) against the base specification, stored Implementation Guide (IG)
packages and custom profiles. Any number of identical instances can run against one PostgreSQL database; changes to
IGs and profiles reach every instance within seconds, without restarts.

## Key features

- ✅ Validate FHIR resources against the base specification, IGs and custom profiles
- 📦 Store IG packages by upload, from a package registry, or from an approved https URL; dependencies are resolved
- 📄 Results as a JSON summary or a FHIR `OperationOutcome` (JSON or XML)
- ⚖️ Horizontal scaling: stateless instances share one PostgreSQL database
- 🔐 API keys for everything: ADMIN keys for IG management, VALIDATE keys for validation
- 🩺 Liveness, readiness and Prometheus metrics; graceful shutdown on SIGTERM
- 🐳 Container images for x86_64 and arm64 on `ghcr.io/nazrulworld/fhir-validation-service`

## Pages

- [[Installation Guide]]: Docker Compose with the published image, Compose from a source checkout, or the jar with
  your own PostgreSQL
- [[API Documentation]]: endpoints, requests and responses

## Reference (in the repository)

- [Configuration](https://github.com/nazrulworld/fhir-validation-service/blob/main/specs/001-service-hardening/contracts/configuration.md):
  every environment variable, with defaults and bounds
- [OpenAPI definition](https://github.com/nazrulworld/fhir-validation-service/blob/main/src/main/resources/openapi.yaml)
- [Changelog](https://github.com/nazrulworld/fhir-validation-service/blob/main/CHANGELOG.md)
- [Load tests](https://github.com/nazrulworld/fhir-validation-service/blob/main/perf/README.md)
- [Releases](https://github.com/nazrulworld/fhir-validation-service/releases): jar, `compose.yaml` and environment
  template

## System requirements

- Docker with Compose v2, **or** Java 17 or later with your own PostgreSQL
- PostgreSQL 16
- About 2 GB of memory per instance
- Java 17 and Maven, only to build from source

## Quick start

```bash
mkdir fhir-validation && cd fhir-validation
cat > .env <<EOF
FV_TAG=1.0.0
PG_PASSWORD=$(openssl rand -hex 24)
FV_ADMIN_API_KEY=$(openssl rand -hex 32)
EOF
# create compose.yaml as shown in the Installation Guide (option A), then:
docker compose up -d --wait
curl -fsS http://127.0.0.1:8880/health/readiness

curl -X POST http://127.0.0.1:8880/R4/validate \
  -H 'Content-Type: application/fhir+json' \
  --data '{"resourceType":"Patient","active":true}'
```

See the [[Installation Guide]] for the full steps, the other installation options, upgrades and backups.
