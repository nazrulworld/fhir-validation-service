# Implementation Plan: Multi-IG Validator Contexts (Up to Two IGs per Context, with Aliases)

**Branch**: `012-multi-ig-validator-contexts` | **Date**: 2026-11-19 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/012-multi-ig-validator-contexts/spec.md`

## Summary

Let an administrator include one validator context built from two IG packages and all their dependencies, keyed
`A#a|B#b` in include order, findable by the reversed pair and (for exact single-IG requests with no single-IG context)
by its first IG. Validation looks up the exact key first, then aliases, and never builds a context.

Technical approach (see [research.md](./research.md)): keep the key a single string so all existing propagation
(config row, snapshots table, `pg_notify`, `ConfigSync`, `PinnedAccess`, terminology defaults) works unchanged; give
`SnapshotKey` a `members()` view and make the few places that treat the key as one package member-aware; derive
aliases in memory from the included key set on each revision; add an include-time conflict check on top of the
existing multi-root `DependencyResolver`, exempting `hl7.terminology*` and `hl7.fhir.uv.extensions*`. One forward-only
migration (V17) widens two columns and adds two partial unique indexes. Ships as **1.2.0**.

## Technical Context

**Language/Version**: Java 17

**Primary Dependencies**: Vert.x 4.5.x (web, web-openapi, pg-client), HAPI FHIR 8.x, Caffeine, Log4j2 — no new dependency

**Storage**: PostgreSQL via Flyway; migration `V17__multi_ig_contexts.sql` (V16 shipped in 1.1.0)

**Testing**: JUnit 5 + vertx-junit5, Testcontainers PostgreSQL (`BaseTestContainer`, `AppHarness`), pure unit tests
for parsing, conflict rules and alias derivation

**Target Platform**: Linux container (x86_64, arm64), horizontally scaled behind a load balancer

**Project Type**: web service (single Maven project)

**Performance Goals**: single-resource validation p95 unchanged (< 200 ms warm; within 5 % of 1.1.0). Alias lookup is a
hash-map read on the event loop.

**Constraints**: no context built at validation time; at most two IGs per context; key and `ig` ≤ 256 characters;
lookups from memory only (no DB on the validation path)

**Scale/Scope**: tens of included contexts per FHIR version. Memory: a combined context costs its resolved package set
under the existing `HeapBudget`; packages shared with the members' single-IG contexts count for their indexes only
(spec 010), so two already included IGs combined cost roughly their indexes plus HAPI's per-context structures.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design: still passing.*

| Principle | Status | How the design complies |
|---|---|---|
| **I. Non-blocking event loop** | PASS | Include, conflict check and alias check run inside the existing include transaction (async pg-client); resolution is in-memory metadata work as today. Builds stay on `fv-packages`. Alias lookup is an in-memory map read on the event loop, like the included-set read today. No `.get()`/`.join()`. |
| **II. Stateless, horizontally scalable** | PASS | Combined keys are stored where single keys are (config row array, snapshots table); aliases are a derived cache rebuilt from that set on every applied revision. Consistency: include/exclude/PATCH bump the version revision and `pg_notify` as today; the poll catches missed notifications. Each combined context is built at most once per instance (one `SnapshotKey`). |
| **III. FHIR conformance** | PASS | Package sets are deterministic: same two IGs → same set (multi-root resolver, sorted). Conflicting versions of non-base packages are refused rather than silently merged; exempt choices are reported as warnings. SC-006 compares results with `validator_cli.jar -ig A -ig B` on conformance samples. No issue filtered. |
| **IV. Contract-first API** | PASS | `openapi.yaml` changes land first ([contracts/http-api.md](./contracts/http-api.md)). All additive: an optional body field, new response fields, a new `ig` value form, two new problem types on new failure cases. 1.1.0 requests keep status and body, so no Complexity Tracking entry; ships as MINOR 1.2.0. Errors use problem JSON. |
| **V. Test-first, real dependencies** | PASS | Each story starts with a failing IT on Testcontainers: `MultiIgIncludeIT` (US1), `MultiIgValidationIT` (US2), `MultiIgAliasIT` (US3), `MultiIgManageIT` (US4), `MultiIgPropagationIT` (two instances). Unit: `IgReferenceTest` (pair parsing, FR-012a regression), `DependencyConflictTest`, `ContextAliasesTest`, `SnapshotKeyTest`. `MigrationUpgradeIT` covers V17 on a populated 1.1.0 schema. Contract tests for the changed operations. Golden-outcome check that single-IG results are unchanged. |
| **VI. Performance and capacity** | PASS | Impact stated above. The validation path changes (`IgReference` resolution), so a k6 comparison against 1.1.0 goes in the PR. No new setting: combined contexts are bounded by `HeapBudget` admission like any include. |
| **VII. Observability** | PASS | Context key appears in logs, usage rows and existing metric tags exactly as `name#version` does; include logs the members, aliases and exempt choices. `/health` lists members and aliases. Every request log line keeps its request id. |
| **VIII. Simplicity, dependencies** | PASS | No new dependency, table or infrastructure. One forward-only migration (V17). Aliases derived, not stored. |
| **Security & compliance** | PASS | Access is checked for every member and dependency before any answer; a member the caller can't use answers 404 without naming which; conflict details never name a package or version the caller can't use (424 instead). `alias-conflict` names the other context only when the caller may use it. Making a combined context private requires owning both IG packages (or system). |
| **Workflow gate: migrations on the previous release's schema** | PASS | V17 is a catalog-only widen plus two partial indexes that existing (single-key) rows don't match. `MigrationUpgradeIT` applies it to a populated V16 database. A 1.1.0 instance on the V17 schema keeps working (it never writes combined keys). |

## Project Structure

### Documentation (this feature)

```text
specs/012-multi-ig-validator-contexts/
├── spec.md
├── plan.md              # this file
├── research.md          # Phase 0
├── data-model.md        # Phase 1
├── quickstart.md        # Phase 1
├── contracts/
│   └── http-api.md      # Phase 1
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 (/speckit-tasks)
```

### Source Code (repository root)

```text
src/main/resources/
├── openapi.yaml                                   # contract first (§1–§4 of contracts/http-api.md)
└── db/migration/V17__multi_ig_contexts.sql        # widen ig_package / usage ig, two partial unique indexes

src/main/java/nzi/fhir/validator/
├── core/validator/
│   ├── SnapshotKey.java          # members(), isCombined(), pair validation
│   ├── IgReference.java          # sealed Single | Pair; pair parsing; lookup order; FR-012a single-key filter
│   ├── ContextAliases.java       # NEW: derived alias index per FHIR version
│   ├── ResolvedContext.java      # roots = key.members()
│   ├── ConfigSync.java           # rebuild ContextAliases on each applied revision
│   └── ValidatorRegistry.java    # health listing: igPackages, aliases
├── core/npm/DependencyResolver.java   # conflicts(rootA, rootB, …) with exempt prefixes
├── core/security/AccessPolicy.java    # every member must be usable
├── core/service/
│   ├── IgPackageService.java          # include(List<entry>), conflict + alias checks; exclude/PATCH canonicalisation
│   ├── ConfigRevisionRepository.java  # unchanged shape; key strings may contain '|'
│   └── FhirValidationService.java     # resolve via aliases; 404 label = requested value
├── web/ProblemException.java          # dependencyConflict(...), aliasConflict(...)
├── web/endpoint/ValidatorApi.java     # parse igPackages; mutual exclusion with igPackageId
├── web/endpoint/ValidationApi.java    # ig pair form
└── cli/AccessCommands.java            # --ig accepts a pair

src/test/java/nzi/fhir/validator/
├── core/validator/  IgReferenceTest, SnapshotKeyTest, ContextAliasesTest, MultiIgIncludeIT, MultiIgAliasIT,
│                    MultiIgPropagationIT
├── core/npm/        DependencyConflictTest
├── web/             MultiIgValidationIT, MultiIgManageIT (+ contract cases)
└── core/service/    MigrationUpgradeIT (V17 case)

CHANGELOG.md, README.md (multi-IG section), wiki API Documentation problem-type rows
```

**Structure Decision**: Single Maven project as today; changes stay in the existing `core/validator`, `core/npm`,
`core/security`, `core/service` and `web` packages, plus one new class (`ContextAliases`).

## Complexity Tracking

No violations; nothing to justify.
