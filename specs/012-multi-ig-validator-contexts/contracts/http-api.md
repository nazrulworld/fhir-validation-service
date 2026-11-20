# HTTP Contract Changes: Multi-IG Validator Contexts

**Feature**: `012-multi-ig-validator-contexts` | Target: `src/main/resources/openapi.yaml` (lands before the code,
Principle IV). Every change is additive; requests valid in 1.1.0 keep their status and body.

## 1. `POST /{version}/validator` (include)

### Request body

```yaml
type: object
# either igPackageId (today) or igPackages (new), never both
properties:
  igPackageId:      { type: string }
  igPackageVersion: { type: string, default: latest }
  igPackages:
    type: array
    minItems: 1
    maxItems: 2
    items:
      type: object
      required: [igPackageId]
      properties:
        igPackageId:      { type: string }
        igPackageVersion: { type: string, default: latest }   # exact, latest or a version series
  shareable:   { type: boolean, default: true }
  terminology: { $ref: '#/components/schemas/TerminologySettings' }
```

`required: [igPackageId]` is replaced by a description rule (checked in code, 400 invalid-request): exactly one of
`igPackageId` or a non-empty `igPackages`.

Example:

```json
{ "igPackages": [
    { "igPackageId": "dk.fhir.ig.dk-core", "igPackageVersion": "3.4.0" },
    { "igPackageId": "dk.ehealth.sundhed.fhir.ig.core", "igPackageVersion": "latest" } ] }
```

### Response 200 (`IncludeResponse`, new fields)

| Field | Single include | Combined include |
|---|---|---|
| `key.igPackage` | `A#a` (unchanged) | `A#a\|B#b` |
| `igPackages` (new) | `[{"igPackageId":"A","igPackageVersion":"a"}]` | both entries, concrete versions, key order |
| `aliases` (new) | `[]` | `["B#b\|A#a", "A#a"]` |
| `requestedVersion` | unchanged | omitted; each requested version is echoed as `requestedVersion` inside its `igPackages` entry |
| `warnings` | unchanged | also one line per exempt base-package choice: `Package hl7.terminology.r4 is required in versions 5.0.0 (A#a) and 6.0.2 (B#b); using 6.0.2` |

### New and changed errors

| Status | type | When |
|---|---|---|
| 400 | `invalid-request` | 0 or >2 entries; both body forms; same `igPackageId` twice; combined key over 256 characters |
| 400 | `dependency-conflict` (new) | the two IGs need different versions of a non-exempt package. Extension `conflicts: [{package, versions: {"A#a": "x", "B#b": "y"}}]` |
| 409 | `alias-conflict` (new) | another combined context for this FHIR version already has first IG `A#a`. Detail names that context's key only when the caller may use it, otherwise just "already used" |
| 404 / 424 / 413 / 422 | unchanged | applied to each member and every dependency; never says which member failed when the caller may not see it |

Reversed pair already included: 200 with the existing context (as "already included" today).

## 2. `GET|POST /{version}/validate`

`ig` parameter description gains:

> Two IGs: `name#version|name#version` (URL-encode `#` as `%23` and `|` as `%7C`), both with exact versions; the
> combined context included with these two IGs in either order is used. A single exact `name#version` uses the
> single-IG context if one is included, otherwise the combined context whose first IG it is. A name without a version,
> or `latest`, considers single-IG contexts only.

| Request | Outcome |
|---|---|
| `ig=A#a\|B#b`, key included | 200, `X-Validation-Context: A#a\|B#b` |
| `ig=B#b\|A#a`, key `A#a\|B#b` included | 200, `X-Validation-Context: A#a\|B#b` |
| `ig=A#a\|B#b`, neither order included | 400 invalid-request `IG packages A#a\|B#b are not included for R4; include them first` |
| `ig=A#a`, no single `A#a`, `A#a\|B#b` included | 200, `X-Validation-Context: A#a\|B#b` |
| `ig=A` / `ig=A#latest` | today's behaviour over single-IG contexts only |
| `ig=A\|B#b`, `ig=A#1.x\|B#b`, `ig=A#a\|A#b`, `ig=A#a\|B#b\|C#c`, `ig=A#a\|` | 400 invalid-request |

`X-Validation-Context-Owner` / `-Shareable` report the combined context's access. 404/424 as today when the caller may
not use a member or dependency.

## 3. `PATCH` and `DELETE /{version}/validator?ig=…`

`ig` accepts `A#a\|B#b` or `B#b\|A#a`; both address the stored key. A single `A#a` addresses only a single-IG context
(404 when none, even if a combined context has first IG `A#a`). Responses report `igPackage` (PATCH) / `excluded`
(DELETE) as the stored key. All other rules unchanged.

## 4. `GET /health` (`contexts`) and inactive-context listings

Each context entry gains `igPackages` (members, key order) and, for combined contexts, `aliases`. `key` holds the
combined key. Public listing rule unchanged: listed only when the context and every package (all members and
dependencies) are shareable.

## 5. Problem types (docs site)

Add rows for `dependency-conflict` (400) and `alias-conflict` (409) to the API Documentation wiki problem-type table,
in the `<a id="<type>"></a>` form the spec 011 converter turns into `/problems/<type>` pages.
