# API Documentation

The FHIR Validation Service API validates FHIR resources (STU3, R4, R4B, R5) against the core specification or one
included Implementation Guide (IG) package, and manages stored IG packages and validator contexts. This page is
generated from the
[OpenAPI definition](https://github.com/nazrulworld/fhir-validation-service/blob/main/src/main/resources/openapi.yaml)
(API version **1.0.0**); when the two disagree, the OpenAPI file wins.

**Base URL**: `http://localhost:8880` (replace with your server). To install the service, see [[Installation Guide]].

## Conventions

- **Errors** are RFC 9457 problem documents (`application/problem+json`), see [Problem](#problem). Every 4xx from
  validation is problem JSON, whatever `Accept` asks for.
- **Correlation id**: every response carries an `X-Request-Id` header. Error bodies repeat it as `correlationId`.
- **FHIR version** path parameter (`{version}`): `STU3`, `R4`, `R4B` or `R5`, case-insensitive. A deployment may
  serve fewer (`FV_ENABLED_FHIR_VERSIONS`); `GET /health` lists the served versions. Anything else is rejected with
  an `unsupported-fhir-version` problem.
- **IG package references** in query parameters are `name#version`. URL-encode `#` as `%23`: an unencoded `#` is cut
  off by the client.
- **Propagation**: administrative changes are committed to the shared database and applied by every instance within
  the propagation window, without restarts.
- **Overload**: when an instance is at its admission limit, or not ready, it answers **503** with a `Retry-After`
  header.

## Authentication

API keys are sent as `Authorization: Bearer <key>`. Every key belongs to an organisation.

| Operations | Key required |
|------------|--------------|
| Validation and package reads (`/dependencies`, `/conformance`) | a **VALIDATE** or **ADMIN** key |
| Upload or register an IG, `POST`/`PATCH`/`DELETE /{version}/validator`, `PATCH /igs/{name}/{version}` | an **ADMIN** key, from an allowed network when `FV_AUTH_ADMIN_CIDRS` is set |
| Health and metrics | none |

- **401** `unauthorized`: no valid key (missing, malformed, unknown, revoked or expired); the response doesn't say
  which, and carries `WWW-Authenticate: Bearer`.
- **403** `forbidden`: the key's role doesn't allow the operation, the key's organisation is deactivated, an
  ownership transfer by a caller that isn't a system organisation administrator, or (administrative operations) a
  client address outside `FV_AUTH_ADMIN_CIDRS`.

**Organisations and sharing.** Stored IG packages and validator contexts are owned by an organisation and are either
shareable (usable by every caller) or private (the owner and the system organisation only). A package or context the
caller may not use is answered exactly like one that does not exist (**404** `package-not-found`); a dependency the
caller may not use gives **424** `dependency-not-found` without naming it.

**Public mode.** An operator can turn access control off for a whole deployment (`FV_GLOBAL_DISABLED_ACL_SECURITY`).
No operation then needs a key and the `Authorization` header is ignored: validation and package reads run as the
`trial validator` key, administrative operations as the `system administrator` key. The admin network allowlist still
applies. Packages and contexts are always stored as shareable (`shareable: false` is ignored with a warning in
`warnings`), and `GET /health` reports `security.aclSecurity: "disabled"`.

## Endpoints

| Method | Path | Purpose | Auth |
|--------|------|---------|------|
| GET | [`/health`](#get-health) | Detailed health report | none |
| GET | [`/health/liveness`](#get-healthliveness) | Liveness probe | none |
| GET | [`/health/readiness`](#get-healthreadiness) | Readiness probe | none |
| GET | [`/metrics`](#get-metrics) | Prometheus metrics | none |
| POST | [`/{version}/validate`](#post-versionvalidate) | Validate a FHIR resource | validate |
| POST | [`/{version}/validator`](#post-versionvalidator) | Include a stored IG version (build a validator context) | admin |
| PATCH | [`/{version}/validator`](#patch-versionvalidator) | Change sharing, ownership or terminology of a context | admin |
| DELETE | [`/{version}/validator`](#delete-versionvalidator) | Exclude an included IG version | admin |
| POST | [`/igs/upload`](#post-igsupload) | Upload and store an IG package | admin |
| POST | [`/igs/register`](#post-igsregister) | Fetch and store an IG package | admin |
| PATCH | [`/igs/{name}/{version}`](#patch-igsnameversion) | Change sharing or transfer ownership of a stored package | admin |
| GET | [`/igs/{name}/{version}/dependencies`](#get-igsnameversiondependencies) | Dependencies of a stored IG package | validate |
| GET | [`/igs/{name}/{version}/conformance`](#get-igsnameversionconformance) | File listing of a stored IG package (deprecated) | validate |

---

### Health and metrics

#### GET /health

Detailed health report: database connectivity, the readiness report, the terminology allowlist state, the served FHIR
versions and inactive contexts, and the access control state. Operation id `healthApiHealth`.

| Status | Body |
|--------|------|
| 200 | [HealthResponse](#healthresponse): service is healthy |
| 503 | [HealthResponse](#healthresponse): service is unhealthy |

#### GET /health/liveness

Liveness probe: reports only that the process is alive, and never depends on the database or validators. Operation id
`healthApiLiveness`.

| Status | Body |
|--------|------|
| 200 | `{"status": "UP", "timestamp": 1761465600000}` (`timestamp` is int64 epoch milliseconds) |

#### GET /health/readiness

Readiness probe. **200** when this instance can serve traffic: the database is reachable, every enabled FHIR version
has a usable validator (none out of date), and the instance is not draining. Otherwise **503** with the reasons.
Use it for readiness probes and load balancer health checks. Operation id `healthApiReadiness`.

| Status | Body |
|--------|------|
| 200 | [ReadinessResponse](#readinessresponse): ready |
| 503 | [ReadinessResponse](#readinessresponse): not ready, see `reasons` |

#### GET /metrics

Prometheus metrics in the text exposition format (`text/plain`), for example `fv_validations_total`,
`fv_validation_seconds`, `fv_validation_pending` and `fv_config_revision`. Operation id `metricsApiScrape`.

---

### Validation

#### POST /{version}/validate

Validate a FHIR resource against exactly one included IG package (the `ig` parameter), its dependencies and the core
specification, or against the core specification alone when `ig` is absent. Validation never builds a context: name
an IG version that was included with [`POST /{version}/validator`](#post-versionvalidator). Operation id
`validationApiValidate`.

**Parameters**

| Name | In | Required | Description |
|------|----|----------|-------------|
| `version` | path | yes | FHIR version: `STU3`, `R4`, `R4B` or `R5` |
| `ig` | query | no | The one IG package to validate against: `name#version`, `name#latest` or `name` (at most 256 characters). Without a version, or with `latest`, the highest included version you may use is chosen and the response reports it. A version series such as `name#1.2.x` is rejected (400). The trial validator key, and every caller in public mode, can name shareable packages only |
| `profile` | query | no | Comma-separated canonical URLs of profiles to validate against, in addition to `meta.profile`. Each must be defined by the chosen `ig` package, its dependencies or the core specification (the core specification only when `ig` is absent), and is matched exactly against `url` or `url\|version`. Profiles are never downloaded. At most 10 distinct profiles, each at most 1024 characters |

**Request body** (required): the resource, as `application/json` or `application/fhir+json`, or as
`application/xml`, `application/fhir+xml` or `text/xml`.

**Response format** follows the `Accept` header:

| `Accept` | Response body |
|----------|---------------|
| absent, `*/*` or `application/json` | [ValidationResponse](#validationresponse) |
| `application/fhir+json` | FHIR `OperationOutcome` (JSON) |
| `application/fhir+xml` or `application/xml` | FHIR `OperationOutcome` (XML) |

**Response headers** (200)

| Header | Description |
|--------|-------------|
| `X-Validation-Context` | The IG package (`name#version`) the result came from, or `base` for the core-only context |
| `X-Validation-Context-Owner` | Short name of the organisation owning the context (the system organisation for `base`) |
| `X-Validation-Context-Shareable` | `true` or `false` |

**Terminology.** A context whose terminology mode is `server` or `ecosystem` checks codes that can't be decided from
its packages with an allowlisted terminology server. A code that could not be checked (server unavailable, time budget
used up, no permitted server) is reported as a warning, never an error, and the answer is still 200.

**Responses**

| Status | Meaning |
|--------|---------|
| 200 | Validation completed, **whether the resource is valid or not**: check `valid` or the OperationOutcome issues |
| 400 | `invalid-resource` (the body is not FHIR, with `issues`); `invalid-request` (e.g. a malformed `ig`, profile limits, an `ig` package for another FHIR version, or a stored version that is not included for this FHIR version); `unknown-profile` (a requested profile is not available, with `unresolvedProfiles`; nothing is validated) |
| 401 | `unauthorized` |
| 403 | `forbidden`: the key's organisation is deactivated |
| 404 | `package-not-found`: the `ig` package is not stored, or the caller may not use it |
| 406 | `not-acceptable` |
| 413 | `payload-too-large`: body larger than `FV_HTTP_MAX_BODY_MB` |
| 415 | `unsupported-media-type` |
| 424 | `dependency-not-found`: a dependency of the `ig` package is not available to the caller (not named) |
| 500 | `internal-error` |
| 503 | `not-ready` (an included context is still being built or rebuilt), `overloaded` or `insufficient-capacity`, with `Retry-After` |

**Example**

```bash
curl -X POST 'http://localhost:8880/R4/validate?ig=hl7.fhir.dk.core%233.4.0' \
  -H "Authorization: Bearer $FV_VALIDATOR_API_KEY" \
  -H 'Content-Type: application/fhir+json' \
  --data '{"resourceType":"Patient","active":true}'
```

```json
{
  "valid": true,
  "messages": [],
  "fhirVersion": "R4",
  "appliedProfiles": [],
  "igPackage": "hl7.fhir.dk.core#3.4.0",
  "igOwner": "system",
  "igShareable": true
}
```

---

### Validator contexts

These operations need an ADMIN key. A change applies to every instance within the propagation window.

#### POST /{version}/validator

Include a stored IG version: adds a pinned validator context for `igPackageId#igPackageVersion` on every instance.
Other versions of the same package stay included and usable through `?ig=`. Including a version that is already
included changes nothing, and the response reports the existing owner and sharing. Nothing is downloaded. Operation id
`validatorApiIncludeIg`.

**Request body** (required, `application/json`):

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `igPackageId` | string | yes | | Package name |
| `igPackageVersion` | string | no | `latest` | An exact version, `latest` (the highest stored version), or a version series `N.x`, `N.x.x` or `N.N.x` (the highest matching stored version your organisation may use). The context is pinned to that concrete version; storing a newer matching version later does not change it |
| `shareable` | boolean | no | `true` | Sharing of a newly included context. `false` is allowed only when your organisation owns the package (or you are the system organisation). Ignored, with a warning, when the context is already included |
| `terminology` | [TerminologySettings](#terminologysettings) | no | | Terminology mode of the new context. Ignored, with a warning, for an already included context; change it with PATCH |

**Responses**

| Status | Meaning |
|--------|---------|
| 200 | IG included: [IncludeResponse](#includeresponse) |
| 400 | `invalid-request` (e.g. the package does not declare this FHIR version, a malformed version, or a highest series match for another FHIR version); `tx-server-not-registered` (the terminology server is not in the HL7 terminology registry for this FHIR version) |
| 401 | `unauthorized` |
| 403 | `forbidden` |
| 404 | `package-not-found`: not stored, no version matching the series, or not usable by the caller |
| 413 | `package-too-large`: the context would not fit in this instance's heap (refused before it is committed) |
| 422 | `fhir-version-disabled`: the FHIR version is supported but not enabled (`FV_ENABLED_FHIR_VERSIONS`) |
| 424 | `dependency-not-found`: a dependency is not available to the caller (not named) |
| 500 | `internal-error` |
| 503 | `tx-registry-unavailable` (the registry's list was never obtained and the server is not on the built-in fallback list), `overloaded` |

```bash
curl -H "Authorization: Bearer $FV_ADMIN_API_KEY" -H 'Content-Type: application/json' \
  --data '{"igPackageId":"hl7.fhir.dk.core","igPackageVersion":"3.4.0","terminology":{"mode":"none"}}' \
  http://localhost:8880/R4/validator
```

#### PATCH /{version}/validator

Change sharing, ownership or terminology of an included context. The context's owner or the system organisation may
change `shareable`; only the system organisation may transfer ownership (`owner`) to another active organisation.
Access changes take effect without a rebuild. A terminology change rebuilds the context on every instance; the old
context serves until the new one is ready. Also works for a context that is inactive because its FHIR version is not
enabled: the change is stored and applies when the version is enabled again. Operation id
`validatorApiChangeIncludedIg`.

**Parameters**: `version` (path), `ig` (query, required): the exact included version, `name#version`; no `latest`.

**Request body** (required): [AccessChangeRequest](#accesschangerequest).

**Responses** (checked in this order: 404, 403, 400)

| Status | Meaning |
|--------|---------|
| 200 | Changed: [IncludeAccessResponse](#includeaccessresponse) |
| 404 | `package-not-found`: not included, or the caller is neither the owner nor the system organisation |
| 403 | `forbidden`: a transfer by a caller that isn't the system organisation |
| 400 | `invalid-request` (body, unknown or inactive target organisation, a private context of a package owned by another organisation, a malformed terminology setting); `tx-server-not-registered` |
| 401 | `unauthorized` |
| 500 | `internal-error` |
| 503 | `tx-registry-unavailable`, `overloaded` |

```bash
curl -X PATCH -H "Authorization: Bearer $FV_ADMIN_API_KEY" -H 'Content-Type: application/json' \
  --data '{"terminology":{"mode":"ecosystem"}}' \
  'http://localhost:8880/R4/validator?ig=hl7.fhir.dk.core%233.4.0'
```

#### DELETE /{version}/validator

Exclude one `name#version` from the included set on every instance. The package stays stored, and its terminology
setting is deleted with the context. Validation requests naming it are refused with 400 `invalid-request` until it is
included again. Also works for a context that is inactive because its FHIR version is not enabled. Operation id
`validatorApiExcludeIg`.

**Parameters**: `version` (path), `ig` (query, required): the exact version to exclude, `name#version`; no `latest`.

| Status | Meaning |
|--------|---------|
| 200 | Excluded: [ExcludeResponse](#excluderesponse) |
| 400 | `invalid-request` |
| 401 | `unauthorized` |
| 403 | `forbidden` |
| 404 | `package-not-found`: not included, or the caller's organisation doesn't own its context and isn't the system organisation |
| 500 | `internal-error` |
| 503 | `overloaded` |

```bash
curl -X DELETE -H "Authorization: Bearer $FV_ADMIN_API_KEY" \
  'http://localhost:8880/R4/validator?ig=hl7.fhir.dk.core%233.4.0'
```

---

### IG packages

#### POST /igs/upload

Upload and store an IG package, owned by the caller's organisation. Uploading a `name#version` that another
organisation stored gives 409 `conflict`, unless the caller is the system organisation; a replacement keeps the stored
owner and sharing. Operation id `igPackageApiUploadIg`.

**Request body** (required, `multipart/form-data`):

| Field | Required | Description |
|-------|----------|-------------|
| `file` | yes | The IG package archive (`.tgz`), at most `FV_HTTP_MAX_BODY_MB`. Accepted part types: `application/octet-stream`, `application/gzip`, `application/x-gzip`, `application/x-tar`, `application/x-compressed-tar` |
| `shareable` | no | `true` (default) or `false`. Ignored when the version is already stored |

**Responses**

| Status | Meaning |
|--------|---------|
| 200 | Package stored: [IgRegistrationResponse](#igregistrationresponse) |
| 400 | `invalid-request`: invalid request or package |
| 401 | `unauthorized` |
| 403 | `forbidden` |
| 409 | `conflict`: another organisation stored this `name#version` |
| 413 | `package-too-large`, `payload-too-large` |
| 422 | `package-unsafe`: path traversal, too many entries, or too high an expansion ratio |
| 500 | `internal-error` |
| 503 | `overloaded`, with `Retry-After` |

```bash
curl -H "Authorization: Bearer $FV_ADMIN_API_KEY" \
  -F "file=@hl7.fhir.dk.core-3.4.0.tgz;type=application/gzip" \
  http://localhost:8880/igs/upload
```

#### POST /igs/register

Fetch and store an IG package, from the configured package registries (by `name` and `version`) or from an approved
https URL (`downloadUrl`). Downloads are limited to the hosts in `FV_PACKAGE_SOURCES`; any other host is refused
(`package-source-not-allowed`) without an outbound request. Operation id `igPackageApiRegisterIg`.

With `name` and no version (or `latest`), the registries are asked for the highest released version: if it is already
stored it is returned without downloading it again, otherwise it is downloaded and stored (with its dependencies when
`includeDependency` is true). If no registry can be consulted or none lists the package, the highest stored version
the caller may use is returned with a warning; 404 when there is none. A version series (`1.2.x`, `1.x.x`) resolves
to the highest matching registry release and then behaves exactly like a registration of that version.

Every package newly stored by the call, downloaded dependencies included, is owned by the caller's organisation with
`shareable` from the request (the package licence is not used). A version that is already stored keeps its owner and
sharing, and is answered 404 when the caller may not use it. A `downloadUrl` archive whose `name#version` another
organisation stored gives 409 `conflict`.

**Request body** (required, `application/json`):

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `downloadUrl` | string (uri) | | Approved https URL of the package archive |
| `name` | string | | Package name, e.g. `hl7.fhir.us.core` |
| `version` | string | `latest` | An exact version, `latest`, or a version series `N.x`, `N.x.x` or `N.N.x` (`x` or `X`; a release wins over a pre-release). At most 64 characters |
| `includeDependency` | boolean | `true` | Also fetch the package's dependencies (declared series or `latest` are resolved the same way) |
| `shareable` | boolean | `true` | Sharing of every package newly stored by this call |

**Responses**

| Status | Meaning |
|--------|---------|
| 200 | Package stored or returned: [IgRegistrationResponse](#igregistrationresponse) |
| 400 | `invalid-request` (e.g. a malformed version), `package-source-not-allowed` |
| 401 | `unauthorized` |
| 403 | `forbidden` |
| 404 | `package-not-found` |
| 409 | `conflict` |
| 413 | `package-too-large` |
| 422 | `package-unsafe` |
| 500 | `internal-error` |
| 503 | `overloaded`, with `Retry-After` |

```bash
curl -H "Authorization: Bearer $FV_ADMIN_API_KEY" -H 'Content-Type: application/json' \
  --data '{"name":"hl7.fhir.us.core","version":"7.0.0"}' \
  http://localhost:8880/igs/register
```

#### PATCH /igs/{name}/{version}

Change sharing or transfer ownership of a stored IG package. The owner or the system organisation may change
`shareable`; only the system organisation may transfer ownership (`owner`) to another active organisation. Takes
effect on every instance within the propagation window; no validator context is rebuilt. Operation id
`igPackageApiChangeAccess`.

**Request body** (required): [AccessChangeRequest](#accesschangerequest).

**Responses** (checked in this order: 404, 403, 400)

| Status | Meaning |
|--------|---------|
| 200 | Changed: [IgAccessResponse](#igaccessresponse) |
| 404 | `package-not-found`: not stored, or the caller is neither the owner nor the system organisation |
| 403 | `forbidden`: a transfer by a caller that isn't the system organisation |
| 400 | `invalid-request`: body, unknown or inactive target organisation |
| 401 | `unauthorized` |
| 500 | `internal-error` |
| 503 | `overloaded` |

```bash
curl -X PATCH -H "Authorization: Bearer $FV_ADMIN_API_KEY" -H 'Content-Type: application/json' \
  --data '{"shareable":false}' http://localhost:8880/igs/acme.core/1.0.0
```

#### GET /igs/{name}/{version}/dependencies

Declared dependencies of a stored IG package. Operation id `igPackageApiGetDependenciesGraph`.

| Status | Body |
|--------|------|
| 200 | `{"name": "<package>", "version": "<version>", "dependencies": ["<string>", ...]}` |
| 401 | `unauthorized` |
| 403 | `forbidden` |
| 404 | `package-not-found` |
| 500 | `internal-error` |
| 503 | `overloaded` |

#### GET /igs/{name}/{version}/conformance

> **Deprecated.** Despite its path, this is not a conformance report: it lists the files in a stored IG package.

Operation id `igPackageApiGenerateConformanceReport`.

| Status | Body |
|--------|------|
| 200 | `{"name": "<package>", "version": "<version>", "files": ["<string>", ...]}` |
| 401 | `unauthorized` |
| 403 | `forbidden` |
| 404 | `package-not-found` |
| 500 | `internal-error` |
| 503 | `overloaded` |

---

## Schemas

### ValidationResponse

| Field | Type | Description |
|-------|------|-------------|
| `valid` | boolean | Whether the resource passed validation |
| `messages` | array of [ValidationMessage](#validationmessage) | Issues found |
| `fhirVersion` | string | FHIR version used |
| `appliedProfiles` | array of string | Profiles resolved and applied: the requested ones in request order, then resolvable `meta.profile` entries; each once, never an unresolved URL |
| `igPackage` | string, nullable | The concrete IG package (`name#version`) used; `null` for the base context |
| `igOwner` | string | Short name of the organisation owning the context (the system organisation for base) |
| `igShareable` | boolean | Whether the context is shareable (`true` for base) |

### ValidationMessage

| Field | Type | Description |
|-------|------|-------------|
| `severity` | string | `FATAL`, `ERROR`, `WARNING` or `INFORMATION` |
| `location` | string | Where in the resource |
| `message` | string | Description of the issue |

### TerminologySettings

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `mode` | string | yes | `none`: codes are checked only against the context's packages and built-in code systems, with no outbound request. `server`: codes that can't be decided locally are checked with `server`. `ecosystem`: each code system is routed to the server the HL7 terminology registry names for it; `server`, when set, replaces the registry's default server (tx.fhir.org) |
| `server` | string | for `server` | Base URL, exactly as the registry lists it (at most 512 characters; not allowed for `none`) |

Servers must be open servers listed by the [HL7 terminology registry](https://ecosystem.hl7.org/tx-reg) for the FHIR
version. The registry lists none for STU3 and R4B. No other properties are allowed.

### AccessChangeRequest

At least one property; no others allowed.

| Field | Type | Description |
|-------|------|-------------|
| `shareable` | boolean | New sharing |
| `owner` | string | Transfer to this organisation (short name, `^[A-Za-z0-9_-]{2,32}$`). System organisation administrators only; the target must exist and be active |
| `terminology` | [TerminologySettings](#terminologysettings) | New terminology setting (`PATCH /{version}/validator` only) |

### IncludeResponse

| Field | Type | Description |
|-------|------|-------------|
| `status` | string | `success` |
| `fhirVersion` | string | FHIR version whose configuration changed |
| `key` | object | The context pinned: `fhirVersion`, `igPackage` (concrete `name#version`) |
| `requestedVersion` | string | The `igPackageVersion` sent (`latest`, an exact version or a series) |
| `revision` | int64 | Committed configuration revision |
| `included` | array of string | Packages included |
| `resolved` | array of string | Packages resolved as dependencies |
| `terminology` | [TerminologySettings](#terminologysettings) | The context's terminology setting |
| `owner` | string | Owning organisation (may be another organisation when it was already included) |
| `shareable` | boolean | The context's sharing |
| `warnings` | array of string | Warnings, if any |

### ExcludeResponse

| Field | Type | Description |
|-------|------|-------------|
| `status` | string | `success` |
| `fhirVersion` | string | FHIR version whose configuration changed |
| `revision` | int64 | Committed configuration revision |
| `excluded` | string | The `name#version` excluded |
| `included` | array of string | Versions still included |
| `warnings` | array of string | Warnings, if any |

### IncludeAccessResponse

| Field | Type | Description |
|-------|------|-------------|
| `status` | string | `success` |
| `fhirVersion` | string | FHIR version |
| `igPackage` | string | `name#version` |
| `revision` | int64 | Committed configuration revision |
| `terminology` | [TerminologySettings](#terminologysettings) | The context's terminology setting |
| `owner` | string | Short name of the owning organisation |
| `shareable` | boolean | `true` when every caller may use it |
| `warnings` | array of string | Warnings, if any |

### IgRegistrationResponse

| Field | Type | Description |
|-------|------|-------------|
| `status` | string | `success` |
| `name` | string | Package name |
| `version` | string | The concrete version stored or returned |
| `requestedVersion` | string | The `version` sent (`latest`, exact or a series); absent for `downloadUrl` and upload |
| `downloaded` | boolean | `true` when the archive was fetched in this call |
| `owner` | string | Short name of the organisation owning the stored package |
| `shareable` | boolean | The stored package's sharing (unchanged when it was already stored) |
| `warnings` | array of string | Warnings, if any |

### IgAccessResponse

| Field | Type | Description |
|-------|------|-------------|
| `status` | string | `success` |
| `name` | string | Package name |
| `version` | string | Package version |
| `owner` | string | Short name of the owning organisation |
| `shareable` | boolean | `true` when every caller may use it |
| `warnings` | array of string | Present when `shareable: false` was ignored because access control is disabled |

### HealthResponse

| Field | Type | Description |
|-------|------|-------------|
| `status` | string | `UP` or `DOWN` |
| `timestamp` | int64 | Epoch milliseconds |
| `postgres.status` | string | `UP` or `DOWN` |
| `postgres.responseTime` | int64 | Database round trip |
| `postgres.error` | string | Present when the database is unreachable |
| `readiness` | [ReadinessResponse](#readinessresponse) | The readiness report |
| `terminology.registry` | string | The HL7 terminology registry (fixed) |
| `terminology.allowlist` | object | Per FHIR version: `source` (`registry`, `stored-registry` or `built-in`), `fetchedAt`, `registryLastUpdate`, `servers` (count), `reason`. Readiness does not depend on it |
| `fhirVersions.served` | array of string | FHIR versions this instance serves |
| `fhirVersions.inactiveContexts` | object | Stored contexts that are inactive: `count`, `private` (counted only, never named), `items` (the first 100 shareable ones: `fhirVersion`, `igPackage`, `owner`, `reason` = `fhir-version-disabled`) |
| `security.aclSecurity` | string | `enabled` or `disabled` (public mode) |

### ReadinessResponse

| Field | Type | Description |
|-------|------|-------------|
| `status` | string | `UP` or `DOWN` |
| `timestamp` | int64 | Epoch milliseconds |
| `catalogRevision` | int64 | The package catalog revision this instance has loaded |
| `versions` | object | Per FHIR version: `appliedRevision`, `latestRevision`, `base` (state, packages, packageBytes) and `contexts` (keyed `name#version`: kind `pinned`, state `ACTIVE`, `BUILDING`, `STALE` or `ABSENT`, appliedRevision, packages, packageBytes, lastUsed, reason, terminology `{mode, server}`) |
| `reasons` | array of string | Why the instance is not ready |

`contexts` lists only contexts that are shareable and whose packages are all shareable; the others are summarised in
`privateContexts` (`count`, and `states` per state). Owners are never listed. Readiness counts the base and pinned
contexts only.

### Problem

RFC 9457 problem details, media type `application/problem+json`.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `type` | string (uri) | yes | Stable problem type, see below |
| `title` | string | yes | Short summary |
| `status` | integer | yes | HTTP status code |
| `detail` | string | no | Curated, safe text; never exception messages, class names or stack traces |
| `correlationId` | string | yes | Same as the `X-Request-Id` response header |
| `issues` | array of [ValidationMessage](#validationmessage) | no | Present only for type `invalid-resource` |
| `unresolvedProfiles` | array of string | no | Present only for type `unknown-profile`: every requested entry that did not resolve, after trimming and de-duplication, in request order |

```json
{
  "type": "https://nazrulworld.github.io/fhir-validation-service/problems/package-not-found",
  "title": "Package not found",
  "status": 404,
  "correlationId": "3f1c2a9e-5b7d-4e2a-9c1f-8d6b0a4e7c21"
}
```

Problem types (appended to `https://nazrulworld.github.io/fhir-validation-service/problems/`): `invalid-request`,
`invalid-resource`, `unknown-profile`, `unsupported-fhir-version`, `unauthorized`, `forbidden`, `not-found`,
`conflict`, `package-not-found`, `dependency-not-found`, `package-source-not-allowed`, `package-too-large`,
`package-unsafe`, `fhir-version-disabled`, `tx-server-not-registered`, `tx-registry-unavailable`,
`unsupported-media-type`, `not-acceptable`, `payload-too-large`, `overloaded`, `insufficient-capacity`, `not-ready`,
`internal-error`.
