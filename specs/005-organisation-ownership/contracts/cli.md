# Command-Line Contract: Organisations, Keys and Access (3.1.0)

The command line is in the service jar. When the first argument is a command group, it runs that command instead of
starting the server:

```
java -jar fhir-validation-service.jar <group> <command> [options]
docker compose run --rm validator fv-admin <group> <command> [options]   # same image and environment; fv-admin wraps java -jar
```

**Configuration**: the same `FV_PG_*` environment as the service. Migrations are applied first. No HTTP server is
started.

**Output**: stdout is a single JSON document on success. Errors go to stderr as a single line,
`error: <code>: <message>`. Logs go to stderr.

**Exit codes**:

| Code | Meaning |
|---|---|
| 0 | success |
| 1 | unexpected failure (database unreachable, …) |
| 2 | usage or validation error (unknown flag, missing required field, malformed value) |
| 3 | not found (organisation, key, package, context) |
| 4 | conflict (duplicate short name or key name; system organisation protection; package owned elsewhere) |

## Organisation JSON (file input and output)

```json
{
  "id": "4f0c…",                      // output only
  "nameShort": "acme",                // required on create
  "nameLong": "Acme Health ApS",      // required on create
  "type": "COMMERCIAL",               // required on create: COMMERCIAL | NON_COMMERCIAL (SYSTEM is refused)
  "active": true,                     // output only (use activate/deactivate)
  "addressLines": ["Street 1", "2. sal"],
  "postCode": "2100",
  "country": "DK",
  "email": "info@acme.example",
  "phone": "+45 12 34 56 78",
  "website": "https://acme.example",
  "contactPersonName": "Jane Doe",
  "contactPersonMobile": "+45 87 65 43 21",
  "contactPersonEmail": "jane@acme.example",
  "createdAt": "2026-11-06T10:00:00Z", // output only
  "updatedAt": null                     // output only
}
```

Unknown fields in the file give exit code 2. Output-only fields in the file are ignored.

## Field flags (create and update)

`--name-short`, `--name-long`, `--type`, `--address-line` (repeatable; replaces the whole list), `--post-code`,
`--country`, `--email`, `--phone`, `--website`, `--contact-person-name`, `--contact-person-mobile` and
`--contact-person-email`. An empty value (`--email ""`) clears an optional field on update.

**Precedence**: the `--file` value is read first, then each flag replaces its field (FR-023).

## `org` commands

| Command | Options | Result (stdout) |
|---|---|---|
| `org create` | `[--file f.json]` and field flags | the created organisation |
| `org update` | `--id <uuid>` or `--name-short <name>` (selector); `[--file]`; field flags; `--rename <new short name>` | the updated organisation. Only the given fields change |
| `org deactivate` | selector | the organisation (`active: false`). Exit 4 for the system organisation |
| `org activate` | selector | the organisation (`active: true`) |
| `org show` | selector | the organisation, plus `keys: [{name, role, revoked}]` |
| `org list` | `[--active true\|false]` | `[organisation, …]` ordered by `nameShort` |

For `update`, `--name-short` is the selector, and `--rename` changes the short name. A file passed to update may
contain `nameShort` only when it equals the selector.

## `key` commands

| Command | Options | Result |
|---|---|---|
| `key create` | `--org <name>` `--name <key name>` `--role ADMIN\|VALIDATE` `[--key-stdin]` | `{name, role, org, key}`. `key` is printed **only here, only once**. With `--key-stdin`, the key is read from stdin, must match `[A-Za-z0-9_-]{32,128}`, and isn't echoed back |
| `key revoke` | `--name <key name>` | `{name, revoked: true}` |
| `key list` | `[--org <name>]` | `[{name, role, org, createdAt, lastUsedAt, revoked}]`. Never the key or its hash |

The name `bootstrap` is refused (exit 4). It belongs to `FV_ADMIN_API_KEY`.

## `package` and `snapshot` commands (FR-019)

The command line acts as the system organisation: an operator with database access is trusted.

| Command | Options | Result |
|---|---|---|
| `package share` | `--ig name#version` `--shareable true\|false` | `{name, version, owner, shareable}` |
| `package transfer` | `--ig name#version` `--to <org>` | same |
| `snapshot share` | `--fhir-version R4` `--ig name#version` `--shareable true\|false` | `{fhirVersion, igPackage, owner, shareable, revision}` |
| `snapshot transfer` | `--fhir-version R4` `--ig name#version` `--to <org>` | same |

These commands commit with the same revision bump and notification as the HTTP operations, so running instances
apply them within the propagation window. A target organisation that is inactive or unknown gives exit 2.

## Examples

```bash
java -jar app.jar org create --file acme.json --email ops@acme.example
java -jar app.jar key create --org acme --name acme-ci --role VALIDATE
java -jar app.jar org deactivate --name-short acme
java -jar app.jar package share --ig acme.fhir.core#1.0.0 --shareable false
```
