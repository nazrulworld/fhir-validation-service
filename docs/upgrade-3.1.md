# Upgrading to 3.1.0

3.1.0 adds **organisations**. Every API key, IG package and included IG (validator context) now belongs to one, and
content can be private to its organisation or shareable with everyone. The upgrade is additive: with the defaults,
previous clients see the same results, status codes and response fields (plus new, additive ones).

- Everything that exists when you upgrade, the bootstrap key included, belongs to the built-in **system
  organisation** and is **shareable**.
- New uploads, registrations and includes are owned by the caller's organisation and shareable by default.
- Nothing becomes private until somebody asks for it (`"shareable": false`, `PATCH`, or the command line).

Order of steps: **back up → roll out 3.1.0 → verify → create organisations and keys**.

## 1. Back up the database

```bash
docker compose exec -T postgres pg_dump -Fc -U postgres -d fhir_validator > fhir_validator-pre-3.1.dump
```

## 2. Roll out 3.1.0

```bash
FV_TAG=3.1.0 docker compose up -d --wait            # or scripts/rolling-restart.sh for several instances
```

Migration V8 adds the `organisations` table (with the system organisation), an `owner` column on `api_keys` and
`fhir_implementation_guides`, `is_shareable` on `fhir_implementation_guides`, and the `fhir_validator_snapshots` table
for the access of included IGs (one system-owned, shareable row per IG already included).

**A rolling upgrade works.** Every new column has a database default (owner = system organisation, shareable), so
3.0 instances that don't know the columns keep writing valid rows while 3.1 instances start. But 3.0 instances don't
check access: **create organisations, non-system keys and private content only after every instance runs 3.1.0.**

## 3. Configure the system organisation (optional)

`FV_SYSTEM_ORG_*` variables are applied to the system organisation at every start; unset fields keep their stored
values. Its type and active state can't be configured.

| Variable | Field |
|---|---|
| `FV_SYSTEM_ORG_NAME_SHORT` | short name (default `system`; shown as the owner of system content) |
| `FV_SYSTEM_ORG_NAME_LONG` | long name (default `FHIR Validation Service`) |
| `FV_SYSTEM_ORG_EMAIL`, `FV_SYSTEM_ORG_PHONE`, `FV_SYSTEM_ORG_WEBSITE` | contact details |
| `FV_SYSTEM_ORG_COUNTRY` | ISO 3166-1 alpha-2 code, e.g. `DK` |
| `FV_SYSTEM_ORG_POST_CODE`, `FV_SYSTEM_ORG_ADDRESS_LINES` | address; lines separated by `\|` |
| `FV_SYSTEM_ORG_CONTACT_PERSON_NAME`, `_MOBILE`, `_EMAIL` | contact person |

An invalid value stops start-up with a message naming the variable.

## 4. Verify

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST -H 'Content-Type: application/fhir+json' \
  --data '{"resourceType":"Patient"}' http://127.0.0.1:8880/R4/validate    # 200, X-Validation-Context-Owner: system
docker compose run --rm validator fv-admin org list                        # the system organisation
```

## 5. Organisations and keys: use the command line

Raw SQL on `fhir_validator_schema.api_keys` is no longer needed (and would create system-owned keys). Use `fv-admin`,
a wrapper around `java -jar /app/app.jar` in the image, with the same environment as the service:

```bash
APP="docker compose run --rm validator fv-admin"
$APP org create --name-short acme --name-long "Acme Health ApS" --type COMMERCIAL --country DK
$APP key create --org acme --name acme-ci --role VALIDATE        # prints the key once
$APP org deactivate --name-short acme                             # its keys get 403 within FV_AUTH_CACHE_TTL_MS
```

Keys created by SQL before 3.1.0 keep working; they belong to the system organisation. To move a client to its own
organisation, create a key for it with `key create --org …`, switch the client, then `key revoke` the old key.
Reference: [`specs/005-organisation-ownership/contracts/cli.md`](../specs/005-organisation-ownership/contracts/cli.md).

## 6. Rollback

Restore the backup and start 3.0.0. V8 only adds columns with defaults and new tables, so a 3.0 instance also runs on
the V8 schema, but it ignores ownership: private content becomes usable by everyone again. Restoring the backup is the
only way to remove V8 itself.

## What else changed

- `POST /igs/upload` (or a `downloadUrl` registration) of a `name#version` another organisation stored answers **409
  `conflict`** instead of replacing it. The system organisation may still replace any package; a replacement keeps the
  stored owner and sharing.
- A key whose organisation is deactivated answers **403 `forbidden`**.
- `/health` lists only fully shareable contexts; the others are counted in `privateContexts` by state. Owners are never
  listed.
- New administrative operations: `PATCH /igs/{name}/{version}` and `PATCH /{version}/include-ig?ig=name#version`
  change sharing (owner or system organisation) or transfer ownership (system organisation only).
