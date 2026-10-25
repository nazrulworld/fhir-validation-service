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
| 401/403, 400, 500 | `Problem` |

Success means the change is **committed**. Other instances apply it within the propagation window
(SC-002). The response doesn't wait for them.

### `POST /{version}/register-profile`

- 200 response gains `revision`.
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

## Headers (all operations)

| Header | Direction | Rule |
|--------|-----------|------|
| `X-Request-Id` | in/out | Accepted if it matches `[A-Za-z0-9-]{8,64}`, otherwise generated. Always echoed |
| `Retry-After` | out | On 503 (seconds) |
| `Authorization` | in | `Bearer <api-key>` |
| CORS | out | Allowed origins come from `FV_CORS_ALLOWED_ORIGINS` (default: none, so CORS is disabled). No more `.*` (baseline deviation 3) |
