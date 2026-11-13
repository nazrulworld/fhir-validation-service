# Contract: HTTP API changes (`src/main/resources/openapi.yaml`)

**Feature**: `009-fhir-version-acl-toggles` | Service version 4.1.0 → **4.2.0** (additive, not breaking; research R14)

No path is added or removed. No existing status code changes for an existing case. The changes are:
- one new response, 422 on `POST /{version}/validator`;
- new optional fields in `HealthResponse`;
- descriptions that explain both settings.

## 1. `POST /{version}/validator` (`validatorApiIncludeIg`)

Add a response:

```yaml
'422':
  $ref: '#/components/responses/FhirVersionDisabled'
```

```yaml
components:
  responses:
    FhirVersionDisabled:
      description: >-
        (4.2.0) The FHIR version is supported by this release but not enabled on this deployment
        (FV_ENABLED_FHIR_VERSIONS). Nothing is stored or built.
      content:
        application/problem+json:
          schema:
            $ref: '#/components/schemas/Problem'
          example:
            type: fhir-version-disabled
            status: 422
            detail: "IG validator contexts for R5 can't be added: this service serves R4 only (FV_ENABLED_FHIR_VERSIONS)"
```

Order of checks: authorisation (or the fixed caller, §4), then the version. A version name that isn't supported at
all (e.g. `R9`) still gets **400 `unsupported-fhir-version`**. The 422 is checked before the body is parsed, so the
response doesn't depend on the body.

Append to the operation description: *"(4.2.0) A FHIR version that is supported but not enabled on this deployment
is refused with 422 fhir-version-disabled; this also applies when an inactive context for it is already stored."*

## 2. `DELETE` and `PATCH /{version}/validator` (`validatorApiExcludeIg`, `validatorApiChangeIncludedIg`)

No schema change. Append to both descriptions: *"(4.2.0) Also works for a context that is inactive because its FHIR
version is not enabled (FV_ENABLED_FHIR_VERSIONS): the change is stored; nothing is built; it applies when the
version is enabled again."*

A version name that isn't supported at all still gets 400 `unsupported-fhir-version`.

## 3. Every other `/{version}/…` operation (`validationApiValidate`, …)

The status codes don't change: a version that isn't enabled gets **400 `unsupported-fhir-version`**, the same as an
unknown version (FR-005). The `detail` text now lists the served versions, e.g. `"FHIR version 'R5' is not supported.
Supported versions: R4"`.

Update the `FhirVersion` schema description: *"STU3, R4, R4B or R5 (case-insensitive). A deployment may serve fewer
(FV_ENABLED_FHIR_VERSIONS, 4.2.0); /health lists the served versions."*

## 4. `HealthResponse` (`GET /health`): new optional properties

```yaml
fhirVersions:
  type: object
  description: (4.2.0) FHIR versions this instance serves, and stored validator contexts that are inactive.
  properties:
    served:
      type: array
      items: { $ref: '#/components/schemas/FhirVersion' }
    inactiveContexts:
      type: object
      properties:
        count: { type: integer, description: All inactive contexts }
        private: { type: integer, description: "Private ones (context or package not shareable): counted, never named" }
        items:
          type: array
          maxItems: 100
          description: First 100 shareable inactive contexts, ordered by FHIR version then package.
          items:
            type: object
            properties:
              fhirVersion: { type: string }
              igPackage:   { type: string, description: "name#version" }
              owner:       { type: string, description: Owning organisation's short name }
              reason:      { type: string, enum: [fhir-version-disabled] }
security:
  type: object
  description: (4.2.0) Access control state.
  properties:
    aclSecurity:
      type: string
      enum: [enabled, disabled]
```

`/health` is unauthenticated, so (code review of PR #72) private inactive contexts are only counted, as the readiness
report already does for private contexts, and whether administrative operations are network-limited is not reported
(the startup log says it).

Example with `FV_ENABLED_FHIR_VERSIONS=R5`, access control disabled and no allowlist:

```json
{
  "status": "UP",
  "fhirVersions": {
    "served": ["R5"],
    "inactiveContexts": {
      "count": 1,
      "private": 0,
      "items": [{"fhirVersion": "R4", "igPackage": "hl7.fhir.us.core#6.1.0", "owner": "system", "reason": "fhir-version-disabled"}]
    }
  },
  "security": {"aclSecurity": "disabled"},
  "postgres": {"status": "UP", "responseTime": 2},
  "readiness": {"status": "UP", "versions": {"R5": {"base": {"state": "READY"}, "contexts": []}}}
}
```

`/health/readiness` and `/health/liveness` are unchanged. Readiness already covers only the served versions.

## 5. `components.securitySchemes.apiKey.description` (append)

> (4.2.0) An operator can turn access control off for a whole deployment (FV_GLOBAL_DISABLED_ACL_SECURITY). Then no
> operation needs a key and the Authorization header is ignored. Each request runs as a fixed caller: validation as
> the `trial validator` key (also the package reads), administrative operations as the `system administrator` key. The admin network
> allowlist still applies. GET /health reports `security.aclSecurity`.

The `security` declarations on operations stay as they are. They describe the default deployment. The disabled mode
is an operator setting, documented in the description and in README.

## 6. Request handling in disabled mode (for contract tests)

| Request | Enabled (4.1 behaviour) | Disabled |
|---|---|---|
| ADMIN path, no header | 401 | 2xx as `system administrator` (or 403 when outside `FV_AUTH_ADMIN_CIDRS`) |
| ADMIN path, `Bearer garbage` | 401 | 2xx; header not read |
| `POST /{v}/validate`, no header | 401 | 2xx as `trial validator`; usage charged to the trial org |
| `GET /igs/{n}/{v}/dependencies`, no header | 401 | 2xx as `trial validator` (another org's private package: 404) |
| Validation naming another org's private context | 404 for the trial key | 404 (FR-031) |
| Any keyed path before the startup ACL step commits | 503 `not-ready`, `Retry-After: 5` | 503 `not-ready`, `Retry-After: 5` (research R10) |
| Fixed caller's organisation deactivated | 403 | 403 |
| Include, register, upload or PATCH with `shareable: false` | stored private | stored shareable, `warnings` names the override (FR-034) |
| PATCH with `shareable: true` on private content | shareable | shareable |
