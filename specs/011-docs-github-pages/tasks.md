---

description: "Task list for 011-docs-github-pages (documentation site from the wiki; docs/CI only, no service release)"
---

# Tasks: Publish the User Guide from the Wiki to GitHub Pages

**Input**: Design documents from `specs/011-docs-github-pages/`

**Prerequisites**: plan.md, spec.md (US1–US5, FR-001–FR-033), research.md (R1–R11), data-model.md,
contracts/converter-cli.md, contracts/site-urls.md, contracts/docs-site-workflow.md, quickstart.md

**Tests**: Required. Constitution Principle V (test-first): every converter behaviour starts with a failing
`unittest` test (`python3 -I -m unittest discover -s scripts/tests/docs-site`). Site-level behaviour is gated by
html-proofer and the quickstart scenarios (V1–V13, C1–C7).

**Organization**: Tasks are grouped by user story (spec.md US1–US5) so each story can be built and checked on its own.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: US1–US5 from spec.md
- Paths are relative to the repository root. `conv` = `scripts/build-docs-site.py`, `tests/` =
  `scripts/tests/docs-site/`, `fx/` = `scripts/tests/docs-site/fixtures/`.
- Nothing generated is ever committed: all build output goes to `target/docs-site/` (already git-ignored).

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Scaffolding directories, pinned tool versions and the test harness.

- [X] T001 Create `site/Gemfile` pinning exactly `gem "jekyll", "4.4.1"`, `gem "just-the-docs", "0.12.0"` and, in
  `group :test`, `gem "html-proofer", "5.2.3"`; `source "https://rubygems.org"`; no other gems unless Jekyll 4.4 on
  Ruby 3.4 needs them (research R1). Generate and commit `site/Gemfile.lock` with Ruby 3.4 and
  `bundle lock --add-platform x86_64-linux arm64-darwin ruby` run in `site/`. Create `site/_redoc/package.json`
  (`"private": true`, `"dependencies": {"@redocly/cli": "2.79.0"}`) and commit `site/_redoc/package-lock.json`
  generated with Node 22 (`npm install --package-lock-only --prefix site/_redoc`); add `site/_redoc/node_modules/` to
  the root `.gitignore` (research R1, FR-024, FR-025).
- [X] T002 [P] Create `site/_config.yml`: `title: FHIR Validation Service`, `description`, `theme: just-the-docs`,
  `url: https://nazrulworld.github.io`, `baseurl: /fhir-validation-service`, `search_enabled: true`,
  `heading_anchors: true`, `color_scheme: light`, `aux_links` {"GitHub": repo URL, "Releases": repo `/releases`},
  `aux_links_new_tab: false`, `markdown: kramdown` with `kramdown: {input: GFM, auto_ids: false}` (ids come from the
  converter, research R4), `exclude: [Gemfile, Gemfile.lock, _redoc, vendor]`; no `include:` here (downloads are
  published by `site/_plugins/downloads.rb`, research R5).
- [X] T003 [P] Create the test harness `scripts/tests/docs-site/support.py`: loads `conv` by path with
  `importlib.util.spec_from_file_location` (tests run under `python3 -I`), helpers `make_wiki(tmp, {name: text})`,
  `make_repo(tmp, {path: text})` (initialises a git repo and commits, so `--commit` defaults work), `run(argv) ->
  (exit_code, stdout, stderr)` calling `main(argv)` in-process, and `read_out(out, rel)` returning front matter (dict)
  + body.
- [X] T004 [P] Create fixtures under `fx/`: `wiki-basic/` (Home, Installation-Guide, API-Documentation using
  `[[API Documentation]]`, `[[Text|Installation-Guide]]`, a full wiki URL with `#authentication`, a fenced code block
  containing `[[Not A Link]]`, `{{ x }}` and `{% endraw %}`, a duplicate heading), `wiki-sidebar/` (+ `_Sidebar.md`
  listing API-Documentation before Home, `_Footer.md`, one page not in the sidebar), `wiki-broken/` (`[[Missing Page]]`),
  `repo/` (`CHANGELOG.md`; `docs/a.md` ↔ `docs/b.md` cycle; `docs/a.md` links `../src/Foo.java`, a directory
  `../src/`, a missing `gone.md`, `../../outside.md`, and an image `img/x.png`; `api/openapi.yaml` with top-level
  `openapi: 3.0.3`; `other.yaml` without it; `.env.example`; `run.sh`; `data.json`).
- [X] T005 [P] Capture `fx/github-anchors.json`: for each heading of the real wiki pages (`Home.md`,
  `Installation-Guide.md`, `API-Documentation.md` at the current wiki revision) the id GitHub renders, obtained once
  with `gh api markdown -f mode=gfm -f text=@<page>` and stripping `user-content-`; also store a copy of the three pages
  as `fx/wiki-real/` so the test runs offline. Record the refresh command in `fx/README.md`. Before saving, compare
  4 headings (`#### POST /{version}/validate`, a duplicate heading, a heading with backticks,
  `### Option A: Docker Compose with the published image`) with the `id`s in the live wiki page's HTML
  (`curl -s https://github.com/nazrulworld/fhir-validation-service/wiki/API-Documentation` and
  `…/wiki/Installation-Guide`); if any differ, the wiki's ids win and research.md R4 is corrected.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The converter skeleton every story builds on: CLI, output directory, build metadata, diagnostics.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

### Tests (write first, must fail)

- [X] T006 Write `tests/test_cli.py`: usage error → exit 2; `--out` outside `<repo>/target/` and non-empty → exit 2,
  nothing deleted; default `--out` = `<repo>/target/docs-site/src`; `--out` is wiped and recreated each run (a stale
  file disappears); `--commit` defaults to `$GITHUB_SHA`, else `git -C <repo> rev-parse HEAD`; `--wiki-commit` defaults
  to the wiki `HEAD`; `_config.generated.yml` contains `baseurl`, `build.commit`, `build.commit_short` (7 chars),
  `build.wiki_commit`, `build.wiki_commit_short`; `site/` is copied except `site/_redoc/`; stdout summary lines
  `pages: N`, `reference: markdown=N openapi=N text=N assets=N`, `links rewritten: N`, `warnings: N`; with
  `GITHUB_ACTIONS=true` warnings/errors also appear as `::warning file=…::` / `::error file=…::` on stderr; exit 1 when
  any error was recorded (contracts/converter-cli.md).

### Implementation

- [X] T007 Create `conv` (`#!/usr/bin/env python3`, module docstring with usage like `scripts/corpus-010.py`, stdlib
  only, Python ≥ 3.10): `main(argv) -> int` with argparse options `--wiki` (required), `--repo`, `--out`, `--commit`,
  `--wiki-commit`, `--base-path` (default `/fhir-validation-service`), `--no-redoc`, `--list-sources`; a `Build`
  object holding `commit`, `wiki_commit`, `pages`, `references`, `warnings`, `errors` (data-model.md SiteBuild);
  `warn(source, msg)` / `error(source, msg)` writing `warning: <source>: <msg>` / `error: <source>: <msg>` to stderr
  (plus `::warning`/`::error` annotations when `GITHUB_ACTIONS=true`); safe `--out` handling; copy of `site/`;
  `write_page(rel_path, front_matter, body)` writing YAML front matter with stable key order and **always**
  `render_with_liquid: false` (FR-014, research R3); `_config.generated.yml`; summary (FR-027); exit codes 0/1/2; output only under `target/` (FR-026). Make T006
  pass.
- [X] T008 Add to `conv` the Markdown scanner shared by all stories: splits a document into segments
  "fenced code block", "inline code span" and "text", so link rewriting and heading handling only ever touch "text"
  segments (wiki-basic fixture's `[[Not A Link]]` inside a fence stays as-is); plus `github_anchor(text, seen)`
  implementing research R4 (lower-case; remove every char that is not a Unicode letter/digit, space, `-` or `_`;
  spaces → `-`; duplicates `-1`, `-2` in order). Add unit tests for both in `tests/test_markdown.py` first.

**Checkpoint**: `python3 -I scripts/build-docs-site.py --wiki fx/wiki-basic --repo fx/repo --out target/t` produces an
empty site skeleton with generated config and a summary.

---

## Phase 3: User Story 1 - Read and search the guide on the documentation site (Priority: P1) 🎯 MVP

**Goal**: every wiki page is a site page with the right title, order, links, anchors, literal content and footer, in
the Just the Docs theme with search, dark mode and phone layout.

**Independent Test**: build from the current wiki (`--no-redoc`), run Jekyll + html-proofer, and check quickstart
V1–V5, V8, V12 (guide part).

### Tests for User Story 1 (write first, must fail)

- [X] T009 [P] [US1] Write `tests/test_wiki_pages.py`: every `*.md` in the wiki except `_Sidebar` and `_Footer`
  becomes a page (FR-001); Home → `index.md` with `permalink: /` (FR-002); others `<Page>.md` with
  `permalink: /<Page>` (exact case, FR-019a); `title` = name with `-` → space (FR-003); `wiki_edit_url` =
  `https://github.com/nazrulworld/fhir-validation-service/wiki/<name>/_edit` (FR-015); nav order without sidebar:
  Home = 1, rest alphabetical by title; with `fx/wiki-sidebar`: sidebar order first, unlisted pages after it
  alphabetically (FR-004); page names matching `[/\\\x00]` or resolving outside `--out` → error, exit 1 (FR-033); two
  names with the same key (case-insensitive, space = hyphen) → error.
- [X] T010 [P] [US1] Write `tests/test_wiki_links.py`: `[[API Documentation]]`, `[[Text|Installation-Guide]]` and
  `https://github.com/nazrulworld/fhir-validation-service/wiki/API-Documentation#authentication` become
  `[…](/fhir-validation-service/API-Documentation)`, `[Text](/fhir-validation-service/Installation-Guide)` and
  `…/API-Documentation#authentication`; `wiki/Home` → `/fhir-validation-service/`; matching treats space and hyphen as
  equal and ignores case (FR-005); `fx/wiki-broken` → exit 1 and stderr
  `error: Home: wiki link to missing page "Missing Page"` (FR-006); links in code are untouched; links to
  `…/releases`, `…/issues`, `ghcr.io` and `…/releases/download/v${VERSION}/…` are unchanged (FR-012).
- [X] T011 [P] [US1] Write `tests/test_anchors.py`: for every page in `fx/wiki-real/`, the ids written as
  `{: id="…"}` lines after headings equal `fx/github-anchors.json` in order (FR-019, SC-005); a new fixture
  `fx/headings.md` covers a leading digit (`## [1.1.0] - 2026-11-16` → `110---2026-11-16`), non-ASCII letters
  (`## Überblick` → `überblick`), an emoji, and a closing-`#` heading; when Jekyll is available
  (`unittest.skipUnless`), the built HTML has exactly those `id`s and no `{:` or `{#` text in headings; headings in
  fenced blocks (e.g. `# create compose.yaml …` in a shell block) get no id; `{{ x }}` and `{% endraw %}` appear verbatim in the output
  body and every generated page has `render_with_liquid: false` (FR-014).

### Implementation for User Story 1

- [X] T012 [US1] In `conv`, collect wiki pages (data-model.md WikiPage: `name`, `key`, `title`, `url`, `edit_url`,
  `nav_order`, `anchors`, `body`), validate names, parse `_Sidebar.md` (order of first wiki link / wiki URL), compute
  nav order, and write `index.md` / `<Page>.md` with front matter `title`, `nav_order`, `permalink`,
  `wiki_edit_url`, `render_with_liquid: false`. Make T009 pass.
- [X] T013 [US1] In `conv`, rewrite wiki links (`[[Page]]`, `[[Text|Page]]`, full wiki URLs with optional anchor) in
  text segments to `<base-path>/<Page>[#anchor]` (Home → `<base-path>/`), record unresolved targets as errors, and count
  rewrites for the summary. Make T010 pass.
- [X] T014 [US1] In `conv`, insert a kramdown block attribute line `{: id="<github_anchor>"}` directly after every
  ATX heading (and Setext heading, if any) in text segments of every generated Markdown page (guide and, later,
  reference pages); never the `{#id}` header form (research R4). Make T011 pass.
- [X] T015 [P] [US1] Create `site/_includes/footer_custom.html`: when `page.wiki_edit_url` is set, an
  "Edit this page in the wiki" link; always "Built from" `site.build.commit_short` linked to
  `https://github.com/nazrulworld/fhir-validation-service/commit/{{ site.build.commit }}` and "wiki"
  `site.build.wiki_commit_short` linked to the wiki's `_history`/commit view (FR-015).
- [X] T016 [P] [US1] Create `site/_includes/head_custom.html` (inline script: apply the stored choice from
  `localStorage` key `jtd-theme` inside try/catch, else `prefers-color-scheme: dark` → `jtd.setTheme('dark')`) and
  `site/_includes/header_custom.html` (an accessible toggle button switching `light`/`dark` via `jtd.setTheme` and
  storing the choice) (FR-017, research R10).
- [X] T017 [US1] Build the current wiki end to end (`--no-redoc`, Jekyll, html-proofer per quickstart §1) and fix
  layout issues at 360 px width (wide tables and code blocks scroll inside their own box, not the page; add
  `site/_sass/custom/custom.scss` only if needed) (FR-018, SC-009). Check quickstart V1–V5, V8, V12.

**Checkpoint**: the guide renders locally with nav, search, dark mode, working deep links and literal examples.

---

## Phase 4: User Story 2 - A wiki edit is published automatically, and a bad edit never breaks the site (Priority: P1)

**Goal**: the `docs-site` workflow builds, checks and deploys from `main` on wiki edits, relevant pushes and manual
dispatch; failures and non-collaborator edits never deploy.

**Independent Test**: on a branch, the PR run builds and checks without a deploy job; after merge, quickstart C1–C7.

### Tests for User Story 2 (write first, must fail)

- [X] T018 [P] [US2] Write `tests/test_list_sources.py`: `--list-sources` writes nothing to `--out`, prints every
  repository path the site would publish (sorted, one per line, repository-relative), exits 0, and still exits 1 on an
  unresolved wiki link (contracts/converter-cli.md).
- [X] T019 [P] [US2] Write `scripts/tests/docs-site/test_editor_check.sh` (POSIX sh, run by the workflow and locally):
  runs `scripts/docs-site-check-editor.sh` with a stub `gh` on `PATH` that answers `{"permission":"write"}`,
  `{"permission":"read"}`, `{"permission":"none"}` and exits 1 (API error); expects exit 0 only for
  `admin`/`maintain`/`write`, and a message naming the editor otherwise; a login containing `$(…)`, quotes or `;` is
  passed through unchanged as one argument and never executed (FR-032, FR-033).

### Implementation for User Story 2

- [X] T020 [US2] Implement `--list-sources` in `conv` (stop after link resolution; print `sorted(paths)`). Make T018
  pass. (Its output is non-empty only once US3 resolves repository files; until then it prints nothing.)
- [X] T021 [P] [US2] Create `scripts/docs-site-check-editor.sh`: reads the login from `$EDITOR_LOGIN` and the repo from
  `$GITHUB_REPOSITORY` (never from arguments built by the workflow), calls
  `gh api "repos/$GITHUB_REPOSITORY/collaborators/$EDITOR_LOGIN/permission" --jq .permission`, fails closed on any API
  error, exits 0 for `admin`, `maintain`, `write`, else prints
  `::error::wiki edit by <login> who has no push access; not deploying` and exits 1. Make T019 pass.
- [X] T022 [US2] Create `.github/workflows/docs-site.yml` exactly per contracts/docs-site-workflow.md: triggers
  (`gollum`, `workflow_dispatch`, `push` to `main`, `pull_request` with the listed paths); top-level
  `permissions: contents: read`; job `build` with steps checkout (`fetch-depth: 1`), wiki checkout
  (`repository: ${{ github.repository }}.wiki`, `path: wiki-src`, `persist-credentials: false`), editor check (only
  `github.event_name == 'gollum'`, `env: EDITOR_LOGIN: ${{ github.event.sender.login }}`, `GH_TOKEN`), relevance check
  (only `push`: `--list-sources` + `site/`, `scripts/build-docs-site.py`, `scripts/docs-site-check-editor.sh`,
  `scripts/tests/docs-site/`, the workflow file vs `git diff --name-only "$BEFORE" "$GITHUB_SHA"` after
  `git fetch --no-tags --depth=1 origin "$BEFORE"`, with `BEFORE: ${{ github.event.before }}` via `env:`; all-zero
  `before` or a failed fetch → build; sets step output `skip`, declared as the build job's `outputs: skip`; while the
  wiki has an unresolved link, `--list-sources` exits 1 and the push run fails on purpose, surfacing the broken wiki),
  converter
  tests + editor-check test, `ruby/setup-ruby@v1` (`ruby-version: "3.4"`, `bundler-cache: true`,
  `working-directory: site`), `actions/setup-node@v7` (`node-version: "22"`, `cache: npm`,
  `cache-dependency-path: site/_redoc/package-lock.json`), convert, `actions/configure-pages@v6`
  (main only), Jekyll build (`JEKYLL_ENV=production`, `BUNDLE_GEMFILE=site/Gemfile`, both config files), html-proofer
  (`--disable-external --assume-extension .html --swap-urls '^/fhir-validation-service:'
  --ignore-files '/openapi\.yaml\.html$/'` — Redoc resolves its own `#operation/…` links in the browser),
  `actions/upload-pages-artifact@v5` (main only); job `deploy` with `needs: build`, the `if:` from the contract,
  `permissions: {pages: write, id-token: write}`, `environment: github-pages`,
  `concurrency: {group: pages, cancel-in-progress: false}`, `actions/deploy-pages@v5` (FR-020–FR-023, FR-032,
  FR-033). No `${{ … }}` expression of event data inside any `run:` block.
- [X] T023 [US2] Verify the editor-check assumption (research R8): from a workflow run on the branch (temporary
  `workflow_dispatch` input or a test step), confirm `GET repos/{repo}/collaborators/{login}/permission` works with the
  default `GITHUB_TOKEN`; if it does not, switch `scripts/docs-site-check-editor.sh` to the plan's fallback
  (collaborator list lookup, fail closed) and note the outcome in research.md R8.

**Checkpoint**: the PR run of this branch is green (build + checks, no deploy).

---

## Phase 5: User Story 3 - Linked repository files become readable reference pages (Priority: P2)

**Goal**: repository files linked from the wiki (and transitively from them) are published as Reference pages,
the Redoc API reference and code-view/download pages.

**Independent Test**: build with the current wiki; every repository-file link lands on a site page whose content
matches the built commit; quickstart V9–V11.

### Tests for User Story 3 (write first, must fail)

- [X] T024 [P] [US3] Write `tests/test_reference_markdown.py` with `fx/repo`: a wiki link
  `…/blob/main/CHANGELOG.md` → `/fhir-validation-service/reference/CHANGELOG`, the page has `parent: Reference`,
  `title` = first heading ("else file name"), `permalink: /reference/CHANGELOG`; `/raw/` and any ref are treated the
  same and content comes from `--repo` (FR-007, FR-008); `docs/a.md` ↔ `docs/b.md` published once each and the run
  terminates (FR-009); relative links in `docs/a.md`: `../src/Foo.java` →
  `https://github.com/nazrulworld/fhir-validation-service/blob/<commit>/src/Foo.java`, `../src/` →
  `…/tree/<commit>/src`, missing `gone.md` → `…/blob/<commit>/docs/gone.md` + warning, `../../outside.md` → text only +
  warning (FR-012a, data-model.md link outcomes); image `img/x.png` copied to `reference/docs/img/x.png` and the link
  rewritten (FR-013); headings get GitHub ids; `reference/index.md` exists with `title: Reference`,
  `has_children: true` and the last `nav_order`; children ordered by first discovery, breadth-first from guide pages
  in nav order (research R10); two sources mapping to one address → error.
- [X] T025 [P] [US3] Write `tests/test_reference_files.py`: `api/openapi.yaml` (top-level `openapi:`) → kind
  `openapi`, YAML copied to `reference/api/openapi.yaml`, page at permalink `/reference/api/openapi.yaml.html` with
  `layout: null`, `search_exclude: true`, `title: API reference`, `parent: Reference`; with `--no-redoc` the page is a
  placeholder linking the YAML; Redocly invocation is built as an argument list (no shell) containing
  `npm ci --prefix <repo>/site/_redoc` followed by `<repo>/site/_redoc/node_modules/.bin/redocly`, `build-docs`,
  `--inlineBundle` (not `--disableGoogleFont`, removed in Redocly CLI 2) and `--template` pointing at
  `site/_redoc/template.hbs` (patch `subprocess.run` to capture
  both calls; a non-zero exit of either → error `Redocly build-docs failed`) (FR-024); `other.yaml`,
  `.env.example`, `run.sh`, `data.json` → kind `text`, download at `reference/<path>` and a code-view page at
  `/reference/<path>.html` with a fenced block of the exact content (fence length longer than any backtick run in the
  file) and a download link (FR-011); `.env.example` appears in `_config.generated.yml` `docs_downloads`
  (research R5); a link to a directory or a missing file stays external + warning for missing (FR-012); repo links
  on wiki pages are rewritten in the summary count.

### Implementation for User Story 3

- [X] T026 [US3] In `conv`, classify and queue repository links (data-model.md ReferenceFile: `path` "repository-relative,
  normalised, inside the repo root; unique"; `kind` `markdown` (`.md`), `openapi` (`.yaml`/`.yml`/`.json` whose top
  level has an `openapi:` key), `text` (`.yaml`, `.yml`, `.json`, `.sh`, `.env`, `.env.*`, `*.env`)), process the queue
  breadth-first with a visited set, and write Markdown reference pages (front matter `title`, `parent: Reference`,
  `nav_order`, `permalink`, `render_with_liquid: false`), the `reference/index.md` parent, relative-link outcomes and
  image copies. Make T024 pass.
- [X] T027 [P] [US3] Create `site/_redoc/template.hbs` from the Redocly CLI 2.79.0 default template: keep `{{{redocHead}}}`
  / `{{{redocHTML}}}`, no Google Fonts, add a slim top bar (link back to `<base-path>/`, link "Download YAML") and a
  footer "Built from <short sha>" using `templateOptions` values the converter passes (`siteHome`, `yamlUrl`,
  `commit`, `commitShort`); keep it phone-friendly.
- [X] T028 [US3] In `conv`, publish `openapi` and `text` files: copy downloads, write code-view pages, run
  `npm ci --prefix <repo>/site/_redoc` (skip when `site/_redoc/node_modules/.package-lock.json` equals the lock
  file) and then `<repo>/site/_redoc/node_modules/.bin/redocly build-docs <file> --output <tmp> --inlineBundle
  --template <repo>/site/_redoc/template.hbs --templateOptions.siteHome=… --templateOptions.yamlUrl=…
  --templateOptions.commit=… --templateOptions.commitShort=…`, each as an argument list (no shell); the version is
  pinned only in `site/_redoc/package.json`/lock (FR-024, FR-025); wrap its output as the page body with the front matter from T025, implement
  `--no-redoc`, and list downloads in `docs_downloads` for `site/_plugins/downloads.rb`. Make T025 pass.
- [X] T029 [US3] Add a self-containment check to `tests/test_reference_files.py` that runs only when `npm` is available
  (`unittest.skipUnless`): the real Redoc page for `fx/repo/api/openapi.yaml` has no `src=`/`href=` to a host other than
  `github.com/nazrulworld/…` (FR-010, quickstart V10). The workflow job has Node, so it always runs in CI.
- [X] T030 [US3] Build with the current wiki and repository and check quickstart V9–V11; confirm html-proofer
  passes for the rest of the site and that the Redoc page's back-link and YAML download work; confirm
  `--list-sources` now prints the expected paths from contracts/site-urls.md.

**Checkpoint**: all Reference pages render and pass html-proofer; the push relevance check (US2) now has real input.

---

## Phase 6: User Story 4 - A contributor previews the site locally (Priority: P3)

**Goal**: documented local commands give the same site and the same failures as CI.

**Independent Test**: follow CONTRIBUTING on a clean checkout; compare with the CI artifact of the same commit/wiki
revision (quickstart §1, V13).

- [X] T031 [P] [US4] Write `tests/test_determinism.py`: two runs with identical inputs and `--commit`/`--wiki-commit`
  produce byte-identical `--out` trees (compare file lists and contents) (contracts/converter-cli.md Determinism,
  SC-008); fix any ordering/timestamp source in `conv` it exposes.
- [X] T032 [US4] Add a "Documentation site" section to `CONTRIBUTING.md`: the guide is edited in the wiki and
  published automatically by `.github/workflows/docs-site.yml`; prerequisites (Python ≥ 3.10, Ruby 3.4 + Bundler,
  Node 22 — note the macOS system Ruby is too old); the exact commands from quickstart §1 (clone wiki to
  `target/wiki-src`, tests, converter, `bundle install` in `site/`, Jekyll build, html-proofer, `jekyll serve`, URL
  `http://localhost:4000/fhir-validation-service/`); `--no-redoc` for offline runs; `workflow_dispatch` after pushing
  wiki changes with git (FR-028, FR-030).

**Checkpoint**: a contributor can preview locally and gets CI's result.

---

## Phase 7: User Story 5 - One-time setup and pointers to the site (Priority: P3)

**Goal**: Pages is enabled, the site is discoverable, and the operations runbook is published.

**Independent Test**: quickstart §4 verification; README/CONTRIBUTING/wiki Home links; `/reference/docs/operations`
exists after the next build.

- [X] T033 [US5] Add the one-time setup to the "Documentation site" section of `CONTRIBUTING.md` (after T032):
  `gh api -X POST repos/nazrulworld/fhir-validation-service/pages -f build_type=workflow`,
  `gh repo edit … --homepage https://nazrulworld.github.io/fhir-validation-service/`, the browser step
  Settings → General → Features → Wikis → "Restrict editing to users in teams with push access", the verification
  command, and that repository admin rights are required (FR-029).
- [X] T034 [P] [US5] Link the published site from `README.md` (near the top, next to the existing docs pointers)
  (FR-030).
- [X] T035 [P] [US5] Add an entry under `## [Unreleased]` in `CHANGELOG.md` ("Documentation" / "Added": the user guide
  is published at https://nazrulworld.github.io/fhir-validation-service/, generated from the wiki) (FR-031).
- [X] T036 [US5] Wiki repository (separate clone, `wiki/`): in `Home.md` add the site URL near the top and an
  "Operations" link to `https://github.com/nazrulworld/fhir-validation-service/blob/main/docs/operations.md` in the
  "Reference (in the repository)" list (FR-016, FR-030). Commit and push to the wiki only after the workflow is merged
  and Pages is enabled, then run `workflow_dispatch` (git pushes may not raise `gollum`). **Confirm with the
  maintainer before pushing** (outward-facing).
- [X] T037 [US5] With the maintainer's confirmation and admin rights, run the one-time setup from T033 and verify
  `gh api repos/nazrulworld/fhir-validation-service/pages --jq .build_type` → `workflow` and the repository homepage
  (FR-029).

**Checkpoint**: the live site exists at the documented URL and every pointer reaches it.

---

## Phase 8: Polish & Cross-Cutting Concerns

- [X] T038 [P] Run the full converter suite and the editor-check test locally
  (`python3 -I -m unittest discover -s scripts/tests/docs-site`, `sh scripts/tests/docs-site/test_editor_check.sh`)
  and the quickstart V1–V13 against the current wiki; fix findings. Record the time the first-time local setup took
  (CONTRIBUTING steps on a clean checkout) in the PR description.
- [X] T039 [P] Review `.github/workflows/docs-site.yml` for FR-033: `grep -n '\${{ *github.event' .github/workflows/docs-site.yml`
  must show matches only under `env:` keys, never inside `run:`.
- [ ] T040 After merge to `main` and setup (T036/T037), run quickstart C1–C7 and record the outcomes (including the
  ≤ 10 min wiki-edit-to-live time, SC-001) in the PR or a follow-up comment; revert the deliberate broken wiki edit of C5.
- [X] T041 Mark `specs/011-docs-github-pages/checklists/requirements.md` notes with the implementation outcome and
  update research.md R8 if T023 changed the editor-check mechanism.

---

## Dependencies & Execution Order

### Phase dependencies

- Setup (T001–T005) → Foundational (T006–T008) → user stories.
- **US1** (T009–T017) needs Foundational. It is the MVP.
- **US2** (T018–T023) needs US1's converter (T012–T014) to build anything; T019/T021 (editor check) are independent of
  the converter and can start right after Setup.
- **US3** (T024–T030) needs Foundational and T014 (anchors on reference pages); independent of US2. US2's relevance
  check only becomes meaningful after US3 (T020 note).
- **US4** (T031–T032) needs US1 (and US3 for a full comparison).
- **US5**: T033–T035 can be written any time after T032; T036–T037 need US2 merged (workflow on `main`).
- Polish after all stories; T040 after merge and setup.

### Within each story

Tests first (must fail) → converter code → scaffolding → end-to-end check.

### Parallel opportunities

- Setup: T002, T003, T004, T005 in parallel after T001 (T002 is independent of T001 too).
- US1: T009, T010, T011 in parallel; T015, T016 in parallel with T012–T014 (different files).
- US2: T018, T019 in parallel; T021 in parallel with T020.
- US3: T024, T025 in parallel; T027 in parallel with T026.
- US5: T033 (after T032), T034, T035 in parallel.

## Parallel Example: User Story 1

```text
Task: "T009 [US1] tests/test_wiki_pages.py"
Task: "T010 [US1] tests/test_wiki_links.py"
Task: "T011 [US1] tests/test_anchors.py"
# then, alongside the converter work T012–T014:
Task: "T015 [US1] site/_includes/footer_custom.html"
Task: "T016 [US1] site/_includes/head_custom.html + header_custom.html"
```

## Parallel Example: User Story 3

```text
Task: "T024 [US3] tests/test_reference_markdown.py"
Task: "T025 [US3] tests/test_reference_files.py"
Task: "T027 [US3] site/_redoc/template.hbs"   # alongside T026
```

## Implementation Strategy

### MVP first (US1)

1. Setup + Foundational.
2. US1 → build locally; the guide is readable with nav, search, dark mode and deep links. Stop and validate
   (quickstart V1–V5, V8, V12).

### Incremental delivery

1. US1 → local site (MVP).
2. US2 → workflow; PR builds check the site; after merge and setup, wiki edits deploy.
3. US3 → Reference section and API reference; relevance check gets real input.
4. US4 + US5 → contributor docs, pointers, CHANGELOG, wiki links, one-time setup, go-live checks (T040).

All of US1–US3 ship in one PR; the one-time setup and wiki edits (T036, T037) follow the merge, each confirmed with
the maintainer.
