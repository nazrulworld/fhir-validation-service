# HTTP API Contract Changes (2.0.0)

The source of truth is `src/main/resources/openapi.yaml`, under constitution Principle IV. This file
describes the delta from 1.2.0, and every item must land in `openapi.yaml` together with the code.

## Removed: `POST /{version}/register-profile`

- The path and the operation `profileApiRegisterProfile` are deleted from `openapi.yaml`.
- Any method on `/{STU3|R4|R4B|R5}/register-profile` gets **404** with problem `type: not-found` (the
  router's standard unknown-route handling). It no longer needs an admin key, and no admission slot is
  taken.
- Replacement: package the StructureDefinitions as an IG, upload it with `POST /igs/upload`, and include it
  with `POST /{version}/include-ig`.
- The description of the security scheme no longer lists `register-profile` among the admin operations.

## Changed: `POST /{version}/validate`

### `profile` query parameter, new description

> Comma-separated canonical URLs of profiles to validate against, in addition to `meta.profile`. Each URL
> must be defined by an IG included for this FHIR version (or its dependencies) or by the core
> specification. It is matched exactly, against `url` or `url|version`. Profiles are never downloaded.
> If any URL can't be resolved, the request fails with 400 `unknown-profile` and nothing is validated.
> At most 10 distinct profiles per request, each at most 1024 characters.

Add `maxLength` to the parameter schema as documentation only. The limit is enforced on the list as a
whole after splitting, not on the raw query string.

### New 400 case: `invalid-request` (profile limits, FR-006a)

```json
{
  "type": "invalid-request",
  "title": "Bad Request",
  "status": 400,
  "detail": "At most 10 profiles may be requested, each at most 1024 characters"
}
```

- The count is taken after trimming, dropping empty entries and removing duplicates. So
  `?profile=a,a,a` counts as 1.
- The URLs are **not** echoed back for this error.
- Both limits are fixed constants. There is no configuration variable.

### New 400 case: `unknown-profile`

```http
HTTP/1.1 400 Bad Request
Content-Type: application/problem+json
X-Request-Id: 7d0c…

{
  "type": "unknown-profile",
  "title": "Bad Request",
  "status": 400,
  "detail": "One or more requested profiles are not available for FHIR R4; include the IG that defines them",
  "unresolvedProfiles": [
    "http://example.org/StructureDefinition/typo"
  ]
}
```

- `unresolvedProfiles`: every requested entry that didn't resolve, after trimming and de-duplication, in
  request order. It is never empty for this type.
- The response is problem JSON whatever the `Accept` header asks for, the same as every other 4xx.
- The response is never an `OperationOutcome`, even if the client accepts only FHIR types (Q4: A).
- Precedence, where the first failure wins: 400/415/406 (request shape, **including the profile limits
  above**), then 503 (not ready or overloaded), then 400 `invalid-resource`, then 413 (Bundle entries),
  then **400 `unknown-profile`**, then 200.
- Add a `components/schemas` entry `UnknownProfileProblem` (allOf `Problem` with the
  `unresolvedProfiles: string[]` property), referenced from the validate operation's 400 response
  description.

### `ValidationResponse.appliedProfiles`, tightened semantics

- New description: "Profiles that were resolved and applied: the requested profiles in request order,
  then resolvable `meta.profile` entries. Each appears once. An unresolved URL is never listed."
- The shape (`string[]`) is unchanged.

### Unchanged (Q2: A)

An unknown `meta.profile` inside the resource still produces an ERROR issue, and the request still
answers 200 with `valid: false`.

## Admin surface after the change

`ApiKeyAuthHandler`'s admin pattern becomes `^/(igs/(upload|register)|[^/]+/include-ig)/?$`. Admission
control (`FV_ADMIN_MAX_PENDING`) covers exactly those operations.

## Metrics

| Metric | Change |
|---|---|
| `fv.profile.count{version}` | **Removed** |
| validation outcome label | New value `unknown_profile`, next to `valid`, `invalid`, `unparseable` and `error` |
