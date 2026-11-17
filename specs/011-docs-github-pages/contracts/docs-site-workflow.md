# Contract: `.github/workflows/docs-site.yml`

## Triggers (FR-021)

```yaml
on:
  gollum:
  workflow_dispatch:
  push:
    branches: [main]
  pull_request:
    paths:
      - site/**
      - scripts/build-docs-site.py
      - scripts/docs-site-check-editor.sh
      - scripts/tests/docs-site/**
      - .github/workflows/docs-site.yml
```

Top-level `permissions: contents: read`.

## Job `build` (all triggers)

| Step | Behaviour |
|---|---|
| checkout | `actions/checkout@v7` (main repo, `fetch-depth: 1`) |
| checkout wiki | `actions/checkout@v7`, `repository: ${{ github.repository }}.wiki`, `path: wiki-src`, `persist-credentials: false` |
| editor check | only `gollum`: `scripts/docs-site-check-editor.sh` → `GET repos/{repo}/collaborators/$EDITOR_LOGIN/permission` (`EDITOR_LOGIN` via `env:`); fail unless `admin`/`maintain`/`write`; message names the editor (FR-032) |
| relevance check | only `push`; the step's `skip` output is exposed as the job output `skip`: `--list-sources` + scaffolding paths vs `git diff --name-only $BEFORE $SHA` after `git fetch --no-tags --depth=1 origin $BEFORE`; no overlap → set `skip=true`, remaining steps skipped, job succeeds; all-zero `before` or a failed fetch → build |
| setup | `ruby/setup-ruby@v1` (3.4, `bundler-cache`, `working-directory: site`), `actions/setup-node@v7` (22, `cache: npm`, `cache-dependency-path: site/_redoc/package-lock.json`) |
| tests | `python3 -I -m unittest discover -s scripts/tests/docs-site` with `DOCS_SITE_JEKYLL_TESTS=1`, `DOCS_SITE_REDOC_TESTS=1`; `sh scripts/tests/docs-site/test_editor_check.sh` (after setup, so the Jekyll and Redocly tests run) |
| convert | `python3 -I scripts/build-docs-site.py --wiki wiki-src` (fails on unresolved wiki links, FR-006) |
| configure | `actions/configure-pages@v6` (on `main` only; supplies nothing the converter needs — `baseurl` is fixed) |
| jekyll | `bundle exec jekyll build -s target/docs-site/src -d target/docs-site/_site --config …/_config.yml,…/_config.generated.yml`, `JEKYLL_ENV=production` |
| link check | `bundle exec htmlproofer target/docs-site/_site --disable-external --assume-extension .html --swap-urls '^/fhir-validation-service:' --ignore-files '/openapi\.yaml\.html$/'` (FR-023; the Redoc page resolves its own `#operation/…` links in the browser, research R6) |
| upload | `actions/upload-pages-artifact@v5` with `path: target/docs-site/_site` (main only) |

No wiki-derived value (page name, sender, commit message) appears inside a `run:` script; event data reaches scripts
only through `env:` (FR-033).

## Job `deploy`

```yaml
deploy:
  needs: build
  if: github.ref == 'refs/heads/main' && github.event_name != 'pull_request' && needs.build.outputs.skip != 'true'
  permissions: { pages: write, id-token: write }
  environment: { name: github-pages, url: ${{ steps.deployment.outputs.page_url }} }
  concurrency: { group: pages, cancel-in-progress: false }
  steps:
    - id: deployment
      uses: actions/deploy-pages@v5
```

## Guarantees

- A failure in any `build` step means no `deploy`; the live site stays as it was (FR-023, SC-004).
- Runs on refs other than `main` and pull requests build and check only (FR-022).
- Deployments are serialised; a running deployment is never cancelled (FR-020).
