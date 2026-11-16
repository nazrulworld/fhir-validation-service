# HTTP Contract Changes: 1.1.0 (spec 010)

These changes land in `src/main/resources/openapi.yaml` before or with the code (constitution IV). Problem documents
keep the existing format (`application/problem+json`). Field rules are in [data-model.md](../data-model.md) §3 to §5.
All changes are additive. A request without the new parameters gets the same status and body as in 1.0.0.

---

## 1. `POST /{version}/validate`: new query parameters

```yaml
- name: tx
  in: query
  required: false
  description: >-
    Terminology server for this request only: an http(s) base URL, at most 512 characters, without query, fragment
    or user info. It must be on the official HL7 terminology registry's allowlist for this FHIR version (otherwise
    400 tx-server-not-registered, or 503 tx-registry-unavailable while no registry list was ever obtained). No call
    is made to a server that is not allowed. Not allowed with txMode=none. Overrides the context's own server; the
    context's setting stays the default for other requests. The resource itself is never sent to the server.
  schema:
    type: string     # no maxLength: the 512-character limit is checked in code so the refusal has the curated text
- name: txMode
  in: query
  required: false
  description: >-
    Terminology mode for this request only: `none` (no terminology server; codes that can't be decided from the
    packages are checked offline as without a server), `server` (one server: `tx`, or the context's server) or
    `ecosystem` (route each code system through the HL7 registry; `tx`, if given, replaces the registry's default
    server). Absent: the context's mode, or `server` when only `tx` is given. See the resolution table in the
    description.
  schema:
    type: string     # no enum: the three values are checked in code so the refusal has the curated text
```

`tx` and `txMode` are plain strings in the schema. Length, allowed values and repeats are checked in code, so every
refusal carries the curated texts of §2 (spec FR-007), in the order of spec FR-008a.

Operation description, added paragraph:

> **Terminology per request.** A context's terminology setting is its default. `tx` and `txMode` choose another
> setting for one request without a rebuild. Effective setting (D = the context's setting; `none` for the base
> context):
>
> | txMode | tx | effective |
> |---|---|---|
> | – | – | D |
> | – | url | D's mode if `server`/`ecosystem`, else `server`; server = url |
> | server | url | server url |
> | server | – | server with D's server; 400 if D has none |
> | ecosystem | url | ecosystem, url as default server |
> | ecosystem | – | ecosystem, D's server if D is ecosystem, else the registry default |
> | none | – | none |
> | none | url | 400 |
>
> The 200 response reports the setting used in `X-Tx-Mode`, `X-Tx-Server` and `X-Tx-Source`.

## 2. New and changed answers

| Case | Status | Problem type | Detail (fixed text) |
|---|---|---|---|
| `txMode` not one of the three values (the contract layer refuses it) | 400 | `invalid-request` | "txMode must be none, server or ecosystem" |
| `tx` or `txMode` given more than once | 400 | `invalid-request` | "Give tx and txMode at most once each" |
| `tx=` or `txMode=` empty after trimming surrounding whitespace | 400 | `invalid-request` | "tx and txMode must not be empty" |
| `tx` malformed, not http(s), with query, fragment or user info | 400 | `invalid-request` | "tx must be an http or https base URL without query, fragment or user info" |
| `tx` over 512 characters | 400 | `invalid-request` | "tx must be at most 512 characters" |
| `txMode=none` with `tx` | 400 | `invalid-request` | "tx is not allowed with txMode=none" |
| `txMode=server`, no `tx` and the context has no server | 400 | `invalid-request` | "txMode=server needs a tx server; this context has none" |
| `tx` not on the allowlist (a registry list exists) | 400 | `tx-server-not-registered` | existing spec 007 text and `registry` extension |
| `tx` not on the built-in list and no registry list was ever obtained | 503 | `tx-registry-unavailable` | existing spec 007 text, `Retry-After` |
| `txMode=ecosystem` without `tx` and no allowed server for the version | 400 / 503 | as above | as `TxServerAllowlist.require(settings, version)` |

Check order:
1. The existing checks: version, media types, `profile`, `ig` shape.
2. `tx` and `txMode` shape.
3. Body present.
4. `ig` resolution and access (404 and 424 stay ahead of any terminology answer, so a refused caller learns nothing
   about the context's setting).
5. Effective setting and allowlist.
6. Context readiness (503 `not-ready`).
7. Validation.

The address is never echoed in a `detail` (the existing `tx-server-not-registered` text names the server the caller
sent, as spec 007 does for saved settings; that is the caller's own input).

## 3. 200 response headers

```yaml
X-Tx-Mode:
  description: Terminology mode used for this validation (none, server or ecosystem)
  schema: { type: string, enum: [none, server, ecosystem] }
X-Tx-Server:
  description: >-
    Normalised base URL of the terminology server used (mode server). For mode ecosystem, the `tx` value if given,
    else the context's server, else absent (the registry's own default server is not reported, because routing picks
    a server per code system). Absent for mode none.
  schema: { type: string }
X-Tx-Source:
  description: Where the setting came from - `request` (tx or txMode given) or `context` (the context's default)
  schema: { type: string, enum: [request, context] }
```

- The headers are the same for every `Accept`. The body (JSON summary or OperationOutcome) does not change.
- Refusals (4xx, 503) carry none of the three headers.
- The usage record holds the same three values, empty for a request refused before validation starts.
- `tx` is logged at DEBUG only.

## 3a. Rolling upgrade from 1.0.0

Instances still on 1.0.0 ignore `tx` and `txMode` (no headers, the context default is used) and still rebuild a
context when its setting changes. Callers should rely on the parameters only once every instance runs 1.1.0. The
release notes say so (spec FR-028a).
- CORS: the three headers are added to `Access-Control-Expose-Headers`.

## 4. Context endpoints (`/{version}/validator` POST/PATCH, `/validators`): behaviour notes only

- No schema change. The `terminology` property is still accepted, validated and shown.
- Description change: "Changing `terminology` takes effect on the next validation on every instance; the context is
  **not** rebuilt (1.1.0). It is the default; a validation request can override it with `tx`/`txMode`."
- `PATCH` with only a `terminology` change answers as before, but no rebuild follows. A `STILL_APPLYING` warning can
  only come from the configuration propagation, never from a build.

## 5. `info.version`

`1.0.0` → `1.1.0`.
