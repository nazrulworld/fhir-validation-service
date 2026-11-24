# Contract: Operator surface (configuration, CLI, metrics, logs)

**Feature**: `014-api-throttling` | **Release**: 1.4.0

> **Release reset (2026-11-24, PR #106)**: the feature ships in **1.0.0**, and the migration of §5 is part of the baseline
> `V18__baseline_1_0.sql`. The operator surface below is unchanged.

## 1. Environment variables

The bounds are in [data-model.md](../data-model.md) §4. A value outside its bounds fails startup, like every other
`ConfigLoader` entry.

| Variable | Default | Effect |
|---|---|---|
| `FV_THROTTLE_SYNC_INTERVAL_MS` | 1000 | How often each instance shares its counts and reads its peers' counts (200–1000) |
| `FV_THROTTLE_CAPS_REFRESH_MS` | 10000 | How often each instance reloads caps and defaults from the database (1000–30000) |
| `FV_THROTTLE_MAX_TRACKED` | 100000 | Most keys plus organisations whose recent counts are held in memory |

`FV_GLOBAL_DISABLED_ACL_SECURITY=true` turns throttling off entirely. Startup then logs `Rate limits are not applied:
access control is disabled (FV_GLOBAL_DISABLED_ACL_SECURITY)`. When access control is enabled, startup logs `Rate
limits: default key <n|none>/min, default organisation <n|none>/min; <k> key cap(s), <o> organisation cap(s)`.

## 2. Command line: the `limit` group

```text
java -jar fhir-validation-service.jar limit set    (--key <name> | --org <short-name> | --default-key | --default-org) --per-minute <n>
java -jar fhir-validation-service.jar limit remove (--key <name> | --org <short-name> | --default-key | --default-org)
java -jar fhir-validation-service.jar limit list   [--org <short-name>]
```

Exactly one target flag is required for `set` and `remove`.

### 2.1 Errors and exit codes

The exit codes are the same as the other groups (spec 005 `contracts/cli.md`).

| Condition | Exit | stderr |
|---|---|---|
| no target, more than one target, or `--per-minute` missing or not a whole number from 1 to 1000000 | 2 | `error: usage: …` |
| unknown key name or organisation short name | 3 | `error: not-found: API key '<name>' not found` / `Organisation '<o>' not found` |
| the target is the system organisation or one of its keys | 4 | `error: conflict: The system organisation and its keys are never rate limited` |
| `remove` on a target that has no cap | 0 | the JSON result with `"changed": false` |

### 2.2 Output of `set` and `remove` (stdout, one JSON document)

```json
{ "target": "key", "name": "acme-ci", "id": "6c1…", "previous": 30, "perMinute": 120, "changed": true,
  "effective": { "perMinute": 120, "source": "own" } }
```

- `target` is one of `key`, `organisation`, `default-key` or `default-organisation`.
- For the defaults, `name` and `id` are omitted.
- `previous` and `perMinute` are `null` when there is no cap.
- After a `remove`, `effective` shows what now applies: `{"perMinute": 600, "source": "default"}`, or
  `{"perMinute": null, "source": "none"}`.

The change applies on every instance within `FV_THROTTLE_CAPS_REFRESH_MS` plus one sync interval (FR-014). No restart
is needed.

### 2.3 Output of `list`

```json
{
  "defaults": { "key": 600, "organisation": 1200 },
  "organisations": [
    { "name": "system", "id": "00000000-0000-0000-0000-100000000001", "perMinute": null,
      "effective": { "perMinute": null, "source": "not-throttled" },
      "keys": [ { "name": "system administrator", "id": "…", "role": "ADMIN", "revoked": false,
                  "perMinute": null, "effective": { "perMinute": null, "source": "not-throttled" } } ] },
    { "name": "trial", "id": "00000000-0000-0000-0000-100000000002", "perMinute": 60,
      "effective": { "perMinute": 60, "source": "own" },
      "keys": [ { "name": "trial validator", "id": "00000000-0000-0000-0000-200000000002", "role": "VALIDATE",
                  "revoked": false, "perMinute": 30, "effective": { "perMinute": 30, "source": "own" } } ] }
  ]
}
```

- `source` is one of `own`, `default`, `none` (no cap at that level) or `not-throttled` (system organisation and its
  keys; US5 AC4).
- `--org` limits the output to one organisation. `defaults` is always included.

### 2.4 Audit

Each successful `set` or `remove` logs INFO `cli: limit <set|remove> <target>=<name|default> id=<uuid|-> old=<n|none>
new=<n|none>` (FR-016). For the defaults, `service_settings.updated_by` is `cli:<instance name>`.

## 3. Metrics

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `fv_throttle_checked_total` | counter | (none) | validation requests checked against caps |
| `fv_throttle_refused_total` | counter | `scope` = `key` \| `organisation`; `reason` = `cap` \| `unconfirmed` | 429s (FR-017) |
| `fv_throttle_tracked_subjects` | gauge | (none) | windows in memory |
| `fv_throttle_evictions_total` | counter | (none) | windows evicted by size (raise `FV_THROTTLE_MAX_TRACKED` if this grows) |
| `fv_throttle_peers` | gauge | (none) | other live instances seen in the last 5 s |
| `fv_throttle_local_mode` | gauge | (none) | 1 while counts can't be shared (FR-019) |
| `fv_throttle_sync_failures_total` | counter | `step` = `flush` \| `heartbeat` \| `read` \| `caps` \| `cleanup` | failed sync statements |
| `fv_throttle_sync_seconds` | timer | (none) | duration of one sync tick |
| `fv_throttle_ticks_skipped_total` | counter | (none) | ticks skipped because the previous one was still running |

No metric carries a key, organisation or instance identifier.

**Suggested alerts** (documented in `docs/operations.md`):
- `fv_throttle_local_mode == 1` for more than 1 minute;
- `rate(fv_throttle_evictions_total[5m]) > 0`.

## 4. Log lines

| Level | When | Text |
|---|---|---|
| INFO | the first refusal for a subject in a minute, then at most once a minute while refusals continue | `throttle: <key\|organisation> <uuid> (org <uuid>) refused <n> request(s) in the last minute (cap <c>); request <request-id>` |
| WARN | sharing fails and local mode starts | `throttle: cannot share request counts (<step>: <sqlstate or reason>); enforcing caps on this instance's counts only` |
| INFO | local mode ends | `throttle: request counts shared again after <s> s` |
| WARN | the cap refresh fails (at most once a minute) | `throttle: cannot reload rate limits (<reason>); keeping the snapshot from <time>` |

Every line logged while serving a request carries the request id (Principle VII). No resource content or key value is
ever logged.

## 5. Database

- `V18__validation_rate_limits.sql` adds columns, constraints and two UNLOGGED tables, and seeds the initial caps
  ([data-model.md](../data-model.md)). Since the 1.0.0 reset, the baseline `V18__baseline_1_0.sql` does this.
- The UNLOGGED tables are emptied after a database crash or failover, which is harmless: windows restart.
- They are not part of logical backups that matter.
- Retention: no operator action is needed. Instances delete rows older than 2 minutes themselves.
