# Operations

Procedures and behaviour details for running the service. Settings are listed in the README.

## Serving fewer FHIR versions

`FV_ENABLED_FHIR_VERSIONS` takes a comma-separated list of `STU3`, `R4`, `R4B` and `R5`. Spaces are trimmed, case is
ignored and duplicates are ignored. Unset or empty, every version is served. Any other name (including `R6`) stops the
start with a configuration error naming it.

What changes for the versions that are not listed:

- Their core specification is not loaded, and readiness doesn't wait for them. This saves memory and start time.
- Requests that name them get the same **400 `unsupported-fhir-version`** as an unknown version. The message lists
  the versions that are served.
- `POST /{version}/validator` for them answers **422 `fhir-version-disabled`**, and nothing is stored.
- At startup, their stored validator contexts are marked **inactive** in one transaction and not built. Every
  deactivated context is logged at WARN, followed by a count.
- Inactive contexts can still be excluded (`DELETE`), shared, transferred or given another terminology server
  (`PATCH`), through the API or `fv-admin snapshot share|transfer`. The change is stored and applies later.
- When a version is listed again, its contexts become active again at the next start, with any changes made in the
  meantime. They are logged at INFO.
- `GET /health` reports `fhirVersions.served` and `fhirVersions.inactiveContexts`: a count, how many are private, and
  the first 100 shareable ones (private contexts are counted, never named, on this unauthenticated endpoint). The
  `fv_validator_contexts_inactive` gauge counts them too.

Uploading or registering IG packages is not restricted.

Every instance records its served versions in `service_settings.fhir_versions`. **An instance whose served versions
differ from the stored ones refuses to start** (exit 1, nothing changed) unless `FV_FHIR_VERSIONS_CHANGE_CONFIRMED=true`
is also set, so one instance with a stale setting can't switch contexts off for the whole fleet. The first start on a
database with no stored set stores it without needing the confirmation.

**Restricting (or changing) versions in a fleet:**
1. Set `FV_ENABLED_FHIR_VERSIONS` to the same value, and `FV_FHIR_VERSIONS_CHANGE_CONFIRMED=true`, on every instance.
2. Restart them. The first instance to start marks the other versions' contexts inactive, and instances still running
   the old setting drop them within the configuration poll interval.
3. Remove `FV_FHIR_VERSIONS_CHANGE_CONFIRMED` in the next deployment. While it stays set, a start logs a WARN asking for
   it to be removed.

## Turning access control off (public mode)

> **Security warning.** With `FV_GLOBAL_DISABLED_ACL_SECURITY=true` (or `1` / `yes`), **no endpoint needs a key**.
> Anyone who can reach the service can upload and register packages, include and exclude validator contexts, and
> validate. Use it only on a closed network, behind a gateway that authenticates callers, or on a developer machine.

In public mode:

- The `Authorization` header is ignored, whatever it holds.
- Every request runs as one of two fixed callers:
  - validation and the package reads run as the `trial validator` key (`00000000-0000-0000-0000-200000000002`, trial
    organisation), so they see shareable content only;
  - administrative operations run as the `system administrator` key (`00000000-0000-0000-0000-200000000001`, system
    organisation).
- Ownership is unchanged: anything created is owned by the system organisation.
- Access rules are unchanged: private content of other organisations stays private (404).
- Usage records are charged to the trial validator and the trial organisation.
- Packages and validator contexts are always stored as **shareable**: `shareable: false` on include, register,
  upload or PATCH is ignored, with a warning in the response's `warnings`. Making private content shareable still
  works; content that is already private stays private until it is changed. The command line is not affected.
- `FV_AUTH_ADMIN_CIDRS` **still applies**: administrative operations from other networks get 403.
- Both fixed keys get new random values (256 bits) at every start. These values are never logged or shown.
- A fixed key you revoke stays revoked across restarts (logged at WARN): revoking `trial validator`
  (`fv-admin key revoke --name "trial validator"`) stops anonymous validation and package reads (403).
- `FV_ADMIN_API_KEY` and `FV_VALIDATOR_API_KEY` are ignored, and their format is not checked.
- The startup log has a prominent WARN. `GET /health` reports `security.aclSecurity: "disabled"`, and
  `fv_acl_security_disabled` is 1.

Every instance records its mode in `service_settings.acl_security`. **An instance whose mode differs from the stored
one refuses to start** (exit 1, nothing changed) unless `FV_ACL_MODE_CHANGE_CONFIRMED=true` is also set. This stops a
single misconfigured instance from replacing the keys the whole fleet uses. A database with no stored mode needs no
confirmation on its first start.

**Switching off:**
1. On every instance, set `FV_GLOBAL_DISABLED_ACL_SECURITY=true` and `FV_ACL_MODE_CHANGE_CONFIRMED=true`.
2. Restart all instances. As soon as the first one starts, the fixed keys stop working on the instances not yet
   restarted.
3. Remove `FV_ACL_MODE_CHANGE_CONFIRMED` in the next deployment. While it stays set, a start logs a WARN asking for it
   to be removed.

**Switching back on:**
1. On every instance, unset `FV_GLOBAL_DISABLED_ACL_SECURITY` (or set `false`), set `FV_ACL_MODE_CHANGE_CONFIRMED=true`,
   and set both `FV_ADMIN_API_KEY` and `FV_VALIDATOR_API_KEY`.
2. Restart all instances. The first one installs the configured keys in one transaction and logs
   `API keys re-synchronised from environment`.
3. Remove `FV_ACL_MODE_CHANGE_CONFIRMED`.

Everything created while access control was off stays owned by the system organisation and can be reached with the
restored admin key. No manual database change is needed in either direction.

After switching back:
- A key variable left unset keeps its unknown random value, so that key can't be used until the variable is set. A
  WARN names it.
- A value that was revoked before is never installed again (see [Seeded keys](#seeded-keys)). An ERROR names it.

## Seeded keys

`system administrator` and `trial validator` are installed from configuration at every start, with an expiry of
2099-12-30T23:59:59Z. They are rotated by changing `FV_ADMIN_API_KEY` / `FV_VALIDATOR_API_KEY` and restarting.
Seeded keys are revoked like any other key, with `fv-admin key revoke --name "trial validator"`. A revoked seeded
key's value is never installed again (it is remembered in `revoked_key_hashes`): an instance still configured with a
leaked, revoked value logs an ERROR and leaves the stored key alone, also after the key was rotated. Deploy a new value
to replace it. Instances configured with different non-revoked values still overwrite each other's key, so change the
value on all instances together. An instance started without the variable leaves the stored key as it is.

## If startup fails

The service stops (exit 1) instead of running half-configured when:

- **another organisation is already called `trial`**: `An organisation named 'trial' already exists with another id;
  rename it before starting`. Rename it (`fv-admin org update --name-short trial --rename <other>`) and start again.
- **a seeded key conflicts**: `Cannot install the 'system administrator' key: another key (revoked keys count too)
  already uses its name (api_keys_name_key)` or `... its value (api_keys_key_hash_key)`. Revoking doesn't help: revoked
  keys still hold their name and value. For a name conflict, rename the other key in the database
  (`UPDATE fhir_validator_schema.api_keys SET name = '<new name>' WHERE name = 'system administrator';`); for a value
  conflict, configure a different value. Then restart.
- **`FV_VALIDATOR_API_KEY` is malformed or equals `FV_ADMIN_API_KEY`**: a configuration error names the variable.
- **the served FHIR versions or the access-control mode changed** without the matching confirmation setting (see
  above).
- **the database schema is from an unsupported early build** (its migration history stops before V15): this release
  cannot upgrade it, and nothing is changed. Keep running the build that created it, or back up the database and
  recreate it from empty.
- **a migration failed earlier** (a failed row in `flyway_schema_history`): repair the database, then start again.

## Usage records on shutdown

The `db` sink buffers records for up to `FV_USAGE_FLUSH_MS` (1 s by default). A graceful stop (SIGTERM) writes them
before exiting; a hard kill (SIGKILL, `docker rm -f`, an OOM kill) loses at most that last interval.

## Usage retention

With `FV_USAGE_SINK=db`, every authorised validation adds one row (about 250 bytes) to
`fhir_validator_schema.validation_usage`. Nothing deletes old rows. Schedule a cleanup, for example:

```sql
DELETE FROM fhir_validator_schema.validation_usage WHERE created_at < now() - interval '90 days';
```

## Rate limits

Validation requests (`POST /{version}/validate`) are limited per API key and per organisation, per rolling minute
(spec 014, 1.4.0). The caps are stored in the database: `rate_limit_per_minute` on `api_keys` and `organisations`,
and the two defaults in `service_settings` (`throttle_default_key_per_minute`, `throttle_default_org_per_minute`; an
absent row means "not limited at that level"). Manage them with `fv-admin limit set | remove | list`
([contracts](../specs/014-api-throttling/contracts/operations.md)). The system organisation and its keys are never
limited, and nothing is limited while access control is disabled.

How it works:
- Each instance counts its admitted requests per key and organisation in one-second buckets, in memory. No validation
  request waits on the database.
- Once every `FV_THROTTLE_SYNC_INTERVAL_MS` (1 s) each instance writes its counts to the UNLOGGED table
  `throttle_counts`, records a heartbeat in `throttle_instances` and reads the other instances' counts of the last
  5 seconds. Instances delete rows older than 2 minutes themselves; nothing needs to be scheduled.
- With one instance a cap is exact. With several, an instance keeps room for what the others may have admitted but not
  yet shared, so the fleet admits at most the cap plus 10 % (at least one request) per rolling minute. For very small
  caps on several instances this means admitting slightly fewer than the cap (cap 5 on 3 instances: 4 per minute), and
  a cap smaller than the number of instances (cap 1 on 3 instances) can be exceeded by up to the number of other
  instances. In the first second after an instance joins, the others still count it out, so the bound can be exceeded
  briefly.
- A refused request is counted in `fv_throttle_refused_total` and logged at most once a minute per key or
  organisation (`throttle: key <id> (org <id>) refused <n> request(s) …`). It is not written to `validation_usage`.

When the database can't be reached (local mode):
- each instance keeps enforcing every cap on its own counts and the last caps it read, so a client can get at most the
  cap times the number of instances per minute. Validation is never refused because sharing failed;
- the instance logs `throttle: cannot share request counts (…)` once at WARN, sets `fv_throttle_local_mode` to 1, and
  logs `throttle: request counts shared again after <s> s` when sharing works again.

Metrics: `fv_throttle_checked_total`, `fv_throttle_refused_total{scope,reason}`, `fv_throttle_tracked_subjects`,
`fv_throttle_evictions_total`, `fv_throttle_peers`, `fv_throttle_local_mode`, `fv_throttle_sync_failures_total{step}`,
`fv_throttle_sync_seconds` and `fv_throttle_ticks_skipped_total`. None carries a key, organisation or instance id.
Suggested alerts: `fv_throttle_local_mode == 1` for more than a minute; `rate(fv_throttle_evictions_total[5m]) > 0`
(raise `FV_THROTTLE_MAX_TRACKED`).

Readiness reports `rate limits not loaded` until an instance has read the caps at startup.

