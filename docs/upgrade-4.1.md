# Upgrading to 4.1.0

4.1.0 hardens API keys, makes every validation need a key, records who used how much of the service, and gives the
system and trial organisations fixed, well-known ids (spec 008). It is a **minor release with breaking changes**, which
the constitution allows when the break is documented (Principle IV, 1.3.0). Read this page before upgrading.

## Breaking changes

- **Validation needs a key.** `POST /{version}/validate`, `GET /igs/{name}/{version}/dependencies` and
  `GET /igs/{name}/{version}/conformance` answer **401 `unauthorized`** (`WWW-Authenticate: Bearer`) without a VALIDATE
  or ADMIN key. Every client must send `Authorization: Bearer <key>`.
- **`FV_AUTH_ANONYMOUS_VALIDATE` is removed.** An instance that still sets it starts normally and logs:
  `FV_AUTH_ANONYMOUS_VALIDATE is deprecated and ignored: removed in 4.1.0, validation always requires a VALIDATE or ADMIN key`.
- **The upgrade is not rolling.** Migration V11 changes the system organisation's id from
  `00000000-0000-0000-0000-000000000001` to `00000000-0000-0000-0000-100000000001`. 4.0 instances hard-code the old id,
  so they must not run against the upgraded database.
- **The bootstrap key is renamed.** The key installed from `FV_ADMIN_API_KEY` is now called `system administrator`
  (id `00000000-0000-0000-0000-200000000001`). The names `system administrator` and `trial validator` are reserved; the
  name `bootstrap` is an ordinary name again.

## Steps

1. **Back up the database.**
2. **Give every client a key.** Either create a VALIDATE key per client
   (`fv-admin key create --org <org> --name <name> --role VALIDATE`), or set `FV_VALIDATOR_API_KEY` to a shared key
   (32–128 characters of `[A-Za-z0-9_-]`, different from `FV_ADMIN_API_KEY`). That key is installed at every start as
   `trial validator`, a VALIDATE key of the new **trial** organisation: it may use shareable content only, like
   anonymous callers could before.
3. **Remove `FV_AUTH_ANONYMOUS_VALIDATE`** from your environment.
4. **Stop every 4.0 instance.**
5. **Start 4.1.0.** Migrations V10 to V12 run automatically, then the service makes sure both well-known organisations
   exist and installs the configured keys.
6. Optionally set the new settings (below), and plan retention for usage records if you enable the `db` sink.

Rollback: stop 4.1.0 and restore the backup from step 1. V11 can't be reversed in place while 4.0 code expects the old
id.

## Database

- `V10__api_key_expiry_and_audit.sql`: `api_keys` gains `expires_at` (NULL = never) and `last_used_ip`.
- `V11__well_known_organisations.sql`: re-keys the system organisation and every key, IG package and included context
  it owns; changes the column defaults to the new id; seeds the **trial** organisation
  (`00000000-0000-0000-0000-100000000002`, `trial`, "Free trial validation service", non-commercial); renames the
  `bootstrap` key.
- `V12__validation_usage.sql`: the `validation_usage` table, written only by the `db` usage sink.

## If startup fails

The service stops (exit 1) instead of running half-configured when:

- **another organisation is already called `trial`**: `An organisation named 'trial' already exists with another id;
  rename it before starting 4.1.0`. Rename it (`fv-admin org update --name-short trial --rename <other>`) and start
  again.
- **the system administrator key conflicts**: `Cannot install the 'system administrator' key: another key already uses
  its name (api_keys_name_key)` or `... its value (api_keys_key_hash_key)`. V11 skips the rename when a key called
  `system administrator` already exists, so the old `bootstrap` row and that key are both still there. Rename or revoke
  the conflicting key (`fv-admin key revoke --name <name>`), then restart.
- **`FV_VALIDATOR_API_KEY` is malformed or equals `FV_ADMIN_API_KEY`**: a configuration error names the variable.

## New settings (all optional)

| Variable | Default | Purpose |
|---|---|---|
| `FV_VALIDATOR_API_KEY` | *(unset: warning at startup)* | the trial organisation's shared VALIDATE key |
| `FV_AUTH_ADMIN_CIDRS` | *(unset: no restriction)* | comma-separated IPv4/IPv6 networks administrative operations may come from; others get 403 for every key |
| `FV_AUTH_TRUSTED_PROXIES` | *(unset: forwarded headers ignored)* | proxies whose `Forwarded` / `X-Forwarded-For` headers are believed (right-most untrusted address wins) |
| `FV_USAGE_SINK` | `none` | `log`, `db` or `log,db`: where usage records go |
| `FV_USAGE_QUEUE_SIZE` / `FV_USAGE_BATCH_SIZE` / `FV_USAGE_FLUSH_MS` | 10000 / 500 / 1000 | the `db` sink's bounded queue and batching; the batch may not exceed the queue |

## Seeded keys

`system administrator` and `trial validator` are installed from configuration at every start, with an expiry of
2099-12-30T23:59:59Z. They are rotated by changing `FV_ADMIN_API_KEY` / `FV_VALIDATOR_API_KEY` and restarting, and
revoked by unsetting the variable and restarting (the start revokes the key it installed earlier). `fv-admin key
revoke` refuses them, because the next start would install them again.

## Usage retention

With `FV_USAGE_SINK=db`, every authorised validation adds one row (about 250 bytes) to
`fhir_validator_schema.validation_usage`. Nothing deletes old rows. Schedule a cleanup, for example:

```sql
DELETE FROM fhir_validator_schema.validation_usage WHERE created_at < now() - interval '90 days';
```
