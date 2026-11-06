# HTTP API Contract Changes: IG-Scoped Validation (3.0.0)

`src/main/resources/openapi.yaml` stays the source of truth (Principle IV). This file lists the changes that must
land in it with, or before, the code. Every error body is the existing problem-details JSON. Unchanged operations
are not listed.

---

## 1. `POST /{version}/validate` (changed)

### New query parameter

```yaml
- name: ig
  in: query
  required: false
  description: >-
    The one IG package to validate against: `name#version`, `name#latest` or `name`. URL-encode `#` as `%23`;
    an unencoded `#` is cut off by the client and the bare name then resolves to the highest stored version.
    Without a version (or with `latest`), the highest stored version is used. Any stored package that supports
    this FHIR version can be named; included packages are pre-built, others are built on first use from stored
    packages only (nothing is downloaded). Absent: the core specification only. One value; at most 256 characters.
  schema:
    type: string
    maxLength: 256
```

The `profile` description changes from "defined by an IG included for this FHIR version" to "defined by the chosen
`ig` package, its dependencies or the core specification (the core specification only when `ig` is absent)".

### Response 200 (changed)

- Header on every format: `X-Validation-Context`, which is the concrete `name#version` used or `base`.
- `ValidationResponse` gains:

```yaml
igPackage:
  type: string
  nullable: true
  description: The concrete IG package (name#version) the result came from; null when the base context was used.
```

The OperationOutcome body is unchanged. Use the header to see the context.

### Errors (checked in this order, first failure wins)

| # | Condition | Status | `type` |
|---|---|---|---|
| 1 | Unsupported version, or a content type, `Accept` or profile-limit violation | as today | as today |
| 1 | `ig` malformed (bad characters, empty part, more than one `#`, more than 256 characters, repeated or comma-separated) | 400 | `invalid-request` |
| 1 | Empty body | 400 | `invalid-request` |
| 2 | `ig` package not stored (after one catalog refresh) | **404** | `package-not-found` (detail names the package and says to register or upload it first) |
| 2 | Resolved package doesn't declare this FHIR version | 400 | `invalid-request` (detail names the resolved `name#version`) |
| 3 | Context stale, or still building after `FV_IG_BUILD_WAIT_MS` | 503 | `not-ready`, `Retry-After: 5` |
| 3 | No admin admission slot for an on-demand build | 503 | `overloaded`, `Retry-After: 1` |
| 3 | Memory is full of pinned contexts after evicting on-demand ones | 503 | **`insufficient-capacity`** (new), `Retry-After: 30` |
| 3 | The context alone exceeds the whole heap | 413 | `package-too-large` |
| 3 | On-demand build failed because a dependency isn't stored | **424** | **`dependency-not-found`** (new; detail names the missing `name#version` and says to register or upload it) |
| 3 | On-demand build failed for any other reason | 503 | `not-ready`, `Retry-After: 5` (curated detail; the next request tries again) |
| 4+ | Validation admission, unparseable body, Bundle limit, `unknown-profile` | as today | as today |

Add `404` and `424` to the operation's responses.

---

## 2. `POST /{version}/include-ig` (changed)

The description changes: include **adds** a pinned context for `igPackageId#igPackageVersion` and never replaces
other versions of the same package. The request body is unchanged (`igPackageVersion` defaults to `latest` = the
highest stored version; nothing is downloaded).

`IncludeResponse`:

```yaml
IncludeResponse:
  type: object
  properties:
    status:      { type: string, enum: [success] }
    fhirVersion: { type: string }
    key:
      type: object
      properties:
        fhirVersion: { type: string }
        igPackage:   { type: string, description: concrete name#version }
    revision:    { type: integer, format: int64 }
    included:    { type: array, items: { type: string }, description: every pinned IG key of this FHIR version }
    resolved:    { type: array, items: { type: string }, description: the package list of this key's context }
    warnings:    { type: array, items: { type: string } }
```

Errors are unchanged: 400, 404 `package-not-found`, 413 `package-too-large` (checked for this key's package set only,
after evicting on-demand contexts), and 503.

Including a key that is already included is a no-op that still answers 200, with the current revision and no
rebuild.

---

## 3. `DELETE /{version}/include-ig` (new)

```yaml
delete:
  operationId: validationApiExcludeIg
  summary: Stop pre-building an included IG version for a FHIR version
  description: >-
    Removes one name#version from the included set and propagates to every instance. The package stays stored and
    can still be named in ?ig= (it is then built on demand). Administrative: requires the administrator key.
  parameters:
    - $ref: version path parameter
    - name: ig
      in: query
      required: true
      description: exact name#version (no `latest`)
      schema: { type: string, maxLength: 256 }
  responses:
    '200': ExcludeResponse
    '400': malformed or versionless `ig`
    '401' / '403': as for other administrative operations
    '404': package-not-found (not included for this version)
    '500', '503'
```

```yaml
ExcludeResponse:
  type: object
  properties:
    status:      { type: string, enum: [success] }
    fhirVersion: { type: string }
    revision:    { type: integer, format: int64 }
    excluded:    { type: string }
    included:    { type: array, items: { type: string } }
    warnings:    { type: array, items: { type: string } }
```

Wiring (security-relevant): `ApiKeyAuthHandler.isAdmin` matches **POST** only today
(`^/(igs/(upload|register)|[^/]+/include-ig)/?$`). It must also match `DELETE /{version}/include-ig`. Otherwise the
exclude operation would fall under the validate rules and could be called anonymously. `AdminAdmissionHandler`
uses the same predicate, so admission follows. A regression test calls DELETE without a key (expect 401) and with a
VALIDATE key (expect 403).

---

## 4. `POST /igs/register` (changed)

The request is unchanged. With `version` absent or `latest`, the version comes from the package registries (the
highest released version); before, it was the highest stored version.

`IgRegistrationResponse`:

```yaml
IgRegistrationResponse:
  type: object
  properties:
    status:     { type: string, enum: [success] }
    name:       { type: string }
    version:    { type: string, description: the concrete version stored or returned }
    downloaded: { type: boolean, description: true if the archive was fetched in this call }
    warnings:   { type: array, items: { type: string } }
```

| Case | Result |
|---|---|
| Registry latest is already stored | 200, `downloaded: false`, no archive transfer |
| Registry latest is new | 200, `downloaded: true`; dependencies fetched when `includeDependency` |
| Registries unreachable or disabled, some version stored | 200 with the highest stored version, `downloaded: false`, warning "No package registry could be consulted; using the highest stored version" |
| Registries reachable but none lists the package, some version stored | 200 with the highest stored version, warning "No package registry lists this package; using the highest stored version" |
| As above, nothing stored | 404 `package-not-found` |
| Exact version | as today, plus the `downloaded` flag |

`POST /igs/upload` and the `downloadUrl` form of register return the same schema (`downloaded: true` for URL
downloads, `false` for uploads).

---

## 5. `GET /health` and `/health/readiness` (changed detail shape)

The validator details stay where 2.x put them (`readiness.versions` in `/health`, `versions` in
`/health/readiness`); their content changes from one entry per version to the base context plus a list of IG
contexts, and `catalogRevision` is added.

```json
{
  "status": "UP",
  "postgres": { "status": "UP", "responseTime": 2 },
  "readiness": {
    "status": "UP",
    "catalogRevision": 42,
    "versions": {
      "R4": {
        "appliedRevision": 7,
        "latestRevision": 7,
        "base": { "state": "ACTIVE", "appliedRevision": 7, "packages": [], "packageBytes": 0 },
        "contexts": [
          { "key": "hl7.fhir.us.core#6.1.0", "kind": "pinned", "state": "ACTIVE", "appliedRevision": 7,
            "packages": ["hl7.fhir.us.core#6.1.0", "hl7.fhir.uv.smart-app-launch#2.1.0", "..."],
            "packageBytes": 3456789, "lastUsed": "2026-11-06T10:15:00Z" },
          { "key": "hl7.fhir.us.core#7.0.0", "kind": "on-demand", "state": "BUILDING", "appliedRevision": 0,
            "packages": [], "packageBytes": 0 }
        ]
      }
    }
  }
}
```

Readiness (`/health/readiness`) is true when every `base` and every `pinned` context is `ACTIVE`. On-demand contexts
never affect it.

---

## 6. Problem types

| Type | Status | New in 3.0 |
|---|---|---|
| `package-not-found` | 404 | Now also returned by validate and exclude |
| `insufficient-capacity` | 503 | Yes |
| `dependency-not-found` | 424 | Yes |
| `invalid-request`, `not-ready`, `overloaded`, `package-too-large`, `unknown-profile` | unchanged | No |

## 7. Notifications on `fv_config_changed` (internal contract between instances)

| `kind` | Payload | Sent when | Receiver action |
|---|---|---|---|
| `IG` | `{fhirVersion, revision, key}` | include | Sync the version's pinned set |
| `IG_EXCLUDE` | `{fhirVersion, revision, key}` | exclude | Sync the version's pinned set |
| `PACKAGE` | `{revision, key}` | any package store (insert or replace) | Refresh the catalog view, invalidate contexts whose package hash changed, re-resolve pinned keys |

The periodic poll compares both the per-version `revision` and the catalog `revision`.
