# Quickstart: Build, check and validate the documentation site

**Feature**: `011-docs-github-pages` | Contracts: [converter-cli](contracts/converter-cli.md),
[site-urls](contracts/site-urls.md), [docs-site-workflow](contracts/docs-site-workflow.md)

## Prerequisites (local)

- Python ≥ 3.10, Ruby 3.4 with Bundler, Node 22 (macOS: the system Ruby 2.6 is too old — e.g.
  `brew install ruby@3.4 node@22` and put both on `PATH`, or run the Ruby steps in the `ruby:3.4` container:
  `docker run --rm -v "$PWD:/repo" -w /repo -e BUNDLE_GEMFILE=/repo/site/Gemfile ruby:3.4 sh -c 'bundle install && …'`).
- `gh` authenticated, only for the one-time setup and the editor-check scenario.

## 1. Local build and preview (US4, FR-028)

```sh
git clone --depth 1 https://github.com/nazrulworld/fhir-validation-service.wiki.git target/wiki-src
python3 -I -m unittest discover -s scripts/tests/docs-site
python3 -I scripts/build-docs-site.py --wiki target/wiki-src          # add --no-redoc to skip Node
export BUNDLE_GEMFILE=$PWD/site/Gemfile
bundle install
bundle exec jekyll build -s target/docs-site/src -d target/docs-site/_site \
  --config target/docs-site/src/_config.yml,target/docs-site/src/_config.generated.yml
bundle exec htmlproofer target/docs-site/_site --disable-external \
  --assume-extension .html --swap-urls '^/fhir-validation-service:' --ignore-files '/openapi\.yaml\.html$/' --allow-missing-href
bundle exec jekyll serve -s target/docs-site/src \
  --config target/docs-site/src/_config.yml,target/docs-site/src/_config.generated.yml
# open http://localhost:4000/fhir-validation-service/
```

**Expected**: tests pass; converter summary shows `pages: 3`, Reference `markdown=4 openapi=1 text=1` once the wiki
links `docs/operations.md`; htmlproofer reports no failures; the site matches [site-urls](contracts/site-urls.md).

## 2. Validation scenarios

| # | Scenario | Steps | Expected |
|---|---|---|---|
| V1 | Landing, nav, titles (US1-1/2) | open `/` | Home content; sidebar Home, API Documentation, Installation Guide, Reference |
| V2 | Search (US1-3, SC-006) | search a term only in the Installation Guide (e.g. `FV_SHUTDOWN_GRACE_MS`) | Installation Guide in results |
| V3 | Dark mode, phone width (FR-017/018, SC-009) | toggle scheme; reload; resize to 360 px | choice persists; no horizontal scroll in prose |
| V4 | Deep links (FR-019, SC-005) | open `/API-Documentation#post-versionvalidate`, `#authentication` | same sections as the wiki |
| V5 | Wiki link forms (US1-4) | copy `wiki-src` to a scratch dir, add `[[API Documentation]]`, `[[x\|API-Documentation]]`, a full wiki URL with `#authentication`; rebuild | all three point to `/fhir-validation-service/API-Documentation…` |
| V6 | Broken wiki link (US2-3) | add `[[Missing Page]]` in the scratch wiki; run converter | exit 1, `error: …"Missing Page"` |
| V7 | Broken anchor (US2-4) | add `[x](API-Documentation#no-such-section)` as a wiki URL; build + htmlproofer | htmlproofer fails |
| V8 | Literal Liquid (FR-014, SC-007) | add a code block with `{{ x }}` and `{% endraw %}`; build | shown verbatim |
| V9 | Reference pages (US3) | follow each Reference link | configuration, CHANGELOG, perf README, operations rendered; API reference renders Redoc and offers the YAML; `.env.example` page has a code block and download |
| V10 | Self-contained API reference (FR-010) | `DOCS_SITE_REDOC_TESTS=1 python3 -I -m unittest discover -s scripts/tests/docs-site -p test_reference_files.py` | the self-containment test passes: no external script, stylesheet, preload, import, image or CSS `url()` |
| V11 | Unpublished targets (FR-012, FR-012a) | links to Releases, `${VERSION}` download, a directory | stay external / go to `tree|blob/<sha>`; missing file → warning, exit 0 |
| V12 | Footer (FR-015) | any guide page; any reference page | "Edit this page in the wiki" (guide only) + commit and wiki commit links |
| V13 | Determinism (SC-008) | run the converter twice; `diff -r` the two outputs | identical |

## 3. CI scenarios (after merge)

| # | Scenario | Expected |
|---|---|---|
| C1 | Edit a wiki page in the browser (US2-1, SC-001) | `docs-site` run on `gollum`; deployed; change live in ≤ 10 min |
| C2 | Push to `main` touching only Java code | run ends at the relevance check, no deploy |
| C3 | Push to `main` changing `CHANGELOG.md` (US2-5) | build + deploy; Reference page updated |
| C4 | PR changing `site/_config.yml` (US2-6) | build + link check, no deploy job |
| C5 | Wiki edit with `[[Missing Page]]` (SC-004) | build fails; live site unchanged; revert the edit afterwards |
| C6 | `workflow_dispatch` on `main` (US2-2) | rebuild from current wiki, deploy |
| C7 | Editor check (FR-032) | dispatch a test run with the check pointed at a non-collaborator login (unit-tested step script) → fails naming the login |

## 4. One-time setup (FR-029, admin)

```sh
gh api -X POST repos/nazrulworld/fhir-validation-service/pages -f build_type=workflow
gh repo edit nazrulworld/fhir-validation-service --homepage https://nazrulworld.github.io/fhir-validation-service/
```

Then in the browser: **Settings → General → Features → Wikis → "Restrict editing to users in teams with push
access"** (no API exists for this setting). Verify: `gh api repos/nazrulworld/fhir-validation-service/pages --jq
.build_type` → `workflow`.
