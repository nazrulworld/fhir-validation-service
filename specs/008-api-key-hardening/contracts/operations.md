# Contract: Operator surface (configuration, CLI, metrics, usage log line, upgrade)

**Feature**: `008-api-key-hardening`

## 1. Environment variables

The full table, with bounds, is in [data-model.md](../data-model.md) §6. Behaviour:
- Invalid CIDRs in `FV_AUTH_ADMIN_CIDRS` or `FV_AUTH_TRUSTED_PROXIES`: startup fails with
  `invalid FV_AUTH_ADMIN_CIDRS entry '<entry>'`.
- `FV_USAGE_SINK` must be `none`, `log`, `db`, `log,db` or `db,log`. Any other value fails startup.
- `FV_USAGE_BATCH_SIZE` greater than `FV_USAGE_QUEUE_SIZE` fails startup with
  `FV_USAGE_BATCH_SIZE (<b>) must not exceed FV_USAGE_QUEUE_SIZE (<q>)`.
- `FV_VALIDATOR_API_KEY`:
  - malformed → fatal startup error, exit 1;
  - unset → WARN `FV_VALIDATOR_API_KEY is not set: validation requires keys created with 'keys create'`;
  - its value never appears in the safe config dump or in logs.
- `FV_AUTH_ANONYMOUS_VALIDATE`: if present → WARN `FV_AUTH_ANONYMOUS_VALIDATE is deprecated and ignored: removed in
  4.1.0, validation always requires a VALIDATE or ADMIN key`.

## 2. Command line (`java -jar … <group> <command>`)

| Command | Change |
|---|---|
| `keys create --org <o> --name <n> --role <r> [--expires <ISO-8601 instant>]` | new `--expires`; a past instant is a usage error (exit 2) |
| `keys list [--org <o>]` | JSON rows gain `"expiresAt"` and `"lastUsedIp"` |
| `keys create/revoke` | the names `system administrator` and `trial validator` are reserved (was `bootstrap`); `keys revoke` accepts them; a revoked seeded key stays revoked until its configured value changes |
| `usage summary (--org <o> \| --key <n>) [--since <instant>]` | **new, optional (FR-022)**; `--since` is compared with each request's start time; JSON `{scope, since, validations, byOutcome{…}, totalMs, avgMs, validateMs, cpuTimeNs, allocatedBytes}` |

## 3. Metrics (Prometheus names)

| Metric | Type | Tags | New? |
|---|---|---|---|
| `fv_validation_seconds` | timer (histogram) | `version` | existing; duration of the whole service call |
| `fv_validations_total` | counter | `version`, `outcome` | existing; validation count by outcome |
| `fv_validation_cpu_seconds` | distribution summary | `version`, `outcome` | new |
| `fv_validation_allocated_bytes` | distribution summary | `version`, `outcome` | new |
| `fv_usage_written_total` | counter | – | new |
| `fv_usage_dropped_total` | counter | `reason` = `queue_full` \| `write_failed` \| `closed` \| `unattributed` \| `log_full` | new |
| `fv_usage_queue_size` | gauge | – | new (only with `db`) |

There are never `key`, `org` or `client_ip` tags.

## 4. Usage log line (`FV_USAGE_SINK` contains `log`)

Logger `nzi.fhir.validator.usage`, level INFO, one JSON object per line as the message. Example:

```json
{"requestId":"6f1c…","startedAt":"2026-11-11T10:15:02.113Z","apiKeyId":"00000000-0000-0000-0000-200000000002",
 "apiKeyName":"trial validator","orgId":"00000000-0000-0000-0000-100000000002","fhirVersion":"R4",
 "ig":"hl7.fhir.us.core#6.1.0","profiles":1,"contentType":"JSON","requestBytes":2312,"outcome":"invalid",
 "httpStatus":200,"issues":{"fatal":0,"error":2,"warning":5,"information":1},"clientIp":"203.0.113.7",
 "totalMs":84,"queueMs":1,"contextMs":0,"validateMs":79,"cpuTimeNs":71234000,"allocatedBytes":18874368}
```

The field names match `UsageRecord` and the columns in [data-model.md](../data-model.md) §3. The line never
contains resource content. `log4j2.xml` routes the logger with `additivity="false"` to a dedicated `UsageConsole`
appender (`%msg%n`), so each line is exactly one JSON object whatever `FV_LOG_FORMAT` is. Lines are written from the
writer's own thread through a bounded queue (8 192 lines); when it is full, lines are dropped and counted as
`fv_usage_dropped_total{reason="log_full"}`. Operators may replace that
appender with a file or another appender.

## 5. Upgrade from 4.0 (documented in `docs/upgrade-4.1.md` and `CHANGELOG.md`)

1. **Stop every 4.0 instance.** This is not a rolling upgrade, because V11 re-keys the system organisation.
2. Create VALIDATE keys for every client that used anonymous validation, or set `FV_VALIDATOR_API_KEY` for a shared
   trial key.
3. Remove `FV_AUTH_ANONYMOUS_VALIDATE`.
4. Optionally set `FV_AUTH_ADMIN_CIDRS`, `FV_AUTH_TRUSTED_PROXIES` and `FV_USAGE_SINK`.
5. Start 4.1.0. Migrations V10 to V12 run, and the well-known orgs and seeded keys are ensured.
6. If you enable `db` usage, plan a retention job for `validation_usage`.

Rollback: restore the database backup taken before step 5. V11 can't be reversed in place while 4.0 code expects
the old id.
