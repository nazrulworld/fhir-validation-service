# Data Model: Publish the User Guide from the Wiki to GitHub Pages

**Feature**: `011-docs-github-pages` | **Date**: 2026-11-17

No database and no service state. The "data" is the in-memory model of one converter run
(`scripts/build-docs-site.py`). Everything is rebuilt from scratch on every run.

## Entities

### WikiPage

| Field | Type | Rule |
|---|---|---|
| `name` | str | wiki file name without `.md`; must match `^[^/\\\x00]+$`; `_Sidebar`, `_Footer` are not pages (FR-001) |
| `key` | str | `name` lower-cased with spaces → `-`; used to resolve wiki links (FR-005); two pages with the same key → error |
| `title` | str | `name` with `-` → space (FR-003) |
| `url` | str | `/` for `Home`, else `/<name>` (FR-019a) |
| `edit_url` | str | `https://github.com/nazrulworld/fhir-validation-service/wiki/<name>/_edit` (FR-015) |
| `nav_order` | int | from `_Sidebar.md`, else Home = 1 and the rest alphabetical by title (FR-004) |
| `anchors` | list[str] | GitHub-style heading ids, in order, duplicates suffixed (FR-019, research R4) |
| `body` | str | rewritten Markdown |

### ReferenceFile

| Field | Type | Rule |
|---|---|---|
| `path` | str | repository-relative, normalised, inside the repo root; unique |
| `kind` | enum | `markdown` (`.md`), `openapi` (`.yaml`/`.yml`/`.json` whose top level has an `openapi:` key), `text` (`.yaml`, `.yml`, `.json`, `.sh`, `.env`, `.env.*`, `*.env`) (FR-008, FR-010, FR-011) |
| `title` | str | `markdown`: first heading text, else file name; `openapi`: "API reference"; `text`: file name |
| `page_url` | str | `markdown`: `/reference/<path minus .md>`; `openapi`/`text`: `/reference/<path>.html` |
| `download_url` | str? | `openapi`/`text`: `/reference/<path>`; `markdown`: none |
| `discovered_from` | str | the page or file that first linked it (for the summary and warnings) |
| `nav_order` | int | order of first discovery, breadth-first from guide pages in nav order (research R10) |
| `assets` | list[str] | images referenced by a `markdown` file, published at `/reference/<asset path>` (FR-013) |

### Link (one occurrence in a source)

| Field | Type | Rule |
|---|---|---|
| `source` | WikiPage \| ReferenceFile | where it appears (outside code spans and fenced blocks) |
| `form` | enum | `wiki` (`[[Page]]`, `[[Text\|Page]]`), `wiki-url`, `repo-url` (`/blob/` or `/raw/`, any ref), `relative` (inside reference Markdown), `other` |
| `target` | str | page key or repository path, plus optional `#anchor` |
| `outcome` | enum | see state table below |

### SiteBuild

| Field | Type | Rule |
|---|---|---|
| `commit` | str | main-repository commit (`GITHUB_SHA`, or `git rev-parse HEAD` locally) |
| `wiki_commit` | str | wiki checkout `HEAD` |
| `pages`, `references` | lists | above |
| `warnings` | list[str] | missing repo files, links escaping the repo, unsupported relative targets |
| `errors` | list[str] | unresolved wiki links, URL collisions, invalid page names, Redoc failure |
| `result` | enum | `ok` (exit 0) or `failed` (exit 1); any error → `failed`, nothing deployed (FR-023) |

## Link outcomes (state table)

| Form / target | Outcome | Rewritten to | Effect |
|---|---|---|---|
| `wiki`/`wiki-url`, page exists | `site-page` | `{{base}}/<Page>#anchor` | — |
| `wiki`/`wiki-url`, page missing | `error` | unchanged | build fails (FR-006) |
| `repo-url` or `relative`, file exists, supported kind | `site-reference` | page URL (md) / page URL (openapi, text) | file queued once (FR-007, FR-009) |
| `repo-url`, directory / unsupported kind / missing / non-file page / templated (`${…}`) | `external` | unchanged | missing → warning (FR-012) |
| `relative` (in reference Markdown), directory | `github` | `…/tree/<commit>/<path>` | — (FR-012a) |
| `relative`, unsupported kind | `github` | `…/blob/<commit>/<path>` | — (FR-012a) |
| `relative`, missing | `github` | `…/blob/<commit>/<path>` | warning (FR-012a) |
| `relative`, escapes repo root | `text` | link removed, text kept | warning |
| `relative`, image inside repo | `asset` | `{{base}}/reference/<path>` | file copied (FR-013) |
| anything else (http(s) elsewhere, `mailto:`, in-page `#anchor`) | `untouched` | unchanged | in-page anchors are checked by html-proofer |

`{{base}}` is the site base path written by the converter as a literal string (pages render without Liquid,
research R3), i.e. `/fhir-validation-service`.

## Lifecycle of one run

`collect wiki pages` → `resolve sidebar order` → `rewrite guide pages (queue reference files)` →
`process queue breadth-first (rewrite, queue further files; each path once)` → `render API reference (Redoc)` →
`write pages, files, _config.generated.yml` → `print summary` → exit 0/1.
With `--list-sources` the run stops after the queue is complete and prints the repository paths, one per line.
