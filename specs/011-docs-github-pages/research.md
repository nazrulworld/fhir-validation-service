# Research: Publish the User Guide from the Wiki to GitHub Pages

**Feature**: `011-docs-github-pages` | **Date**: 2026-11-17 | **Spec**: [spec.md](spec.md)

Versions were checked on 2026-11-17 against rubygems.org, the npm registry and the GitHub releases API.

## R1. Tool versions and pinning

- **Decision**:
  - Ruby **3.4** (CI: `ruby/setup-ruby@v1` with `bundler-cache: true`); `site/Gemfile` pins `jekyll "4.4.1"`,
    `just-the-docs "0.12.0"`, and in group `:test` `html-proofer "5.2.3"`. `site/Gemfile.lock` is committed with the
    platforms `x86_64-linux`, `arm64-darwin` and `ruby` (`bundle lock --add-platform …`).
  - Node **22** (CI: `actions/setup-node@v7` with `cache: npm`, `cache-dependency-path: site/_redoc/package-lock.json`);
    Redocly CLI **2.79.0** pinned in `site/_redoc/package.json` with a committed `site/_redoc/package-lock.json`, so the
    whole dependency tree is locked. The converter runs `npm ci --prefix site/_redoc` (once per run, skipped when
    `node_modules` matches the lock) and then the local binary `site/_redoc/node_modules/.bin/redocly`.
    `site/_redoc/node_modules/` is git-ignored. No Redoc bundle is committed.
    *Implementation note*: `@redocly/cli` 2.79.0 has no runtime dependencies (it ships bundled), so the lock file pins
    one package with its integrity hash; `npm ci` runs with `--ignore-scripts`, and a stamp file
    (`node_modules/.fv-lock-sha256`) skips the install when the lock is unchanged.
  - Python: the runner's `python3` (3.12 on `ubuntu-latest`); the converter supports Python ≥ 3.10, stdlib only.
  - Actions: `actions/checkout@v7`, `actions/configure-pages@v6`, `actions/upload-pages-artifact@v5`,
    `actions/deploy-pages@v5` — major-version tags, the style of `.github/workflows/ci.yml` (FR-025).
- **Rationale**: exact gem versions plus a committed lock file make CI and local builds identical (FR-028, SC-008).
  Major tags for actions match the repository's existing convention. The latest Pages action majors are required
  together (`upload-pages-artifact@v5` produces the artifact format `deploy-pages@v5` expects).
- **Alternatives considered**: the `github-pages` gem (rejected: pins Jekyll 3 and blocks Just the Docs 0.12 and
  `render_with_liquid`); pinning actions by commit SHA (rejected: no other workflow in the repo does it; can be adopted
  repo-wide later); `ci.yml` stays on `checkout@v4` — not touched by this feature.
- **Local note**: macOS ships Ruby 2.6 and this machine has Node 16; both are too old (Jekyll 4.4 needs Ruby ≥ 2.7,
  Redocly CLI 2 needs Node ≥ 20.19). The quickstart documents installing Ruby 3.4 and Node 22.

## R2. Where the site is assembled

- **Decision**: the converter copies `site/` (scaffolding) into a build directory, default `target/docs-site/src`,
  and writes the generated pages and files next to it; Jekyll builds from there into `target/docs-site/_site`.
  `site/` itself only ever holds hand-written files.
- **Rationale**: `target/` is already git-ignored (FR-026: generated output never committed), and a fresh copy per run
  means no stale generated page survives a wiki rename.
- **Alternatives considered**: generating into `site/` with a `.gitignore` (rejected: stale files between local runs,
  easy to commit by mistake); a new top-level `build/` (rejected: needs a new ignore rule for no gain).

## R3. Literal content (`{{ … }}`, `{% … %}`)

- **Decision**: every generated page carries `render_with_liquid: false` in its front matter (Jekyll ≥ 4.0). The
  layouts and includes, which are hand-written, still use Liquid.
- **Rationale**: turns Liquid off per page without changing the content, so even `{% endraw %}` inside an example is
  safe (FR-014, SC-007).
- **Alternatives considered**: wrapping bodies in `{% raw %}…{% endraw %}` (rejected: breaks when the content itself
  contains `{% endraw %}`); escaping braces (rejected: changes the text).

## R4. GitHub-compatible heading anchors

- **Decision**: the converter computes each heading's id with GitHub's algorithm — lower-case; drop every character
  that is not a letter, digit, space, hyphen or underscore (Unicode-aware); spaces → `-`; duplicates get `-1`, `-2`, …
  in document order — and writes it as a kramdown block attribute line directly after the heading:
  `{: id="<id>"}`. The header-id form `{#id}` is not used: kramdown only accepts it for ids that start with a
  letter, and GitHub ids can start with a digit (`## [1.1.0] - 2026-11-16` → `110---2026-11-16`). Headings inside
  fenced code blocks are skipped. Example: `#### POST /{version}/validate` → `post-versionvalidate`.
- **Rationale**: explicit ids do not depend on the Markdown engine's auto-id rules (kramdown's own rules strip leading
  digits and differ on punctuation), so FR-019/SC-005 hold by construction.
- **Verification**: a converter test compares the ids for every heading of the wiki pages with the ids in the live
  wiki page HTML (`<a id="user-content-…" class="anchor">`, prefix removed), recorded as a fixture
  (`scripts/tests/docs-site/fixtures/github-anchors.json`) so the test runs offline. *Implementation note
  (2026-11-17)*: GitHub's Markdown API (`gh api markdown`) no longer returns heading ids, so the live wiki HTML is the
  only source — which is also the source the site has to match. All 48 ids of the three pages match.
- **Alternatives considered**: relying on kramdown's GFM auto-ids (rejected: not identical for emoji and punctuation
  edge cases, and silent drift on engine updates).

## R5. Page addresses (FR-019a)

- **Decision**:
  - Guide page `<Page>.md` → front matter `permalink: /<Page>` → output `<Page>.html`, served at `<site>/<Page>`
    (GitHub Pages resolves an extensionless path to `.html`). `Home` → `permalink: /`.
  - Reference Markdown `<path>.md` → `permalink: /reference/<path without .md>`.
  - Download `<path>` → static file at `reference/<path>`; its code-view / API reference page →
    `permalink: /reference/<path>.html`.
  - Downloads and images are copied to `_downloads/reference/<path>` and published byte for byte by a 15-line Jekyll
    generator, `site/_plugins/downloads.rb`, from the list `docs_downloads` in `_config.generated.yml`.
    *Implementation note*: this replaces the planned `include:` list. Jekyll skips dot-files (`.env.example`) and
    reads any file that starts with `---` (common in YAML) as front matter, which would alter a download; the
    generator avoids both.
    *Go-live note (2026-11-17)*: `actions/upload-pages-artifact@v5` also drops dot-files unless
    `include-hidden-files: true` is set; the first deployment served 404 for `/reference/.env.example` until it was.
  - Generated page sources live in `pages/` (`guide-NNN.md`, `ref-NNN.md|html`, `reference.md`); their addresses come
    only from `permalink`, so a wiki page name can never collide with scaffolding files.
- **Rationale**: one mechanical rule each; repository paths are unique, so no two sources collide (the converter
  still checks and fails on a collision, e.g. `Foo.md` vs `foo.md` on a case-insensitive file system).

## R6. API reference page (FR-010, FR-024)

- **Decision**: the converter runs `site/_redoc/node_modules/.bin/redocly build-docs <openapi.yaml> --output <tmp>
  --inlineBundle --template site/_redoc/template.hbs --templateOptions.<…>` and writes the result as a Jekyll page
  at `reference/<path>.html` with `layout: null`, `render_with_liquid: false`, `search_exclude: true`, a title
  ("API reference") and `parent: Reference`. The template adds a slim bar (link back to the site, YAML download) and
  the build footer (commit). *Implementation note*: in Redocly CLI 2 `--disableGoogleFont` was removed (passing it is
  an error; the page no longer loads Google Fonts), and without `--inlineBundle` the page loads Redoc from
  `cdn.redocly.com`; `--inlineBundle` embeds it (the page is about 2.7 MB). The converter runs Redocly and `npm ci` with
  `REDOCLY_TELEMETRY=off`, `REDOCLY_SUPPRESS_UPDATE_NOTICE=true` and `DO_NOT_TRACK=1` (the CLI otherwise sends usage
  telemetry). A converter test asserts the page loads nothing from another site (no external script, stylesheet,
  preload, module import, image or CSS `url()`; plain hyperlinks such as Redoc's attribution link are allowed).
- **Rationale**: one address that deep links into Redoc (`…/openapi.yaml.html#operation/validate`), is listed in the
  sidebar's Reference section, and is excluded from the search index (Redoc's markup would swamp it). No Redoc bundle
  in the repo.
- **Link check**: html-proofer skips this page (`--ignore-files`); Redoc resolves its `#operation/…` links in the
  browser, and its static HTML need not contain those ids. The page's back-link and download link are checked by the
  converter test and quickstart V9.
- **Alternatives considered**: `npx --yes @redocly/cli@2.79.0` without a lock file (rejected after analysis:
  pins only the top package, so transitive dependencies float, builds are not reproducible and the supply chain is
  unpinned); iframe inside a normal layout (rejected: Redoc deep links break, poor phone
  scrolling); Redoc from a CDN at view time (rejected by the spec: not self-contained); vendored
  `redoc.standalone.js` (rejected by the maintainer).
- **Offline/fast local runs**: `--no-redoc` writes a placeholder page with the same address and a link to the YAML.
  CI never uses it.

## R7. Workflow triggers and the "published files changed" rule (FR-021)

- **Decision**: trigger on `gollum`, `workflow_dispatch`, `push` to `main` (no `paths` filter), and `pull_request`
  with `paths: [site/**, scripts/build-docs-site.py, scripts/tests/docs-site/**, .github/workflows/docs-site.yml]`.
  For a `push`, the first step runs the converter with `--list-sources` (prints every repository path the site
  publishes for the current wiki) and compares it, plus the scaffolding paths, with
  `git diff --name-only <before> <sha>`; no overlap → the job ends successfully without building.
- **Rationale**: the published file set is decided by the wiki at build time, so a static `paths:` list would drift.
  The check costs one shallow wiki clone and a stdlib Python run (seconds).
- **Edge**: a new branch push or a force push has `before` = all zeros or an unknown commit → build anyway.

## R8. Deploy safety and concurrency (FR-020, FR-022, FR-023, FR-032)

- **Decision**: two jobs. `build` (permissions `contents: read`) runs the converter, Jekyll and html-proofer, then
  `actions/upload-pages-artifact`. `deploy` (`needs: build`, `if: github.ref == 'refs/heads/main' && github.event_name
  != 'pull_request'`, environment `github-pages`, permissions `pages: write`, `id-token: write`) runs
  `actions/deploy-pages`. `concurrency: {group: pages, cancel-in-progress: false}` is set on the `deploy` job only, so
  pull-request builds never queue behind deployments.
- **Behaviour note**: with `cancel-in-progress: false` a running deployment always finishes; GitHub keeps at most one
  *pending* run per group, so a third trigger replaces the pending one. Because each run reads the wiki and `main` at
  run time, the newest run always carries the newest content — the spec's "site ends up at the newer content" holds.
- **Editor check**: for `gollum`, a step calls `GET /repos/{repo}/collaborators/{sender}/permission` with the
  workflow token and fails unless the permission is `admin`, `maintain` or `write`. The login is passed through
  `env:` (`SENDER: ${{ github.event.sender.login }}`), never interpolated into `run:` text (FR-033). If the call itself
  fails (e.g. 404 for a non-collaborator), the check fails closed. To verify during implementation: the endpoint is
  readable with the default `GITHUB_TOKEN` (needs repository metadata read only). *Verified 2026-11-17*: the first `gollum` run
  (run 44044938408) read the permission with the workflow token (`wiki edit by nazrulworld (admin): allowed to
  deploy`); no fallback needed. A git push to the wiki did raise `gollum`, and the edit was live in under a minute.
- **Untrusted input**: page names come from wiki file names; the converter only uses them as data (paths are
  validated against `^[^/\\\x00]+$`, written with `os.path.join` under the output root, and rejected when they
  resolve outside it). No wiki-derived value appears in a `run:` line.

## R9. Wiki checkout

- **Decision**: `actions/checkout@v7` with `repository: ${{ github.repository }}.wiki`, `path: wiki-src`,
  `fetch-depth: 1`, `persist-credentials: false`. Locally: `git clone --depth 1
  https://github.com/nazrulworld/fhir-validation-service.wiki.git`.
- **Rationale**: the wiki is public; the workflow token can read it. A failed clone fails the build before deploy,
  so the live site stays (spec edge case).

## R10. Navigation, theme behaviour, footer

- **Decision**:
  - Guide pages get `nav_order` from `_Sidebar.md` (order of first wiki link; pages not listed follow,
    alphabetically) or Home-first-then-alphabetical; the Reference section is a generated parent page
    `reference/index.md` (`title: Reference`, `has_children: true`, last `nav_order`), its children ordered by first
    discovery in a breadth-first walk from the guide pages in nav order.
  - `_config.yml`: `aux_links` "GitHub" and "Releases"; `search_enabled: true`; `heading_anchors: true`;
    `color_scheme: light` plus a `dark` toggle: `_includes/head_custom.html` applies the stored choice or
    `prefers-color-scheme` via `jtd.setTheme`, and a toggle button in `_includes/header_custom.html`.
  - `_includes/footer_custom.html`: "Edit this page in the wiki" (when `page.wiki_edit_url` is set) and
    "Built from <short sha> (wiki <short sha>)" linked to the commits, from `site.build` values in
    `_config.generated.yml`.
  - `_Footer.md` content is not reproduced (the build footer replaces it); raw HTML in wiki pages passes through as
    kramdown renders it (GitHub's sanitiser is not reproduced; content comes from collaborators only, FR-032).
- **Rationale**: uses Just the Docs' documented extension points only; no theme fork.

## R11. Converter tests

- **Decision**: `python3 -I -m unittest discover -s scripts/tests/docs-site` with fixture wikis/repos under
  `scripts/tests/docs-site/fixtures/`; tests run before the build step in the workflow and are written before the
  converter code (constitution V, test-first; Testcontainers rules do not apply — no service code).
- **Coverage**: wiki link forms incl. spaces and anchors; missing page fails; `_Sidebar`/no sidebar ordering; repo
  link kinds (md, openapi, yaml/json/env/sh, dir, missing, Releases, `${VERSION}` template, `/raw/`, other refs);
  transitive md + cycles; relative links to unpublished files → `blob|tree/<sha>`; escaping links; images; code
  blocks untouched; Liquid literal; anchor ids vs the GitHub fixture; URL collisions; hostile page names;
  `--list-sources`.
