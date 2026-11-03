# Validate Command: Every Argument

Source: `ValidateCommand`, `ValidationEngineCommand` and `picocli/options/*` at 7.0.3 and master `26ac536`, plus guidance from the Confluence page. Disagreements between them are in `docs-vs-source.md`.

Contents: positional sources · FHIR version and definitions · terminology · references and fetching · profiles and conformance · message control · output · Questionnaire and context · limits · FHIRPath legacy · watch mode · presets · inherited options · translation overrides · help and deprecated forms · version differences.

Option name spelling matters, because names mix `kebab-case`, `camelCase` and one `snake_case`. Parsing behaviour in the "Values and default" columns comes from the converters, not the help text, and differences from the help are called out.

## Positional sources

| Form | Behaviour |
|---|---|
| File path | Validates that file. A missing path throws `File X does not exist`. |
| Folder | Validates direct children only, skipping ignorable files. `-recurse` does **not** apply here: it only affects `-ig` folders. |
| Wildcard (`dir/*.json`) | Files matching the pattern. The `*` must be in the filename, not the directory path. A missing directory is an error, but an existing directory with no match validates nothing and exits 0. Quote it if you want the validator, not the shell, to expand it. |
| `http:` or `https:` URL | Fetched and validated. Authentication is not supported for source URLs. |

Formats are JSON, XML, Turtle and Smart Health Cards (signed JWTs, downloadable files, or QR text, but not QR images). NDJSON, text and FML also have loaders (`IgLoader.loadContent`). Extensions `.json`, `.xml`, `.ttl`, `.jwt` and `.jws` imply the format, and otherwise the content is inspected.

## FHIR version and definitions

| Option | Values and default | Effect |
|---|---|---|
| `-version <v>` | Major: `1.0`, `1.4`, `3.0`, `4.0`, `5.0`, `6.0`. Full: `1.0.2`, `1.4.0`, `3.0.2`, `4.0.1`, `4.1.0`, `4.3.0`, `5.0.0`, `6.0.0`. `dev` selects `hl7.fhir.r5.core#current`. Help says default 5.0, but the real flow scans first. | Chooses the core package. See the version algorithm in `validation-chain.md`. The list is a completion hint, not parse-time validation. |
| `-ig <x>`, alias `-defn` | Repeatable. Package `id`, or `id#ver`. Special versions: `current` (latest build of main), `current$branch` (a branch), `dev` (your local IG Publisher build). Also a canonical URL with optional `\|ver` (for example `http://hl7.org/fhir/us/core\|1.0.1`), a URL returning a conformance resource, a local folder, `.tgz`, `.pack`, `igpack.zip`, or a single conformance file. Prefix `[4.0]` to force the FHIR version of a folder or file source. | Loads conformance content and non-core package dependencies, installing packages into the cache as needed. With no version, uses the cached or latest published one. Core packages (`hl7.fhir.rN.core`) are removed from the list and only used to set the stated version. A conflict with `-version` throws `Parameters are inconsistent`. |
| `-recurse` | Flag, off. | Include subfolders when `-ig` is a folder. |
| `-native` | Flag, off. | **Stub.** Help describes schema, schematron, JSON Schema and ShEx checks, but `SchemaValidator` only adds an information message saying validation is "not done yet". The Confluence page also says it is not supported. |
| `-no-internal-caching` | Flag, off. | Sets `TerminologyCache.setNoCaching(true)`. |
| `-do-debug` | Flag, off. | Debug mode on the engine and validator. |
| `-show-times` | Flag, off. | Logs timing per source. |

If no version is given, use `-ig` only for what it does: it can influence the scan only when it is **not** a core package.

## Terminology

| Option | Values and default | Effect |
|---|---|---|
| `-tx <url>` | Default: `txFhirProduction` from settings, else `https://tx.fhir.org`. `n/a` means none. | Sets the server. Naming any server turns the ecosystem off, so all requests go to it. On master, adding `-ecosystem` keeps it on. `n/a` means no terminology at all, ecosystem included. |
| `-ecosystem <url>` | **master only**. Default `https://ecosystem.hl7.org/tx-reg` when `-tx` is unset. `n/a` disables. | The registry that decides which server is authoritative for a code system. With both options, the `-tx` server replaces `tx.fhir.org` wherever the ecosystem would refer to it. |
| `-txLog <file>` | No log by default. | Logs terminology operations. |
| `-txCache <dir>` | Default `[tmp]/default-tx-cache/r<N>` per FHIR release. `n/a` means memory only. | On-disk terminology cache. Written at the end of every engine command, even after a failure. |
| `-clear-tx-cache` | Flag, off. | Clears the cache before validating. Note this clears the **shared** default folder if `-txCache` is not set. |
| `-sct <edition>` | Aliases at 7.0.3: `intl`, `us`, `us+icd10cm`, `uk`, `uk/clinical`, `au`, `at`, `ca`, `ca-en`, `ca-fr`, `nl`, `se`, `es`, `es-ES`, `ar`, `dk`, `be`, `ee`, `fi`, `de`, `in`, `ie`, `nz`, `no`, `kr`, `ch`, `uy`, or a module id. Default `900000000000207008` (international). | Adds a `system-version` expansion parameter for SNOMED CT. The server must have the edition loaded. Value sets from IGs that bind a particular edition ignore this setting. |
| `-expansion-parameters <file>` | No default. | Parameters resource supplying fixed code system and value set versions. Loaded after the engine is built. |
| `-language <lang>` | No default. Single value in the CLI. | Language for checking coding displays, with the same values as `xml:lang`. Ignored where the resource sets `language`. The page's repeatable form and `-language -en` removal have no matching logic in source. |
| `-display-issues-are-warnings` | Flag, off. | Wrong displays are **errors** by default since 6.0.6; this makes them warnings. Upstream keeps the error default because a wrong display often signals a wrong code. |
| `-no-extensible-binding-warnings` | Flag, off. | Hides review warnings for codes outside extensible bindings. Only a person can judge those codes. |
| `-unknown-codesystems-cause-errors` | Flag, off. | For **required** bindings, a non-HL7 unknown code system is an error, not a warning. Required bindings to HL7 code systems are errors regardless. |
| `-codesystem-validation-size-limit <n>` | 7.0.3+. Default 1000. `0` means no limit. | Maximum codes checked for a single ValueSet include, ConceptMap group, or CodeSystem supplement. Above the limit none are checked and a hint is issued. Servers that support batches, such as `tx.fhir.org`, receive codes in batches. |
| `-check-display <mode>` | `Ignore`, `Check`, `CheckCase`, `CheckSpace`, `CheckCaseAndSpace`. **Case-sensitive**: uses `valueOf`, so `check` throws. | **No effect found at 7.0.3**: stored on the validator, but nothing reads it, even though help describes it. |
| `-tx-routing` | Flag, off. | Prints a routing dump showing which server answered and why. |
| `-authorise-non-conformant-tx-servers` | Flag, off. Inherited. | Allows servers that fail conformance checks. A nominated server should be a `tx.fhir.org` clone, or software that passed conformance testing: the page lists Ontoserver 6.15.0+ and CATY. Prefer registering an extra server with `tx.fhir.org` as authoritative for its code system, so routing finds it automatically. |
| `-ai-service <s>` | `claude`, `chatgpt`, `ollama`, `ollama:{port}`, `ollama:{url}`, each with optional `#{model}`. Keys come from `fhir-settings.json`. Experimental. | After other validation, asks the service whether code and text pairs agree, and reports disagreements as warnings. Answers are remembered, and text identical to a known display is skipped. Shares codes and text with that service, so avoid it for real patient data unless the service is self-hosted. |

## References and fetching

| Option | Values and default | Effect |
|---|---|---|
| `-check-references` | Flag, off. | Policy becomes `CHECK_VALID`, so external targets are resolved and validated. Off means `IGNORE`. |
| `-resolution-context <ctx>` | `http(s)://base`, or `file:<folder>`. | Base for relative references. Without one, relative targets fail to resolve when checking is on. |
| `-assumeValidRestReferences` | Flag, off. | Uses the type in REST-shaped references when target types are checked. FHIR does not require references to look RESTful, so by default `Group/123` says nothing about the target type. With this on, it can fail where only Patient is allowed, without fetching. |
| `-allow-example-urls` | Optional boolean (arity 0..1), off. | Treats `example.org` and `acme.com` references as resolvable, and allows extensions from those domains. |
| `-showReferenceMessages` | Flag, off. | Shows detailed messages from validating reference targets, not only a summary. |
| `-disable-default-resource-fetcher` | Flag, off. | No on-the-fly canonical or package fetching. Uses `DisabledValidationPolicyAdvisor`. |
| `-r5-bundle-relative-reference-policy <p>` | `always`, `never`, `default` (applied in R5 and later). | R5 ended Bundle reference resolution at the matching step, with no fallback, and the validator has enforced that since 6.6. Use `never` for content that relied on the older looser behaviour. |

## Profiles and conformance

| Option | Values and default | Effect |
|---|---|---|
| `-profile <url>` | Repeatable. | Validates as if listed in `meta.profile`. If not already loaded, it is fetched with `loadProfile`. A value that still cannot be resolved stops the run. |
| `-profiles <a,b>` | Hidden, comma-delimited. | Convenience for several profiles. |
| `-bundle <rule> <profile>` | Takes **two** values, repeatable. `rule` is `Type`, `index`, or `Type:index`. | Applies a profile to matching Bundle entries. `Type:index` counts within that type from 0; a bare index counts all entries from 0. Unknown profiles and malformed rules abort rather than produce issues. |
| `-extension <domain>` | Repeatable. `any` allows all. | Domains allowed for unknown extensions. Otherwise an unknown extension is an error. |
| `-matchetype <file>` | Repeatable. File must exist. | Compares against a matchetype; discards non-fatal validation messages first. |
| `-cert <file\|dir>` | Repeatable. File or folder must exist. JWKS, PEM or DER. | Public keys for signature validation, when the certificate is not in the signature. Folders from `fhir-settings.json` are searched too. Supports `application/json` (JWS) and `application/pkcs7-signature` signatures. Canonicalisation goes in `Signature.targetFormat`. |
| `-forPublication` | Flag, off. | Checks conformance with the Shareable* profiles: SHALL for HL7 resources, SHOULD otherwise. |
| `-no-experimental-content` | Flag, off. | Errors on `experimental: true` in canonical resources. |
| `-jurisdiction <j>` | Country or region code, `uv` or `global` (the whole world, meaning no specific jurisdiction), or a full `urn:iso:std:iso:3166...` or m49 value. Unknown values throw. **Default comes from the system locale**, or from `-locale`. | Used by profiles with country-specific bindings. Set it for reproducible results across machines. |
| `-usage <code=value>` | 7.0.3+. Repeatable. `system#code=Coding:sys#code`, `Quantity:n:sys#code`, `Reference:url`. No prefix means Coding. | Use contexts in force. Scoped bindings and constraints only apply when set. Malformed values fail at parse time. |
| `-check-ips-codes` | **7.0.3 only**, removed on master. Set by the `-ips` presets at 7.0.3. | Reports SNOMED codes outside the IPS free set, as hints. |

## Message control

| Option | Values and default | Effect |
|---|---|---|
| `-level <l>` | `hints` (`h`, `i`, `info`), `warnings` (`w`, `warning`), `errors` (`e`, `error`). Unknown values silently mean hints. Default hints. | Filters reported messages after validation. Does not change exit-code logic, since errors are always kept. |
| `-best-practice <l>` | `ignore`, `hint`, `warning`, `error`, or the first letter. Default warning. Invalid values throw. | Severity for best-practice rules. |
| `-hintAboutNonMustSupport` | Flag, off. | Hints about data outside mustSupport. Only runs when profiles are in play. |
| `-html-in-markdown <l>` | `ignore`, `warning` (default), `error`. | Embedded HTML in markdown. |
| `-security-checks` | Flag, off. | Flags HTML-like tags in strings. Does not replace output sanitising. |
| `-no_unicode_bidi_control_chars` | Flag, off. | By default an unterminated control character is a warning (see CVE-2021-42574). This makes any BiDi control character an error. |
| `-no-invariants` | Flag, off. | Skips all invariants. Upstream's only stated reason is performance. Otherwise use it for diagnosis only. |
| `-want-invariants-in-messages` | Flag, off. | Includes invariant FHIRPath in messages. |
| `-advisor-file <file>` | Must exist with extension `.json` or `.txt`. | Suppresses messages. See the formats below. |
| `-verbose` | Flag, off. | Adds crumb trails and message ids, including which profiles ran, why, and slice-matching reasons. Low signal, as it can't know which slices you expected to match. |
| `-show-message-ids` | Rewritten to `-verbose` before parsing. | Same as `-verbose`. |
| `-resource-id-rule <r>` | `optional` (default), `required`, `prohibited`. Case-insensitive. | Rule for the outermost resource only. Contained resources always need ids. Bundle entries and parameters are always optional. |

Advisor formats (`RulesDrivenPolicyAdvisor`):

```text
# text file: comment lines start with #, rules start with -
- Type_Specific_Checks_DT_String_Length
- Terminology_TX_NoValid_*@Patient.identifier.*
- SOME_ID@^Patient\.name\[\d+\]
```

```json
{ "suppress": ["SOME_ID", "OTHER_ID@Patient.name.*", "REGEX_ID@^Patient\\.name"] }
```

Forms are `id`, or `id@path`, or `id@^regex`. A trailing `*` in the id is a prefix match, ids compare case-insensitively, `*` matches one path segment, and a trailing `*` also matches deeper paths. Get ids with `-verbose`. Suppressed issues are hidden, not fixed, and a note is logged that some may be hidden.

## Output

| Option | Values and default | Effect |
|---|---|---|
| `-output <file>` | Default stdout through the logger. | Writes the OperationOutcome. Extension `.json` gives JSON, anything else XML, when no style is set. |
| `-html-output <file>` | Off. | HTML summary of the results, with slice-matching detail when slicing ran. |
| `-output-style <s>` | `eslint-compact`, `csv` (for spreadsheets), `xml`, `json`, `compact`, `compact-split`. An unknown value logs a message and uses the default renderer. | Output format, with or without `-output`. `compact-split` writes one file per input, so `-output` must be an **existing directory**. |
| `-log <file>` | Off. | Log for map transforms. |

## Questionnaire and context

| Option | Values and default | Effect |
|---|---|---|
| `-questionnaire <m>` | `none` (`ignore`), `check`, `required` (`require`). Unknown values mean none. **Effective default `check`**, although help says none. | How QuestionnaireResponse relates to its Questionnaire. `required` needs a questionnaire present. The Questionnaire must be loaded with `-ig`. |
| `-launch-context <name:ref>` | 7.0.3+. Repeatable, each name once. Everything after the first colon is the reference. | Supplies SDC `launchContext` values such as `%patient`, by file or absolute URL. A variable declared by the questionnaire wins. Undeclared names are ignored without error, so one set can serve many questionnaires. |

## Limits

| Option | Values and default | Effect |
|---|---|---|
| `-max-validation-messages <n>` | `0` means unlimited. | Stops at the limit, and the last entry becomes a warning. |
| `-validation-timeout <ms>` | `0` means unlimited. Applies per resource. | Stops at the time limit and adds a warning. |

Both produce partial results and neither is an error, so they can exit 0. Upstream warns such issues "may not be complete or accurate", so treat a run that hit either limit as incomplete.

## FHIRPath legacy

| Option | Effect |
|---|---|
| `-implicit-fhirpath-string-conversions` | Restores pre-5.6.48 string conversions. |
| `-allow-double-quotes-in-fhirpath` | Accepts legacy double-quoted strings. |

## Watch mode

| Option | Values and default | Effect |
|---|---|---|
| `-watch-mode <m>` | `none` (default), `single`, `all`, or `n`, `s`, `a`. Invalid values throw. | Keeps running and revalidates changed files. `all` also revalidates the rest, which is useful for internal dependencies. Stop with Ctrl-C. |
| `-watch-scan-delay <ms>` | Default 1000. | How often files are checked. |
| `-watch-settle-time <ms>` | Default 100. | Wait after a change before revalidating. |

## Presets

Expanded before parsing, so they are textual replacements (`CLI.addAdditionalParamsForPreset`).

| Preset | Expands to |
|---|---|
| `-ips` | `-version 4.0 -ig hl7.fhir.uv.ips#2.0.1 -profile .../Bundle-uv-ips -extension any -bundle Composition:0 .../Composition-uv-ips`, plus `-check-ips-codes` at 7.0.3 |
| `-ips#<ver>` | As above, with that IPS version. |
| `-ips$<branch>` | As above, with `hl7.fhir.uv.ips#current$<branch>`. |
| `-ips:au` | `-version 4.0 -ig hl7.fhir.au.ps#current`, AU Bundle and Composition profiles, `-extension any`. |
| `-cda` | `-version 5.0 -ig hl7.cda.uv.core#2.0.3-sd` |
| `-ccda` | `-version 5.0 -ig hl7.cda.us.ccda#5.0.0` |
| `-view-definition` | `-version 5.0 -ig org.sql-on-fhir.ig#current` |

Do not also pass `-version` with a preset, as it would be repeated `[inferred]`.

## Inherited options

These work with any subcommand, in any position.

| Option | Effect |
|---|---|
| `-locale <bcp47>` | Sets the default locale. It picks the output language (German, Dutch and Spanish are the listed translations), the assumed language of resources with none, and the default jurisdiction. Without it the system locale applies. |
| `-fhir-settings <file>` | Settings file, which must exist. Resolution order: this option, the `fhir.settings.path` system property, then `~/.fhir/fhir-settings.json`. See below. |
| `-no-http-access` | Prohibits all HTTP(S). Everything must already be cached. |
| `-ssrf-protection-enabled <bool>` | 7.0.3+. Takes a value. On by default. `false` is for testing and trusted input only. Prefer per-server settings, as below. |
| `-proxy <host:port>` | HTTP proxy. |
| `-https-proxy <host:port>` | HTTPS proxy. |
| `-auth <user:pass>` | Basic proxy authentication. The password is visible in process lists. |
| `-debug-log <file>` | Debug logging to a file. |
| `-trace-log <file>` | Trace logging to a file. If both are set, the most verbose wins. |

`fhir-settings.json` fields relevant here:

```json
{
  "tempPath": "/var/tmp/fhir",
  "packageManagement": {
    "servers": [{ "url": "http://mypackageserver:4873", "serverType": "npm" }]
  },
  "servers": [
    {
      "url": "http://tx.internal:8080/fhir",
      "type": "fhir",
      "allowHttp": true,
      "allowPrivateNetwork": true
    }
  ]
}
```

| Field | Effect |
|---|---|
| `tempPath` | Replaces `[tmp]`, including the default terminology cache location. |
| `packageManagement.servers` | Extra package registries. |
| `servers[].allowHttp` | Permits plain HTTP to that server, without upgrading to HTTPS. |
| `servers[].allowPrivateNetwork` | Permits that server to resolve to a private, loopback or link-local address. Independent of `allowHttp`. |
| `servers[].authenticationType`, `username`, `token`, `apikey`, `headers` | Credentials, such as for a protected `-resolution-context` server. Keep this file out of version control. |

## Translation overrides

| Option | Effect |
|---|---|
| `-po <file>` | Repeatable. Loads translations at runtime. Filename follows `validator-messages-<locale>.po` or `rendering-phrases-<locale>.po`. |
| `-po-dir <dir>` | Loads all `.po` files under a folder. |
| `-po-stale-handling <m>` | `include` (default), `exclude`, `warn`. |

## Help and deprecated forms

`-h`, `-help` and `-?` show help (`/?` also maps to it). `help <subcommand>` shows a subcommand's help.

Old-style forms are rewritten with a warning: `-convert`, `-compile`, `-compare`, `-fhirpath`, `-install`, `-narrative`, `-snapshot`, `-scan`, `-special`, `-spreadsheet`, `-tests`, `-txTests`, `-transform`, `-aiTests`, `-factory`, `-server`, `-codegen`, `-ig-codegen`, `-preloadCache`, `-lang-transform`, `-lang-regen`, `-to-version` and `-re-package` (`-tx-pack` too).

The rewrite is in place, so an old command with sources before the keyword keeps that order. Move the subcommand first. `-package-name` without `re-package` silently prepends `codegen`.

## Version differences

Compared by extracting option names at each tag.

| Change | Options |
|---|---|
| Added between 6.9.12 and 7.0.3 (marked "7.0.3+" above) | `-codesystem-validation-size-limit`, `-launch-context`, `-ssrf-protection-enabled`, `-usage`; subcommand `ig-codegen` |
| Removed between 7.0.3 and master | `-check-ips-codes` |
| Added between 7.0.3 and master | `-ecosystem` |

The CLI has been picocli-based since at least 6.9.12. Very old guides using `org.hl7.fhir.validator.jar` describe an earlier parser, so verify any flag taken from them. The Confluence page also names flags that do not exist, listed in `docs-vs-source.md`.
