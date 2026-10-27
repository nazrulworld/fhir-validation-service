# Contract: HTTP API changes (1.0.0)

`src/main/resources/openapi.yaml` stays the single source of truth (Principle IV). This document
lists the **changes** the implementation must make to it. Each change lands in `openapi.yaml`
before or together with its code. Breaking changes are marked ⚠ and listed in `CHANGELOG.md`
under 1.0.0.

## Security schemes (new)

```yaml
components:
  securitySchemes:
    apiKey:            # Authorization: Bearer <key>
      type: http
      scheme: bearer
security: []           # default: anonymous; set per operation below
```

| Operation | Security | Required role |
|-----------|----------|---------------|
| `healthApiHealth`, `healthApiLiveness`, `healthApiReadiness` (new), `metrics` (new) | none | — |
| `validationApiValidate` | `apiKey` (optional when `FV_AUTH_ANONYMOUS_VALIDATE=true`) | `VALIDATE` or `ADMIN` |
| `validationApiIncludeIg` ⚠, `profileApiRegisterProfile` ⚠, `igPackageApiUploadIg` ⚠, `igPackageApiRegisterIg` ⚠ | `apiKey` | `ADMIN` |

Administrative operations (the four rows above with `ADMIN`) have their own admission limit, `FV_ADMIN_MAX_PENDING`
(default 4), separate from validation. A request holds its slot until its response ends. Beyond the limit it gets 503
`Problem` (type `overloaded`) with `Retry-After`, after authorisation, so unauthenticated calls never take a slot.
| `igPackageApiGetDependenciesGraph`, `igPackageApiGenerateConformanceReport` | `apiKey` (optional when `FV_AUTH_ANONYMOUS_VALIDATE=true`, like validation) | `VALIDATE` or `ADMIN` |

## New operations

| Method & path | operationId | Responses |
|---------------|-------------|-----------|
| `GET /health/readiness` | `healthApiReadiness` | 200 `{status:"UP", versions:{R4:{revision:n}, …}}` · 503 `{status:"DOWN", reasons:[…]}` |
| `GET /metrics` | `metricsApiScrape` | 200 `text/plain; version=0.0.4` (Prometheus exposition format) |

## Changed operations

### `POST /{version}/validate`

- `version`: enum `[STU3, R4, R4B, R5]`, matched case-insensitively. UUIDs are no longer accepted ⚠
  (they never worked, B14).
- **Remove** the `enum` from the `Accept` and `Content-Type` header parameters (negotiation is
  done in code, R11). `requestBody.content` gains `application/*+json` and `application/*+xml`
  wildcard entries.
- Query `profile` (comma-separated canonicals) is unchanged.
- Responses:

| Status | When | Content types |
|--------|------|---------------|
| 200 | Validated (valid or invalid) | `application/json` → `ValidationResponse` (unchanged shape) · `application/fhir+json` / `application/fhir+xml` / `application/xml` → `OperationOutcome` |
| 400 ⚠ | Body is not parseable FHIR (previously 200) | `Problem` (type `invalid-resource`) with `issues: [{severity:"FATAL", location, message}]` |
| 400 | Bad version or parameter | `Problem` |
| 401 / 403 | Credentials missing/insufficient (when required) | `Problem` |
| 406 / 415 | No acceptable response type / unsupported body type | `Problem` |
| 413 | Body larger than `FV_HTTP_MAX_BODY_MB` | `Problem` |
| 503 | Overloaded (admission full) or not ready, with a `Retry-After` header | `Problem` |
| 500 ⚠ | Internal failure (previously 400 + `valid:false`) | `Problem` |

`ValidationResponse` gains two **optional, additive** fields: `fhirVersion` and `appliedProfiles`
(string array).

### `POST /{version}/include-ig`

Body unchanged (`igPackageId`, `igPackageVersion` default `latest`). Responses:

| Status | Body |
|--------|------|
| 200 | `{status:"success", fhirVersion, revision, included:[…], resolved:["name#version", …], warnings:[…]}` (additive) |
| 404 ⚠ | `Problem`: package not stored (previously 400) |
| 413 | `Problem` (`type: …/problems/package-too-large`): the new IG set would not fit in the answering instance's heap. Nothing is committed; the detail names the estimate, the free heap and `FV_MEMORY_LIMIT` |
| 401/403, 400, 500 | `Problem` |

Success means the change is **committed**. Other instances apply it within the propagation window
(SC-002). The response doesn't wait for them. It waits for the answering instance's own rebuild for at
most min(10 s, `FV_HTTP_REQUEST_TIMEOUT_MS`/2); after that it answers 200 with a "still rebuilding"
entry in `warnings` while the rebuild continues (FR-020). Warnings are curated text and never carry
exception messages.

Before committing a changed package set, the instance estimates the build's heap (64 MB plus 6 × the
packages' expanded size) and answers 413 if it exceeds the free heap. A committed set that an instance
cannot build (for example one committed through an instance with more memory) fails that instance's
build the same way: the version answers 503 and `/health` names the shortfall, the other versions keep
serving, and the process stays up. It is retried with backoff.

### `POST /{version}/register-profile`

- 200 response gains `revision` and `warnings` (additive).
- Success means the profile is **committed**. The answering instance applies it before answering, but
  that apply can queue behind a validator rebuild of the same version, so the response waits at most
  min(10 s, `FV_HTTP_REQUEST_TIMEOUT_MS`/2). After that it answers 200 with a "still applying" entry in
  `warnings` (FR-020). Warnings are curated text and never carry exception messages.
- 400 `Problem` when the profile's `fhirVersion` doesn't match the path, or the version is not
  enabled (no more silent R6→R4 mapping, B15).

### `POST /igs/register`

- `downloadUrl` must satisfy the SSRF rules (R9). If not → 400 `Problem`
  (`type: …/problems/package-source-not-allowed`), and no outbound request is made.
- Unknown name/version → **404** `Problem` (previously a hung request, B10).
- 200 body returns the real `version` (B11).
- Archive limit exceeded → **413** `Problem` (`type: …/problems/package-too-large`) or **422**
  (`…/problems/package-unsafe`: traversal, entry count, ratio).

### `POST /igs/upload`

- Same limits and errors as above. The `maxLength` comes from config, so the spec value becomes
  documentation only.

### `GET /igs/{name}/{version}/dependencies`

- Unknown package → **404** ⚠ (previously 200 with an empty list). DB error → 500 (previously 404).
  (B21)

### `GET /igs/{name}/{version}/conformance`

- **Deprecated** ⚠: kept for 1.x, with the response renamed to what it is (`{name, version,
  files:[…]}`) and the hard-coded `"conformance":"basic"` removed. Marked `deprecated: true` in
  the spec. Planned for removal in 2.0 or replacement by a real report (B21).

## Problem format (all non-validation errors)

```yaml
Problem:                 # RFC 9457, media type application/problem+json
  type: object
  required: [type, title, status, correlationId]
  properties:
    type:          { type: string, format: uri, example: "https://nazrulworld.github.io/fhir-validation-service/problems/package-not-found" }
    title:         { type: string, example: "Package not found" }
    status:        { type: integer, example: 404 }
    detail:        { type: string, description: "Curated, safe text. Never exception messages, class names or stack traces" }
    correlationId: { type: string, description: "Same as the X-Request-Id response header" }
    issues:        { type: array, description: "Present only for type invalid-resource", items: { $ref: '#/components/schemas/ValidationMessage' } }
```

Problem `type` catalogue (stable identifiers): `invalid-request`, `invalid-resource`, `unsupported-fhir-version`,
`unauthorized`, `forbidden`, `not-found`, `package-not-found`, `package-source-not-allowed`,
`package-too-large`, `package-unsafe`, `unsupported-media-type`, `not-acceptable`,
`payload-too-large`, `overloaded`, `not-ready`, `internal-error`.

## Metrics (`GET /metrics`)

Service meters, Prometheus text format (Vert.x HTTP/pool meters are added in production):

| Metric | Labels | Meaning |
|--------|--------|---------|
| `fv_validations_total` | `version`, `outcome` (`valid`, `invalid`, `unparseable`, `error`) | Validation requests |
| `fv_validation_seconds` | `version` | Validation duration histogram |
| `fv_validation_pending` / `fv_validation_max_pending` | | Admitted validations / admission limit |
| `fv_admin_pending` / `fv_admin_max_pending` | | Administrative operations in progress / their admission limit (`FV_ADMIN_MAX_PENDING`) |
| `fv_config_revision` | `version` | Configuration revision applied on this instance |
| `fv_validator_ready` | `version` | 1 when the version's validator serves requests |
| `fv_snapshot_packages` / `fv_snapshot_package_size_bytes` | `version` | IG packages and their archive size in the active validator |
| `fv_snapshot_build_failures_total` | `version` | Failed validator builds |
| `fv_profile_count` | `version` | Custom profiles loaded |
| `fv_package_cache_size_bytes`, `fv_package_cache_hits_total`, `fv_package_cache_misses_total` | | IG package cache |

## Headers (all operations)

| Header | Direction | Rule |
|--------|-----------|------|
| `X-Request-Id` | in/out | Accepted if it matches `[A-Za-z0-9-]{8,64}`, otherwise generated. Always echoed |
| `Retry-After` | out | On 503 (seconds) |
| `Authorization` | in | `Bearer <api-key>` |
| CORS | out | Allowed origins come from `FV_CORS_ALLOWED_ORIGINS` (default: none, so CORS is disabled). No more `.*` (baseline deviation 3) |
