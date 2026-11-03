# Confluence Page Versus Source

Comparison of the "Using the FHIR Validator" Confluence export (2026-11-01) with the 7.0.3 source. Treat this as a list of open disagreements: for what a released jar does, the source is the better evidence, and for intent, the page is. Neither was confirmed by running the jar. `scripts/verify-behaviors.sh` tests the most important ones.

Upstream fixes may land at any time, so re-check before reporting one as a bug.

## Flags on the page that the CLI does not define

Passing any of these is expected to fail with an unknown-option error and exit 1 `[inferred]`. Picocli is case-sensitive here: the source never enables case-insensitive options.

| Page says | Status | Use instead |
|---|---|---|
| `-check-references-to <url>` | No option in the CLI. `ValidationEngineParameters` still has the field. | `-check-references` |
| `-txcache` | Wrong case | `-txCache` |
| `-ai-server` (in one example) | Typo; the same section says `-ai-service` | `-ai-service` |
| `-current` | Page says not yet supported; no option | None |
| `-lang`, `-coding-display` | Page says not supported; no options | `-language`; see `-check-display` below |
| `-security-only` | Page says not yet supported; no option | `-security-checks` is a different, existing check |
| `-no-html-tags-in-content` | Described as a future security tag; no option | `-html-in-markdown error` |

## Behaviour disagreements

| Topic | Page | Source | Guidance |
|---|---|---|---|
| Default FHIR version | 4.0.1 when none is found | `5.0.0` in `determineVersion`; help says 5.0 | Page text is stale. Always pass `-version`. |
| `-ig hl7.fhir.r3.core` | "Synonymous with specifying the version" | The convertor sets the version, then `getValidationEngine` overwrites it when `-version` is absent | Likely upstream bug. Verify, and use `-version` meanwhile. |
| Valid versions | R2 to R5 table, including `4.3` | Major list omits `4.3` but has `6.0`; full list has `4.1.0`, `4.3.0`, `6.0.0` | The list is a completion hint, not validated at parse time. `4.3` still likely works through version lookup. |
| No source given | "At least one source" required | With `-version`, no source means no work and exit 0 | Check for sources yourself. |
| `-ips` default | IPS 1.1.0 | Preset uses `hl7.fhir.uv.ips#2.0.1` | Page is stale. |
| `-check-ips-codes` | Set by `-ips` | True at 7.0.3; option and preset use removed on master | Version-dependent. |
| `-native` | "Not presently supported" | Option exists, but `SchemaValidator` only adds "not done yet" messages | Page is right. It performs no schema validation. |
| `-check-display` | Page's old `-coding-display` "not supported" | Option exists and is stored, but nothing reads `getCheckDisplay()` | Accepted, with no effect found at 7.0.3. Invalid case still throws. |
| Wrong display | Error since 6.0.6 | Help for `-check-display` claims `Ignore` is the default | Page is right about severity; use `-display-issues-are-warnings` to downgrade. Needs terminology to detect. |
| `-language` | Repeatable; `-language -en` removes a language | Single string passed to the validator as its language, with no list or removal logic found; a second use should be a parse error | Use one plain language value. Do not rely on add or remove syntax. |
| `Locale: en-AU. Languages: ...` log line | Shown as output | Not found; logs `Locale: <country>/<code>` | Page is stale. |
| `-profile <ig-canonical>` | Validates using the IG's global profiles | `asSdList` requires a StructureDefinition, so an IG URL should throw `Unable to resolve profile` | Verify before use. |
| Global profiles from package IGs | Implied to work | Only IGs loaded as files or folders are recorded for `validator.igs`; the package branch does not add them | Verify. Name the profile with `-profile`. |
| `[ver]` prefix on `-ig` | Loads the package for that FHIR version | Used for file and folder sources; the package branch uses the package's own version | Do not rely on it for packages. |
| "Packages are assumed to have the same version as -version" | Stated | Loader is chosen by the package's `fhirVersion`, so content converts | Mixed versions load but are not equivalent. |
| Terminology cache location | "User's local directory" | `[tmp]/default-tx-cache/r<N>`; `[tmp]` follows `tempPath` in settings | Log line gives the true location. |
| HTTP fetch for references | One Accept header with several types | GET as `application/json`, then retry `application/fhir+xml` | Minor; matters for strict servers. |
| `-unknown-codesystems-cause-errors` | Final sentence says "warning not an error" | Help and code: makes it an **error** | Page sentence is a typo. Required bindings to HL7 code systems are always errors. |
| SNOMED `uk` | `999000041000000102` | `83821000000107`; `uk/clinical` is `999000021000000109` | Use the alias, not a typed id. |
| `-sct` values | 10 editions | Also `us+icd10cm`, `uk/clinical`, `at`, `ca-en`, `ca-fr`, `es-ES`, `ar`, `ee`, `fi`, `de`, `in`, `ie`, `nz`, `no`, `kr`, `ch`, `uy` | The server must carry the edition. |
| Version conversion scope | "r2, r3, and r4" | Accepts any supported target | Quality varies, so review output. |

## Confirmed by both

- `-questionnaire` effective default is `check`. The CLI help's `none` is wrong.
- `-jurisdiction` defaults from the system locale.
- External references are not followed by default. Relative references need `-resolution-context`.
- Unknown extensions are prohibited by default.
- `-level` values, `-best-practice` values and default, and `-html-in-markdown` default.
- `-validation-timeout` output "may not be complete or accurate".
- SSRF settings `allowHttp` and `allowPrivateNetwork`.

## Not reviewed

The page links to these, and they were not available: Validator Advisor Framework, Using fhir-settings.json, Running the Validator as a local HTTP service, and Using the FHIR Mapping Language. What this skill says on those topics comes from source only.
