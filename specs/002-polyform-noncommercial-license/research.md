# Research: PolyForm Noncommercial 1.0.0 Licensing

**Feature**: [spec.md](./spec.md) | **Date**: 2026-10-27

## R1 — Source and integrity of the license text

- **Decision**: Use `PolyForm-Noncommercial-1.0.0.md` from the official
  `polyformproject/polyform-licenses` repository at tag `1.0.0`
  (commit `76a278c402bc43b8d2b561da140b0f3e17263015`), unmodified. The file is 73 lines and
  its SHA-256 is `c0ea4a896d2c8c394b29f9427589996db826cd501c512279ff0ed3ef48fabbe5`.
  The Required Notice goes in front of it, separated by one blank line. A unit test removes
  the notice header and compares the rest of the file against that hash (FR-001, SC-002).
- **Rationale**: Pinning to a tag and hash makes "unaltered text" checkable by a machine and
  catches any reformatting by an editor.
- **Alternatives considered**: Copying from polyformproject.org, which has no stable version
  or hash. Copying from the SPDX `.txt` rendering, which is re-wrapped, so its hash differs
  from the canonical Markdown.

## R2 — Required Notice placement and wording

- **Decision**: The first line of `LICENSE.md` is
  `Required Notice: Copyright 2025-2026 Md Nazrul Islam (https://github.com/nazrulworld)`.
  The license's own *Notices* section requires every recipient to also get "any plain-text
  lines beginning with `Required Notice:`". The same line therefore also opens the jar's
  `META-INF/NOTICE` and the image's copy of the license.
- **Rationale**: This follows the PolyForm project's own guidance. Because it is a single
  line, it is easy to grep for in every package.
- **Alternatives considered**: A separate `NOTICE` file in the repository. It would duplicate
  the notice and could drift out of sync with `LICENSE.md`.

## R3 — GitHub license detection (affects FR-003 / SC-001)

- **Finding**: GitHub detects licenses with `licensee`, which only recognises the licenses
  vendored from choosealicense.com (47 licenses: Apache, MIT, GPL, MPL, BSD and so on).
  PolyForm is **not** among them. With a correctly named `LICENSE.md`, GitHub shows a
  "View license" link in the sidebar, classed as *Other*, and never displays
  "PolyForm Noncommercial 1.0.0".
- **Decision**: Name the file `LICENSE.md` so GitHub still finds and links it. Make the
  license name visible through the README badge and the `LICENSE.md` heading instead.
  FR-003 and SC-001 were updated to match.
- **Alternatives considered**: Re-formatting the text to trick licensee. That isn't
  possible, because there is no PolyForm template for it to match, and it would break
  SC-002 anyway.

## R4 — Generating the third-party listing (FR-013, FR-014, FR-015)

- **Decision**: Use `org.codehaus.mojo:license-maven-plugin` **2.4.0**, goal `add-third-party`,
  bound to `generate-resources` and configured as follows:
  - `excludedScopes=test,provided,system`, so only libraries that end up in the shaded jar
    are listed.
  - `licenseMerges` collapses the 26 different license spellings found in the POMs (for
    example "Apache 2", "The Apache Software License, Version 2.0" and "Apache-2.0") into
    SPDX ids.
  - `includedLicenses` names exactly the 8 SPDX ids we ship a text for (R5). Together with
    `failOnBlacklist=true`, a new license fails the build until someone adds its text and
    a clarification for it.
  - **Verified 2026-10-27 (multi-licensed libraries)**: the plugin accepts a library when *at
    least one* of its licenses is allowed. With only Apache-2.0 allowed, it logged
    `License: 'Eclipse Public License - v 1.0' for '…io.vertx:vertx-auth-common:4.5.35…' is OK
    since it is also licensed under 'The Apache Software License, Version 2.0'`, and it still
    reported Javassist, Jakarta Annotations and HdrHistogram until one of their options was
    allowed. So the alternatives that are not elected (EPL-1.0, MPL-1.1, LGPL-2.1,
    GPL-2.0-with-classpath-exception) stay off the allowlist and need no text. A library offered
    *only* under one of them fails the build, as intended.
  - `failOnMissing=true`, plus `overrideUrl` pointing at
    `src/license/override-THIRD-PARTY.properties`: an artifact with no license metadata is listed
    as `Unknown license`, which is not on the allowlist, so the build fails and names it unless
    someone states its license in that file. The plugin only reads the override file. A
    `useMissingFile`/`missingFile` setup was tried first and dropped, because the plugin rewrites
    that file (header and timestamp) on every build.
  - `generateBundle=true` and `bundleThirdPartyPath=META-INF/THIRD-PARTY.txt`. The listing is
    written to `target/generated-sources/license/META-INF/`, which the plugin registers as a
    resource root, so it lands in the jar.
  - `force=true` (*implementation finding*): when its output is up to date, the plugin skips the
    whole goal, including registering that resource root. An incremental `mvn package` would
    then ship a jar without the listing. The unbundled copy the plugin also produces
    (`THIRD-PARTY.txt` at the jar root) is removed by a shade filter on the project artifact.
- **Rationale**: The plugin reads license data from the resolved dependency tree of the
  current build, so the listing always matches what is shipped (FR-014). It runs offline,
  which the Docker build needs, since only `dependency:go-offline` has network access in
  practice. It also has built-in fail switches for FR-015, and the Docker build runs them even
  though it skips tests.
- **Alternatives considered**: The CycloneDX SBOM plugin, which produces machine-readable
  output but no human notices or license texts. It could be added later. A hand-maintained
  list, which goes stale on every dependency bump and violates FR-014. The plugin's
  `download-licenses` goal, which fetches texts over the network at build time; that is
  flaky and not reproducible, so it was rejected in favour of committed texts (R5).

## R5 — License texts and clarifications

- **Survey (current runtime tree, 142 artifacts)**: The licenses in use are Apache-2.0
  (the large majority), MIT, BSD-2-Clause, BSD-3-Clause (which includes the EDL 1.0 used by
  Jakarta Activation and XML Binding), EPL-1.0 and EPL-2.0 (offered alongside other
  licenses), MPL-2.0 (Saxon-HE), Unicode-3.0 (ICU4J), CC0-1.0 (LatencyUtils), and the
  LGPL-2.1/MPL-1.1/GPL-2.0-with-Classpath-Exception options of tri- and dual-licensed
  libraries.
- **Decision**: Commit one text per shipped SPDX id under
  `src/main/resources/META-INF/third-party-licenses/<SPDX-id>.txt`. Commit
  `src/main/resources/META-INF/THIRD-PARTY-CLARIFICATIONS.md`, which records:
  - **Elections for multi-licensed libraries**: Vert.x modules (EPL-1.0 or EPL-2.0 /
    Apache-2.0) → Apache-2.0. Javassist (Apache-2.0 / LGPL-2.1 / MPL-1.1) → Apache-2.0.
    Jakarta Annotations (EPL-2.0 / GPL-2.0 with CPE) → EPL-2.0. HdrHistogram
    (BSD-2-Clause / CC0-1.0) → BSD-2-Clause.
  - **Missing metadata**: `jakarta-regexp:jakarta-regexp:1.4` has no license in its POM.
    It is an Apache Jakarta project and Apache-2.0 licensed, and this is also stated in
    `override-THIRD-PARTY.properties`.
  - **MPL-2.0 (Saxon-HE)**: the library is redistributed unmodified, and its source is
    available from Saxonica / Maven Central.
  - **EPL-2.0**: the library is redistributed unmodified, and its source is available from
    the Eclipse project.
- **Rationale**: Committing the texts keeps builds deterministic and offline. Because only
  the elected license needs a text, the set stays at 8 files, the same 8 ids as the allowlist
  (R4).
- **Alternatives considered**: Shipping every option of every multi-licensed library. That
  is permitted but noisy. The listing still shows all options, so recipients can see
  them.

## R6 — Keeping the project's and dependencies' LICENSE/NOTICE files correct in the shaded jar

- **Finding**: Shade currently copies the first `META-INF/LICENSE*` and `META-INF/NOTICE*` it
  finds and drops later duplicates with a warning. As a result, one arbitrary dependency's
  license and notice end up in the fat jar, and every other dependency's NOTICE is lost.
- **Decision**:
  - Add `ApacheNoticeResourceTransformer`, which merges every dependency's NOTICE into a
    single `META-INF/NOTICE`. Its `preamble1` is the project's notice block, starting with the
    Required Notice line; `addHeader=true`, with `projectName`, `preamble2` and `preamble3`
    emptied. *Implementation note*: a project `META-INF/NOTICE` resource was tried first, but the
    transformer doesn't keep artifact order, so it didn't end up first.
  - *Implementation finding*: the six `ph-*` libraries (ph-commons, ph-schematron, and others)
    keep `LICENSE` and `NOTICE` at the **jar root**, where the NOTICE transformer doesn't look.
    An `AppendingTransformer` on `NOTICE` collects them into the jar's root `NOTICE`, so the
    attributions Apache-2.0 §4(d) requires survive. Their root `LICENSE` (the Apache-2.0 text) is
    excluded by a shade filter.
  - Add `ApacheLicenseResourceTransformer`, which drops the colliding dependency
    `META-INF/LICENSE*` files. Their texts are covered by `META-INF/third-party-licenses/`.
  - The project's own license goes at the jar root as `LICENSE.md`. The transformer matches
    only `META-INF/LICENSE*`, so the root file survives. It is copied there by an extra
    `<resource>` entry from `${project.basedir}` that includes only `LICENSE.md`; the
    default `src/main/resources` entry is re-declared alongside it.
- **Rationale**: This satisfies the redistribution conditions of Apache-2.0 §4(d) for
  NOTICE files (spec edge case: "NOTICE content is carried, not dropped").
- **Alternatives considered**: Keeping each dependency's NOTICE under a per-artifact path.
  Shade cannot rename resources per artifact without a custom transformer.

## R7 — Container image

- **Decision**:
  - **Labels**: add `org.opencontainers.image.licenses="PolyForm-Noncommercial-1.0.0"`
    (the OCI annotation takes an SPDX expression) and
    `org.opencontainers.image.authors="Md Nazrul Islam <email2nazrul@gmail.com>"`.
  - **Files**: the builder stage extracts `LICENSE.md`, `META-INF/NOTICE`,
    `META-INF/THIRD-PARTY.txt`, `META-INF/THIRD-PARTY-CLARIFICATIONS.md` and
    `META-INF/third-party-licenses/` from the built jar with `jar xf`. The runtime stage
    copies them read-only to `/app/LICENSE.md` and `/app/licenses/`.
  - **Base-image components**: `/app/licenses/IMAGE-COMPONENTS.md` notes that the Temurin
    JRE's licenses are in `/opt/java/openjdk/legal/` and that the Ubuntu packages (tini,
    curl and others) have theirs in `/usr/share/doc/<package>/copyright`. Both locations are
    already present in the image.
- **Publish workflow**: `.github/workflows/docker-publish.yml` passes
  `docker/metadata-action` labels to the push. Those labels override Dockerfile `LABEL`s, and the
  action fills `org.opencontainers.image.licenses` from GitHub's detected repository license,
  which is "Other" (R3) and likely `NOASSERTION`. The action's `labels:` input must set
  `org.opencontainers.image.licenses=PolyForm-Noncommercial-1.0.0` (and the `authors` label)
  explicitly. The published image is then checked with `scripts/check-license-packaging.sh`.
- **Rationale**: Extracting from the jar means the image carries exactly what the jar
  carries, so the two can't diverge. Base-image components already ship their notices in
  their standard places, and pointing to them avoids duplicating hundreds of files.
- **Alternatives considered**: Copying from `target/` in the build context. The Dockerfile
  builds the jar itself, so `target/` may be stale or absent.

## R8 — Build metadata

- **Decision**: Add the following to `pom.xml`: `<url>`; `<licenses>` with
  name `PolyForm Noncommercial License 1.0.0`,
  url `https://polyformproject.org/licenses/noncommercial/1.0.0` and
  `distribution=repo`; `<developers>` (Md Nazrul Islam, email2nazrul@gmail.com); and `<scm>`.
  Add a property `license-maven-plugin.version`. The constitution requires versions as
  properties; the existing plugins don't follow that yet, but the new one will.
- **Rationale**: The `pom.xml` deployed to GitHub Packages is what dependency scanners and
  Maven consumers read (FR-007).

## R9 — Verification

- **Decision**:
  - **Unit tests (surefire, part of `mvn verify`)**:
    - `LicenseFileTest`: checks the Required Notice line and the license-text hash (R1).
    - `ThirdPartyNoticesTest`: reads `target/classes/META-INF/THIRD-PARTY.txt`. It checks
      that the file is non-empty, that it contains no "Unknown license", and that every
      license id it lists either has a file in `third-party-licenses/` or is covered by an
      election in the clarifications file.
  - **New script `scripts/check-license-packaging.sh <jar> [<image>]`**: checks the
    required entries in the shaded jar. If an image is given, it also checks the image
    label and the files under `/app`. CI runs it in `ci.yml` after the image build.
- **Rationale**: Surefire runs before `package`, so a test can't inspect the shaded jar.
  The script covers that gap without adding failsafe.
