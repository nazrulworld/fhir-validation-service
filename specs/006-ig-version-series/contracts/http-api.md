# HTTP API Contract Changes: IG Version Series (3.2.0)

`src/main/resources/openapi.yaml` stays the source of truth (Principle IV). This file lists the changes that must
land in it with, or before, the code. Every error body is the existing problem-details JSON. All changes are
additive except the status of a `?ig=` series, which goes from 404 to 400 (see plan.md, Constitution Check).
Operations not listed are unchanged.

---

## 1. `POST /igs/register` (changed)

### Request body: `version`

```yaml
version:
  type: string
  default: latest
  maxLength: 64
  description: >-
    An exact version, `latest`, or a version series: `N.x`, `N.x.x` or `N.N.x` (for example `1.2.x`; `x` or `X`).
    A series is resolved to the highest matching version listed by the configured package registries (a release
    before a pre-release). If no registry can be consulted or none lists a matching version, the highest matching
    stored version the caller may use is returned with a warning. Any other value containing an `x` part is
    rejected with 400 invalid-request.
```

### Description (append)

> Version series (3.2.0): `1.2.x` / `1.x.x` resolve to the highest matching registry release, then behave exactly
> like a registration of that version (stored → returned without download; new → downloaded and stored).

### Responses

| Status | Type | When |
|--------|------|------|
| 200 | `IgRegistrationResponse` | Series resolved. `version` is the concrete version, `requestedVersion` the series. |
| 400 | `invalid-request` | Malformed series (`1.x.3`, `x.x.x`, `1.2.3.x`, …). No registry is contacted. |
| 404 | `package-not-found` | No registry lists a matching version and no matching stored version is usable by the caller. Also the existing 404 when the chosen version is stored privately by another organisation. |

## 2. `POST /{version}/include-ig` (changed)

### Request body: `igPackageVersion`

```yaml
igPackageVersion:
  type: string
  default: latest
  description: >-
    An exact version, `latest` (the highest stored version), or a version series `N.x`, `N.x.x` or `N.N.x`
    (for example `1.2.x`): the highest matching stored version your organisation may use. No registry is contacted.
    The context is pinned to that concrete version; storing a newer matching version later does not change it.
```

### Responses

| Status | Type | When |
|--------|------|------|
| 200 | `IncludeResponse` | `key.igPackage` is the concrete `name#version`, `requestedVersion` the value sent. |
| 400 | `invalid-request` | Malformed series, or the highest match doesn't support this FHIR version (no lower match is tried). |
| 404 | `package-not-found` | No stored version in the series that the caller may use. The message names `name#series` and says to register or upload first. |

## 3. `POST /{version}/validate` (changed)

`ig` query parameter description (append):

> The version must be exact (or `latest`, or omitted). A version series such as `name#1.2.x` is rejected with 400
> invalid-request; use the concrete version reported by include-ig.

| Status | Type | When |
|--------|------|------|
| 400 | `invalid-request` | `?ig=` version contains an `x` part. Before 3.2.0 this was 404 `package-not-found`. Nothing is validated. |

## 4. Schemas

### `IgRegistrationResponse` (new property)

```yaml
requestedVersion:
  type: string
  description: The version value of the request (latest, an exact version or a version series) (3.2.0)
```

### `IncludeResponse` (new property)

```yaml
requestedVersion:
  type: string
  description: The igPackageVersion value of the request (latest, an exact version or a version series) (3.2.0)
```

## 5. Example

```http
POST /igs/register
{"name": "hl7.fhir.us.core", "version": "6.1.x"}

200
{"status": "success", "name": "hl7.fhir.us.core", "version": "6.1.0", "requestedVersion": "6.1.x",
 "downloaded": true, "owner": "acme", "shareable": true, "warnings": []}
```

```http
POST /R4/include-ig
{"igPackageId": "hl7.fhir.us.core", "igPackageVersion": "6.x"}

200
{"status": "success", "fhirVersion": "R4", "key": {"fhirVersion": "R4", "igPackage": "hl7.fhir.us.core#6.1.0"},
 "requestedVersion": "6.x", "revision": 12, "included": [...], "resolved": [...], "owner": "acme",
 "shareable": true, "warnings": []}
```
