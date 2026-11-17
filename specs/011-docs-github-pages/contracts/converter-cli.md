# Contract: `scripts/build-docs-site.py`

Python ≥ 3.10, standard library only. Run as `python3 -I scripts/build-docs-site.py …` (like `scripts/corpus-010.py`).

## Synopsis

```text
python3 -I scripts/build-docs-site.py --wiki DIR [--repo DIR] [--out DIR]
                                      [--commit SHA] [--wiki-commit SHA]
                                      [--base-path PATH] [--no-redoc] [--list-sources]
```

| Option | Default | Meaning |
|---|---|---|
| `--wiki DIR` | required | checkout of `fhir-validation-service.wiki.git` |
| `--repo DIR` | the repository containing the script | main-repository checkout; reference files are read from here |
| `--out DIR` | `<repo>/target/docs-site/src` | Jekyll source to create; **deleted and recreated** each run; must be inside `<repo>/target/`, empty, non-existent, or a previous output (marker file `.docs-site-build`) |
| `--commit SHA` | `$GITHUB_SHA`, else `git -C <repo> rev-parse HEAD` | main-repository commit shown in footers and used in `blob/<sha>`/`tree/<sha>` links |
| `--wiki-commit SHA` | `git -C <wiki> rev-parse HEAD` | wiki revision shown in footers |
| `--base-path PATH` | `/fhir-validation-service` | site base path used in rewritten links and `baseurl` |
| `--no-redoc` | off | write a placeholder API reference page instead of running Redocly (offline local runs; never in CI) |
| `--list-sources` | off | resolve links only; print every repository path the site would publish, sorted, one per line; write nothing |

## Output (in `--out`)

- a copy of `site/` (scaffolding; `site/_redoc/`, `Gemfile*`, `.bundle/`, `vendor/` are not copied) and the marker
  file `.docs-site-build`;
- `pages/guide-NNN.md` for every guide page (`NNN` = navigation order; the address comes from `permalink`);
- `pages/reference.md` (section parent, `/reference/`) and `pages/ref-NNN.md` for Markdown reference files and
  code-view pages, `pages/ref-NNN.html` for the Redoc page (`.md` placeholder with `--no-redoc`);
- `_downloads/reference/<path>` for every download and image, published byte for byte by `site/_plugins/downloads.rb`;
- `_config.generated.yml` (JSON, valid YAML): `baseurl`, `build: {commit, commit_short, wiki_commit,
  wiki_commit_short}`, `docs_downloads` (paths under `_downloads/`).

Every generated page has front matter `title`, `nav_order`, `permalink`, `render_with_liquid: false`, and as
applicable `parent: Reference`, `wiki_edit_url`, `search_exclude`, `layout`.

## Streams and exit codes

- **stdout**: the summary — `pages: N`, `reference: markdown=N openapi=N text=N assets=N`, `links rewritten: N`,
  `warnings: N` (or the source list for `--list-sources`).
- **stderr**: one line per warning/error, `warning: <source>: <message>` / `error: <source>: <message>`. When
  `GITHUB_ACTIONS=true`, also emitted as `::warning file=<source>::<message>` / `::error …` annotations. Messages never
  echo raw wiki text beyond the link target.
- **Exit**: `0` success (warnings allowed); `1` build error (unresolved wiki link, URL collision, invalid page name,
  Redocly failure, unreadable input); `2` usage error.

## Errors (exit 1)

| Condition | Message (example) |
|---|---|
| wiki link target missing | `error: Home: wiki link to missing page "Missing Page"` |
| two sources map to one address | `error: address /reference/x is produced by a.md and A.md` |
| page name with `/`, `\` or NUL, or starting with `.` or `_` (other than `_Sidebar`, `_Footer`) | `error: invalid wiki page name` |
| two page names equal ignoring case and space/hyphen | `error: same wiki page name: …` |
| no `Home` page | `error: the wiki has no Home page` |
| `npm ci --prefix site/_redoc --ignore-scripts` or Redocly CLI fails / not available | `error: src/main/resources/openapi.yaml: Redocly build-docs failed (exit N)` |
| `--out` not safe to delete | usage error, exit 2 |

## Determinism

Same wiki revision + same repository commit + same options → byte-identical `--out` (sorted iteration, no
timestamps). This is what makes the local build match CI (FR-028, SC-008).
