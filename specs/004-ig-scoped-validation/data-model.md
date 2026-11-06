# Data Model: IG-Scoped Validation

**Feature**: `004-ig-scoped-validation` | **Date**: 2026-11-06 | Decisions referenced as R*n* are in
[research.md](./research.md).

## 1. Persistent (PostgreSQL)

### 1.1 `fhir_validator_logs` (changed by V7)

One row per (validator_id `standard_validator`, FHIR version), as before.

| Column | Type | Change | Meaning in 3.0 |
|---|---|---|---|
| `fhir_version` | VARCHAR | none | STU3, R4, R4B or R5 |
| `included_ig_packages` | TEXT[] | **meaning changed** | The set of included IG keys (`name#version`). Each entry is its own **pinned context**. Several versions of one package may appear |
| `resolved_ig_packages` | TEXT[] | **dropped** | Resolved per key at apply time from the catalog view instead (R3) |
| `revision` | BIGINT | none | Incremented on every include or exclude of this version |
| `updated_at` | TIMESTAMPTZ | none | |

**Rules**
- Entries are unique and sorted (they are read sorted, as today).
- Include adds a key and never removes another version of the same package (FR-019). Exclude removes exactly one
  key (FR-024).
- Each write locks the row (`FOR UPDATE`), bumps `revision` and notifies (`kind = IG` for include, `kind = IG_EXCLUDE`
  for exclude) inside the same transaction.

### 1.2 `fhir_package_catalog` (new, V7)

| Column | Type | Meaning |
|---|---|---|
| `id` | SMALLINT PK, `CHECK (id = 1)` | Single row |
| `revision` | BIGINT NOT NULL | Incremented on every package write (insert or replace) |
| `updated_at` | TIMESTAMPTZ | |

Seeded with `(1, 1, now())`. Each package store becomes one transaction: upsert `fhir_implementation_guides`,
`UPDATE fhir_package_catalog SET revision = revision + 1 RETURNING revision`, then
`pg_notify('fv_config_changed', {"kind":"PACKAGE","key":"name#version","revision":n})` (R4).

### 1.3 `fhir_implementation_guides` (unchanged schema)

This table is the source of the catalog view: `ig_package_id`, `ig_package_version`, `ig_package_meta`
(`fhirVersions` / `fhirVersion`), `dependencies`, `content_sha256`, `expanded_bytes`. The catalog view reads
metadata only, never `content_raw`.

## 2. In-memory, per instance (derived and rebuildable; Principle II)

### 2.1 `SnapshotKey`

`record SnapshotKey(SupportedFhirVersion fhirVersion, String igPackage)`. `igPackage` is a concrete `name#version`,
or `null` for the base context. It is used for registry slots, health details and metric labels.
`toString()` gives `R4:hl7.fhir.us.core#6.1.0` or `R4:base`.

### 2.2 `IgReference` (parsed `ig` parameter)

| Field | Rule |
|---|---|
| `name` | `[A-Za-z0-9][A-Za-z0-9._-]*` |
| `version` | `null` when absent or `latest` (case-insensitive), otherwise `[A-Za-z0-9][A-Za-z0-9.+_-]*` |
| raw length | at most 256 characters, at most one `#` |

A parse failure is 400 `invalid-request` (FR-002). Resolving it through the catalog view (2.3) gives a concrete
`SnapshotKey`.

### 2.3 `PackageCatalogView`

An immutable snapshot of package metadata, swapped atomically on refresh.

| Field | Content |
|---|---|
| `revision` | Catalog revision it reflects |
| `packages` | `name#version` → `{declaredFhirVersions: Set<SupportedFhirVersion>, dependencies: List<String>, sha256: bytes, expandedBytes: long}` |
| `versionsByName` | `name` → stored versions |

**Operations**
- `highest(name)`: `PackageVersionComparator.max(versionsByName[name])` (FR-002, FR-022).
- `contains(key)`: the existence check (FR-003).
- `supports(key, fhirVersion)`: true when the package declares nothing or declares the version, the same rule as
  `checkFhirVersion` (FR-004).
- `asCatalog()`: the `DependencyResolver.Catalog` used to resolve a key's package set.

**Refresh**: on a `PACKAGE` notification, a poll that sees a higher catalog revision, a local store, or a lookup miss
(R4). Concurrent refreshes coalesce into one in-flight refresh.

### 2.4 `ValidatorSnapshot` (extended)

The existing immutable snapshot gains:

| Field | Meaning |
|---|---|
| `key` | `SnapshotKey` |
| `packageHashes` | `name#version` → `sha256` for every resolved package (used for invalidation, FR-025) |
| `expandedBytes` | Used for health details and size decisions |

### 2.5 Registry slot

| Field | Meaning |
|---|---|
| `key` | `SnapshotKey` |
| `kind` | `BASE`, `PINNED` or `ON_DEMAND` |
| `snapshot` | The active `ValidatorSnapshot`, or none |
| `status` | `ABSENT`, `BUILDING`, `ACTIVE` or `STALE` |
| `reason` | Curated text for health (never an exception message) |
| `wantedResolved` | Resolved package list the slot is building or holds |
| `appliedRevision` | Pinned slots: the configuration revision applied. On-demand slots: the catalog revision it was built at |
| `lastUsed` | Monotonic time of the last `snapshot()` hit (LRU) |
| `waiters` | Requests or includes waiting for the current build |
| `retry` | Pinned only: backoff delay and timer |

**State transitions**

```text
                 build ok                     content of a resolved package replaced (pinned)
ABSENT ──► BUILDING ──────► ACTIVE ─────────────────────────────────────► STALE ──(rebuild ok)──► ACTIVE
              │                │  resolved set changed: rebuild in the background, old snapshot keeps serving
              │ build fails    │
              ▼                ▼ (on-demand only) LRU / memory eviction, content replaced, or catalog removal
   pinned: STALE + retry     removed from the registry (the next request builds again)
   on-demand: slot removed, error returned to waiters (FR-017)

Pinned slot whose key is excluded (a revision without the key) ──► demoted to an on-demand slot (evictable)
```

**Registry invariants**
- At most one slot per key, and at most one build per key at a time (FR-011).
- Base slots exist for every enabled version and are never removed.
- Pinned slots are exactly the keys in the latest applied `included_ig_packages`.
- On-demand slots never exceed `FV_IG_MAX_ON_DEMAND` (R8). Eviction picks the least recently used slot that isn't
  `BUILDING`.
- A key that becomes pinned while it is an on-demand slot is promoted: the snapshot is kept and the slot moves to
  the pinned set, without a rebuild. An excluded key is demoted to an on-demand slot, which the LRU or memory
  pressure may evict later (R10). The snapshot isn't dropped at once, so a request soon after doesn't trigger a
  rebuild.

## 3. API payloads (summary; full contract in [contracts/http-api.md](./contracts/http-api.md))

| Payload | Fields added or changed |
|---|---|
| `ValidationResponse` | + `igPackage` (string or null) |
| validate 200 headers | + `X-Validation-Context: <name#version>` or `base` |
| `IncludeResponse` | + `key` `{fhirVersion, igPackage}`; `included` = all pinned keys of the version; `resolved` = this key's package list |
| `ExcludeResponse` (new) | `status, fhirVersion, revision, excluded, included, warnings` |
| `IgRegistrationResponse` | + `downloaded` (boolean), + `warnings` (array) |
| `HealthResponse.readiness` (and `/health/readiness`) | `versions.<v>` = `{appliedRevision, latestRevision, base, contexts[]}`, + `catalogRevision` |
| Problem types | + `insufficient-capacity` (503), + `dependency-not-found` (424); `package-not-found` (404) now also on validate |

## 4. Validation rules traced to requirements

| Rule | Requirement |
|---|---|
| `ig` syntax and length 256 | FR-002 |
| Versionless `ig` resolves to the highest stored version, then the FHIR version check | FR-002, FR-004 |
| Unknown package is 404, nothing built | FR-003 |
| Absent `ig` gives the base context (empty package list) | FR-005, R1 |
| Profiles resolve only from the chosen context | FR-007 |
| Include adds and never replaces | FR-019 |
| Exclude of a key that isn't included is 404 | FR-024 |
| Replaced content invalidates every context containing it | FR-025 |
| On-demand count and memory bounds, LRU, pinned never evicted | FR-015, FR-016 |
