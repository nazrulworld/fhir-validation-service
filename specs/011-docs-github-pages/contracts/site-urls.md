# Contract: Site addresses

Site root: `https://nazrulworld.github.io/fhir-validation-service/` (`<site>`). These addresses are public and
permanent once published (FR-019a). Rules are mechanical; the converter fails on a collision.

| Source | Address | Example |
|---|---|---|
| wiki `Home` | `<site>/` | `https://nazrulworld.github.io/fhir-validation-service/` |
| wiki `<Page>` | `<site>/<Page>` (exact case) | `<site>/Installation-Guide` |
| wiki deep link `wiki/<Page>#<anchor>` | `<site>/<Page>#<anchor>` (same anchor, FR-019) | `<site>/API-Documentation#post-versionvalidate` |
| repo Markdown `<path>.md` | `<site>/reference/<path>` | `<site>/reference/specs/001-service-hardening/contracts/configuration` |
| repo OpenAPI file | page `<site>/reference/<path>.html`, download `<site>/reference/<path>` | `<site>/reference/src/main/resources/openapi.yaml.html` |
| repo text file | page `<site>/reference/<path>.html`, download `<site>/reference/<path>` | `<site>/reference/.env.example.html` |
| image used by a reference page | `<site>/reference/<path>` | — |
| Reference section index | `<site>/reference/` | — |

Expected set for the current wiki (2026-11-17), once the wiki links `docs/operations.md` (FR-016):

```text
/                                   Home
/API-Documentation                  API Documentation
/Installation-Guide                 Installation Guide
/reference/                         Reference
/reference/specs/001-service-hardening/contracts/configuration
/reference/src/main/resources/openapi.yaml.html   (+ /reference/src/main/resources/openapi.yaml)
/reference/CHANGELOG
/reference/perf/README
/reference/docs/operations
/reference/.env.example.html        (+ /reference/.env.example)
```

Mapping a wiki URL by hand: replace `https://github.com/nazrulworld/fhir-validation-service/wiki/` with `<site>/`
(and `wiki/Home` with `<site>/`). Mapping a repo file URL: replace `…/blob/<ref>/` with `<site>/reference/` and drop
`.md` (Markdown) or append `.html` (other files' pages).
