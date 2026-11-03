# Contract: `scripts/export-custom-profiles.sh`

This script runs **before** upgrading to 2.0.0, against the 1.x database, and turns stored custom profiles
into IG packages that can be uploaded (FR-014, FR-015a). It is read-only against the database.

## Requirements

`sh`, `psql`, `tar` and `gzip`. It does not need `jq` or Java.

## Connection

The standard libpq variables: `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER` and `PGPASSWORD`, or a
`~/.pgpass` file. If one of these is unset and the matching `FV_PG_HOST`, `FV_PG_PORT`, `FV_PG_DATABASE`,
`FV_PG_USER` or `FV_PG_PASSWORD` variable is set, the script uses the `FV_PG_*` value. Passwords are
never accepted as arguments.

## Usage

```text
scripts/export-custom-profiles.sh --check
scripts/export-custom-profiles.sh [--out DIR] [--name NAME] [--package-version VER]
                                  [--dependency NAME#VERSION]...
```

| Option | Default | Meaning |
|---|---|---|
| `--check` | | Prints `<fhirVersion> <count>` per FHIR version, plus `total <n>`, writes nothing and exits 0 |
| `--out DIR` | `./custom-profiles-export` | Output directory. It is created if missing, and the script refuses to overwrite existing `.tgz` files |
| `--name NAME` | `local.custom.profiles` | Package name prefix. The FHIR release is appended: `local.custom.profiles.r4` |
| `--package-version VER` | `1.0.0` | Version of every generated package |
| `--dependency NAME#VERSION` | none | Extra dependency written into **every** generated `package.json`. It can be repeated |

## Output

One archive per FHIR version that has profiles:
`DIR/<name>.<rel>-<VER>.tgz`, where `<rel>` is `stu3`, `r4`, `r4b` or `r5` for stored `fhir_version` values
`3.0.2`, `4.0.1`, `4.3.0` and `5.0.0`.

Archive layout:

```text
package/package.json
package/StructureDefinition-1.json
package/StructureDefinition-2.json
...
```

`package.json`:

```json
{
  "name": "local.custom.profiles.r4",
  "version": "1.0.0",
  "type": "fhir.ig",
  "fhirVersions": ["4.0.1"],
  "dependencies": { "hl7.fhir.r4.core": "4.0.1", "hl7.fhir.dk.core": "3.4.0" }
}
```

Each `StructureDefinition-<n>.json` holds `profile_json::text` exactly as stored, numbered in `url` order.

It also writes `DIR/MANIFEST.txt` with the lines `<archive> <url>`, so the operator can compare the
contents with the `--check` counts.

## Exit codes

| Code | Meaning |
|---|---|
| 0 | Success. This includes "no custom profiles found", in which case nothing is written and a line says so |
| 1 | Bad arguments, or the output already exists |
| 2 | Database connection or query failed. Any partial output is removed |
| 3 | The table `fhir_profiles` doesn't exist: the database was already upgraded, so it's too late to export |

## Acceptance (from `scripts/upgrade-rehearsal-2.0.sh`)

Starting from a 1.2.0 database with the dk Patient profile registered, `--dependency hl7.fhir.dk.core#3.4.0`
produces `local.custom.profiles.r4-1.0.0.tgz`. After upgrading, uploading that archive and including it
for R4 gives the same error-level issues and `appliedProfiles` for the dk fixtures as 1.2.0 did.
