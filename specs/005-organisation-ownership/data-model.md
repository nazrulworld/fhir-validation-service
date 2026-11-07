# Data Model: Organisation Ownership

**Feature**: `005-organisation-ownership` | **Date**: 2026-11-06 | **Research**: [research.md](./research.md)

Schema `fhir_validator_schema`. One new Flyway migration: `V8__organisations.sql`.

## 1. Persistent model (PostgreSQL)

### 1.1 `organisations` (new)

| Column | Type | Null | Rule |
|---|---|---|---|
| `id` | `UUID` | no | PK, default `gen_random_uuid()`. The system organisation is `00000000-0000-0000-0000-000000000001` |
| `name_short` | `VARCHAR(32)` | no | `^[A-Za-z0-9_-]{2,32}$`; unique on `lower(name_short)` |
| `name_long` | `VARCHAR(256)` | no | 1–256 characters, trimmed |
| `type` | `VARCHAR(16)` | no | `SYSTEM`, `COMMERCIAL` or `NON_COMMERCIAL` (CHECK) |
| `is_active` | `BOOLEAN` | no | default `true` |
| `address_lines` | `TEXT[]` | no | default `'{}'`; at most 6 lines, each at most 128 characters (checked in the service) |
| `post_code` | `VARCHAR(16)` | yes | |
| `country` | `CHAR(2)` | yes | `^[A-Z]{2}$` (CHECK); ISO 3166-1 alpha-2 membership checked in the service |
| `email` | `VARCHAR(254)` | yes | address shape (service) |
| `phone` | `VARCHAR(32)` | yes | `^[+0-9 ()-]{3,32}$` (service) |
| `website` | `VARCHAR(256)` | yes | absolute `http`/`https` URL (service) |
| `contact_person_name` | `VARCHAR(128)` | yes | |
| `contact_person_mobile` | `VARCHAR(32)` | yes | same rule as `phone` |
| `contact_person_email` | `VARCHAR(254)` | yes | address shape (service) |
| `created_at` | `TIMESTAMPTZ` | no | default `now()` |
| `updated_at` | `TIMESTAMPTZ` | yes | set on every update |

Constraints (FR-003):

- `organisations_system_id_chk`: `(type = 'SYSTEM') = (id = '00000000-0000-0000-0000-000000000001')`
- `organisations_system_active_chk`: `type <> 'SYSTEM' OR is_active`
- `organisations_one_system_uq`: a unique partial index on `((true)) WHERE type = 'SYSTEM'`
- `organisations_name_short_uq`: a unique index on `lower(name_short)`

Seed row: `(SYSTEM_ID, 'system', 'FHIR Validation Service', 'SYSTEM', true)`. Fields set in the environment are
applied at start (research R10).

Organisations are never deleted (FR-005). The foreign keys below use `ON DELETE RESTRICT`, so a manual delete fails
while anything still references the organisation.

### 1.2 `api_keys` (changed)

| Column | Change |
|---|---|
| `owner` | **new** `UUID NOT NULL DEFAULT SYSTEM_ID REFERENCES organisations(id) ON DELETE RESTRICT` |

Index `api_keys_owner_idx (owner)`. All existing rows, including `bootstrap`, get the default, the system
organisation (FR-009). `upsertBootstrap` always writes `SYSTEM_ID` (FR-006). `name` stays globally unique.

### 1.3 `fhir_implementation_guides` (changed)

| Column | Change |
|---|---|
| `owner` | **new** `UUID NOT NULL DEFAULT SYSTEM_ID REFERENCES organisations(id) ON DELETE RESTRICT` |
| `is_shareable` | **new** `BOOLEAN NOT NULL DEFAULT true` |

Indexes: `fhir_implementation_guides_owner_idx (owner)`, and `fhir_implementation_guides_private_idx (owner) WHERE
NOT is_shareable` for the "owned or shareable" lookups. The unique key `(ig_package_id, ig_package_version)` is
unchanged, so a package identity has exactly one owner.

### 1.4 `fhir_validator_snapshots` (new, research R1)

Access of each **included** (pinned) snapshot.

| Column | Type | Null | Rule |
|---|---|---|---|
| `fhir_version` | `VARCHAR(16)` | no | PK part; a supported version |
| `ig_package` | `VARCHAR(192)` | no | PK part; `name#version` |
| `owner` | `UUID` | no | FK `organisations(id)` `ON DELETE RESTRICT`, default `SYSTEM_ID` |
| `is_shareable` | `BOOLEAN` | no | default `true` |
| `created_at` | `TIMESTAMPTZ` | no | default `now()` |
| `updated_at` | `TIMESTAMPTZ` | yes | |

Index `fhir_validator_snapshots_owner_idx (owner)`.

Invariants, kept by `ConfigRevisionRepository` in the same transaction as the locked `fhir_validator_logs` row:

- include: append to `included_ig_packages` **and** `INSERT … ON CONFLICT DO NOTHING` the row;
- exclude: remove from the array **and** `DELETE` the row;
- access change: `UPDATE` the row and bump `revision` (kind `IG_ACCESS`);
- an array entry with no row (written by a 3.0 instance during a rolling upgrade) is read as `(SYSTEM_ID, true)`;
- a row with no array entry is ignored and removed on the next include or exclude of that version.

Backfill in V8: one row `(fhir_version, entry, SYSTEM_ID, true)` per entry of every `included_ig_packages` array
(`unnest`).

### 1.5 `fhir_validator_logs`, `fhir_package_catalog` (unchanged)

The per-version `revision` and the catalog `revision` keep their spec 004 roles. They now also cover access changes
(§4).

## 2. In-memory model

| Type | Package | Shape | Notes |
|---|---|---|---|
| `Organisation` | `core.org` | record of every §1.1 field | Validation in `Organisation.validate()` (shared by the command line and system-organisation configuration) |
| `OrganisationRepository` | `core.org` | `create`, `update`, `setActive`, `find(id or name)`, `list`, `applySystemConfig` | All return `Future`. A `name_short` change bumps the catalog revision and notifies (research R8) |
| `Caller` | `core.security` | `(UUID orgId, String orgShortName, Role role, boolean system, boolean anonymous)` | `Caller.ANONYMOUS` constant. Stored in `RoutingContext` under `ApiKeyAuthHandler.CALLER_KEY` |
| `KeyInfo` | `core.security` | `(UUID id, Role role, UUID ownerId, String ownerShortName, boolean ownerActive)` | Extended. The whole record is cached for `FV_AUTH_CACHE_TTL_MS` |
| `AccessPolicy` | `core.security` | static `canUse(Caller, UUID owner, boolean shareable)`, `canUseContext(…)` | Pure. Unit tested exhaustively |
| `Access` | `core.security` | `(UUID owner, String ownerShortName, boolean shareable)` | Value type used by the catalog view and pinned access |
| `PackageCatalogView.Entry` | `core.npm` | + `Access access` | Loaded with a join to `organisations` |
| `PinnedAccess` | `core.validator` | immutable `Map<String igKey, Access>` per FHIR version | Owned by `ConfigSync`, swapped atomically on `IG`, `IG_EXCLUDE` and `IG_ACCESS` |
| `PackageOwnership` | `core.npm` | `(UUID owner, boolean shareable, boolean callerIsSystem)` | Passed to `store`, `storeFile` and `loadPackage` (research R7) |

Base context access: `Access(SYSTEM_ID, systemShortName, true)`, constant, not stored.

Effective access of the context for key `k` (research R4, FR-010):

```
snapshotAccess(k) = PinnedAccess[k]               if k is pinned
                  = catalog.entry(k.igPackage).access  otherwise (on-demand)
allowed(caller, k) = k is base
                  || (canUse(caller, snapshotAccess(k))
                      && ∀ p ∈ resolvedPackages(k): canUse(caller, catalog.entry(p).access))
```

`resolvedPackages(k)` comes from the built snapshot if there is one, otherwise from `ResolvedContext.of(k, view)`.

## 3. State transitions

### Organisation

```
            create                deactivate (not SYSTEM)
  (none) ──────────▶ ACTIVE ◀──────────────────────────▶ INACTIVE
                                 activate
```

- ACTIVE: its keys authenticate. Its private content can be used by its keys and by the system organisation.
- INACTIVE: its keys get 403 within the auth-cache window. Its shareable content stays usable by others. Its
  private content can be used only by the system organisation. Its data is kept.
- There is no deletion. The system organisation is always ACTIVE.

### Package or snapshot access

```
 shareable ⇄ private        (owner or system; private on a snapshot needs ownership of its IG package or system)
 owner A  ──transfer──▶ owner B   (system only; B must exist and be ACTIVE)
```

## 4. Change propagation (Principle II)

| Change | Transaction writes | Notification (`fv_config_changed`) | Effect on other instances |
|---|---|---|---|
| package stored (register, upload, URL) | upsert with owner and flag, catalog `revision + 1` | `PACKAGE` key | catalog view refresh; hash-based invalidation as in 004 |
| package flag or owner (PATCH, command line) | `UPDATE`, catalog `revision + 1` | `PACKAGE` key | catalog view refresh; **no** invalidation |
| organisation `name_short` change | `UPDATE`, catalog `revision + 1` | `PACKAGE` (no key) | short names refresh |
| include | array, child row, version `revision + 1` | `IG` key | pinned build plus `PinnedAccess` swap |
| exclude | array, child row delete, version `revision + 1` | `IG_EXCLUDE` key | release plus `PinnedAccess` swap |
| snapshot flag or owner | child row `UPDATE`, version `revision + 1` | **`IG_ACCESS`** key (new) | `PinnedAccess` swap only, no rebuild |
| organisation active flag | `UPDATE` | none | auth cache TTL (default 30 s) |
| key create or revoke | `INSERT`/`UPDATE` | none | auth cache TTL |

The poll fallback (spec 001) compares both revisions, so a missed notification is caught on the next poll.

## 5. Problem types used

| Status | Type | When |
|---|---|---|
| 400 | `invalid-request` | invalid organisation reference in PATCH; private snapshot of someone else's package; empty PATCH body |
| 403 | `forbidden` | key of an inactive organisation; transfer by the (non-system) owner, a non-owner gets 404 instead; existing role refusals |
| 404 | `package-not-found` | not stored **or not visible** (validate, include, exclude, read endpoints, PATCH, register of a stored version) |
| 409 | `conflict` | upload or URL download of a `name#version` owned by another organisation |
| 424 | `dependency-not-found` | dependency missing (named) or not visible (not named) |
