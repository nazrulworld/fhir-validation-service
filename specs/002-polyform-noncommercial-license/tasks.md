---

description: "Task list for PolyForm Noncommercial 1.0.0 licensing"
---

# Tasks: PolyForm Noncommercial 1.0.0 Licensing

**Input**: Design documents from `/specs/002-polyform-noncommercial-license/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/distribution-layout.md, quickstart.md

**Tests**: Included. Constitution Principle V requires test-first, and plan.md names
`LicenseFileTest`, `ThirdPartyNoticesTest` and `scripts/check-license-packaging.sh`. Write each
test before the files it checks, and confirm it fails first.

**Organization**: Tasks are grouped by user story so each story can be implemented and verified
on its own.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: User story from spec.md (US1–US4)
- All paths are relative to the repository root (single Maven module)

## Fixed values (copy exactly)

- **Required Notice**: `Required Notice: Copyright 2025-2026 Md Nazrul Islam (https://github.com/nazrulworld)`
- **Official text**: `https://github.com/polyformproject/polyform-licenses`, tag `1.0.0`
  (commit `76a278c402bc43b8d2b561da140b0f3e17263015`), file `PolyForm-Noncommercial-1.0.0.md`.
  Its SHA-256 is `c0ea4a896d2c8c394b29f9427589996db826cd501c512279ff0ed3ef48fabbe5`.
  - Fetch with `gh api -H 'Accept: application/vnd.github.raw' repos/polyformproject/polyform-licenses/contents/PolyForm-Noncommercial-1.0.0.md?ref=1.0.0`
- **SPDX id**: `PolyForm-Noncommercial-1.0.0`
- **License URL**: `https://polyformproject.org/licenses/noncommercial/1.0.0`
- **Commercial contact / author**: `Md Nazrul Islam <email2nazrul@gmail.com>`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Create the directories and properties that later tasks fill in.

- [X] T001 Create directories `src/license/`, `src/main/resources/META-INF/third-party-licenses/`, `src/test/java/nzi/fhir/validator/licensing/` and `docker/licenses/`
- [X] T002 Add the property `<license-maven-plugin.version>2.4.0</license-maven-plugin.version>` to the `<properties>` block in `pom.xml` (constitution: versions are centralised as properties)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Create the root license file. Every other story copies it or points to it.

**⚠️ CRITICAL**: US2–US4 refer to `LICENSE.md`. It must exist before they are finished.

- [X] T003 Write the failing test `src/test/java/nzi/fhir/validator/licensing/LicenseFileTest.java`. Use JUnit 5 with no Vert.x extension, since this is a pure file unit test. It reads `LICENSE.md` from the project base directory (`Path.of("LICENSE.md")`) and asserts:
  - line 1 equals the Required Notice exactly;
  - line 2 is empty;
  - the SHA-256 of the bytes from line 3 to the end equals `c0ea4a896d2c8c394b29f9427589996db826cd501c512279ff0ed3ef48fabbe5`;
  - the body starts with `# PolyForm Noncommercial License 1.0.0`.

  Run `./mvnw -B -q test -Dtest=LicenseFileTest` and confirm it fails.
- [X] T004 Create `LICENSE.md` at the repository root: line 1 is the Required Notice, line 2 is blank, then the official file byte-for-byte (fetch it with the `gh api` command above; don't retype or rewrap it). Re-run `LicenseFileTest`; it must pass.

**Checkpoint**: The repository is licensed, which is the minimum legal artifact.

---

## Phase 3: User Story 1 - Visitor learns the license terms from the repository (Priority: P1) 🎯 MVP

**Goal**: The root license file is complete, official and found by GitHub.

**Independent Test**: Follow quickstart.md §1 (`head -1 LICENSE.md`; `tail -n +3 LICENSE.md | shasum -a 256`). After merge, the GitHub sidebar shows a license link to `LICENSE.md`. GitHub classes it as *Other*, which is expected (research R3).

- [X] T005 [US1] Check that no file in the repository contradicts the new license: run `grep -rniI "all rights reserved\|apache license\|MIT license" --exclude-dir=target --exclude-dir=specs --exclude-dir=var .` and remove or fix any project-level claims (dependency names are fine). Record the result in the PR description.

**Checkpoint**: US1 is complete. The repository can be merged as licensed even if nothing else ships.

---

## Phase 4: User Story 2 - README explains what is and is not allowed (Priority: P2)

**Goal**: A reader can tell from the README alone whether their use is permitted, and whom to ask for commercial terms.

**Independent Test**: Read only `README.md` and answer two questions: "may I use this commercially?" (no, not without a separate license) and "whom do I contact?" (Md Nazrul Islam, email2nazrul@gmail.com). This covers SC-003.

- [X] T006 [US2] Add a license badge to `README.md` directly under the `# FHIR Validation Service` heading: `[![License: PolyForm Noncommercial 1.0.0](https://img.shields.io/badge/license-PolyForm%20Noncommercial%201.0.0-blue)](LICENSE.md)` (FR-005)
- [X] T007 [US2] Add a `## License` section at the end of `README.md` (FR-004, FR-006). It must:
  - name PolyForm Noncommercial 1.0.0 and link `LICENSE.md` and `https://polyformproject.org/licenses/noncommercial/1.0.0`;
  - summarise in plain language that use, modification and sharing are free for any noncommercial purpose (personal use, research, education, and charitable, public or government institutions);
  - state that any commercial use requires a separate commercial license from Md Nazrul Islam (email2nazrul@gmail.com);
  - state that bundled third-party components remain under their own licenses, listed inside each package: the jar's `META-INF/THIRD-PARTY.txt`, `META-INF/NOTICE` and `META-INF/third-party-licenses/`, and the image's `/app/licenses/`.
- [X] T008 [P] [US2] Create `CONTRIBUTING.md` (FR-011). It must state that contributions are accepted under the project's license (PolyForm Noncommercial 1.0.0), and that by contributing, contributors agree the copyright holder (Md Nazrul Islam) may also offer the project, including their contributions, under separate commercial terms. Link it from the README `## License` section or the `## Development` section.

**Checkpoint**: US2 is complete and can be checked by reading the README alone.

---

## Phase 5: User Story 3 - Distributed artifacts carry the license and third-party notices (Priority: P3)

**Goal**: The shaded jar, its deployed POM and the container image each declare the license and carry the Required Notice, the third-party listing, the license texts, the merged NOTICE and the clarifications. The exact paths are in contracts/distribution-layout.md.

**Independent Test**: Follow quickstart.md §2–§4: `./mvnw -B verify`, then run `sh scripts/check-license-packaging.sh <jar> [<image>]` and inspect the image label.

### Tests for User Story 3 ⚠️ (write first, confirm they fail)

- [X] T009 [P] [US3] Write the failing test `src/test/java/nzi/fhir/validator/licensing/ThirdPartyNoticesTest.java` (JUnit 5, pure file test). It reads `target/classes/META-INF/THIRD-PARTY.txt` and asserts:
  - the file exists and lists at least one artifact;
  - no line contains `Unknown license`;
  - every line has **at least one** license id for which `src/main/resources/META-INF/third-party-licenses/<id>.txt` exists;
  - every line that lists **more than one** license id is covered in `src/main/resources/META-INF/THIRD-PARTY-CLARIFICATIONS.md`. It counts as covered when the clarifications file contains either the exact `groupId:artifactId` or the group wildcard `groupId:*` (for example `io.vertx:*`). Match coordinates literally, with no regex;
  - every `groupId--artifactId--version` key in `src/license/THIRD-PARTY.properties` appears in the clarifications file as `groupId:artifactId:version`.
- [X] T010 [P] [US3] Write `scripts/check-license-packaging.sh` (POSIX `sh`, `set -eu`, same style as `scripts/smoke.sh`), usage `check-license-packaging.sh <jar> [<image>]`.
  - **Jar** (use `unzip -l`/`unzip -p`): assert these entries exist:
    - `LICENSE.md`, identical to the repository `LICENSE.md` (`cmp`);
    - `META-INF/NOTICE`, whose first line is the Required Notice;
    - `META-INF/THIRD-PARTY.txt`;
    - `META-INF/THIRD-PARTY-CLARIFICATIONS.md`;
    - at least one `META-INF/third-party-licenses/*.txt`.
  - **Image** (if given):
    - assert `docker inspect` label `org.opencontainers.image.licenses` = `PolyForm-Noncommercial-1.0.0`;
    - assert label `org.opencontainers.image.authors` = `Md Nazrul Islam <email2nazrul@gmail.com>`;
    - run `docker run --rm --entrypoint sh <image> -c '...'` and check that `/app/LICENSE.md`, `/app/licenses/NOTICE`, `/app/licenses/THIRD-PARTY.txt`, `/app/licenses/THIRD-PARTY-CLARIFICATIONS.md`, `/app/licenses/IMAGE-COMPONENTS.md` and `/app/licenses/third-party-licenses/` exist, and that `/app/LICENSE.md` starts with the Required Notice.
  - Print one `OK`/`FAIL` line per check and exit non-zero on any failure. Run it against the current jar (`./mvnw -B -q package -DskipTests`) and confirm it fails.

### Implementation for User Story 3

- [X] T011 [US3] Add project metadata to `pom.xml` after `<description>` (FR-007):
  - `<url>https://github.com/nazrulworld/fhir-validation-service</url>`;
  - `<licenses><license><name>PolyForm Noncommercial License 1.0.0</name><url>https://polyformproject.org/licenses/noncommercial/1.0.0</url><distribution>repo</distribution></license></licenses>`;
  - `<developers><developer><id>nazrulworld</id><name>Md Nazrul Islam</name><email>email2nazrul@gmail.com</email></developer></developers>`;
  - `<scm>` with `url` `https://github.com/nazrulworld/fhir-validation-service` and `connection` `scm:git:https://github.com/nazrulworld/fhir-validation-service.git`.
- [X] T012 [US3] Add `org.codehaus.mojo:license-maven-plugin` `${license-maven-plugin.version}` to `<build><plugins>` in `pom.xml`. Configure one execution `third-party-notices`, goal `add-third-party`, phase `generate-resources`, with (research R4):
  - `excludedScopes=test,provided,system`;
  - `generateBundle=true` and `bundleThirdPartyPath=META-INF/THIRD-PARTY.txt`;
  - `failOnMissing=true`, `useMissingFile=true` and `missingFile=${project.basedir}/src/license/THIRD-PARTY.properties`;
  - `failOnBlacklist=true` with `includedLicenses` = `Apache-2.0|MIT|BSD-2-Clause|BSD-3-Clause|EPL-2.0|MPL-2.0|Unicode-3.0|CC0-1.0`. This is exactly the 8 ids that T014 ships texts for; keep the two lists identical. The plugin accepts a multi-licensed library when at least one of its licenses is allowed (verified, research R4). So the alternatives that aren't elected (EPL-1.0, MPL-1.1, LGPL-2.1, GPL-2.0-with-classpath-exception) still get `licenseMerges` entries below, so that the listing shows SPDX ids, but they MUST NOT be added to `includedLicenses`;
  - `licenseMerges` mapping each spelling to its SPDX id:
    - Apache-2.0: `Apache License, Version 2.0`, `The Apache Software License, Version 2.0`, `Apache Software License 2.0`, `Apache 2`, `The Apache License, Version 2.0`, `Apache License version 2.0`, `Apache License 2.0`, `Apache 2.0`;
    - MIT: `MIT License`, `The MIT License`;
    - BSD-2-Clause: `BSD 2-Clause "Simplified" License`, `BSD 2-Clause License`, `The BSD 2-Clause License`;
    - BSD-3-Clause: `BSD 3 Clause`, `Eclipse Distribution License - v 1.0`, `EDL 1.0`;
    - EPL-1.0: `Eclipse Public License - v 1.0`;
    - EPL-2.0: `Eclipse Public License - v 2.0`, `EPL 2.0`;
    - MPL-2.0: `Mozilla Public License Version 2.0`;
    - MPL-1.1: `MPL 1.1`;
    - LGPL-2.1: `LGPL 2.1`;
    - GPL-2.0-with-classpath-exception: `GPL2 w/ CPE`;
    - CC0-1.0: `Public Domain, per Creative Commons CC0`.

  Run `./mvnw -B generate-resources` and inspect `target/generated-sources/license/THIRD-PARTY.txt`. Add a merge for any spelling that a newer dependency introduces. In the log, confirm that Vert.x, Javassist, Jakarta Annotations and HdrHistogram show `is OK since it is also licensed under …`, and that the only failure is `jakarta-regexp`, which T013 fixes.
- [X] T013 [US3] Create `src/license/THIRD-PARTY.properties` with the header comment `# Licenses for artifacts whose POM declares none. Each entry MUST be explained in META-INF/THIRD-PARTY-CLARIFICATIONS.md.` and the entry `jakarta-regexp--jakarta-regexp--1.4=Apache-2.0`. Re-run `./mvnw -B -q generate-resources` and confirm the build passes and no `Unknown license` remains.
- [X] T014 [P] [US3] Add the license texts under `src/main/resources/META-INF/third-party-licenses/`: `Apache-2.0.txt`, `MIT.txt`, `BSD-2-Clause.txt`, `BSD-3-Clause.txt`, `EPL-2.0.txt`, `MPL-2.0.txt`, `Unicode-3.0.txt` and `CC0-1.0.txt`. Take each one verbatim from `https://raw.githubusercontent.com/spdx/license-list-data/main/text/<id>.txt`. These are the elected or sole licenses from research R5. EPL-1.0, MPL-1.1, LGPL-2.1 and GPL-2.0-with-classpath-exception are offered only as alternatives and are not elected, so they need no text.
- [X] T015 [P] [US3] Create `src/main/resources/META-INF/THIRD-PARTY-CLARIFICATIONS.md`, with one `##` section per item (FR-013 clarifications, data-model.md). Each section names the coordinates, the licenses offered, the license elected or asserted, and the reason:
  1. **Vert.x modules**: write the coordinate literally as `io.vertx:*`; T009 matches this wildcard. These modules are listed with EPL-1.0 or EPL-2.0 alongside Apache-2.0 → Apache-2.0 elected.
  2. **`org.javassist:javassist`** (Apache-2.0 / LGPL-2.1 / MPL-1.1) → Apache-2.0 elected.
  3. **`jakarta.annotation:jakarta.annotation-api`** (EPL-2.0 / GPL-2.0 with Classpath Exception) → EPL-2.0 elected. Redistributed unmodified; source at https://github.com/jakartaee/common-annotations-api.
  4. **`org.hdrhistogram:HdrHistogram`** (BSD-2-Clause / CC0-1.0) → BSD-2-Clause elected.
  5. **`jakarta-regexp:jakarta-regexp:1.4`** (write the full coordinate, as T009 expects): the POM declares no license. It is an Apache Jakarta project distributed under Apache-2.0, as asserted in `src/license/THIRD-PARTY.properties`.
  6. **`net.sf.saxon:Saxon-HE`** (MPL-2.0): redistributed unmodified; the source form is available from Saxonica (https://www.saxonica.com) and Maven Central (`-sources.jar`).
  7. **Eclipse Distribution License 1.0** (`jakarta.activation:jakarta.activation-api`, `jakarta.xml.bind:jakarta.xml.bind-api`) is the BSD-3-Clause license; its text is `BSD-3-Clause.txt`.
  8. **Project license**: these components are not covered by PolyForm Noncommercial; each stays under its own license.

  Rerun `ThirdPartyNoticesTest` after T012–T014; it must pass.
- [X] T016 [US3] In `pom.xml`, under `<build>`, add a `<resources>` block that keeps the default `<resource><directory>src/main/resources</directory></resource>`, plus a second resource with directory `${project.basedir}`, includes `LICENSE.md` only, and no `targetPath`, so it lands at the jar root (research R6). Before saving, check whether the default resources use filtering (they don't today) and keep that behaviour.
- [X] T017 [US3] In the `maven-shade-plugin` `<transformers>` in `pom.xml`, add:
  - `org.apache.maven.plugins.shade.resource.ApacheLicenseResourceTransformer`;
  - `org.apache.maven.plugins.shade.resource.ApacheNoticeResourceTransformer` with `<addHeader>false</addHeader>` and a `<preamble1>` of the Required Notice line followed by a newline and `This product includes third-party software listed in META-INF/THIRD-PARTY.txt; their licenses are in META-INF/third-party-licenses/ and clarifications in META-INF/THIRD-PARTY-CLARIFICATIONS.md.`

  Then run `./mvnw -B -q package -DskipTests && sh scripts/check-license-packaging.sh target/fhir-validation-service-*.jar`; all jar checks must pass. If the root `LICENSE.md` is missing from the jar, check that no transformer matched it, and fall back to `IncludeResourceTransformer` (`resource=LICENSE.md`, `file=${project.basedir}/LICENSE.md`).
- [X] T018 [P] [US3] Create `docker/licenses/IMAGE-COMPONENTS.md`. It explains that `/app/LICENSE.md` is the service license; that `/app/licenses/` holds the third-party notices for libraries bundled in `/app/app.jar`; that the Eclipse Temurin JRE's licenses and notices are in `/opt/java/openjdk/legal/`; and that the Ubuntu packages (including `tini` and `curl`) have their copyright and license files in `/usr/share/doc/<package>/copyright`.
- [X] T019 [US3] Update `Dockerfile` (FR-009, research R7):
  - **Builder stage**: after the `cp ... /build/app.jar` step, add
    `RUN mkdir -p /build/licenses && cd /build/licenses && jar xf /build/app.jar LICENSE.md META-INF/NOTICE META-INF/THIRD-PARTY.txt META-INF/THIRD-PARTY-CLARIFICATIONS.md META-INF/third-party-licenses`
    (adjust the paths to match).
  - **Runtime stage**:
    - Extend the existing `LABEL` with `org.opencontainers.image.licenses="PolyForm-Noncommercial-1.0.0"` and `org.opencontainers.image.authors="Md Nazrul Islam <email2nazrul@gmail.com>"`.
    - Add `COPY --from=builder --chown=root:root --chmod=0444 /build/licenses/LICENSE.md /app/LICENSE.md`.
    - Copy the extracted `META-INF` files into `/app/licenses/` (`NOTICE`, `THIRD-PARTY.txt`, `THIRD-PARTY-CLARIFICATIONS.md` and the `third-party-licenses/` directory), keeping directories traversable (`--chmod=a+rX,a-w`, or `0444` for files and `0555` for directories).
    - Copy `docker/licenses/IMAGE-COMPONENTS.md` to `/app/licenses/IMAGE-COMPONENTS.md`.
  - Run `hadolint Dockerfile` (CI lints it) and `docker build -t fhir-validation-service:lic .`.
- [X] T020 [US3] Wire the check into `.github/workflows/ci.yml`:
  - In the job that runs `./mvnw -B verify`, add a step after it: `sh scripts/check-license-packaging.sh target/fhir-validation-service-*.jar`.
  - In the `arm64-build` job, add a step after the image build: `sh scripts/check-license-packaging.sh` with an extracted jar and `fhir-validation-service:arm64`. Copy the jar out of the image (`docker create` + `docker cp <id>:/app/app.jar /tmp/app.jar`), so the jar and image checks use the same artifact.
- [X] T021 [US3] Make the publish workflow carry, and check, the license on the images it pushes, in `.github/workflows/docker-publish.yml` (SC-004, research R7):
  - In the `push` job's `docker/metadata-action` step (`id: meta`), add a `labels:` input with `org.opencontainers.image.licenses=PolyForm-Noncommercial-1.0.0` and `org.opencontainers.image.authors=Md Nazrul Islam <email2nazrul@gmail.com>`. The action's generated labels are passed to the push and override Dockerfile `LABEL`s. Without this, the published label comes from GitHub's detected license, which is "Other".
  - In the `image` job, after the `Smoke test` step, add a step that copies `/app/app.jar` out of `${{ env.REGISTRY }}/${{ env.IMAGE_NAME }}:ci` (`docker create` + `docker cp`) and runs `sh scripts/check-license-packaging.sh <that jar> ${{ env.REGISTRY }}/${{ env.IMAGE_NAME }}:ci`.
  - In the `push` job, after `Build and push`, add a step that runs `docker buildx imagetools inspect --format '{{ json .Image }}' <first tag from steps.meta.outputs.tags>` for each platform. It must assert that `config.Labels["org.opencontainers.image.licenses"]` equals `PolyForm-Noncommercial-1.0.0` and fail the job otherwise.
- [X] T022 [US3] Run `./mvnw -B verify`, then `sh scripts/check-license-packaging.sh target/fhir-validation-service-*.jar fhir-validation-service:lic`. Every check must report OK, and all existing tests must still pass (SC-004, SC-005, SC-006).

**Checkpoint**: US3 is complete. Every distribution channel carries the license, the notice and the third-party information.

---

## Phase 6: User Story 4 - Maintainer records the licensing change (Priority: P3)

**Goal**: The changelog records when the project became licensed.

**Independent Test**: `CHANGELOG.md` → `## [Unreleased]` contains the license entry.

- [X] T023 [P] [US4] Under `## [Unreleased]` in `CHANGELOG.md`, add a `### Changed` subsection with an entry stating that the project is now licensed under PolyForm Noncommercial 1.0.0 (`LICENSE.md`): free for noncommercial use, with commercial use needing a separate license from Md Nazrul Islam (email2nazrul@gmail.com). Note that releases up to and including 1.1.0 were published without an explicit license. Add a `### Added` entry: the jar and image now carry third-party license notices (jar `META-INF/THIRD-PARTY.txt`, `META-INF/NOTICE`, `META-INF/third-party-licenses/`; image `/app/licenses/`), plus the OCI `licenses`/`authors` labels. Follow the file's existing line wrapping, which is about 120 columns (FR-010).

**Checkpoint**: All user stories are complete.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T024 [P] Cross-check the delivered paths and values against `specs/002-polyform-noncommercial-license/contracts/distribution-layout.md` (every row) and `specs/002-polyform-noncommercial-license/data-model.md` (Distribution metadata table). Fix any drift in the code, or in the contract if the change was intentional.
- [X] T025 Run every step of `specs/002-polyform-noncommercial-license/quickstart.md`, including the FR-015 negative check (temporarily remove the `jakarta-regexp` line from `src/license/THIRD-PARTY.properties`, confirm `./mvnw -B -q package -DskipTests` fails and names the artifact, then restore the line). Record the outcomes in the PR description.
- [X] T026 Run `git status` and confirm that no generated file (`target/`, `THIRD-PARTY.txt`) is staged and that `dependency-reduced-pom.xml` is untouched.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)** has no dependencies.
- **Foundational (Phase 2)**: T003 → T004. Needs T001.
- **US1 (Phase 3)** needs T004.
- **US2 (Phase 4)** links to `LICENSE.md`, so it needs T004. Otherwise it is independent of US1, US3 and US4.
- **US3 (Phase 5)** needs T002 and T004, because the jar and image copy `LICENSE.md`.
- **US4 (Phase 6)** has no code dependency and can be done any time after Phase 2.
- **Polish (Phase 7)** comes after all stories.

### Within User Story 3

- Write the tests first: T009 and T010 [P].
- T011 can be done any time.
- T012 → T013, which makes the build pass.
- T014 and T015 [P] run after T012, because they need the license ids it produces.
- T016 → T017: the jar check passes.
- T018 [P] → T019 (image) → T020 (CI) and T021 (publish workflow) → T022 (full verification).

### Parallel Opportunities

- T008 (CONTRIBUTING) is parallel to T006 and T007, which both edit the README.
- T009 and T010 can be written together.
- T014, T015 and T018 are independent files.
- T023 (changelog) can run in parallel with all of US3.

---

## Parallel Example: User Story 3

```bash
# Tests first, together:
Task: "Write failing ThirdPartyNoticesTest in src/test/java/nzi/fhir/validator/licensing/ThirdPartyNoticesTest.java"
Task: "Write scripts/check-license-packaging.sh"

# After T012/T013, content files together:
Task: "Add SPDX license texts in src/main/resources/META-INF/third-party-licenses/"
Task: "Write src/main/resources/META-INF/THIRD-PARTY-CLARIFICATIONS.md"
Task: "Write docker/licenses/IMAGE-COMPONENTS.md"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Phase 1 (T001–T002) → Phase 2 (T003–T004) → Phase 3 (T005).
2. **Stop and validate** with quickstart §1. The repository is now properly licensed.

### Incremental Delivery

1. MVP (US1): licensed repository.
2. US2: README and CONTRIBUTING. Most readers' needs are met at this point.
3. US3: the jar, POM and image carry the license and third-party notices. This is the largest
   slice and contains every build change.
4. US4: changelog entry. Ship as part of release 1.2.0.

---

## Notes

- Never retype or reformat `LICENSE.md` or the SPDX license texts; fetch them verbatim.
- If a dependency bump later introduces a new license spelling or id, the build fails by design
  (FR-015). The fix is to add a `licenseMerges` entry, a text, or a clarification.
- Commit after each phase. Each phase's checkpoint is a mergeable state.
