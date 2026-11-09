# Upgrading to 4.0.0

4.0.0 adds terminology servers per included IG (spec 007) and serves validation only from included IG contexts. It is
a **breaking** release for clients that name an IG package that isn't included.

## Before you upgrade

- **Use `/{version}/validator` instead of `/{version}/include-ig`.** The endpoint that includes (POST), changes
  (PATCH) and excludes (DELETE) IG validator contexts is renamed; bodies, `?ig=name#version` and responses are
  unchanged. The old path answers 404. Update scripts, CI pipelines and admin tooling.
- **Include every IG your clients name in `?ig=`.** Until 3.2.1 any stored package could be named and was built on
  demand. From 4.0.0 a stored version that isn't included for the FHIR version is refused:

  ```
  400 invalid-request: IG package hl7.fhir.us.core#7.0.0 is not included for R4; include it first (POST /R4/validator)
  ```

  `ig=name` and `ig=name#latest` now resolve to the highest **included** version the caller may use. A package that
  isn't stored, or that the caller may not use, still gets 404 `package-not-found`.
- **Remove `FV_IG_MAX_ON_DEMAND` and `FV_IG_BUILD_WAIT_MS`.** They have no effect any more; an instance that still sets
  them starts normally and logs one warning per variable.
- **Memory:** only base and included contexts exist, so peak memory is no higher than before. Each context with a
  terminology mode can hold up to about 5 MB more (cached code results, `FV_TX_CACHE_SIZE` × 1 KB).

## Database

Migration `V9__context_terminology.sql` runs automatically:

- `fhir_validator_snapshots` gains `tx_mode` (default `NONE`) and `tx_server`. Every existing included context is in
  mode `none` and behaves exactly as before.
- A new table, `tx_registry_snapshot`, keeps the last good terminology server list per FHIR version.

The columns have defaults, so a rolling upgrade is safe: 3.2.x instances keep writing valid rows. During the roll,
3.2.x instances still build `?ig=` contexts on demand while 4.0.0 instances refuse them; finish the roll quickly, or
include the IGs first.

## Terminology servers (new, opt-in)

Nothing changes until you set a terminology mode on an included IG (`POST /{version}/validator` with
`"terminology": {...}`, or `PATCH /{version}/validator?ig=name#version`). See the README, "Terminology servers".

Operational notes:

- **Outbound access.** Instances fetch the server list from `https://ecosystem.hl7.org/tx-reg` at start and every
  `FV_TX_REGISTRY_REFRESH_MS` (6 h), even when no context uses terminology, and call the listed servers for contexts
  that do. Allow egress to `ecosystem.hl7.org` and to the servers you use (for example `tx.fhir.org`). Some listed
  servers are plain `http://`; they are contacted as listed. Only codes, systems, displays and the IG's own value set
  and code system definitions are sent, never the validated resource.
- **No registry list yet:** until an instance (or the shared database) has a list, only `http://tx.fhir.org/r4` and
  `http://tx.fhir.org/r5` are allowed, and saving any other server answers 503 `tx-registry-unavailable`.
- **STU3 and R4B:** the registry lists no server for them, so only mode `none` can be used.
- **Workers:** validation workers wait on terminology calls (bounded by `FV_TX_TIMEOUT_MS` per call and
  `FV_TX_REQUEST_BUDGET_MS` per request). Terminology-heavy traffic may need a larger `FV_VALIDATION_WORKERS`.
- **Health and metrics:** `GET /health` has a `terminology` section (allowlist source and age per version, and each
  context's setting); `/metrics` adds `fv_tx_calls_total`, `fv_tx_call_seconds`, `fv_tx_routing_lookups_total`,
  `fv_tx_code_cache_hits_total` and `fv_tx_allowlist_age_seconds`. The `kind="on-demand"` series of `fv_validator_contexts` and the on-demand build,
  eviction and lookup outcomes are gone.
