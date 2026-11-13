# Data Model: Operator Switches for Enabled FHIR Versions and Global Access Control

**Feature**: `009-fhir-version-acl-toggles` | **Date**: 2026-11-12 | See [research.md](./research.md)

## 1. Validator context (`fhir_validator_schema.fhir_validator_snapshots`, changed)

There is one row per (FHIR version, included IG package): the context's identity, owner, sharing and terminology
(specs 005 and 007). This feature adds two columns:

| Column | Type | Rule |
|---|---|---|
| `is_active` | `BOOLEAN NOT NULL DEFAULT TRUE` | Only active contexts are resolved, built or used (FR-006) |
| `inactive_reason` | `VARCHAR(32) NULL` | `NULL` when active; `'fhir-version-disabled'` is the only value in 4.2 |

- Constraint `fhir_validator_snapshots_active_chk`: `CHECK (is_active = (inactive_reason IS NULL))`.
- Constraint `fhir_validator_snapshots_reason_chk`: `CHECK (inactive_reason IS NULL OR inactive_reason IN
  ('fhir-version-disabled'))`. A future reason extends it in a new migration.
- Partial index `fhir_validator_snapshots_inactive_idx ON (fhir_version) WHERE NOT is_active`, for the health query
  and the metric.
- The included set in `fhir_validator_logs.included_ig_packages` is not changed by (de)activation.

**State transitions** (only at startup, `ContextActivation.reconcile`, one transaction):

```text
            version not in served set (FR-010)
  ACTIVE ───────────────────────────────────────▶ INACTIVE(reason = fhir-version-disabled)
    ▲                                                     │
    └──────────── version back in served set (FR-014) ────┘

  include (POST, served version)  → new row, ACTIVE (FR-009)
  include (POST, unserved version) → refused 422, no row (FR-008)
  exclude (DELETE)                 → row deleted, in either state (FR-032)
  PATCH sharing/terminology/owner  → row updated, state unchanged (FR-032)
```

**Migration `V14__validator_context_active.sql`**:
1. Add both columns with their defaults. Every existing row becomes active (FR-007).
2. Insert a system-owned, shareable row for any included package that has none, as V8 did, so every included
   context can carry its flag.
3. Add the constraints and the index.

## 2. Service setting (`fhir_validator_schema.service_settings`, new)

| Column | Type | Rule |
|---|---|---|
| `name` | `VARCHAR(64) PRIMARY KEY` | 4.2 uses `acl_security` and `fhir_versions` |
| `value` | `VARCHAR(256) NOT NULL` | `acl_security`: `enabled` or `disabled`; `fhir_versions`: served versions, e.g. `R4,R5` (FR-033) |
| `updated_at` | `TIMESTAMPTZ NOT NULL DEFAULT now()` | When the last start wrote it |
| `updated_by` | `VARCHAR(128) NULL` | Host name of the instance that wrote it (audit) |

- `acl_security` is written on every start, inside the startup ACL transaction (FR-024).
- A missing row means no stored mode, so no confirmation is needed (spec edge case). V15 doesn't seed it.
- Constraint `service_settings_acl_chk`: `CHECK (name <> 'acl_security' OR value IN ('enabled','disabled'))`.

**Migration `V15__service_settings.sql`**: creates the table and the constraint.

## 3. Fixed callers (existing rows in `api_keys`, no schema change)

| Caller | Key id | Name | Role | Organisation |
|---|---|---|---|---|
| ADMIN | `00000000-0000-0000-0000-200000000001` | `system administrator` | ADMIN | system `…-100000000001` |
| VALIDATE | `00000000-0000-0000-0000-200000000002` | `trial validator` | VALIDATE | trial (`Constant.TRIAL_ORG_ID`) |

- **Disabled mode, at every start**: `key_hash` = SHA-256 of a fresh 256-bit random value. Expiry stays
  `Constant.SYS_ORG_API_KEY_EXPIRES_AT`. `revoked_at` is kept: a revoked fixed key stays revoked (code review of
  PR #72).
- **Enabled mode**: as in 4.1, `key_hash` = hash of the configured value when one is set. A previous random hash stays
  when the variable is unset, so that key is unusable.
- Random values are never added to `revoked_key_hashes`.

## 4. In-memory structures (rebuildable, Principle II)

| Structure | Where | Source | Bound |
|---|---|---|---|
| `ServedVersions` | `ApplicationConfig` | `FV_ENABLED_FHIR_VERSIONS` | ≤ 4 versions |
| `ConfigRow.inactive` | `ConfigRevisionRepository` | `s.is_active`, `s.inactive_reason` | rows of one version |
| `FixedCallers` cache | `ApiKeyAuthHandler` | `api_keys` by id | 2 entries, TTL `FV_AUTH_CACHE_TTL_MS` |
| ACL gate flag | `ApiKeyAuthHandler` | set after the startup ACL step commits | 1 boolean |
| `AclMode` | `ApplicationConfig` | `FV_GLOBAL_DISABLED_ACL_SECURITY` | enum |

## 5. Configuration (new entries in `ConfigLoader`)

| Variable | Config file path | Kind | Default | Validation |
|---|---|---|---|---|
| `FV_ENABLED_FHIR_VERSIONS` | `fhir.enabledVersions` | STRING → `ServedVersions` | all supported | each item ∈ {STU3, R4, R4B, R5}; otherwise a configuration error with exit 1 (FR-003) |
| `FV_GLOBAL_DISABLED_ACL_SECURITY` | `auth.globalDisabledAclSecurity` | FLAG | false (enforced) | `true`/`1`/`yes` mean disabled; another value means enforced, with a WARN (FR-016) |
| `FV_ACL_MODE_CHANGE_CONFIRMED` | `auth.aclModeChangeConfirmed` | FLAG | false | same parsing (FR-029) |
| `FV_FHIR_VERSIONS_CHANGE_CONFIRMED` | `fhir.versionsChangeConfirmed` | FLAG | false | same parsing (FR-033) |

When the mode is disabled, the format check on `FV_ADMIN_API_KEY` and `FV_VALIDATOR_API_KEY` is skipped (research R13).

## 6. Validation rules traced to requirements

| Rule | Enforced by | Requirement |
|---|---|---|
| Unknown or unserved version name in the setting → exit 1 | `ConfigLoader` | FR-003 |
| Request for an unserved version → 400 `unsupported-fhir-version` | `VersionParams.served` | FR-005 |
| Include for a supported but unserved version → 422 `fhir-version-disabled`, nothing written | `VersionParams.servedForInclude`, `IgPackageService.include` | FR-008 |
| Inactive context never resolved or built | `ConfigSync.resolveAll` skips `ConfigRow.inactive()` | FR-006 |
| Deactivate and reactivate all or nothing | `ContextActivation.reconcile` in one transaction | FR-011, FR-014 |
| Stored mode ≠ configured mode without confirmation → exit 1, nothing changed | `AclModeStartup` (transaction rolled back) | FR-029 |
| Key values never logged | `AclModeStartup`, `NoSecretsInLogsTest` | FR-021, FR-022, SC-008 |
