# Data Model: Multi-IG Validator Contexts

**Feature**: `012-multi-ig-validator-contexts` | **Date**: 2026-11-19 | Decisions: [research.md](./research.md)

## 1. Context key (`SnapshotKey`)

| Field | Type | Rule |
|---|---|---|
| `fhirVersion` | `SupportedFhirVersion` | unchanged |
| `igPackage` | `String` or `null` | `null` = base; `A#a` = single; `A#a\|B#b` = combined. At most 256 characters |
| `members()` | `List<String>` (derived) | `[]` for base, `[A#a]` or `[A#a, B#b]` in key order |
| `isCombined()` | `boolean` (derived) | two members |

Validation: each member is a concrete `name#version` (no `latest`, no series); two members never share a package name;
no more than one `|`. `contextLabel()` (the `X-Validation-Context` value) stays `igPackage` or `base`.

## 2. Context aliases (`ContextAliases`, in memory, per FHIR version)

Derived from the included key set on every applied revision; never stored (research R3).

| Alias | Target | Exists when |
|---|---|---|
| `B#b\|A#a` | `A#a\|B#b` | always, for every combined key |
| `A#a` | `A#a\|B#b` | always; used for validation only when no single key `A#a` is included (FR-012) |

Invariants (enforced in the include transaction and by V17 indexes):
- At most one included combined key per unordered member pair per FHIR version.
- At most one included combined key per first member per FHIR version.

## 3. Stored state (PostgreSQL)

No new table. Changes in **V17** (`V17__multi_ig_contexts.sql`):

| Object | Change |
|---|---|
| `fhir_validator_snapshots.ig_package` | `VARCHAR(192)` → `VARCHAR(256)` |
| `validation_usage.ig` | `VARCHAR(192)` → `VARCHAR(256)` |
| `fhir_validator_snapshots_first_member_uq` | unique `(fhir_version, split_part(ig_package,'\|',1))` `WHERE strpos(ig_package,'\|') > 0` |
| `fhir_validator_snapshots_pair_uq` | unique `(fhir_version, LEAST(m1,m2), GREATEST(m1,m2))` `WHERE strpos(ig_package,'\|') > 0`, with `m1`/`m2` the two `split_part` values |

`fhir_validator_logs.included_ig_packages` (TEXT[]) holds combined keys as is. Owner, sharing, terminology and the
active flag of a combined context live in its `fhir_validator_snapshots` row, like any context. Exclude deletes the row.

## 4. Include request entry

| Field | Required | Values |
|---|---|---|
| `igPackageId` | yes | package name |
| `igPackageVersion` | no | exact, `latest`/omitted, or series `N.x`, `N.x.x`, `N.N.x` (spec 006) |

The body has either the top-level `igPackageId`/`igPackageVersion` (today) or `igPackages` with 1–2 entries, never both.

## 5. Resolved context (`ResolvedContext`)

Unchanged fields (`packages`, `hashes`, `missing`); the roots are `key.members()`. Resolution: `resolve(members)` with
today's highest-wins rule, after the include-time conflict check (research R4).

## 6. Dependency conflict (include-time result)

| Field | Meaning |
|---|---|
| `package` | the package name required in two versions |
| `versions` | `{ "<A#a>": "x", "<B#b>": "y" }`: the version each member's tree needs |
| `exempt` | `true` for `hl7.terminology*` / `hl7.fhir.uv.extensions*` (becomes a warning), `false` otherwise (400) |

## 7. State transitions of a combined context

```text
(absent) --include A|B------------------------------> included (pinned on every instance)
(absent) --include B|A while A|B included-----------> unchanged, response reports A|B
(absent) --include A|C while A|B included-----------> refused 409 alias-conflict
included --PATCH key or reversed pair---------------> included (access/terminology changed, no rebuild)
included --DELETE key or reversed pair--------------> absent (aliases gone on every instance)
included --FHIR version disabled--------------------> inactive (as today) --enabled--> included
included --member/dependency package replaced-------> rebuilt with the same key (as today)
```

Including or excluding a single key `A#a` never changes a combined context; it only changes which context `ig=A#a`
reaches (FR-012).
