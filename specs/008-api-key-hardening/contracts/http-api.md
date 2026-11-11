# Contract: HTTP API changes (`src/main/resources/openapi.yaml`)

**Feature**: `008-api-key-hardening` | Service version 4.0.0 → **4.1.0** (breaking; see research R1)

The paths and payloads don't change. What changes is the authentication requirement, the security declarations and
the documented 401/403 responses.

## 1. Security declarations

Add `security: [{apiKey: []}]` to every operation that needs a key:

| operationId | Path | Roles | Before | After |
|---|---|---|---|---|
| `validationApiValidate` | `POST /{version}/validate` | VALIDATE, ADMIN | anonymous by default | **key required** |
| `igPackageApiGetDependenciesGraph` | `GET /igs/{name}/{version}/dependencies` | VALIDATE, ADMIN | anonymous by default | **key required** |
| `igPackageApiGenerateConformanceReport` | `GET /igs/{name}/{version}/conformance` | VALIDATE, ADMIN | anonymous by default | **key required** |
| `validatorApiIncludeIg` / `ExcludeIg` / `ChangeIncludedIg` | `/{version}/validator` | ADMIN | ADMIN (prose only) | ADMIN, declared |
| `igPackageApiUploadIg` / `RegisterIg` / `ChangeAccess` | `/igs/upload`, `/igs/register`, `PATCH /igs/{name}/{version}` | ADMIN | ADMIN (prose only) | ADMIN, declared |

Open, with no `security`: `/health`, `/health/liveness`, `/health/readiness`, `/metrics`.

No OpenAPI security handler is registered (the router uses `setRequireSecurityHandlers(false)`). Enforcement stays
in `ApiKeyAuthHandler.rootHandler()`, which runs before contract validation (research R9).

## 2. `components.securitySchemes.apiKey.description` (replacement text)

> API key as `Authorization: Bearer <key>`. Validation and the package read operations require a VALIDATE or ADMIN
> key (4.1.0; anonymous validation was removed). Administrative operations require an ADMIN key and, when the
> operator configures an admin network allowlist, must come from an allowed network. Every key belongs to an
> organisation; a key of a deactivated organisation gets 403. Keys may expire; an expired key gets 401.

## 3. Responses

**401 `unauthorized`** (problem+json, `WWW-Authenticate: Bearer`) is returned for:
- no `Authorization` header, or one that isn't `Bearer`;
- a **malformed** bearer value (refused without a lookup, FR-001);
- an unknown, revoked or **expired** key.

The body is the same in every case: `"A valid API key is required (Authorization: Bearer <key>)"`. The response
never says which case applied, so it can't be used as a key oracle.

**403 `forbidden`** is returned for:
- *(new)* an administrative operation from a client address outside `FV_AUTH_ADMIN_CIDRS`:
  `"Administrative operations are not allowed from this network"`. This is decided before the key is read, so it
  applies to every key, valid or not;
- the key's role doesn't allow the operation (for example, the trial validator key on an admin path), as before;
- the key's organisation is deactivated, as before.

Changes to the response tables:
- The `401` description on validate and the two reads: replace "FV_AUTH_ANONYMOUS_VALIDATE=false and no valid API
  key was sent" with "no valid API key was sent (missing, malformed, unknown, revoked or expired)".
- Add `401` and `403` (`$ref Forbidden`) wherever an ADMIN operation lacks them.
- Extend the `Forbidden` description with "or (administrative operations) the client address is outside the
  configured admin networks".

## 4. Order of checks (root handler)

1. Resolve the client address (trusted-proxy rule, research R3).
2. Administrative path with an allowlist set and the client outside it → **403**.
3. Administrative or validate/read path:
   - no bearer, or a malformed one → **401**;
   - lookup (cache, then database) finds nothing, or the key is revoked or expired → **401**;
   - the owner is deactivated → **403**;
   - the role isn't allowed → **403**;
   - otherwise `touch(id, clientIp)` and continue.
4. Every other path is open.

## 5. Unchanged

- The validation response body and the headers `X-Validation-Context`, `X-Validation-Context-Owner` and
  `X-Validation-Context-Shareable`.
- The status codes for valid, authorised requests.
- A usage record is never visible to the client. The request id (`correlationId` in problems, and the request-id
  header) links a response to its usage record.
