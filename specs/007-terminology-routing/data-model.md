# Data Model: Terminology Servers per IG Context, Ecosystem Routing, Included-Only Contexts

**Feature**: `specs/007-terminology-routing/spec.md` | **Research**: [research.md](./research.md)

## 1. Terminology setting (per included context)

`TerminologySettings` record, in `core/terminology/`:

| Field | Type | Rules |
|---|---|---|
| `mode` | `TerminologyMode` enum: `NONE`, `SERVER`, `ECOSYSTEM` | Default `NONE`. |
| `server` | normalised base URL, or `null` | Required for `SERVER`, optional for `ECOSYSTEM`, forbidden for `NONE` (FR-002). Normalised as in §2. At most 512 characters. |

- `TerminologySettings.NONE` is the constant `(NONE, null)`.
- Equality is by value. The setting is part of a context's build identity: it is held in `ResolvedContext.terminology`
  and compared by `ResolvedContext.sameAs` (R12).
- The base context always uses `NONE` and has no stored setting (FR-001).

**JSON form** (request bodies, responses, status):

```json
{ "mode": "ecosystem", "server": "https://tx.fhir.ch/r4" }
```

`mode` is lower-case on the wire (`none`, `server`, `ecosystem`). `server` is omitted when `null`.

### Lifecycle

```
(include, no terminology)             → NONE
(include with terminology)            → checked (§2), stored → context built with it
PATCH terminology (owner / system)    → checked, stored, revision+1, IG_TERMINOLOGY notify
                                        → every instance rebuilds this context; the old one serves until ready
exclude                               → row deleted with the context (existing delete in updateIgSet)
server leaves the allowlist later     → setting kept; calls refused at run time (FR-010c), codes "not checked"
```

## 2. Allowlist

### 2.1 `TxServerEntry` (one kept registry entry)

| Field | Source (registry JSON) | Rules |
|---|---|---|
| `url` | `url` | Normalised: lower-case scheme and host, no default port (`:80` for http, `:443` for https), no trailing `/`, no query or fragment. Only the `http` and `https` schemes. |
| `fhirVersion` | `fhirVersion` (`"4.0.1"`) | Mapped to `SupportedFhirVersion` by major.minor; other versions are dropped. |
| `serverCode` | `server-code` | For logs and metrics labels. |
| `authoritative` | `authoritative[]` | Wildcard patterns, kept for diagnostics. Routing uses `/resolve`. |

An entry is **kept** only when `error == null`, `security == "open"` and its FHIR version maps (FR-008, R8).

### 2.2 `TxServerAllowlist` (immutable, swapped on refresh)

| Field | Type | Meaning |
|---|---|---|
| `perVersion` | `Map<SupportedFhirVersion, VersionList>` | |
| `VersionList.entries` | `Map<String url, TxServerEntry>` | |
| `VersionList.source` | `REGISTRY`, `STORED_REGISTRY` or `BUILT_IN` | `BUILT_IN` only while no registry list was ever obtained for that version (FR-012). |
| `VersionList.fetchedAt` | `Instant` | When the service obtained it. |
| `VersionList.registryLastUpdate` | `Instant`, nullable | The registry's `last-update`. |

**Operations**:
- `isAllowed(url, version)`: exact match on the normalised URL.
- `require(url, version)`: throws `tx-server-not-registered` or `tx-registry-unavailable` (§6).
- `defaultServer(version)`: `tx.fhir.org` for the version, when allowlisted.

### 2.3 `BuiltInTxServer` (enum; fixed in the release)

| Constant | URL | Version |
|---|---|---|
| `TX_FHIR_ORG_R4` | `http://tx.fhir.org/r4` | R4 |
| `TX_FHIR_ORG_R5` | `http://tx.fhir.org/r5` | R5 |

STU3 and R4B have no built-in entry, because the registry lists none for them (R8).

## 3. Shared storage: migration `V9__context_terminology.sql`

```sql
-- 4.0.0 (spec 007): terminology setting per included context, and the last good registry server list.
ALTER TABLE fhir_validator_schema.fhir_validator_snapshots
    ADD COLUMN IF NOT EXISTS tx_mode   VARCHAR(16)  NOT NULL DEFAULT 'NONE'
        CHECK (tx_mode IN ('NONE', 'SERVER', 'ECOSYSTEM')),
    ADD COLUMN IF NOT EXISTS tx_server VARCHAR(512) NULL,
    ADD CONSTRAINT fhir_validator_snapshots_tx_chk CHECK (
        (tx_mode = 'NONE' AND tx_server IS NULL) OR
        (tx_mode = 'SERVER' AND tx_server IS NOT NULL) OR
        (tx_mode = 'ECOSYSTEM'));

CREATE TABLE IF NOT EXISTS fhir_validator_schema.tx_registry_snapshot (
    fhir_version         VARCHAR(16) PRIMARY KEY,
    fetched_at           TIMESTAMPTZ NOT NULL,
    registry_last_update TIMESTAMPTZ NULL,
    body                 JSONB       NOT NULL   -- the kept entries (§2.1), not the raw answer
);
```

- **Rolling upgrade:** the columns have defaults, so 3.x instances keep inserting valid rows. The 3.x on-demand
  path still runs on old instances during the roll, which is harmless.
- **Write rule for `tx_registry_snapshot`:**
  `INSERT ... ON CONFLICT (fhir_version) DO UPDATE ... WHERE tx_registry_snapshot.fetched_at < EXCLUDED.fetched_at`.
- **Size:** 4 rows, each under 20 KB.

## 4. Routing and run-time state (in memory, per instance)

| Entity | Key | Value | Bound / expiry |
|---|---|---|---|
| Routing decision (`RegistryResolver`) | (FHIR version, system URL[\|version]) | chosen URL, or `NONE`, with the reason | 10 000 entries; success 24 h, failure 5 min (FR-017) |
| Server client (`TerminologyClients`) | (normalised URL, FHIR version) | `IgAware`/`RemoteTerminologyServiceValidationSupport` + circuit state | at most `servers × versions` (under 100) |
| Circuit state | server URL | consecutive failures, open-until | open after 5 failures for 60 s (R11) |
| Code result cache | per snapshot (HAPI chain) | `CodeValidationResult` and the like | `FV_TX_CACHE_SIZE` / `FV_TX_CACHE_TTL`; dropped on rebuild; expiring part cleared after a degraded request (R6) |
| `TerminologyBudget` | thread-local, per validation | budget, time charged so far (only time spent waiting on terminology calls and `/resolve` lookups; never a wall-clock deadline), degraded flag | the request's lifetime (R5) |

## 5. Configuration (new and retired)

| Variable | Key | Type | Default | Bounds |
|---|---|---|---|---|
| `FV_TX_REGISTRY_REFRESH_MS` | `tx.registryRefreshMs` | long | 21 600 000 (6 h) | 300 000 to 604 800 000 |
| `FV_TX_TIMEOUT_MS` | `tx.timeoutMs` | long | 5 000 | 500 to 60 000 |
| `FV_TX_REQUEST_BUDGET_MS` | `tx.requestBudgetMs` | long | 10 000 | 1 000 to 120 000 |
| `FV_TX_CACHE_SIZE` | `tx.cacheSize` | int | 5 000 | 100 to 1 000 000 |
| `FV_TX_CACHE_TTL_MS` | `tx.cacheTtlMs` | long | 600 000 | 10 000 to 86 400 000 |
| `FV_TX_MAX_CONNECTIONS_PER_SERVER` | `tx.maxConnectionsPerServer` | int | 10 | 1 to 200 |
| ~~`FV_IG_MAX_ON_DEMAND`~~ | removed | | | Logged as deprecated and ignored (FR-025). |
| ~~`FV_IG_BUILD_WAIT_MS`~~ | removed | | | Logged as deprecated and ignored (FR-025). |

The registry address is **not** configurable (FR-006).

## 6. Problems (errors)

| Status | `type` | When | Extensions |
|---|---|---|---|
| 400 | `invalid-request` | `ig` names a stored, visible, FHIR-compatible package version that isn't included (FR-022); or a versionless `ig` whose name has no included version the caller may use, while some stored version is visible | none. The detail names `name#version` and says to include it. |
| 400 | `invalid-request` | Terminology setting shape: unknown mode; `server` missing for `server` mode or present for `none`; an unparseable URL or a scheme other than http/https | |
| 400 | `tx-server-not-registered` | The server is not in the registry list for the FHIR version (a list exists) | `registry`: `https://ecosystem.hl7.org/tx-reg`, `fhirVersion` |
| 503 | `tx-registry-unavailable` | No list was ever obtained for the version, and the server is not built in (FR-012a) | `Retry-After: 60` |
| 404 | `package-not-found` | Unchanged: an IG that isn't stored or that the caller may not use; a PATCH for a context that isn't included or isn't the caller's | |

## 7. Changed existing entities

- **`ResolvedContext`:** gains `TerminologySettings terminology` (default `NONE`), and `sameAs` includes it. The
  on-demand factory path is removed (R13).
- **`ConfigRevisionRepository.ConfigRow`:** gains `Map<String, TerminologySettings> terminology`. The new
  notification kind `IG_TERMINOLOGY` is added.
- **`ValidatorSnapshot`:**
  - It holds its `TerminologySettings`, for status and for the warm-up budget of 0.
  - `validate` opens and closes the `TerminologyBudget`.
  - After a degraded validation it clears the chain's expiring cache.
- **`ValidatorRegistry`:** `Kind` becomes `{BASE, PINNED}`. The on-demand state and its configuration are removed.
- **`IgReference.resolveIn`:** takes the version's included keys (R13).
- **`TerminologyServices`** (new, process-wide): `allowlist`, `resolver`, `clients`, `chainCache`, `config`.
  `TerminologyServices.OFFLINE` is used by tests and pure `NONE` builds.
