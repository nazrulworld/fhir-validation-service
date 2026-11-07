# HTTP API Contract Changes: Organisation Ownership (3.1.0)

`src/main/resources/openapi.yaml` stays the source of truth (Principle IV). This file lists the changes that must
land in it with, or before, the code. Every error body is the existing problem-details JSON. All changes are
additive: no path, parameter or field is removed or redefined.

---

## 0. Shared components (new)

```yaml
components:
  schemas:
    OwnershipFields:            # mixed into the responses below
      type: object
      properties:
        owner:
          type: string
          description: Short name of the owning organisation.
        shareable:
          type: boolean
          description: True when every caller may use it; false when only the owner (and the system organisation) may.
    AccessChangeRequest:
      type: object
      minProperties: 1
      additionalProperties: false
      properties:
        shareable:
          type: boolean
        owner:
          type: string
          pattern: '^[A-Za-z0-9_-]{2,32}$'
          description: >-
            Transfer to this organisation (short name). System organisation administrators only; the target must
            exist and be active.
```

New common response text for 404 `package-not-found` (description only): "Also returned when the package or context
is owned by another organisation and isn't shareable; the two cases can't be told apart."

New 403 description: "The API key's role doesn't allow this operation, the key's organisation is deactivated, or
(transfers) the caller isn't a system organisation administrator."

## 1. `POST /{version}/validate` (changed)

- The description of the `ig` parameter gains: "Only packages your organisation owns or that are shareable can be
  named. Anonymous callers can name shareable packages only. Without a version, the highest version you may use is
  chosen."
- Response 200:
  - `ValidationResponse` gains `igOwner` (string, the owner's short name; the system organisation for the base
    context) and `igShareable` (boolean).
  - New headers on every format: `X-Validation-Context-Owner` and `X-Validation-Context-Shareable`
    (`true`/`false`).
- 404 `package-not-found`: not stored or not visible.
- 424 `dependency-not-found`: a dependency is missing (named) or not visible (not named): "A dependency of the
  requested IG package is not available."
- 403 `forbidden`: the key's organisation is deactivated.

## 2. `POST /{version}/include-ig` (changed)

The request body gains:

```yaml
shareable:
  type: boolean
  default: true
  description: >-
    Sharing of a newly included context. false is allowed only when your organisation owns the IG package (or you
    are the system organisation). Ignored, with a warning, when the context is already included.
```

- `IncludeResponse` gains `owner` and `shareable` (the context's, which may belong to another organisation when it
  was already included, spec clarification Q1).
- 404: the package or a dependency isn't visible to the caller. 400 `invalid-request`: `shareable=false` for
  another organisation's package.

## 3. `DELETE /{version}/include-ig` (changed)

- Allowed for the context owner and system organisation administrators.
- 404 for any other caller, the same answer as "not included".

## 4. `PATCH /{version}/include-ig?ig=name#version` (new, administrative)

```yaml
patch:
  operationId: validationApiChangeIncludedIgAccess
  summary: Change sharing or transfer ownership of an included validator context
  parameters: [version (path), ig (query, required, exact name#version)]
  requestBody: {required: true, content: {application/json: {schema: {$ref: '#/components/schemas/AccessChangeRequest'}}}}
  responses:
    '200': {schema: IncludeAccessResponse}   # {status, fhirVersion, igPackage, owner, shareable, revision, warnings}
    '400': invalid-request (empty body, unknown or inactive target organisation, private context of another org's package)
    '401': unauthorized
    '403': forbidden (transfer requested by the owner, who isn't the system organisation; inactive organisation)
    '404': package-not-found (not included, or not owned by the caller and the caller isn't the system organisation)
```

**Check order** (sections 4 and 5): 404 (not found, or not owner and not system) → 403 (transfer by a non-system
caller) → 400 (body and target organisation). A non-owner always gets 404, even for a transfer, so the response never
reveals that a private context or package exists.

The change takes effect on every instance within the propagation window. The context isn't rebuilt.

## 5. `PATCH /igs/{name}/{version}` (new, administrative)

```yaml
patch:
  operationId: igPackageApiChangeAccess
  summary: Change sharing or transfer ownership of a stored IG package
  requestBody: AccessChangeRequest
  responses:
    '200': {schema: IgAccessResponse}        # {status, name, version, owner, shareable}
    '400': invalid-request
    '401': unauthorized
    '403': forbidden (transfer requested by the owner, who isn't the system organisation; inactive organisation)
    '404': package-not-found (not stored, or not owned by the caller and the caller isn't the system organisation)
```

`ApiKeyAuthHandler.ADMIN_PATHS` gains `^/igs/[^/]+/[^/]+/?$`. The only operation on that path is this PATCH, and
every method on an administrative path needs ADMIN. A regression test covers it in `ApiKeyAuthIT`.

## 6. `POST /igs/register` and `POST /igs/upload` (changed)

- The request gains `shareable` (boolean, default `true`). For register, it's in the JSON body. For upload, it's a
  multipart form field `shareable` (`true`/`false`). It applies to every package newly stored by the call, including
  downloaded dependencies. The package licence is not used.
- `IgRegistrationResponse` gains `owner` and `shareable`, the stored package's values. Those may differ from the
  request when the version was already stored, in which case they are left unchanged.
- Register of an already stored version that isn't visible to the caller: 404.
- Upload or URL download of a `name#version` stored by another organisation: **409 `conflict`** ("IG package
  name#version is already stored by another organisation"), unless the caller is the system organisation.

## 7. `GET /igs/{name}/{version}/dependencies` and `/conformance` (changed)

404 `package-not-found` when the package isn't visible to the caller (anonymous callers: shareable only).

## 8. `GET /health` (changed shape of `versions.*`)

- `contexts[]` lists only contexts that are shareable and whose packages are all shareable.
- A new field `privateContexts`: `{count: int, states: {ACTIVE: int, BUILDING: int, STALE: int, ABSENT: int}}`.
- Owners are never listed.

## 9. Notifications (`fv_config_changed`, internal)

A new `kind` value, `IG_ACCESS`, with payload `{fhirVersion, revision, kind: "IG_ACCESS", key: "name#version"}`.
`PACKAGE` notifications may now have `key: null` (organisation rename: refresh only).
