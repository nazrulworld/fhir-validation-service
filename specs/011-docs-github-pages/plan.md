# Implementation Plan: Publish the User Guide from the Wiki to GitHub Pages

**Branch**: `011-docs-github-pages` | **Date**: 2026-11-17 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/011-docs-github-pages/spec.md`

## Summary

Generate a Just the Docs (Jekyll 4) site from the GitHub wiki on every wiki edit, relevant push to `main` or manual
dispatch, and deploy it with the Pages "GitHub Actions" source. A stdlib-only converter
(`scripts/build-docs-site.py`) turns wiki pages into Jekyll pages, rewrites wiki and repository links, pulls linked
repository files (transitively) into a Reference section — Markdown as pages, the OpenAPI definition as a Redoc page
rendered by a pinned Redocly CLI, other text files as downloads with a code view — writes GitHub-identical heading
ids, and disables Liquid on every generated page. The build fails on unresolved wiki links (converter) and broken
internal links/anchors (html-proofer), and a wiki edit deploys only when its editor has push access. Nothing generated
is committed. Docs/CI only: no service code, contract or version change.

## Technical Context

**Language/Version**: Python ≥ 3.10 (converter, stdlib only); Ruby 3.4 (Jekyll); Node 22 (Redocly CLI, build time
only); GitHub Actions YAML.

**Primary Dependencies**: Jekyll 4.4.1, Just the Docs 0.12.0, html-proofer 5.2.3 (`site/Gemfile` + committed
`Gemfile.lock`); `@redocly/cli` 2.79.0 (`site/_redoc/package.json` + committed `package-lock.json`, `npm ci`); actions `checkout@v7`, `configure-pages@v6`,
`upload-pages-artifact@v5`, `deploy-pages@v5`, `ruby/setup-ruby@v1`, `setup-node@v7` ([research R1](research.md)).

**Storage**: N/A — generated into git-ignored `target/docs-site/`; published as a Pages artifact.

**Testing**: `python3 -I -m unittest` with fixture wikis/repos (`scripts/tests/docs-site/`); html-proofer on the
built site; the [quickstart](quickstart.md) scenarios for end-to-end checks.

**Target Platform**: GitHub Pages (project site `https://nazrulworld.github.io/fhir-validation-service/`); local
preview on macOS/Linux.

**Project Type**: documentation build tooling (script + static-site scaffolding + CI workflow).

**Performance Goals**: wiki edit live ≤ 10 min (SC-001); converter run seconds; whole workflow a few minutes
(dominated by Ruby/Node setup, cached).

**Constraints**: converter stdlib only; deterministic output (local = CI); no external resources on the API reference
page; no wiki-derived value interpolated into workflow shell; only `main` deploys.

**Scale/Scope**: 3 wiki pages (~950 lines) and ~6 reference files today; designed for tens of pages.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Note |
|---|---|---|
| I. Non-blocking event loop | N/A | no service code |
| II. Stateless instances | N/A | no shared state; the site is rebuilt from scratch every run |
| III. FHIR conformance | N/A | |
| IV. Contract-first API | PASS | `openapi.yaml` is only read and rendered; it stays the single source of truth, and the site shows it from the built commit |
| V. Test-first | PASS | converter unit tests (fixtures) are written before the converter; html-proofer gates every build. JUnit/Testcontainers rules target service code and do not apply |
| VI. Performance budgets | N/A | no runtime impact (stated in spec Assumptions) |
| VII. Observability | PASS (adapted) | converter summary + GitHub annotations for warnings/errors; footer shows the built commit and wiki revision |
| VIII. Simplicity & dependencies | PASS with justification | new **build-time-only** tools: Ruby/Jekyll/Just the Docs/html-proofer and Node/Redocly CLI. Justified below; none enters the service jar, image or `pom.xml` |
| Technology constraints | PASS | `pom.xml` untouched; dependency-centralisation rule is about Maven and is mirrored by exact pins in `Gemfile.lock` and one Redocly constant |
| Workflow & gates | PASS | `mvn verify` unaffected; CHANGELOG entry under `[Unreleased]` (FR-031) |
| Security & compliance | PASS | no PHI; outbound calls only to GitHub, rubygems and npm in CI; editor check + untrusted-input handling (FR-032/033) |

**Principle VIII justification**:
- *Jekyll + Just the Docs* — the maintainer's decision; native to GitHub Pages, gives sidebar navigation, client-side
  search and colour schemes without writing a site generator. Simpler alternative rejected: publishing the wiki
  as-is (no search, no build check — the problem the feature solves); a hand-written Python HTML generator (would
  re-implement search, theming and Markdown rendering).
- *html-proofer* — the standard internal-link checker for Jekyll output; without it FR-023/SC-003 cannot be enforced.
  Rejected: a custom checker in the converter (duplicates a mature tool and cannot see theme-generated links).
- *Redocly CLI (Node)* — renders the OpenAPI definition into a self-contained page; the maintainer chose it over a
  vendored bundle. Rejected: hand-rendering endpoints in Markdown (the wiki's API-Documentation already does that; the
  Reference page must be the definition itself).

Post-design re-check (after Phase 1): unchanged — the design adds no service dependency, no shared state and no
contract change. **Gate: PASS.**

## Project Structure

### Documentation (this feature)

```text
specs/011-docs-github-pages/
├── plan.md              # This file
├── research.md          # Phase 0: versions, URL scheme, anchors, Redoc, triggers, deploy safety
├── data-model.md        # Phase 1: converter run model and link outcomes
├── quickstart.md        # Phase 1: local build, validation scenarios, one-time setup
├── contracts/
│   ├── converter-cli.md        # scripts/build-docs-site.py interface
│   ├── site-urls.md            # public address scheme
│   └── docs-site-workflow.md   # .github/workflows/docs-site.yml triggers, jobs, guarantees
├── checklists/requirements.md
└── tasks.md             # Phase 2 (/speckit-tasks)
```

### Source Code (repository root)

```text
scripts/
├── build-docs-site.py                 # converter (new, stdlib only)
├── docs-site-check-editor.sh          # wiki-editor push-access check (new, FR-032)
└── tests/docs-site/                   # new
    ├── test_build_docs_site.py        # unittest suite (loads the script by path)
    └── fixtures/
        ├── wiki-basic/ wiki-sidebar/ wiki-broken/ …     # fixture wikis
        ├── repo/                      # fixture repo files (md chain with cycle, openapi, .env.example, image, dir)
        └── github-anchors.json        # ids GitHub renders for the real wiki headings (research R4)

site/                                  # new, hand-written scaffolding only
├── Gemfile, Gemfile.lock
├── _config.yml                        # theme, baseurl, aux_links, search, color scheme, exclude
├── _includes/
│   ├── head_custom.html               # apply stored/system colour scheme
│   ├── header_custom.html             # light/dark toggle
│   └── footer_custom.html             # wiki edit link + built-from commits
├── _plugins/downloads.rb              # publishes _downloads/ byte for byte (dot-files, "---" headers; research R5)
└── _redoc/                           # not copied to the site
    ├── template.hbs                   # Redoc page template (bar + footer)
    └── package.json, package-lock.json   # pins @redocly/cli 2.79.0 and its whole dependency tree

.github/workflows/docs-site.yml        # new
README.md, CONTRIBUTING.md, CHANGELOG.md   # pointers (FR-030, FR-031)
wiki (separate repo): Home.md (site URL), Installation-Guide.md or Home.md (link docs/operations.md, FR-016)
```

**Structure Decision**: tooling lives next to the existing helpers in `scripts/`; scaffolding in a new `site/`;
generated output only in `target/docs-site/` (already git-ignored). Wiki edits (FR-016, FR-030) are made in the wiki
repository, not in this repository.

## Phase 0 / Phase 1 outputs

- [research.md](research.md) — R1–R11, no open `NEEDS CLARIFICATION`.
- [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md).

## Risks

| Risk | Mitigation |
|---|---|
| Collaborator-permission API not readable with the default token | verify first (research R8); fallback: compare `sender.login` with `gh api repos/…/collaborators` via the same token, fail closed |
| `gollum` not raised for git pushes to the wiki | `workflow_dispatch` documented in CONTRIBUTING |
| Redocly or its dependencies change output | whole tree locked in `site/_redoc/package-lock.json`, installed with `npm ci` |
| Redocly template options change between versions | exact version pin; converter test on the produced page (no external hosts, title, footer) |
| GitHub anchor algorithm drift | fixture captured from `gh api markdown`; refresh documented |
| Theme upgrade breaks includes | exact gem pins; html-proofer + quickstart V1–V4 on upgrade PRs |

## Complexity Tracking

| Item | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| Converter tests use Python's built-in `unittest`, not JUnit 5 + `vertx-junit5` (the tooling Principle V names) | Principle V's tooling rules target service code; the converter is a stdlib-only Python build script with no service, database or HTTP code. It is still developed test-first. | A JUnit wrapper around a Python script would add a Maven dependency on a build tool without testing anything more |

New build-time tools are justified under Principle VIII above.
