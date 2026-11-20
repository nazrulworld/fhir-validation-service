# Research: Multi-IG Validator Contexts

**Feature**: `012-multi-ig-validator-contexts` | **Date**: 2026-11-19 | **Spec**: [spec.md](./spec.md)

The Technical Context had no open unknowns: the stack is fixed by the constitution. The questions below are design
decisions found by reading the current code (1.1.0); each closes one way the feature could be built.

## R1. How a combined context is identified and stored

**Decision**: Keep one string key per context. A combined context's key is `A#a|B#b` and is stored where single keys are
stored today: `fhir_validator_logs.included_ig_packages` (TEXT[]) and `fhir_validator_snapshots.ig_package` (primary key
with `fhir_version`). `SnapshotKey.igPackage` keeps holding the key; `SnapshotKey` gains `members()` (one or two
`name#version`, in key order) and `isCombined()`. The `X-Validation-Context` header, usage rows, `/health` listings and
log lines use the key as they use `name#version` today.

**Rationale**: Every piece of propagation (`ConfigRevisionRepository`, `ConfigSync`, `PinnedAccess`,
`TerminologyDefaults`, `ContextActivation`, `pg_notify` payloads) is keyed by that one string. Keeping one string means
none of it changes shape; only code that reads the key *as a package* (listed in R6) changes.

**Alternatives considered**:
- A separate `fhir_validator_snapshot_members` table with a surrogate id: cleaner relationally, but every cache,
  notification and API that is keyed by `ig_package` would need a second identity. Rejected as far more change for no
  behaviour gain at a fixed maximum of two members.
- Sorting members into a canonical key: rejected by the spec (the key keeps include order; FR-007).

## R2. Column widths

**Decision**: Migration **V17** widens `fhir_validator_snapshots.ig_package` and `validation_usage.ig` from
`VARCHAR(192)` to `VARCHAR(256)`. An include whose combined key would exceed 256 characters is refused with 400
invalid-request before anything is stored.

**Rationale**: The validate `ig` parameter is limited to 256 characters (FR-010), so a longer key could be included but
never named. 256 matches the parameter. Widening a `VARCHAR` in PostgreSQL is a catalog-only change (no rewrite), and
`included_ig_packages` is `TEXT[]` already.

**Alternatives considered**: `TEXT` (no limit, but the contract limit would then live only in code); raising the `ig`
limit to 385 (2 × (127 + 1 + 64) + 1): rejected, real names are far shorter and the 256 limit is already published.

Note: memory of "next migration is V16" is outdated; V16 (`V16__usage_terminology.sql`) shipped in 1.1.0.

## R3. Aliases: stored or derived?

**Decision**: Derived, never stored. Each instance builds an in-memory `ContextAliases` index per FHIR version from the
included key set it already syncs: for each combined key `A#a|B#b`, `B#b|A#a → key` and `A#a → key`. It is rebuilt
whenever `ConfigSync` applies a new revision, so it is always consistent with the included set (Principle II).

The two uniqueness rules are enforced twice:
1. In the include transaction, under the existing `lockForUpdate` on the version's config row (includes already
   serialise there): reversed pair already included → "already included" (FR-008); first IG already the first member
   of another combined key → 409 `alias-conflict` (FR-012).
2. As defence in depth, V17 adds two partial unique indexes on `fhir_validator_snapshots` for combined rows:
   `(fhir_version, split_part(ig_package,'|',1))` and
   `(fhir_version, LEAST(split_part(..,1), split_part(..,2)), GREATEST(...))`.

**Rationale**: Aliases are a pure function of the key set, so storing them would add a second thing to keep consistent.
The indexes catch any write path that bypasses the service check (for example hand-written SQL).

**Alternatives considered**: an `aliases TEXT[]` column (redundant state); free-form administrator aliases (out of scope).

## R4. Dependency resolution and the conflict rule (FR-006, FR-006a)

**Finding**: `DependencyResolver.resolve(Collection<String> roots, …)` already accepts several roots. When a package is
reached in several versions it keeps the highest and adds a warning; this is today's rule *inside* one IG's tree and it
is unchanged by this feature.

**Decision**: A new `DependencyResolver.conflicts(rootA, rootB, catalog, selectable)`:
1. Resolve A alone and B alone with today's rules (each tree's internal choices stay as they are).
2. For every package name present in both results with different versions: if the name starts with `hl7.terminology` or
   `hl7.fhir.uv.extensions` it is **exempt** (recorded for the warning); otherwise it is a **conflict**. A root of one
   tree found in the other in a different version is compared the same way (FR-006, last sentence).
3. Any conflict → the include fails with 400 `dependency-conflict` naming the package and both versions — unless the
   caller may not use one of the conflicting versions, which answers 424 `dependency-not-found` as today (no naming).
4. No conflict → the context's package set is `resolve({A, B})`, whose highest-wins rule produces exactly the exempt
   choice FR-006a asks for. The include response's `warnings` lists each exempt choice.

Conflicts are checked at include time only. A later rebuild (for example after a package is replaced) resolves
`resolve({A, B})` again with the same deterministic rules, as single-IG contexts do today.

**On "stored with the context" (FR-006a)**: the choice is not written as a separate column. It is a deterministic
function of the stored key and the stored package catalog, which is already the shared state every instance resolves
from (the same guarantee single-IG contexts rely on, spec 004 research R3). FR-006a was amended to say exactly this
(analysis finding I1, 2026-11-19).

**Alternatives considered**: rejecting at rebuild time too (would make a stored context unbuildable after an unrelated
catalog change; rejected), letting HAPI load both versions (duplicate canonicals, nondeterministic precedence;
rejected by Principle III).

## R5. Resolving `ig` on validation (FR-010 to FR-013, FR-012a)

**Decision**: `IgReference` becomes a sealed type: `Single(name, version)` (today's record) and
`Pair(Single first, Single second)`. Parsing: split on one `|` (after URL decoding, so `%7C` and `|` are equal); more
than one `|`, an empty side, a side without an exact version, a version series, or the same package name on both sides
→ 400 invalid-request. Resolution, all from memory:

| Request | Lookup order |
|---|---|
| `Pair(A#a, B#b)` | included key `A#a\|B#b` → alias `A#a\|B#b` (i.e. key `B#b\|A#a`) → 400 "not included" |
| `Single(A, exact a)` | included single key `A#a` → first-IG alias `A#a` → today's path (stored but not included → 400) |
| `Single(A, latest/none)` | today's rule, **over single keys only** (FR-012a) |

FHIR-version and visibility checks run per member before "not included", in member order, so no 400 reveals a package
the caller can't see (today's FR-024 ordering).

**Bug to avoid**: `IgReference.highestIncluded` matches included keys with `key.startsWith(name + "#")`. A combined
key `A#2.0.0|B#1.0.0` also starts with `A#`, so without a filter `ig=A` would pick it. The filter on single keys is the
whole of FR-012a and gets a regression test.

## R6. Code that reads the key as one package

Found with `grep igPackage()`; each needs the member-aware form:

| Place | Today | Change |
|---|---|---|
| `ResolvedContext.of/forKey` | roots `Set.of(key)` | roots `key.members()`; both stay in the list if not stored |
| `AccessPolicy.canUseContext`, `isPublic` | the one IG must be usable | every member must be usable (`IG_NOT_VISIBLE`); dependencies as today |
| `FhirValidationService.checkAccess` | `ig.label()` in 404 | label of the request as given (never reveals which member failed) |
| `IgPackageService.include` | one name/version | list of 1–2 entries; per-member resolution, version check, conflict check, alias check, private-context rule (owner of **both** packages, or system) |
| `IgPackageService.exclude`, `changeSnapshotAccess`; `cli/AccessCommands` | `IgReference.parse` + exact version | accept a pair; canonicalise reversed pair to the stored key; a single value addresses only a single key (FR-017) |
| `ValidatorRegistry` health listing | `key` | add `igPackages` and `aliases` |
| `ValidatorSnapshot` unknown-profile message | `key.igPackage()` | key text, unchanged format |
| `SnapshotKey` constructor | requires `#` at index > 0 | accept one or two members, each `name#version` |

## R7. Memory admission and shared definitions

**Decision**: No change. `admitBuild` already sums expanded bytes of the resolved package set, counting packages shared
on this instance for their indexes only (spec 010 FR-024). A combined context's set is larger, but measured the same way.
Packages shared with the members' single-IG contexts are parsed once (spec 010), so a combined context of two already
included IGs costs roughly its indexes.

## R8. Contract and release

**Decision**: All additions to `openapi.yaml` (details in [contracts/http-api.md](./contracts/http-api.md)): optional
`igPackages` on the include body; new response fields `igPackages`, `aliases`; `ig` pair form on validate, PATCH and
DELETE; two new problem types. No existing request changes status or body, so no Complexity Tracking entry is needed.
Ships as **1.2.0** (1.x line per the maintainer). The two problem types get rows in the API Documentation wiki
problem-type table so `scripts/build-docs-site.py` publishes `/problems/dependency-conflict` and
`/problems/alias-conflict` (spec 011).
