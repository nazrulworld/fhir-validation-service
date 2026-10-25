# Phase 1 Data Model: Service Hardening

**Feature**: [spec.md](./spec.md) · **Decisions**: [research.md](./research.md)

PostgreSQL schema `fhir_validator_schema` is the only durable store. Every in-memory structure in
section 2 can be rebuilt from it (Principle II). Applied migrations V1/V2 are never edited
(Principle VIII). All changes ship as new versioned migrations.

## 1. Persistent entities

### 1.1 `fhir_implementation_guides`: IG package (existing, altered in V3)

| Column | Type | Change | Rule |
|--------|------|--------|------|
| `id` | BIGINT identity PK | — | |
| `ig_package_id` | VARCHAR(127) | — | npm package name, `[a-z0-9.\-]+` |
| `ig_package_version` | VARCHAR(16) → **VARCHAR(64)** | V3 | FR-024, B8 |
| `ig_package_meta` | JSONB | — | name, version, canonical, url, fhirVersion(s) |
| `content_raw` | BYTEA | — | ≤ `FV_PACKAGE_MAX_COMPRESSED_MB` (checked before insert, R10) |
| `content_sha256` | **BYTEA NULL** | V3 (new) | Integrity check and dedupe of identical re-uploads |
| `source` | **VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN'** | V3 (new) | `UPLOAD` \| `REGISTRY` \| `URL` |
| `dependencies` | TEXT[] | — | Raw `name#version` strings as declared |
| `created_at` | TIMESTAMPTZ | — | No longer used to pick "latest" (R7) |
| `updated_at` | **TIMESTAMPTZ NULL** | V3 (new) | Set on upsert |

Unique: `(ig_package_id, ig_package_version)`. **"latest" is computed in the application** with
`PackageVersionComparator` (R7).

### 1.2 `fhir_profiles`: custom profile (existing, unchanged columns)

| Column | Type | Rule |
|--------|------|------|
| `url` | TEXT | Exact canonical URL. Identity together with `fhir_version`. No key mangling (FR-022) |
| `profile_json` | JSONB | Must parse as a `StructureDefinition` for `fhir_version` |
| `fhir_version` | VARCHAR(16) | Stored as the FHIR version number string (`4.0.1`, …), as today |
| `created_at`, `modified_at` | TIMESTAMPTZ | V3 backfills `modified_at = COALESCE(modified_at, created_at, now())` and sets `DEFAULT now()`. The upsert sets `modified_at = now()` on insert **and** update, so the poll fallback (`modified_at > lastSeen`) never misses new profiles |

Writes run in one transaction with a revision bump and `pg_notify` (see 1.3).

### 1.3 `fhir_validator_logs`: validator configuration (existing, altered in V3)

One row per enabled FHIR version (`validator_id` is always `standard_validator` after removing
per-client validators, FR-016).

| Column | Type | Change | Rule |
|--------|------|--------|------|
| `validator_id` | VARCHAR(36) | — | Constant `standard_validator` |
| `fhir_version` | VARCHAR(16) | — | Enum name: `STU3`, `R4`, `R4B`, `R5` |
| `included_ig_packages` | TEXT[] | — | Directly included `name#version` (admin intent) |
| `resolved_ig_packages` | **TEXT[] NOT NULL DEFAULT '{}'** | V3 (new) | Full deterministic closure (R6). Instances build from this, without resolving again |
| `included_profiles` | TEXT[] | — | Unused; kept to avoid editing V2 |
| `revision` | **BIGINT NOT NULL DEFAULT 1** | V3 (new) | Increases on every IG or profile change for that version |
| `is_active` | BOOLEAN | — | |
| `updated_at` | TIMESTAMP | — | Now always set on change |

V3 also seeds missing rows for every enabled version (`INSERT … ON CONFLICT DO NOTHING`), so
`UPDATE … RETURNING revision` always finds its row.

**Change transaction** (both kinds):

```text
BEGIN
  [IG]      UPDATE fhir_validator_logs SET included_ig_packages=$1, resolved_ig_packages=$2,
               revision = revision + 1, updated_at = now()
            WHERE validator_id='standard_validator' AND fhir_version=$3 RETURNING revision
  [PROFILE] INSERT … fhir_profiles ON CONFLICT … DO UPDATE …;
            UPDATE fhir_validator_logs SET revision = revision + 1 … RETURNING revision
  SELECT pg_notify('fv_config_changed', json_build_object(
           'fhirVersion',$v,'revision',$rev,'kind',$kind,'key',$key)::text)
COMMIT          -- notification is delivered only now
```

Concurrent IG changes for the same version are serialised by the row lock taken by `UPDATE`.
Last writer wins, and every instance converges on the final revision (spec edge case 2).

### 1.4 `api_keys`: caller credentials (new in V4)

| Column | Type | Rule |
|--------|------|------|
| `id` | UUID PK DEFAULT `gen_random_uuid()` | |
| `name` | VARCHAR(64) UNIQUE NOT NULL | Human label, e.g. `bootstrap`, `ci-pipeline` |
| `key_hash` | BYTEA UNIQUE NOT NULL | SHA-256 of the presented key. The plaintext is never stored |
| `role` | VARCHAR(16) NOT NULL CHECK (`role` IN ('ADMIN','VALIDATE')) | |
| `created_at` | TIMESTAMPTZ DEFAULT now() | |
| `revoked_at` | TIMESTAMPTZ NULL | Revoked keys are refused |
| `last_used_at` | TIMESTAMPTZ NULL | Updated at most once per minute per key (asynchronously) |

Key lookups are cached in-process for `FV_AUTH_CACHE_TTL_MS` (default 30 s), so a revocation takes
effect within 30 s on every instance. Bootstrap: `FV_ADMIN_API_KEY` → upsert `name='bootstrap'`
at startup.

### 1.5 `api_clients`: not created

Removed together with per-client validators (FR-016). No migration creates it, and code
references are deleted.

### Migration plan

| File | Contents |
|------|----------|
| `V3__config_revision_and_version_length.sql` | Widen `ig_package_version` to 64. Add `content_sha256`, `source`, `updated_at`. Add `resolved_ig_packages` and `revision` to `fhir_validator_logs`. Seed rows for STU3/R4/R4B/R5. Backfill `resolved_ig_packages = included_ig_packages` |
| `V4__api_keys.sql` | Create the `api_keys` table and an index on `key_hash` |

CI gate: apply V1→V4 to an empty DB, **and** apply V3→V4 on a DB already at the V2 schema with
data (Testcontainers test).

## 2. In-memory (derived) entities

| Entity | Holds | Lifecycle |
|--------|-------|-----------|
| **ValidatorSnapshot** (immutable) | `fhirVersion`, `revision`, `resolvedPackages` (list of `name#version`), the built `FhirValidator`, its `ValidationSupportChain`, a reference to the version's `ProfileStore` | Built on `fv-packages` from the `resolved_ig_packages` of one revision, then swapped in. Never mutated. The old snapshot is garbage-collected once in-flight validations finish |
| **ValidatorRegistry** (process singleton) | `EnumMap<SupportedFhirVersion, AtomicReference<ValidatorSnapshot>>` | Also holds, per version, `appliedRevision` (highest DB revision fully applied, advanced by profile refreshes and snapshot swaps) and `lastIgRevision` (revision the snapshot was built from). Filled at bootstrap. Updated by `ConfigSync`. Readiness = every enabled version ACTIVE with `appliedRevision` equal to the latest DB revision seen |
| **ProfileStore** (per version) | `ConcurrentHashMap<String canonicalUrl, IBaseResource>` | Fully loaded at bootstrap. Single entries are refreshed on PROFILE notifications or by poll diff (`modified_at > lastSeen`) |
| **PackageCache** | Caffeine `IgPackageName → NpmPackage`, byte-weighted, at most `FV_PACKAGE_CACHE_MAX_MB` | Filled lazily. Evicted by size only (packages are immutable per name and version) |
| **ConfigSync** | `PgSubscriber` on `fv_config_changed`, plus a periodic poll of `(fhir_version, revision)` | When the DB revision is greater than `appliedRevision`: refresh changed profiles, rebuild the snapshot **only if** `resolved_ig_packages` differs from the active snapshot's set, then advance `appliedRevision`. A profile-only change never triggers a rebuild |
| **AdmissionController** | `AtomicInteger pending`, limit `FV_VALIDATION_MAX_PENDING` | Increments on accept, decrements on completion. When full → 503 |

### Snapshot state transitions (per FHIR version)

```text
           bootstrap / notify(rev>cur)              build ok
  ABSENT ─────────────────────────────▶ BUILDING ───────────▶ ACTIVE(rev)
     ▲                                   │  ▲                    │
     │ build failed at bootstrap         │  │ notify(rev'>rev)    │
     └──── (retry with backoff,          │  └────────────────────┘
            readiness=false)             │ build failed for rev' > rev
                                         ▼
                                   STALE(rev, wanted rev') → version answers 503, readiness=false,
                                         │                  error metric + /health detail
                                         │ retry with backoff succeeds
                                         ▼
                                   ACTIVE(rev')
```

Per spec FR-002 (clarified 2026-10-25), an instance never answers from an outdated revision. The
old snapshot stays in memory only so that recovery is fast. It isn't used for requests while
STALE. A missing package in `resolved_ig_packages` puts the version in STALE and the package is
named in `/health`. Other versions are unaffected.

## 3. Validation rules (from requirements)

| Rule | Source |
|------|--------|
| FHIR version path value ∈ {STU3, R4, R4B, R5}, case-insensitive. Anything else → 400 on every endpoint | FR-021 |
| Package version label length 1..64, no whitespace | FR-024 |
| Archive limits: compressed, expanded, entries, ratio, and no `..`/absolute entries | FR-014 |
| `downloadUrl`: https, host on the allowlist, every resolved address public | FR-013 |
| Profile: has `url`, parses as `StructureDefinition` for the path's FHIR version, `fhirVersion` element (if present) matches the path version | FR-021, B15 |
| `X-Request-Id`: `[A-Za-z0-9-]{8,64}`, otherwise replaced | FR-031 |
| API key: 32–128 chars of `[A-Za-z0-9_-]`. The role must allow the operation | FR-012 |
