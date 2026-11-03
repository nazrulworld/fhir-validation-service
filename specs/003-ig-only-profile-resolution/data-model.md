# Data Model: IG-Only Profile Resolution

**Feature**: [spec.md](./spec.md) | **Research**: [research.md](./research.md)

This feature adds no entities. It removes one stored entity and one column, and it tightens the rules
for two request and response values.

## Persistent state (PostgreSQL, schema `fhir_validator_schema`)

| Object | Before (1.2.0) | After (2.0.0, migration `V6__remove_custom_profiles.sql`) |
|---|---|---|
| `fhir_profiles` (url, profile_json, fhir_version, created_at, modified_at) | Custom profiles, one row per (url, fhir_version) | **Dropped.** Export it first with `scripts/export-custom-profiles.sh` (see [contracts/export-script.md](./contracts/export-script.md)) |
| `fhir_validator_logs.included_profiles` `TEXT[]` | Never read or written by 1.x code | **Dropped** |
| `fhir_validator_logs` (revision, included_ig_packages, resolved_ig_packages, …) | The revision is bumped by IG **and** profile changes | Unchanged schema. The revision is bumped only by IG changes |
| `fhir_implementation_guides`, `api_keys`, other tables | | Unchanged |

**Migration invariants** (tested in `MigrationUpgradeIT`):
- V6 applies cleanly both to an empty database and on top of a 1.2.0 database (V1–V5).
- After V6, the counts of IG packages, configuration rows and API keys are the same as before.
- V6 uses `IF EXISTS`, so running it on a database where the objects are already gone is a no-op.
- V6 drops `fhir_profiles` even when it has rows. There is no safety gate, and this is an accepted risk.
- There is no down-migration. A rollback restores the backup taken before the upgrade (FR-015b).

## Change notifications (`LISTEN/NOTIFY` channel `fv_config_changed`)

Payload `{fhirVersion, revision, kind, key}`. The only value of `kind` is now `"IG"`. A 2.0.0 instance
ignores `kind`, as 1.x already did: any notification triggers a sync of that version. A leftover 1.x
instance sending `"PROFILE"` during a rolling upgrade is harmless, because 2.0.0 just re-reads the
revision. This harmlessness isn't a reason to allow mixing: a mixed 1.x/2.0 fleet is **not supported**
(FR-015c), because 1.x instances fail their profile refresh once V6 has run. Every 1.x instance stops
before 2.0 starts.

## In-memory state per FHIR version

| Structure | Before | After |
|---|---|---|
| `ValidatorSnapshot` | Packages, the chain, **and a reference to `ProfileStore`**. Re-assembled after each profile change | Packages and the chain. **Immutable for its whole life**, replaced only by a rebuild when the resolved package set changes |
| `ProfileStore` | All custom profiles, loaded at start and refreshed on sync | **Removed** |
| Validation support chain | core → `CustomProfileValidationSupport` → IG packages → terminology → snapshot generation | core → IG packages → terminology → snapshot generation |

## Request and response values

### Requested profiles (`?profile=` on `POST /{version}/validate`)

| Rule | Detail |
|---|---|
| Parsing | Split on `,`, trim each entry, drop empty entries, remove duplicates (keeping the first occurrence) |
| Limits | At most 10 entries after parsing, each at most 1024 characters. Otherwise 400 `invalid-request`, before any lookup (FR-006a) |
| Match | Exact canonical: the requested string equals the StructureDefinition's `url`, or equals `url|version` |
| Source | Only the snapshot's chain: core definitions, then the included packages and their resolved dependencies |
| Duplicate canonical across packages | Core wins over packages. Among packages, the last one in the sorted resolved list wins. Deterministic for a given resolved set |
| Any entry unresolved | 400 `unknown-profile`, with `unresolvedProfiles` listing every unresolved entry in request order; no validation runs |

### `appliedProfiles` (JSON validation response)

This is an ordered, de-duplicated list: the requested profiles first (in request order), then
`meta.profile` entries that resolve under the same exact-match rule. An entry that doesn't resolve
**never** appears. An unknown `meta.profile` is still reported as an ERROR issue by the validator (Q2: A).

## State transitions

None are new. A FHIR version's validator still moves through BUILDING → ACTIVE / STALE, driven only by IG
configuration revisions. The profile-refresh transition (`markStale("profile changes could not be applied")`)
is removed.
