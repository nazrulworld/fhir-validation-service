# Fixtures for the documentation-site converter tests (spec 011)

| Path | Purpose |
|---|---|
| `wiki-basic/` | Home, Installation-Guide, API-Documentation: every wiki-link form, code blocks with `[[…]]` and Liquid, duplicate headings, every kind of repository link |
| `wiki-sidebar/` | `_Sidebar.md` ordering, `_Footer.md`, a page not listed in the sidebar |
| `wiki-broken/` | a link to a missing wiki page |
| `repo/` | repository files the wiki links to: Markdown chain with a cycle, relative links, an image, an OpenAPI file, text downloads |
| `headings.md` | heading-id edge cases (leading digit, non-ASCII, emoji, closing `#`, code, duplicates) |
| `wiki-real/` | copy of the real wiki pages at revision 45a0f6e |
| `github-anchors.json` | heading ids GitHub renders for `wiki-real/` |

`github-anchors.json` is taken from the live wiki page HTML (GitHub's Markdown API no longer returns heading ids).
Refresh both after the wiki changes headings:

```sh
cp wiki/*.md scripts/tests/docs-site/fixtures/wiki-real/
for p in Home Installation-Guide API-Documentation; do
  curl -sL "https://github.com/nazrulworld/fhir-validation-service/wiki/$p" \
    | grep -oE '<a id="user-content-[^"]*" class="anchor"' | sed -E 's/.*user-content-([^"]*)".*/\1/'
done   # paste the lists into github-anchors.json ("anchors" → page → ids in order)
```
