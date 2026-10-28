# Implementation Plan: PolyForm Noncommercial 1.0.0 Licensing

**Branch**: `002-polyform-noncommercial-license` | **Date**: 2026-10-27 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/002-polyform-noncommercial-license/spec.md`

## Summary

The project is licensed under PolyForm Noncommercial 1.0.0. The work covers four areas:
- **Repository**: a root `LICENSE.md` containing the official text, pinned by hash, behind a
  `Required Notice` line.
- **Documentation**: the README (badge, License section, commercial contact Md Nazrul Islam,
  email2nazrul@gmail.com), a new `CONTRIBUTING.md` and a changelog entry.
- **Maven**: `pom.xml` gets `<licenses>`/`<developers>` metadata. `license-maven-plugin` generates
  a third-party listing from the shipped dependency tree; the build fails when a license is
  unknown or not covered. Committed license texts and a clarifications file go in the jar,
  and shade transformers merge dependency NOTICE files.
- **Container image**: the Dockerfile extracts the same files from the jar into
  `/app/LICENSE.md` and `/app/licenses/`, and adds the OCI `licenses`/`authors` labels.

Tests and a packaging check script verify all of this in CI.

## Technical Context

**Language/Version**: Java 17 (Maven, shaded jar)

**Primary Dependencies**:
- Existing: maven-shade-plugin 3.5.1, with `ApacheNoticeResourceTransformer` and
  `ApacheLicenseResourceTransformer` added.
- New, build-only: `org.codehaus.mojo:license-maven-plugin` 2.4.0. No runtime dependency is
  added.

**Storage**: N/A. The feature adds files only; there are no schema changes.

**Testing**:
- JUnit 5 unit tests: `LicenseFileTest`, `ThirdPartyNoticesTest`.
- Shell check: `scripts/check-license-packaging.sh` for the jar and the image, run in CI.

**Target Platform**: Shaded jar (GitHub Packages); container image for linux/amd64 and
linux/arm64 (GHCR).

**Project Type**: Web service (single Maven module)

**Performance Goals**: No change at runtime. Build time grows by about 2–5 s for
`add-third-party`.

**Constraints**:
- The Docker build stays offline after `dependency:go-offline`; the license texts are
  committed, not downloaded.
- The license body must stay byte-identical to upstream.

**Scale/Scope**: About 142 bundled runtime artifacts and 8 shipped license texts (research R5).
The build's license allowlist holds exactly those 8 licenses (research R4).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Assessment |
|-----------|------------|
| I. Non-blocking event loop | Not affected; no runtime code |
| II. Stateless / scalable | Not affected; no shared state |
| III. FHIR conformance | Not affected |
| IV. Contract-first API | `openapi.yaml` is unchanged. A license URL in `info.license` is optional and left out to keep the contract diff at zero |
| V. Test-first | `LicenseFileTest` and `ThirdPartyNoticesTest` are written before the files and plugin config they check. No I/O beyond reading build files, so no Testcontainers are needed |
| VI. Performance budgets | Stated above: no runtime latency or memory impact. The image grows by about 100 KB of text. No benchmark is needed, since the hot path is untouched |
| VII. Observability | Not affected. No secrets are added; the contact email is public by design |
| VIII. Dependency discipline | New **build-only** plugin `license-maven-plugin`. Justification: FR-014/015 require a listing generated from the real dependency tree that fails the build when a license is unknown. **Scalability benefit**: none and none needed. It runs only at build time, adds no runtime component, infrastructure or per-instance state, and leaves the horizontal-scaling properties of Principle II unchanged. The simpler alternative, a hand-maintained list, was rejected because it goes stale on every bump (research R4). The version is a pom property |
| Tech constraints | Versions stay centralised as properties. Images remain multi-arch |
| Workflow gates | `mvn verify` passes. The changelog is updated (FR-010) |

**Result**: PASS with no violations, so Complexity Tracking is empty.
**Post-design re-check**: PASS. The design adds files, build configuration, two unit tests and
one CI script; no principle is touched.

## Project Structure

### Documentation (this feature)

```text
specs/002-polyform-noncommercial-license/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── distribution-layout.md
├── checklists/requirements.md
└── tasks.md             # /speckit-tasks
```

### Source Code (repository root)

```text
LICENSE.md                                   # new: Required Notice + official text
CONTRIBUTING.md                              # new
README.md                                    # badge + ## License section
CHANGELOG.md                                 # [Unreleased] entry
pom.xml                                      # url, licenses, developers, scm, license plugin, shade transformers, resources
Dockerfile                                   # extract notices from jar → /app; OCI labels
.github/workflows/ci.yml                     # run scripts/check-license-packaging.sh
scripts/check-license-packaging.sh           # new
src/license/THIRD-PARTY.properties           # new: missing-license overrides
src/main/resources/META-INF/
├── THIRD-PARTY-CLARIFICATIONS.md            # new
└── third-party-licenses/<SPDX-id>.txt       # new: ~8 texts
docker/licenses/IMAGE-COMPONENTS.md          # new: copied into the image only (not the jar)
src/test/java/nzi/fhir/validator/licensing/
├── LicenseFileTest.java                     # new
└── ThirdPartyNoticesTest.java               # new
```

**Structure Decision**: This stays a single Maven module. All licensing content is either
committed under `src/main/resources/META-INF/` or generated into `target/classes/META-INF/`,
so the jar is the one assembled source of notices. The image copies them from the jar
(research R7).

## Notes for implementation

- **Required Notice line**: `Required Notice: Copyright 2025-2026 Md Nazrul Islam (https://github.com/nazrulworld)`.
- **GitHub label**: GitHub labels the license *Other* (research R3). FR-003 and SC-001 were
  adjusted, so this is expected and not a defect.
- **Version**: The release that ships this is 1.2.0 (MINOR). The version bump itself belongs
  to the release PR, not to this feature.
- **Publish workflow labels**: `docker-publish.yml` applies `docker/metadata-action` labels, which
  override Dockerfile `LABEL`s. Its `org.opencontainers.image.licenses` comes from GitHub's
  detected license, which is "Other" (`NOASSERTION`). The workflow must therefore set the label
  explicitly (research R7, task T020).
- **Stale file**: `dependency-reduced-pom.xml` at the repository root is unused
  (`createDependencyReducedPom=false`) and out of scope.

## Complexity Tracking

No violations.
