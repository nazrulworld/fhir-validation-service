# Data Model: Validation Rate Limits per API Key and per Organisation

**Feature**: `014-api-throttling` | **Migration**: `V18__validation_rate_limits.sql` | **Research**: [research.md](./research.md)

All tables live in `fhir_validator_schema`. V15–V17 are never edited (Principle VIII).

## 1. Stored caps (shared, durable)

### 1.1 `api_keys.rate_limit_per_minute` (new column)

| Column | Type | Null | Rule |
|---|---|---|---|
| `rate_limit_per_minute` | `INTEGER` | yes | NULL means the key has no cap of its own, so the default key cap applies (FR-006) |

Constraints:
- `api_keys_rate_limit_chk`: `rate_limit_per_minute IS NULL OR rate_limit_per_minute BETWEEN 1 AND 1000000`;
- `api_keys_rate_limit_system_chk`: `owner <> '00000000-0000-0000-0000-100000000001' OR rate_limit_per_minute IS NULL`
  (FR-006b).

Lifecycle:
- A cap stays when the key is revoked or expires; it is irrelevant then, because auth refuses the key first.
- `upsertWellKnown` writes the column only when it **inserts** a well-known key: 30 for `trial validator`, NULL for
  `system administrator`. Its update path never touches the column (R7).

### 1.2 `organisations.rate_limit_per_minute` (new column)

| Column | Type | Null | Rule |
|---|---|---|---|
| `rate_limit_per_minute` | `INTEGER` | yes | NULL means the organisation has no cap of its own, so the default organisation cap applies |

Constraints:
- `organisations_rate_limit_chk`: `IS NULL OR BETWEEN 1 AND 1000000`;
- `organisations_rate_limit_system_chk`: `id <> '00000000-0000-0000-0000-100000000001' OR rate_limit_per_minute IS NULL`.

A deactivated organisation keeps its cap.

### 1.3 `service_settings` rows (existing table, new names)

| `name` | `value` | Absent row means |
|---|---|---|
| `throttle_default_key_per_minute` | positive integer as text | keys without their own cap are not limited at key level |
| `throttle_default_org_per_minute` | positive integer as text | organisations without their own cap are not limited at organisation level |

New constraint `service_settings_throttle_chk`. It is written with nested `CASE` because PostgreSQL does not guarantee
the evaluation order of `AND`/`OR` in a CHECK. A plain `OR`/`AND` form could cast `'enabled'` (`acl_security`) to int
and fail that row's insert or update:

```sql
CHECK (CASE WHEN name IN ('throttle_default_key_per_minute', 'throttle_default_org_per_minute')
            THEN CASE WHEN value ~ '^[1-9][0-9]{0,6}$' THEN value::int <= 1000000 ELSE false END
            ELSE true END)
```

`updated_at` and `updated_by` record the last change. The CLI sets `updated_by` to `cli:<instanceName>`.

### 1.4 Initial values (V18, FR-015)

| Target | Value | How |
|---|---|---|
| `throttle_default_key_per_minute` | 600 | `INSERT … ON CONFLICT (name) DO NOTHING` |
| `throttle_default_org_per_minute` | 1200 | same |
| trial organisation `…100000000002` | 60 | `UPDATE organisations SET rate_limit_per_minute = 60 WHERE id = … AND rate_limit_per_minute IS NULL` |
| trial validator key `…200000000002` | 30 | `UPDATE api_keys … WHERE id = …` (no row on a fresh database). Otherwise the insert path of `upsertWellKnown` sets it |

## 2. Shared request counts (UNLOGGED, ephemeral)

### 2.1 `throttle_counts`

One row per subject, second and instance that admitted at least one request in that second.

| Column | Type | Null | Meaning |
|---|---|---|---|
| `scope` | `CHAR(1)` | no | `K` (API key) or `O` (organisation) |
| `subject_id` | `UUID` | no | the key id or the organisation id (no foreign key: rows are short-lived and must never block key or organisation changes) |
| `epoch_second` | `BIGINT` | no | the instance's clock, in whole seconds |
| `instance_id` | `UUID` | no | random per process start |
| `count` | `INTEGER` | no | requests this instance admitted for this subject in this second (absolute, ≥ 1) |

- PK `(scope, subject_id, epoch_second, instance_id)`.
- Index `throttle_counts_second_idx (epoch_second)` serves the per-tick read and the cleanup.
- Written once per tick by each instance with one `unnest` upsert (`count = EXCLUDED.count`).
- Rows with `epoch_second < now − 120` are deleted every 30 s by any instance.
- Size bound: active subjects × instances × 120 rows of about 60 B. For example, 2 000 active subjects × 3 instances
  is at most about 43 MB, and the steady state is much lower because only seconds with traffic have rows.

### 2.2 `throttle_instances`

| Column | Type | Null | Meaning |
|---|---|---|---|
| `instance_id` | `UUID` | no | PK |
| `instance_name` | `VARCHAR(128)` | yes | `ServiceSettingsRepository.instanceName()`, for operators |
| `seen_at` | `TIMESTAMPTZ` | no | database `now()` at the last heartbeat |

- `peers` = rows with `seen_at > now() − interval '5 seconds'` and `instance_id <> me`.
- An instance deletes its own row on graceful stop. Rows older than 1 h are deleted by the cleanup.

## 3. In-memory model (per instance, rebuildable; Principle II)

| Type | Holds | Bound | Rebuilt from |
|---|---|---|---|
| `ThrottleCaps` (immutable snapshot) | `Map<UUID,Integer> keyCaps`, `Map<UUID,Integer> orgCaps`, `OptionalInt defaultKey`, `OptionalInt defaultOrg`, `loadedAt` | rows with a non-NULL cap | §1, every `FV_THROTTLE_CAPS_REFRESH_MS` |
| `Window` (per subject) | 61 one-second buckets × (`local`, `remote`); unconfirmed admissions; refusal log state (`refusedSinceLog`, `lastLogAt`, `firstRefusedRequestId`) | about 0.6 KB | §2.1 (remote) or nothing (local, lost on restart: an acceptable reset) |
| `windows` | Caffeine `Cache<SubjectKey, Window>` | `FV_THROTTLE_MAX_TRACKED`; expire 2 min after last access | as above |
| `SyncState` | `peers`, `lastReadOk`, `localMode`, `instanceId` | constant | §2.2 |

`SubjectKey` is `(scope, UUID)`.

### Effective cap resolution

```text
skip if aclMode == DISABLED or caller.system()
keyCap = caps.keyCaps[caller.apiKeyId] ?? caps.defaultKey ?? none
orgCap = caps.orgCaps[caller.orgId]    ?? caps.defaultOrg ?? none
check each level that has a cap; a level with none is neither counted nor reported
```

### Decision states for one request

```text
CHECK ─┬─ key over cap ───────────────► REFUSE(scope=key, reason=cap)
       ├─ org over cap ───────────────► REFUSE(scope=organisation, reason=cap)
       ├─ unconfirmed ≥ r (p ≥ 1) ────► REFUSE(scope of that level, reason=unconfirmed, Retry-After ⌈2·sync interval⌉ s = 2 by default)
       └─ otherwise ──────────────────► ADMIT (both windows += 1, unconfirmed += 1)
```

- When both levels refuse, the key is reported, with the larger wait (spec Edge Cases).
- A refusal changes no count.

## 4. Configuration (environment, `ConfigLoader` entries with bounds)

| Variable | Key | Default | Bounds | Meaning |
|---|---|---|---|---|
| `FV_THROTTLE_SYNC_INTERVAL_MS` | `throttle.syncIntervalMs` | 1000 | 200–1000 | flush, heartbeat and read cadence (FR-005: at least once per second) |
| `FV_THROTTLE_CAPS_REFRESH_MS` | `throttle.capsRefreshMs` | 10000 | 1000–30000 | cap snapshot reload (FR-014: well under 60 s) |
| `FV_THROTTLE_MAX_TRACKED` | `throttle.maxTracked` | 100000 | 1000–10000000 | most subjects held in memory |

There is no on/off switch. Throttling is active exactly when access control is enabled. An operator who wants no caps
removes the two defaults and any own caps.

## 5. Validation rules (summary)

- A cap is a whole number from 1 to 1 000 000 (FR-012), enforced by the CLI, then by the CHECK constraints.
- Caps cannot be set for the system organisation or its keys (FR-006b), enforced by the CLI, then by the CHECK
  constraints.
- Setting a cap for an unknown key name or organisation short name is not found (FR-013).
