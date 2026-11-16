# Data Model: Shared Package Definitions and Per-Request Terminology Settings

**Feature**: `010-shared-packages-request-tx` | **Date**: 2026-11-16

Almost everything here is in memory and rebuilt from PostgreSQL (constitution Principle II). The only schema change
is §7 (V16).

---

## 1. TerminologySettings (unchanged type, new role)

`core/terminology/TerminologySettings` keeps its shape (`mode`, `server`) and validation rules (spec 007
data-model §1). It is no longer part of `ResolvedContext`. It is still stored in `fhir_validator_snapshots`
(`tx_mode`, `tx_server`) and now acts as the context **default**.

## 2. TerminologyDefaults (new, per FHIR version)

| Field | Type | Notes |
|---|---|---|
| `settings` | `Map<String, TerminologySettings>` | `name#version` → default; immutable |

- Published by `ConfigSync` with `PinnedAccess` on every sync (start, poll, notification).
- `get(key)` returns `TerminologySettings.NONE` for a missing key and for the base context.

## 3. TerminologyOverride (new, per request)

| Field | Type | Rules |
|---|---|---|
| `mode` | `TerminologyMode` or null | `txMode` query parameter; `none`, `server` or `ecosystem`; at most once |
| `server` | normalised URL or null | `tx` query parameter; ≤ 512 chars; http(s); no query, fragment or user info; at most once |

- Invalid when `mode == NONE && server != null`.
- `TerminologyOverride.ABSENT` means neither parameter was given.

## 4. EffectiveTerminology (new, per request)

| Field | Type | Notes |
|---|---|---|
| `settings` | `TerminologySettings` | Result of the resolution table (spec FR-006) |
| `source` | `REQUEST` \| `CONTEXT` | `REQUEST` when either parameter was given |
| `denied` | boolean | The context default names a server that is not allowed now (only possible with `source = CONTEXT`) |

**Resolution** `resolve(D, O)` (pure function, D = context default, O = override):

| O.mode | O.server | Result | Error |
|---|---|---|---|
| null | null | D, source `CONTEXT` | n/a |
| null | url | (D.mode ∈ {SERVER, ECOSYSTEM} ? D.mode : SERVER, url) | n/a |
| SERVER | url | (SERVER, url) | n/a |
| SERVER | null | (SERVER, D.server) | 400 `invalid-request` when D.server is null |
| ECOSYSTEM | url | (ECOSYSTEM, url) | n/a |
| ECOSYSTEM | null | (ECOSYSTEM, D.mode == ECOSYSTEM ? D.server : null) | n/a |
| NONE | null | NONE | n/a |
| NONE | url | n/a | 400 `invalid-request` (rejected while parsing) |

Allowlist (spec FR-008, research R5):
- `source = REQUEST`: `require(...)` throws 400 or 503.
- `source = CONTEXT`: `isAllowed` → `denied`.

## 5. TerminologyScope (new, thread-local, one per running validation)

| Field | Type | Notes |
|---|---|---|
| `effective` | `EffectiveTerminology` | Fixed when the request starts (FR-003) |
| `router` | `TerminologyRouter` | `NONE`, `DENIED`, `server(url)`, `ecosystem(default)` |
| `scopeId` | String | `none`, `denied`, `server:<url>`, `ecosystem:<url or ->`; part of the code cache key |

Lifecycle: opened in `ValidatorSnapshot.validate` (or by the warm-up with `NONE`) and closed in `finally`. Opening a
second scope on a thread that already has one is an error, the same rule as `TerminologyBudget.open`. With no scope
open, the current scope is the offline one (mode `none`, research R1).

## 6. TerminologyCodeCache (new, one per instance)

| Key part | Source |
|---|---|
| `snapshotId` | unique `long` assigned to each `ValidatorSnapshot` at construction |
| `scopeId` | §5 |
| `op` | `VALIDATE_CODE`, `VALIDATE_CODE_IN_VS` or `LOOKUP_CODE` |
| `fhirVersion` | snapshot version |
| `system`, `code`, `display` | call arguments (null-safe) |
| `valueSetKey` | value set URL; calls without a URL are not cached |
| `optionsKey` | the HAPI `ConceptValidationOptions` flags in HAPI's own key |

Value: the HAPI result, or an explicit "empty" marker (HAPI caches empty results too). A "not checked" answer
(degraded) is never stored.

Limits: `maximumSize = FV_TX_CACHE_SIZE` (default **50,000**, 100 to 1,000,000), `expireAfterWrite =
FV_TX_CACHE_TTL_MS` (default 600,000).

Invalidation:
- `invalidateScope(scopeId)` after a degraded request;
- `invalidateSnapshot(snapshotId)` when a snapshot is dropped or replaced;
- `invalidateEcosystem(fhirVersion)` when that version's allowlist changes.

## 7. UsageRecord / `validation_usage` (changed, V16)

| Column | Type | Notes |
|---|---|---|
| `tx_mode` | `varchar(16)` null | `none`, `server` or `ecosystem`; null for rows written before 1.1.0 and for every request refused before validation starts |
| `tx_server` | `varchar(512)` null | normalised URL, or null |
| `tx_source` | `varchar(8)` null | `request` or `context` |

Migration `V16__usage_terminology.sql`: `ALTER TABLE validation_usage ADD COLUMN …` ×3, all nullable with no default,
so the change is metadata-only on PostgreSQL. `UsageRecord` truncates them to the column sizes like its other strings.

## 8. SharedPackage and SharedPackageDefinitions (new, one per instance)

**SharedPackage**

| Field | Type | Notes |
|---|---|---|
| `key` | `(SupportedFhirVersion, String nameVersion, byte[32] sha256)` | equality on all three (hash by value) |
| `resources` | `List<IBaseResource>` (unmodifiable) | parsed once; never mutated after publication |
| `expandedBytes` | long | for metrics and heap accounting |

**SharedPackageDefinitions**

| Part | Type | Notes |
|---|---|---|
| `entries` | Caffeine `Cache<Key, SharedPackage>` with `weakValues()` | collected once no snapshot references the entry |
| `locks` | `ConcurrentHashMap<Key, Object>` | one lock per key while a parse may happen; removed after a successful put |

Lifecycle of an entry: *absent* → *parsing* (lock held) → *shared* (in `entries`) → *collected* (no strong
reference left). A parse failure goes back to *absent*.

**CustomNpmPackageValidationSupport** (changed): holds `List<SharedPackage>` (strong references) plus the private
replacements (completed or cloned StructureDefinitions). Its `PrePopulatedValidationSupport` indexes stay private.

## 9. ResolvedContext (changed)

The `terminology` component is removed. `sameAs` compares packages and hashes only. `withTerminology` is removed.

## 10. Metrics (new and changed)

| Metric | Type | Tags |
|---|---|---|
| `fv.packages.shared.entries` | gauge | `fhir_version` |
| `fv.packages.shared.expanded.bytes` | gauge | `fhir_version` |
| `fv.packages.shared.lookups` | counter | `fhir_version`, `result` = `hit` \| `miss` \| `failure` |
| `fv.tx.code.cache.hits` | counter (exists) | `fhir_version` |
| `fv.tx.code.cache.misses` | counter (new) | `fhir_version` |
| `fv.tx.code.cache.invalidations` | counter (new) | `reason` = `degraded` \| `snapshot` \| `allowlist` |
| `fv.tx.code.cache.size` | gauge (new) | none |
| `fv.validations.tx_source` | counter (new) | `source` = `request` \| `context` |

No server URL is used as a tag. Only the existing per-server call metrics use a host label (spec 007 FR-026).
