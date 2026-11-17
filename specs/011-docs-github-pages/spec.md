# Feature Specification: Publish the User Guide from the Wiki to GitHub Pages

**Feature Branch**: `011-docs-github-pages`

**Created**: 2026-11-16

**Status**: Draft

**Input**: User description: "@var/specify_instruction_docs_github_pages.md" — publish the service's user guide as a
static documentation site on GitHub Pages, generated automatically from the GitHub wiki.

**Background**: the wiki repository `nazrulworld/fhir-validation-service.wiki.git` (pages `Home`, `Installation-Guide`,
`API-Documentation`), `.github/workflows/ci.yml`, `scripts/`, `README.md`, `CONTRIBUTING.md`, `docs/operations.md`.
State on 2026-11-16: the repository is public, the wiki is enabled, GitHub Pages is not enabled.

## Context

The user guide exists only as wiki pages. The wiki has no full-text search, no dark mode and no build check, and its
pages link out to raw GitHub views of repository files: the configuration contract, the OpenAPI definition, the
changelog, the load-test results and `.env.example`. Those views are hard to read for operators and integrators who
are not developers.

This feature publishes the guide as a documentation site at `https://nazrulworld.github.io/fhir-validation-service/`,
generated from the wiki on every change. **The wiki stays the one place where the guide is written.** Nobody edits the
generated site, and generated files are never committed. Repository files that the wiki links to are pulled into the
site as readable pages.

**Release**: documentation and CI only. The service's HTTP contract, configuration and artifacts do not change, so no
service version bump is required; the changelog records the new documentation site.

**Decisions already made by the maintainer**: the site uses the Just the Docs theme for Jekyll; it is deployed with the
GitHub Pages "GitHub Actions" source; the converter is a Python script using only the standard library; the site is
always built from `main`; `docs/operations.md` is published by linking it from the wiki; the API reference is rendered
during the build by an exact-version Redocly CLI.

## Clarifications

### Session 2026-11-17

- Q: Who may edit the wiki, given that every wiki edit is deployed automatically? → A: Only collaborators with push
  access. The one-time setup turns on the wiki's "Restrict editing to users in teams with push access" setting, and
  the build refuses to deploy a run triggered by a wiki edit whose editor lacks push access (the setting itself is not
  readable through the GitHub API, so the build checks the editor instead) (FR-029, FR-032).
- Q: Where does a link on a published reference page go when it targets a repository file the site does not publish
  (source code, a directory, a spec's `plan.md`)? → A: To the GitHub view of that file or directory at the exact
  commit the site was built from (`blob/<sha>/…` or `tree/<sha>/…`) (FR-012a).
- Q: What web addresses do site pages use? → A: Guide pages keep the wiki page name exactly
  (`<site>/API-Documentation`); reference pages mirror the repository path under `/reference/` with `.md` dropped
  (`<site>/reference/perf/README`); downloads keep their repository path and file name
  (`<site>/reference/src/main/resources/openapi.yaml`) (FR-019a).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Read and search the guide on the documentation site (Priority: P1)

An operator or API integrator opens the documentation site. The landing page is the wiki's `Home` page. A left sidebar
lists every guide page in the wiki's order, a search box finds any term in any page, the reader can switch between a
light and a dark colour scheme, and the site is usable on a phone. A deep link they already have to a section of a
wiki page (for example `.../wiki/API-Documentation#post-versionvalidate`) has a site equivalent that opens the same
section.

**Why this priority**: this is the value of the feature. Without a readable, searchable site nothing else matters.

**Independent Test**: build the site from the current wiki and open it in a browser at desktop and phone width; check
the landing page, sidebar order, search and colour-scheme switch, and that a wiki deep link mapped to the site opens
the same section.

**Acceptance Scenarios**:

1. **Given** the wiki has `Home`, `Installation-Guide` and `API-Documentation`, **When** the site is built, **Then**
   the site root shows the `Home` content and the sidebar lists "Home", then the other pages titled "API
   Documentation" and "Installation Guide" (alphabetically, as no `_Sidebar.md` exists).
2. **Given** a `_Sidebar.md` exists in the wiki, **When** the site is built, **Then** the navigation order follows
   the sidebar and neither `_Sidebar` nor `_Footer` appears as a page.
3. **Given** the site is published, **When** a reader searches for a term that appears only in the Installation
   Guide, **Then** the Installation Guide is among the results.
4. **Given** a wiki page contains `[[API Documentation]]`, `[[the API|API-Documentation]]` or a full
   `https://github.com/nazrulworld/fhir-validation-service/wiki/API-Documentation#authentication` link, **When** the
   site is built, **Then** each link points to the site's API Documentation page (at the `#authentication` section
   for the last one).
5. **Given** a guide page shows a JSON or shell example containing `{{ value }}` or `{% tag %}`, **When** the page is
   published, **Then** those characters appear exactly as written in the wiki.
6. **Given** any published page, **When** a reader scrolls to the footer, **Then** it shows the commit the site was
   built from and, for guide pages, an "Edit this page in the wiki" link to that page's wiki editor.

---

### User Story 2 - A wiki edit is published automatically, and a bad edit never breaks the site (Priority: P1)

A maintainer edits a wiki page in the browser. Within minutes the site shows the change, with no further action. If the
edit introduces a link to a wiki page that does not exist, or a link to a section anchor that does not exist, the build
fails, the maintainer sees the failure, and the previously published site stays online unchanged.

**Why this priority**: the wiki-as-source model only works if publishing is automatic and safe; a manual step would
let the site drift from the wiki.

**Independent Test**: edit a wiki page and watch the workflow run and the site update; then make an edit with a link
to a non-existent page and confirm the run fails and the live site still serves the earlier version.

**Acceptance Scenarios**:

1. **Given** the site is published, **When** a maintainer saves a wiki page edit, **Then** a build starts
   automatically and the change is live without anyone committing to the main repository.
2. **Given** a wiki change was pushed by git and raised no wiki-edit event, **When** a maintainer starts the workflow
   manually, **Then** the site is rebuilt from the current wiki.
3. **Given** a wiki edit adds `[[Missing Page]]`, **When** the build runs, **Then** it fails with a message naming
   the page and the missing target, and the live site is not replaced.
4. **Given** a wiki edit adds a link to `API-Documentation#no-such-section`, **When** the build runs, **Then** the
   internal-link check fails and the live site is not replaced.
5. **Given** a push to `main` changes the site scaffolding, the converter, or any repository file that is published
   on the site, **When** the push lands, **Then** the site is rebuilt and deployed.
6. **Given** a push or pull request on a branch other than `main`, **When** the workflow runs, **Then** it builds and
   checks the site but does not deploy it.
7. **Given** a wiki edit was made by a GitHub user without push access to the repository, **When** the build runs,
   **Then** it fails before deploying, names the editor, and the live site is not replaced.
8. **Given** builds are triggered in quick succession, **When** they run, **Then** a deployment already running is
   never cancelled, deployments happen one at a time, and the site ends up at the newest content (a waiting run may be
   replaced by a newer one).

---

### User Story 3 - Linked repository files become readable reference pages (Priority: P2)

A reader follows a guide link to the configuration contract, the changelog, the load-test results, the operations
runbook or the OpenAPI definition. Instead of a raw GitHub view they land on a site page in a "Reference" section:
Markdown files are rendered pages, the OpenAPI definition is an interactive API reference with the YAML offered as a
download, and other text files such as `.env.example` are shown in a code block with a download link.

**Why this priority**: it removes the hard-to-read raw views, but the guide is useful without it.

**Independent Test**: build the site and follow every repository-file link on the guide pages; each lands on a site
page whose content matches the file at the built commit.

**Acceptance Scenarios**:

1. **Given** a guide page links `https://github.com/nazrulworld/fhir-validation-service/blob/main/CHANGELOG.md`,
   **When** the site is built, **Then** the link points to a Reference page titled by the file's first heading,
   showing the changelog from the built commit.
2. **Given** `perf/README.md` is published and links `perf/results-1.1.0.md`, **When** the site is built, **Then**
   `perf/results-1.1.0.md` is published too and the link inside `perf/README.md` points to it.
3. **Given** two published Markdown files link each other, **When** the site is built, **Then** each is published
   once and the build terminates.
4. **Given** a guide page links `src/main/resources/openapi.yaml`, **When** the site is built, **Then** the link
   points to a rendered API reference page that works without loading anything from another site, and that page
   offers the YAML file as a download.
5. **Given** a guide page links `.env.example`, **When** the site is built, **Then** the link points to a page that
   shows the file in a code block and offers it as a download.
6. **Given** a guide page links a directory, a file missing from the checkout, the Releases page, Issues, the
   container registry, or a templated download URL such as `releases/download/v${VERSION}/…`, **When** the site is
   built, **Then** the link stays external and unchanged, missing-file links are logged as warnings, and the build
   does not fail.
7. **Given** a link uses `/raw/` instead of `/blob/`, or a ref other than `main`, **When** the site is built, **Then**
   it is treated the same way, using the file content of the commit being built.

---

### User Story 4 - A contributor previews the site locally (Priority: P3)

A contributor follows documented commands — clone the wiki, run the converter, serve the site — and sees locally the
same site CI would publish, including the same failures for broken links.

**Why this priority**: it shortens the feedback loop for scaffolding and converter changes; CI alone still protects
the site.

**Independent Test**: on a clean machine with the documented prerequisites, follow the commands and compare the local
page list and link-check result with a CI build of the same commit and wiki revision.

**Acceptance Scenarios**:

1. **Given** the documented prerequisites are installed, **When** a contributor runs the documented commands,
   **Then** the site is served locally and has the same pages and navigation as the CI build of that commit.
2. **Given** a local wiki checkout with a broken wiki link, **When** the contributor runs the converter, **Then** it
   fails with the same message CI would show.

---

### User Story 5 - One-time setup and pointers to the site (Priority: P3)

A repository admin enables the site once, following documented steps. Afterwards the repository homepage, the
`README.md`, the `CONTRIBUTING.md` and the wiki `Home` page all point readers and contributors to the site and explain
that the guide is edited in the wiki.

**Why this priority**: needed once for go-live and for discoverability; small effort.

**Independent Test**: follow the documented setup on the repository; check the Pages settings, the repository
homepage, and the three pointers.

**Acceptance Scenarios**:

1. **Given** Pages is not enabled, **When** an admin runs the documented setup steps, **Then** Pages uses the
   "GitHub Actions" build type and the repository homepage is the site URL.
2. **Given** the feature is complete, **When** a reader opens `README.md` or the wiki `Home` page, **Then** each links
   to the published site.
3. **Given** a contributor reads `CONTRIBUTING.md`, **When** they look for how to change the guide, **Then** it says
   the guide is edited in the wiki, published automatically, and how to preview locally.
4. **Given** the wiki links `docs/operations.md`, **When** the site is built, **Then** the operations runbook is
   published as a Reference page.

---

### Edge Cases

- Wiki link written with spaces (`[[API Documentation]]`, as the wiki uses today) resolves to the page
  `API-Documentation`; matching ignores the space/hyphen difference.
- Wiki link to a page that does not exist fails the build, naming the source page and the target.
- Heading anchors that GitHub generates from headings with punctuation (e.g. `#### POST /{version}/validate` →
  `#post-versionvalidate`) produce the same anchor on the site, including duplicate-heading suffixes (`-1`, `-2`).
- Repo-file links to directories, missing files, non-file GitHub pages, and templated URLs stay external; missing
  files are warnings, not failures.
- Cycles among linked Markdown files: each file is published once.
- Relative links inside a published Markdown file (e.g. `results-1.1.0.md`, `../CHANGELOG.md`) resolve against that
  file's location. When the target is published on the site, the link goes to the site copy; when it is a repository
  file or directory the site does not publish (e.g. `../../src/main/java/…`, `plan.md`), the link goes to its GitHub
  view at the built commit; when it does not exist in the checkout, the link goes to the GitHub view at the built
  commit and a warning is logged; a relative link escaping the repository root is logged as a warning and left as
  plain text. None of these break the internal-link check.
- Images referenced by published Markdown files are published with them, or the link check fails.
- `{{ … }}` and `{% … %}` anywhere in wiki pages or reference files are shown literally.
- No `_Sidebar.md` and/or no `_Footer.md` in the wiki: default ordering applies and nothing fails.
- A wiki page renamed: its old site URL disappears; links to the old name in other wiki pages fail the build until
  fixed.
- Wiki changes pushed via git do not raise a wiki-edit event: covered by the manual trigger (and any later `main`
  push).
- Two runs triggered close together: deployments are serialised, none cancelled.
- A run on a ref other than `main` (branch push, pull request) never deploys.
- Wiki repository unreachable during a build: the build fails and the live site stays as it was.
- A wiki edit by someone without push access (possible only if the edit restriction is turned off): the run fails
  before deploying and names the editor; the live site stays as it was.
- A wiki page name containing shell metacharacters or quotes: it is treated as data, never executed.
- An edit by a user without push access stays in the wiki even though its own run fails, so a later push to `main` or
  a manual run would publish it. The wiki edit restriction (FR-029) is the primary safeguard; the editor check
  (FR-032) is a second line of defence.

## Requirements *(mandatory)*

### Functional Requirements

**Content**

- **FR-001**: Every wiki page MUST become a site page, except `_Sidebar` and `_Footer`, which MUST NOT be published
  as pages.
- **FR-002**: The wiki `Home` page MUST be the site's landing page (site root).
- **FR-003**: A page's title MUST come from its wiki page name, with hyphens shown as spaces (`Installation-Guide` →
  "Installation Guide").
- **FR-004**: Navigation order MUST follow the wiki `_Sidebar.md` when it exists; otherwise `Home` first and the
  remaining pages alphabetically by title.
- **FR-005**: Wiki links MUST be rewritten to site links: `[[Page]]`, `[[Text|Page]]` and full
  `https://github.com/nazrulworld/fhir-validation-service/wiki/Page` URLs, preserving any `#anchor`. Page-name
  matching MUST treat spaces and hyphens as equal and ignore case.
- **FR-006**: A wiki link to a page that does not exist MUST fail the build with a message naming the source page and
  the missing target.
- **FR-007**: Links from published content to files in the main repository
  (`https://github.com/nazrulworld/fhir-validation-service/blob/<ref>/<path>` or `/raw/<ref>/<path>`, any ref) MUST be
  resolved against the checkout of the commit being built, and rewritten to the site copy of that file.
- **FR-008**: Linked Markdown files MUST become pages in a "Reference" navigation section, titled by their first
  heading (falling back to the file name when there is none).
- **FR-009**: Links inside a published Markdown file to other repository Markdown files (relative or full GitHub URL)
  MUST be followed transitively, publishing each file once, and MUST be rewritten to the site copies.
- **FR-010**: A linked file whose top level has an `openapi:` key MUST become a rendered API reference page that
  needs no resources from other sites at view time, and the original YAML MUST be offered as a download from that
  page.
- **FR-011**: Other linked text files (`.yaml`, `.yml`, `.json`, `.env` and dotenv-style files such as `.env.example`,
  `.sh`) MUST be published as downloads, each with a page that shows the content in a code block and links the
  download.
- **FR-012**: Links to directories, to files missing from the checkout, to file types not listed in FR-008–FR-011, and
  to non-file GitHub pages (Releases, Issues, container registry, templated download URLs) MUST stay external and
  unchanged. Links to missing files MUST be logged as warnings and MUST NOT fail the build.
- **FR-012a**: A relative link inside a published Markdown file whose target is a repository file or directory the
  site does not publish (a file type not listed in FR-008–FR-011, a directory, or a missing file) MUST be rewritten to
  the GitHub view of that path at the commit being built (`blob/<sha>/<path>` for files, `tree/<sha>/<path>` for
  directories). Missing targets MUST also be logged as warnings. Supported file types linked from published Markdown
  files are published under FR-008–FR-011 like links from wiki pages.
- **FR-013**: Images referenced by published Markdown files from inside the repository MUST be published alongside
  them.
- **FR-014**: Content MUST be published literally: `{{ … }}` and `{% … %}` sequences in any published page MUST NOT
  be interpreted by the site generator.
- **FR-015**: Every page MUST show a footer with the short commit SHA the site was built from (linked to the commit),
  and guide pages MUST also show an "Edit this page in the wiki" link to that page's wiki editor.
- **FR-016**: The wiki MUST link `docs/operations.md` (from the Installation Guide or `Home`) so the operations runbook
  is published as a Reference page under FR-008.

**Look and behaviour**

- **FR-017**: The site MUST use Jekyll with the Just the Docs theme: left sidebar navigation, full-text search over
  all pages, a light/dark colour-scheme switch, and header links to the GitHub repository and its Releases page.
- **FR-018**: The site MUST be usable at phone width (360 px) without horizontal page scroll for prose content.
- **FR-019**: Heading anchors MUST match the anchors GitHub generates for the same heading in the wiki, including
  duplicate-heading suffixes, so every existing `wiki/<Page>#<anchor>` deep link has the site equivalent
  `<site>/<Page>#<anchor>`.
- **FR-019a**: Site addresses MUST be derived mechanically from the source: a guide page is served at
  `<site>/<Page>`, using the wiki page name with its exact case (`Home` is served at the site root); a reference page
  at `<site>/reference/<repository path without .md>` (e.g. `<site>/reference/perf/README`,
  `<site>/reference/CHANGELOG`); a download at `<site>/reference/<repository path>` with its original file name, and
  its code-view or API reference page at the same path with `.html` appended. Two different source files MUST
  never map to the same address.

**Build and publishing**

- **FR-020**: A GitHub Actions workflow MUST build and deploy the site using the Pages "GitHub Actions" source
  (configure, upload artifact, deploy), with permissions limited to `pages: write`, `id-token: write`,
  `contents: read`, and one `pages` concurrency group that does not cancel in-progress runs.
- **FR-021**: The workflow MUST run on a wiki page create/edit event (`gollum`), on manual dispatch, and on a push to
  `main` that changes the site scaffolding, the converter, the workflow itself, or any repository file currently
  published on the site. It MAY also run on pull requests that change the scaffolding or converter.
- **FR-022**: Only runs on `main` MUST deploy. Runs on any other ref MUST build and check only. Reference content MUST
  always come from the commit being built; there is no release-triggered or tag-based build.
- **FR-023**: The build MUST fail on unresolved wiki links (FR-006) and on broken internal links or anchors, checked on
  the generated site with external links not checked. A failed build MUST NOT deploy, so the previous site stays live.
- **FR-024**: The API reference page (FR-010) MUST be rendered during the build by an exact, pinned version of the
  Redocly CLI run on demand; no Redoc bundle is committed to the repository.
- **FR-025**: All third-party actions and tools used by the workflow MUST be pinned in the same style as
  `.github/workflows/ci.yml`; Jekyll 4 and Just the Docs MUST be pinned in a `Gemfile` with a committed lock file.
- **FR-026**: Generated output MUST NOT be committed. The converter MUST live at `scripts/build-docs-site.py` and use
  only the Python standard library; the site scaffolding (`Gemfile`, `_config.yml`, footer include, any static assets)
  MUST live in a `site/` directory.
- **FR-027**: The converter MUST print a summary: pages published, reference files published by kind, links rewritten,
  and warnings.
- **FR-028**: A contributor MUST be able to build and preview the site locally with documented commands (clone the
  wiki, run the converter, serve the site), producing the same pages, navigation and link-check result as CI for the
  same commit and wiki revision.

**One-time setup and pointers**

- **FR-029**: The documentation MUST describe the one-time setup — enable Pages with the "GitHub Actions" build type,
  set the repository homepage to the site URL, and turn on the wiki setting "Restrict editing to users in teams with
  push access" — as commands an admin can run (`gh`) or, where no command exists, as the exact settings-page step,
  noting that repository admin rights are required.
- **FR-030**: `README.md` MUST link to the published site; `CONTRIBUTING.md` MUST explain that the guide is edited in
  the wiki, is published automatically, and how to preview it locally; the wiki `Home` page MUST mention the site URL.
- **FR-031**: `CHANGELOG.md` MUST record the documentation site under the next release's notes.

**Security**

- **FR-032**: A run triggered by a wiki edit MUST deploy only when the editor (the event's sender) has push access to
  the repository; otherwise the run MUST fail before deploying, naming the editor. Runs triggered by manual dispatch
  or a push to `main` already require write access and need no extra check.
- **FR-033**: Wiki page names, wiki content and event data MUST be handled as untrusted input: they MUST NOT be
  interpolated into workflow shell commands or expressions, so a crafted page name or edit cannot run commands in the
  build.

### Key Entities *(include if feature involves data)*

- **Wiki page**: a Markdown page in the wiki repository; has a page name (file name without `.md`), a title derived
  from it, a navigation position, an edit URL, and the set of wiki links and repo-file links it contains.
- **Reference file**: a repository file reached from a wiki page or from another reference file; has a repository
  path, a kind (Markdown page, OpenAPI definition, downloadable text file), a site URL, and a title (first heading,
  or file name).
- **Link rewrite**: the mapping from an original link (wiki link or GitHub file URL, with optional anchor) to its site
  URL, or a decision to leave it external, with a warning when the target file is missing.
- **Site build**: one run of the converter and site generator for a given main-repository commit and wiki revision;
  produces the site, the summary, and a pass/fail result; deploys only on `main`.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A saved wiki edit is visible on the live site within 10 minutes, with no manual step.
- **SC-002**: 100% of wiki pages (excluding `_Sidebar` and `_Footer`) and 100% of resolvable repo-file links on them
  are published on the site; no published page links to a raw GitHub view of a file that the site also publishes.
- **SC-003**: The live site has 0 broken internal links or anchors.
- **SC-004**: A wiki edit containing a link to a non-existent page or section fails its build in 100% of cases, and
  the site served before the edit stays unchanged.
- **SC-005**: Every section deep link of the current wiki (every heading of every page) opens the same section on the
  site.
- **SC-006**: A search for a distinctive term from any page returns that page among the results.
- **SC-007**: Example text containing `{{ … }}` or `{% … %}` is shown on the site character-for-character as in the
  wiki.
- **SC-008**: A contributor following the documented local-preview steps gets the same page list and the same
  link-check result as CI for the same commit and wiki revision.
- **SC-009**: Every page is readable at 360 px width without horizontal page scroll for prose.
- **SC-010**: No run triggered by a wiki edit from a user without push access deploys; with the wiki edit
  restriction on (FR-029), such edits cannot be made at all.

## Assumptions

- The repository stays public, so GitHub Pages and the wiki checkout need no extra credentials beyond the workflow
  token; the one-time setup is done by someone with admin rights.
- The site URL is `https://nazrulworld.github.io/fhir-validation-service/` (project site, base path
  `/fhir-validation-service`); no custom domain.
- The wiki remains the only source of the guide. Moving the guide into the main repository and per-release
  documentation versions are out of scope.
- Repository files are published only when the wiki (directly or transitively) links them; other files, such as specs
  not linked, are not published. `docs/operations.md` is in scope because the wiki will link it (FR-016).
- The site reflects `main`. Reference pages may be ahead of the latest release between releases; this is accepted.
- The wiki-edit event (`gollum`) is delivered for browser edits; git pushes to the wiki may not raise it, which the
  manual trigger covers.
- Ruby (Jekyll) and Node (Redocly CLI) are build-time tools in CI and for local preview only; they are not runtime
  dependencies of the service. Their justification under constitution Principle VIII is recorded in `plan.md`.
- Performance budgets of constitution Principle VI do not apply: the feature does not touch the service runtime.
- No service version bump: no HTTP contract, configuration or artifact change.
