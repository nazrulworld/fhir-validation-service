# Contract: License and Notice Layout in Distributed Packages

Consumers, compliance scanners and `scripts/check-license-packaging.sh` rely on these paths and
values. Moving or renaming any of them is a change that must be recorded in the changelog.

## Source repository

| Path | Content |
|------|---------|
| `LICENSE.md` | Required Notice line, a blank line, then the official PolyForm Noncommercial 1.0.0 text |
| `CONTRIBUTING.md` | States that contributions are accepted under the project license and that the holder may additionally offer commercial terms |
| `README.md` | License badge near the top. A `## License` section with a summary, links, the commercial contact (Md Nazrul Islam, email2nazrul@gmail.com) and a pointer to where the third-party notices are |
| `CHANGELOG.md` | An `[Unreleased]` → *Changed* entry announcing the license and stating that earlier releases had no explicit license |

## Shaded jar (`fhir-validation-service-<version>.jar`, also deployed to GitHub Packages)

| Entry | Required |
|-------|----------|
| `LICENSE.md` | Identical to the repository `LICENSE.md` |
| `META-INF/NOTICE` | Line 1 is the Required Notice. Contains the merged dependency `META-INF/NOTICE` content |
| `NOTICE` | The appended root-level NOTICE files of the libraries that keep them there (the `ph-*` libraries) |
| `META-INF/THIRD-PARTY.txt` | Generated listing; covers every bundled runtime artifact |
| `META-INF/THIRD-PARTY-CLARIFICATIONS.md` | Elections, missing-metadata assertions and source-availability notes |
| `META-INF/third-party-licenses/<SPDX-id>.txt` | One file per license id used in the listing (or elected for that artifact) |

The jar has no root-level `LICENSE` and no root `THIRD-PARTY.txt`. The `ph-*` libraries' root `LICENSE` is the
Apache-2.0 text, shipped as `META-INF/third-party-licenses/Apache-2.0.txt`. Dependency `META-INF/LICENSE*` files are
dropped for the same reason.

The deployed POM declares `<licenses>` and `<developers>` as described in
[data-model.md](../data-model.md#distribution-metadata).

## Container image (`ghcr.io/nazrulworld/fhir-validation-service:<tag>`, amd64 + arm64)

| Item | Required |
|------|----------|
| Label `org.opencontainers.image.licenses` | `PolyForm-Noncommercial-1.0.0` |
| Label `org.opencontainers.image.authors` | `Md Nazrul Islam <email2nazrul@gmail.com>` |
| `/app/LICENSE.md` | Identical to the jar's `LICENSE.md` |
| `/app/licenses/NOTICE` | Identical to the jar's `META-INF/NOTICE` |
| `/app/licenses/NOTICE-ROOT` | Identical to the jar's root `NOTICE` |
| `/app/licenses/THIRD-PARTY.txt` | Identical to the jar's listing |
| `/app/licenses/THIRD-PARTY-CLARIFICATIONS.md` | Identical to the jar's clarifications file |
| `/app/licenses/third-party-licenses/` | Identical to the jar's directory |
| `/app/licenses/IMAGE-COMPONENTS.md` | Describes the files above and points to `/opt/java/openjdk/legal/` (JRE) and `/usr/share/doc/*/copyright` (OS packages) |

Labels on **published** images (`docker-publish.yml`) MUST equal the values above. The
workflow's `docker/metadata-action` labels override Dockerfile `LABEL`s, so the workflow sets
both labels explicitly.

All files are owned by root and readable by everyone (mode `0444`, directories `0555`). The
service never reads them at runtime.
