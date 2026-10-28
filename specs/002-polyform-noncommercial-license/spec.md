# Feature Specification: PolyForm Noncommercial 1.0.0 Licensing

**Feature Branch**: `002-polyform-noncommercial-license`

**Created**: 2026-10-27

**Status**: Draft

**Input**: User description: "I want to implement PolyForm Noncommercial 1.0.0 LICENSE for this project"

## Context

The project currently carries no license at all: there is no license file, the project metadata
declares none, the published container image carries no license label, and the README does not
mention licensing. Without a license, the default is "all rights reserved", so nobody can lawfully
use, modify or share the code. The goal is to license the project under the PolyForm Noncommercial
License 1.0.0, which permits use, modification and distribution for any noncommercial purpose
(personal use, research, education, charities, public institutions, etc.) and reserves commercial
use to the copyright holder.

All existing commits are authored by a single person (the copyright holder), so no contributor
consent is needed to apply the license.

## Clarifications

### Session 2026-10-27

- Q: Who is the contact for commercial licensing, and how should people reach them? → A: Md Nazrul Islam, email2nazrul@gmail.com.
- Q: Where should license information, notices and clarifications for bundled third-party libraries live? → A: Inside the distributed packages (the packaged build artifact and the container image), covering every bundled third-party library.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Visitor learns the license terms from the repository (Priority: P1)

A developer, researcher or organisation discovers the repository and wants to know whether, and
under what terms, they may use the service. They find a license file at the repository root that
contains the full, unmodified PolyForm Noncommercial 1.0.0 text plus the required copyright
notice, and the code-hosting platform shows the license on the repository page.

**Why this priority**: The license file is the legally operative artifact. Without it nothing else
in this feature has effect; with it alone the project is properly licensed (the MVP).

**Independent Test**: Open the repository root and the repository home page on the hosting
platform; confirm a license file exists, its text matches the official PolyForm Noncommercial
1.0.0 text word-for-word, it begins with a "Required Notice" naming the copyright holder, and the
platform links that file as the repository's license.

**Acceptance Scenarios**:

1. **Given** the repository root, **When** a visitor opens the license file, **Then** it contains
   the complete official PolyForm Noncommercial License 1.0.0 text with no alterations to the
   terms.
2. **Given** the license file, **When** a visitor reads its top, **Then** it shows a
   `Required Notice: Copyright <year(s)> <holder>` line identifying the copyright holder.
3. **Given** the repository home page on the hosting platform, **When** a visitor views the
   repository summary, **Then** a license link to the license file is shown (the platform
   classes PolyForm as "Other"; the license name is visible in the file's heading and the README
   badge).

---

### User Story 2 - User of the README understands what is and is not allowed (Priority: P2)

A prospective user reads the README and quickly understands, in plain language, that the service
is free for noncommercial use, that commercial use requires a separate agreement, and how to
contact the copyright holder (Md Nazrul Islam, email2nazrul@gmail.com) for commercial licensing.

**Why this priority**: Most people read the README, not the license text. A clear summary reduces
accidental misuse and gives commercial users a route to a paid/separate license.

**Independent Test**: Read the README's license section only; a reader can correctly answer
"may I use this at my company for profit?" (no, not without a separate agreement) and "where do I
ask?" (the stated contact).

**Acceptance Scenarios**:

1. **Given** the README, **When** a reader looks for licensing information, **Then** a dedicated
   License section names PolyForm Noncommercial 1.0.0, links to the license file, and summarises
   permitted noncommercial use in plain language.
2. **Given** a reader with a commercial use case, **When** they read the License section, **Then**
   they find a statement that commercial use requires a separate license and the contact
   Md Nazrul Islam, email2nazrul@gmail.com, to request one.
3. **Given** the README, **When** a reader looks at the top of the page, **Then** a license badge
   or one-line notice is visible without scrolling to the License section.

---

### User Story 3 - Distributed artifacts carry the license and third-party notices (Priority: P3)

An operator who obtains the service as a published container image or a published build artifact
(rather than from the source repository) can determine its license from the artifact itself, the
license text travels with the artifact, and the artifact also contains the license information and
notices of every third-party library bundled inside it.

**Why this priority**: The license requires that anyone receiving a copy also receives the terms
(or a link to them) and the Required Notice. Published images and packages are copies, so they
must carry the notice; this matters once the source-level license is in place.

**Independent Test**: Pull the published image and inspect its metadata and filesystem; inspect
the published build artifact's metadata and contents. Each identifies the license and includes the
license text.

**Acceptance Scenarios**:

1. **Given** a newly published container image, **When** an operator inspects its metadata,
   **Then** it declares the license using the standard SPDX identifier
   `PolyForm-Noncommercial-1.0.0`.
2. **Given** a newly published container image, **When** an operator lists its filesystem,
   **Then** the license file (including the Required Notice) is present at a predictable location.
3. **Given** the published build artifact and its project metadata, **When** a consumer or
   dependency-scanning tool reads them, **Then** they declare PolyForm Noncommercial 1.0.0 with a
   link to the official license text, and the packaged artifact contains the license file.
4. **Given** the packaged build artifact or the container image, **When** an operator or
   compliance reviewer looks inside it, **Then** they find a third-party notices listing that
   names every bundled third-party library with its version and license, the license texts for
   those licenses, the NOTICE content those libraries require to be passed on, and any
   clarification needed to interpret them (e.g. which option was chosen for a dual-licensed
   library).
5. **Given** a release that adds, removes or upgrades a bundled library, **When** the package is
   built, **Then** the third-party notices inside it reflect exactly the libraries in that
   package (no stale or missing entries).

---

### User Story 4 - Maintainer records the licensing change (Priority: P3)

The maintainer and downstream users can see when the project became licensed, through the
changelog entry for the release that introduces the license.

**Why this priority**: Constitution requires user-visible changes to be recorded in the changelog;
the date of licensing matters to anyone who obtained earlier copies.

**Independent Test**: Open the changelog and find an entry stating that the project is now
licensed under PolyForm Noncommercial 1.0.0.

**Acceptance Scenarios**:

1. **Given** the changelog, **When** a reader looks at the release containing this change,
   **Then** an entry states the adoption of PolyForm Noncommercial 1.0.0 and notes that previous
   releases were published without an explicit license.

### Edge Cases

- **Third-party dependencies**: The service bundles third-party libraries (e.g. HAPI FHIR, Vert.x)
  under their own licenses. Applying PolyForm Noncommercial to this project MUST NOT purport to
  relicense them; their license information and notices ship inside the packages, and the README
  points to where they are found.
- **Library with unknown or missing license metadata**: The build must not silently omit it; it
  is either listed with a manually supplied clarification or flagged so the release can't ship
  without one.
- **Dual- or multi-licensed library**: The notices state which license the project relies on.
- **NOTICE files of bundled libraries** (e.g. Apache-2.0 components): Their content is carried
  into the package's third-party notices, not dropped when libraries are merged into one artifact.
- **Earlier releases**: Versions published before this change had no explicit license. The change
  applies to the release that introduces it and later; it does not rewrite previously published
  artifacts.
- **Text drift**: A hand-edited or reformatted license text could change legal meaning. The
  license file must match the official text exactly (whitespace/line wrapping aside), and the
  hosting platform must still recognise it.
- **Future contributors**: Once outside contributions arrive, the copyright holder can no longer
  offer commercial licenses for their parts without permission. The contribution guidance must
  state that contributions are accepted under the project's license and that the holder may offer
  the project under separate commercial terms.
- **Copyright years**: The Required Notice covers the project's first year (2025) through the
  current year.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The repository MUST contain a license file at its root holding the complete,
  unaltered text of the PolyForm Noncommercial License 1.0.0.
- **FR-002**: The license file MUST include a Required Notice line in the form
  `Required Notice: Copyright 2025-2026 Md Nazrul Islam (https://github.com/nazrulworld)`
  placed before the license text.
- **FR-003**: The license file MUST be named so that the code-hosting platform automatically
  detects it and links it as the repository's license. (GitHub's detector does not include
  PolyForm, so it is classed as "Other"; see research R3.)
- **FR-004**: The README MUST contain a License section that: names the license, links to the
  license file and to the official license page, summarises permitted noncommercial use, states
  that commercial use requires a separate license, and gives the commercial-licensing contact
  Md Nazrul Islam, email2nazrul@gmail.com.
- **FR-005**: The README MUST display a license indicator (badge or one-line notice) near the top.
- **FR-006**: The README MUST state that bundled third-party components remain under their own
  licenses and say where their license information is found inside the distributed packages.
- **FR-007**: The project build metadata MUST declare the license by name with a link to the
  official license text, so that dependency and compliance scanners can read it.
- **FR-008**: The packaged build artifact MUST include the license file.
- **FR-009**: The published container image MUST declare the license via the standard image
  license metadata field using the SPDX identifier `PolyForm-Noncommercial-1.0.0`, and MUST
  include the license file in its filesystem.
- **FR-010**: The changelog MUST record the adoption of the license in the release that
  introduces it, noting that earlier releases carried no explicit license.
- **FR-011**: The repository MUST provide contribution guidance stating that contributions are
  accepted under the project's license and that the copyright holder may additionally offer the
  project under separate commercial terms.
- **FR-012**: Source files MUST NOT be required to carry per-file license headers; the root
  license file is authoritative. (Adding per-file headers is out of scope.)
- **FR-013**: The packaged build artifact and the container image MUST each contain third-party
  license information for every bundled third-party library: a listing with library name,
  version and license; the full text of each license that appears; the NOTICE content the
  libraries require to be redistributed; and clarifications where relevant (dual-licensing
  choice, missing or ambiguous license metadata).
  - **Scope**: a "bundled third-party library" means any library packaged into the service's
    build artifact. Those are listed in full.
  - **Base image**: components that come with the container base image (the Java runtime and OS
    packages) already carry their own license files inside the image. For these, the image MUST
    include a document naming where those files are; it doesn't re-list them.
- **FR-014**: The third-party license information MUST be produced from the actual contents of
  each package at build time, so it always matches the libraries shipped in that package.
- **FR-015**: A bundled library with no determinable license MUST NOT pass the build silently; it
  MUST either carry an explicit clarification entry or cause the build to fail.

### Key Entities

- **License file**: The authoritative legal text plus the Required Notice (copyright holder and
  years). Single source from which every other license reference is derived.
- **Required Notice**: The copyright line PolyForm requires recipients to preserve; must appear
  in every distributed copy (repository, packaged artifact, container image).
- **License references**: README section and badge, build metadata, container image metadata,
  changelog entry, contribution guidance — each points back to the license file.
- **Third-party notices**: Per-package record of bundled libraries (name, version, license), the
  license texts, required NOTICE content and clarifications. Lives inside the packaged build
  artifact and the container image, and is regenerated with every build.
- **Commercial-licensing contact**: Md Nazrul Islam, email2nazrul@gmail.com.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: After merge, the repository page shows a license link that opens the license
  file, and the license name "PolyForm Noncommercial 1.0.0" is visible on the repository page
  (README badge) without opening any file.
- **SC-002**: The license file's terms match the official PolyForm Noncommercial 1.0.0 text with
  zero wording differences.
- **SC-003**: A reader can determine from the README alone, in under 1 minute, whether their use
  is permitted and whom to contact for commercial use.
- **SC-004**: 100% of distribution channels (source repository, packaged build artifact,
  container image) carry both the license identification and the Required Notice.
- **SC-006**: 100% of third-party libraries bundled in a package appear in that package's
  third-party notices with name, version and license, and every license named there has its full
  text included; zero libraries are listed that are not in the package.
- **SC-005**: No behaviour, API, configuration, performance or memory characteristic of the
  running service changes (zero functional diff; all existing tests continue to pass).

## Assumptions

- The copyright holder is the sole author, Md Nazrul Islam (the only author in the commit
  history), so the license can be applied without third-party consent.
- The commercial-licensing contact is Md Nazrul Islam, email2nazrul@gmail.com.
- The copyright span is 2025 (first commit) to 2026 (current year).
- The license is applied as-is; no additional terms, exceptions or "Commons Clause"-style
  modifications are added.
- The license takes effect with the next release (a MINOR version bump, as it changes no API);
  previously published releases are not re-published.
- Performance/capacity impact (Constitution VI): none — this change touches documentation, build
  metadata and image packaging only; image size grows by the size of one small text file.
- Third-party dependencies keep their own licenses. Their notices are required inside the
  packages (FR-013); committing a third-party notices file to the source repository is not
  required.
- Test-only and build-only tools that are not bundled into a package are not listed in its
  third-party notices.
