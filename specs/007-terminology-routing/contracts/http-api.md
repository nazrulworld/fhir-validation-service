# HTTP Contract Changes: 4.0.0 (spec 007)

The changes below land in `src/main/resources/openapi.yaml` before or with the code (constitution IV). Problem
documents keep the existing format (`application/problem+json`, with `type`, `title`, `status`, `detail` and
extensions). Field and type details are in [data-model.md](../data-model.md) §1 and §6.

---

## 1. `POST /{version}/validate`: included contexts only (FR-021 to FR-024)

The request is unchanged. Responses change:

| Case | Before (3.x) | After (4.0.0) |
|---|---|---|
| `ig=P#v`, included, the caller may use it | 200 | 200 (unchanged) |
| `ig=P#v`, stored, the caller may use it, FHIR version supported, **not included** | 200 after an on-demand build (or 503 while it builds) | **400 `invalid-request`**: "IG package P#v is not included for R4; include it first (POST /R4/include-ig)" |
| `ig=P` / `ig=P#latest` | highest **stored** version the caller may use | highest **included** version the caller may use. If none is included but one is stored and visible: 400 as above. |
| `ig=P#v` not stored, or the caller may not use it | 404 `package-not-found` | 404 `package-not-found` (unchanged) |
| `ig=P#v` for another FHIR version | 400 `invalid-request` | 400 `invalid-request` (unchanged; checked before "not included") |
| `ig=P#v` with a missing dependency | 424 (on-demand) | not possible from validation: included contexts are built at include time |
| context included but not ready | 503 `not-ready` | 503 `not-ready` (unchanged) |

The check order is spec 004 FR-009, with "context readiness and preparation" replaced by "context readiness".

**Terminology in results** (for contexts in mode `server` or `ecosystem`):
- Codes checked by a server produce the server's issues: an error for an invalid code, and a warning or error for a
  wrong display, as the server reports it.
- A code that could not be checked produces a **warning** at the element, with one of the fixed texts in research
  R7. The answer is still 200.
- No new response headers are added.

## 2. `POST /{version}/include-ig`: optional `terminology`

Request body: a new optional property, added beside `igPackageId`, `igPackageVersion` and `shareable`:

```yaml
terminology:
  type: object
  additionalProperties: false
  required: [mode]
  properties:
    mode:   { type: string, enum: [none, server, ecosystem] }
    server: { type: string, maxLength: 512, description: Base URL listed by https://ecosystem.hl7.org/tx-reg for this FHIR version }
```

Behaviour:
- **New context:** the setting is checked and stored with the context, and the context is built with it. The checks
  are, in order:
  1. 400 `invalid-request` for the shape;
  2. 400 `tx-server-not-registered`;
  3. 503 `tx-registry-unavailable`.
  These run after the existing include checks (404, 424, 400 FHIR version), and before the 413 heap check and the
  commit.
- **Context already included:** the setting is ignored with the warning "terminology is ignored for an already
  included context; change it with PATCH /{version}/include-ig".
- **Absent:** the mode is `none`.

`IncludeResponse` gains:

```yaml
terminology:
  $ref: '#/components/schemas/TerminologySettings'   # the context's current setting (also for an existing context)
```

New `503` response documented: `tx-registry-unavailable`. New `400` type documented: `tx-server-not-registered`.

## 3. `PATCH /{version}/include-ig?ig=name#version`: change terminology

The body may now also hold `terminology`, alone or together with `shareable` and `owner`:

```json
{ "terminology": { "mode": "server", "server": "https://tx.fhir.ch/r4" } }
```

- **Body rule:** "Give shareable, owner, terminology or any of them". An unknown field is still 400.
- **Check order:**
  1. 404, for a context that isn't included or isn't the caller's (unchanged);
  2. 403, for a transfer by a caller that isn't the system organisation (unchanged);
  3. 400, for the body: access fields, then the terminology shape;
  4. 400 `tx-server-not-registered`;
  5. 503 `tx-registry-unavailable`.
- **Effect:**
  - A terminology change bumps the revision and notifies `IG_TERMINOLOGY`.
  - Every instance rebuilds the context, and the old one serves until the new one is ready.
  - Access changes take effect at once, as today.
  - The answer waits for the local apply as today, with the same "still applying" warning.
- **Response:** the existing fields plus `terminology`.
- **Setting the same value again:** 200, with no revision bump.

## 4. `DELETE /{version}/include-ig?ig=name#version`

Only the description changes: "The package stays stored. Validation requests naming it are refused with 400 until
it is included again." The old text said "It is then built on demand". The terminology setting is deleted with the
context.

## 5. `GET /health` (detail)

A new object is added. Readiness and liveness are unchanged.

```json
"terminology": {
  "registry": "https://ecosystem.hl7.org/tx-reg",
  "allowlist": {
    "R4": { "source": "registry", "fetchedAt": "2026-11-09T16:20:00Z", "registryLastUpdate": "2026-11-09T16:16:03Z", "servers": 14 },
    "R5": { "source": "built-in", "fetchedAt": null, "registryLastUpdate": null, "servers": 1,
            "reason": "registry list not obtained yet; built-in fallback list in use" }
  }
}
```

- `source` is one of `registry`, `stored-registry` or `built-in`.
- A version with no list and no built-in entry shows `servers: 0` and the reason.
- Each context in `versions[...]` details gains `terminology` (`{mode, server?}`). Its `kind` is now only `base` or
  `pinned`.

## 6. Metrics (`GET /metrics`, Prometheus)

| Metric | Type | Labels |
|---|---|---|
| `fv_tx_calls_total` | counter | `server` (server-code or host), `fhir_version`, `outcome` = `success`, `failure`, `timeout`, `refused`, `circuit_open`, `budget_exhausted` |
| `fv_tx_call_seconds` | histogram | `server`, `fhir_version` |
| `fv_tx_routing_lookups_total` | counter | `fhir_version`, `result` = `hit`, `resolved`, `failed` |
| `fv_tx_code_cache_hits_total` | counter | `fhir_version` |
| `fv_tx_allowlist_age_seconds` | gauge | `fhir_version`, `source` |

- **Labels:** none of them carry client codes, value set URLs or IG names.
- **Removed:** the `kind="on_demand"` series of the existing context metrics.

## 7. Outbound calls (the contract with the registry and the servers)

| Call | When | Request |
|---|---|---|
| `GET https://ecosystem.hl7.org/tx-reg/?fhirVersion={R3\|R4\|R4B\|R5}` | at start, then every `FV_TX_REGISTRY_REFRESH_MS` | `Accept: application/json` |
| `GET https://ecosystem.hl7.org/tx-reg/resolve?fhirVersion=..&url={system[\|version]}&usage=validation` | an ecosystem routing miss | `Accept: application/json` |
| `POST {server}/CodeSystem/$validate-code`, `POST {server}/ValueSet/$validate-code`, `POST {server}/CodeSystem/$lookup` | uncached codes that can't be decided locally | FHIR `Parameters`, with `url`, `code`, `system`, `display`, `valueSet` (the IG's definition, when a package defines it) and `tx-resource` (the IG's CodeSystems the value set uses) |

- Redirects are never followed.
- No credentials are sent.
- The validated resource itself is never sent.
