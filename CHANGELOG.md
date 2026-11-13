# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project
uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [1.0.0] - 2026-11-13

First release. Version numbering starts here: earlier development builds are not supported. The database schema is
created by one baseline migration (V15); a database from a development build keeps its migration history, and one
whose schema stops before V15 is refused at startup.

- FHIR resource validation for STU3, R4, R4B and R5, against the core specification or one included IG package per
  request (`?ig=name#version`), with its dependencies; results as a JSON summary or a FHIR `OperationOutcome`.
- IG packages: upload, register from the FHIR package registries (exact versions, `latest` and version series), size
  and safety checks before unpacking, and validator contexts included per FHIR version and shared by every instance.
- Terminology servers per included IG, chosen from the official HL7 terminology registry, with a per-request budget
  and a code cache.
- Organisations owning API keys, packages and contexts, with private and shareable content; a system and a trial
  organisation; `fv-admin` command line for organisations, keys, sharing and usage.
- API keys (hashed, optional expiry, last use recorded), administrative network allowlist and trusted proxies.
- Usage accounting to the log and/or the database.
- Operator switches: `FV_ENABLED_FHIR_VERSIONS` (serve fewer FHIR versions) and `FV_GLOBAL_DISABLED_ACL_SECURITY`
  (public mode), each guarded against an accidental fleet-wide change.
- Horizontal scaling on one PostgreSQL database, configuration propagation without restarts, liveness, readiness
  and Prometheus metrics, graceful shutdown; container images for `linux/amd64` and `linux/arm64`.
