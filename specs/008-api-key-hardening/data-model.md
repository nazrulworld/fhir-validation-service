# Data Model: API Key Hardening, Key-Only Validation, Usage Accounting and Well-Known Organisations

**Feature**: `008-api-key-hardening` | **Date**: 2026-11-11

Schema: `fhir_validator_schema`. Migrations: `V10` (key expiry and audit), `V11` (well-known organisations),
`V12` (usage). Applied migrations are never edited (Principle VIII).

## 1. `api_keys` (changed, V10 + V11)

| Column | Type | Change | Notes |
|---|---|---|---|
| `id` | UUID PK | V11: the bootstrap row gets `…-200000000001` | Seeded keys have fixed ids (§4) |
| `name` | VARCHAR(64) UNIQUE | V11: `bootstrap` → `system administrator` | Reserved: `system administrator`, `trial validator` |
| `key_hash` | BYTEA UNIQUE | – | SHA-256 |
| `role` | VARCHAR(16) | – | `ADMIN` / `VALIDATE` |
| `owner` | UUID FK organisations | V11: default → `…-100000000001` | `ON DELETE RESTRICT` (as V8) |
| `created_at`, `revoked_at`, `last_used_at` | TIMESTAMPTZ | – | |
| **`expires_at`** | TIMESTAMPTZ NULL | **new (V10)** | `NULL` = never expires |
| **`last_used_ip`** | INET NULL | **new (V10)** | Resolved client address (research R3) |

**Validation rules**:
- A key is active when `revoked_at IS NULL AND (expires_at IS NULL OR expires_at > now())`, and its owner is
  active (the existing 403 rule).
- `--expires` on create must be a future ISO-8601 instant.
- Seeded keys get `expires_at = 2099-12-30T23:59:59Z`.

**State transitions**:

```
created ──(expires_at passes)──▶ expired   (refused 401; row kept)
created ──(revoke)─────────────▶ revoked   (refused 401)
expired/revoked ──(well-known upsert at startup)──▶ active   (seeded keys only: revoked_at = NULL, expires_at reset)
```

Seeded keys can't be revoked from the command line (`keys revoke` refuses reserved names). They are revoked by
rotating or unsetting their configuration value and restarting.

**Java**:
- `KeyInfo(UUID id, String name, Role role, UUID ownerId, String ownerShortName, boolean ownerActive, Instant expiresAt)`.
- `KeySummary` gains `expiresAt` and `lastUsedIp`. Its JSON adds `"expiresAt"` and `"lastUsedIp"`, null when unset.
- `touch(UUID id, String ip)`.

## 2. `organisations` (changed, V11)

| Change | Detail |
|---|---|
| System org id | `00000000-0000-0000-0000-000000000001` → `00000000-0000-0000-0000-100000000001` |
| `organisations_system_id_chk` | Recreated: `(type = 'SYSTEM') = (id = '…-100000000001')` |
| Seed: system | `(…-100000000001, 'system', 'FHIR Validation Service', 'SYSTEM')`, if missing |
| Seed: trial | `(…-100000000002, 'trial', 'Free trial validation service', 'NON_COMMERCIAL')`, `ON CONFLICT DO NOTHING` |

Owner references that move with the system org: `api_keys.owner`, `fhir_implementation_guides.owner` and
`fhir_validator_snapshots.owner` (FKs dropped and re-added, defaults changed; research R13).

**Invariant after V11**: there are no rows with `owner = '…-000000000001'` and no organisation with that id.

## 3. `validation_usage` (new, V12)

| Column | Type | Null | Source (UsageRecord field) |
|---|---|---|---|
| `id` | BIGINT GENERATED ALWAYS AS IDENTITY PK | no | – |
| `created_at` | TIMESTAMPTZ DEFAULT now() | no | insert time |
| `started_at` | TIMESTAMPTZ | no | `startedAt` (handler entry) |
| `request_id` | VARCHAR(64) | no | `requestId` |
| `api_key_id` | UUID FK `api_keys(id)` `ON DELETE SET NULL` | yes | `apiKeyId` |
| `api_key_name` | VARCHAR(64) | yes | `apiKeyName` (kept for history after key deletion) |
| `org_id` | UUID | no | `orgId` (no FK, research R17) |
| `fhir_version` | VARCHAR(16) | yes | `fhirVersion` (null when the version itself was rejected) |
| `ig` | VARCHAR(192) | yes | `ig`: the context label actually used, else the requested reference |
| `profile_count` | SMALLINT | no | `profiles` |
| `content_type` | VARCHAR(16) | yes | `JSON` / `XML` |
| `request_bytes` | INTEGER | no | `requestBytes` |
| `outcome` | VARCHAR(32) | no | `ServiceMetrics.OUTCOME_*` or `rejected` / `timeout` / `aborted` |
| `http_status` | SMALLINT | no | final response status |
| `issues_fatal`, `issues_error`, `issues_warning`, `issues_information` | INTEGER | no | `issueCount` by severity (0 when not validated) |
| `client_ip` | INET | yes | `clientIp` |
| `total_ms`, `queue_ms`, `context_ms`, `validate_ms` | INTEGER | no | phase timings (research R10); -1 when the phase didn't run |
| `cpu_time_ns`, `allocated_bytes` | BIGINT | no | -1 when unsupported or not reached |

Indexes: `validation_usage_key_created_idx (api_key_id, created_at)` and
`validation_usage_org_created_idx (org_id, created_at)`.

**Rules**:
- Exactly one row per validation request that passed authorisation, when `db` is in the sink.
- `total_ms ≥ validate_ms` whenever both are ≥ 0.
- No resource content is ever stored (PHI rule).

## 4. Well-known constants (`core/Constant.java`)

| Constant | Value |
|---|---|
| `SYSTEM_ORG_ID` (+ `SYSTEM_ORG_UUID`) | `00000000-0000-0000-0000-100000000001` |
| `SYSTEM_ORG_SHORT_NAME` / `_LONG_NAME` / `_TYPE` | `system` / `FHIR Validation Service` / `SYSTEM` |
| `TRIAL_ORG_ID` (+ `TRIAL_ORG_UUID`) | `00000000-0000-0000-0000-100000000002` |
| `TRIAL_ORG_SHORT_NAME` / `_LONG_NAME` / `_TYPE` | `trial` / `Free trial validation service` / `NON_COMMERCIAL` |
| `SYS_ORG_ADMIN_API_KEY_ID` / `_NAME` / `_ROLE` | `…-200000000001` / `system administrator` / `ADMIN` |
| `TRIAL_ORG_VALIDATOR_API_KEY_ID` / `_NAME` / `_ROLE` | `…-200000000002` / `trial validator` / `VALIDATE` |
| `SYS_ORG_API_KEY_EXPIRES` (+ `_AT` as `Instant`) | `2099-12-30T23:59:59Z` |

Removed: `SYS_ORG_API_KEY_OWNER` (duplicate of `SYSTEM_ORG_ID`) and `SYS_ORG_VALIDATOR_API_KEY_*` (replaced by the
trial constants). `Organisations.SYSTEM_ID` and `DEFAULT_SYSTEM_SHORT_NAME` delegate here.

## 5. In-memory types

| Type | Package | Fields / role |
|---|---|---|
| `Caller` | `core/security` | + `UUID apiKeyId`, `String apiKeyName` (null for anonymous and `Caller.system`) |
| `Cidr`, `CidrList` | `core/security` | network bytes + prefix; `contains(InetAddress)`; `CidrList.EMPTY` matches nothing |
| `ClientAddress` | `core/security` | `resolve(peer, headers, trusted)` → `InetAddress`; root handler stores it under `ClientAddress.KEY` |
| `UsageRecord` | `core/usage` | immutable record of every §3 column except `id` and `created_at`; `toJson()` for the log sink |
| `UsageMeter` | `core/usage` | mutable per-request marks; `NONE` no-op instance; two-latch emit (research R10) |
| `UsageWriter` | `core/usage` | `write(UsageRecord)`, `close(): Future<Void>` |
| `NoopUsageWriter`, `LogUsageWriter`, `DbUsageWriter`, `CompositeUsageWriter` | `core/usage` | sinks (research R12) |
| `StartupException` | `core` | fatal warm-up error → exit 1 (research R14) |

## 6. Configuration (`ConfigLoader` entries)

| Variable | Key | Kind / bounds | Default |
|---|---|---|---|
| `FV_AUTH_ADMIN_CIDRS` | `auth.adminCidrs` | comma-separated CIDRs; invalid entry → startup error | unset = unrestricted |
| `FV_AUTH_TRUSTED_PROXIES` | `auth.trustedProxies` | comma-separated CIDRs | unset = headers ignored |
| `FV_AUTH_FORWARDED_HEADER` | `auth.forwardedHeader` | `x-forwarded-for` \| `forwarded` | `x-forwarded-for` |
| `FV_VALIDATOR_API_KEY` | `auth.validatorKey` | STRING, redacted in the safe dump | unset = WARN |
| `FV_USAGE_SINK` | `usage.sink` | `none` \| `log` \| `db` \| `log,db` | `none` |
| `FV_USAGE_QUEUE_SIZE` | `usage.queueSize` | INT 100 – 1 000 000 | 10 000 |
| `FV_USAGE_BATCH_SIZE` | `usage.batchSize` | INT 10 – 5 000 | 500 |
| `FV_USAGE_FLUSH_MS` | `usage.flushMs` | LONG 100 – 60 000 | 1 000 |

Cross-check: `FV_USAGE_BATCH_SIZE` must not exceed `FV_USAGE_QUEUE_SIZE`; otherwise startup fails.
| ~~`FV_AUTH_ANONYMOUS_VALIDATE`~~ | removed | WARN "removed in 4.1.0" if present | – |
