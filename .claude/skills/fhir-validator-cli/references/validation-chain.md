# Validation Chain Internals

How one `java -jar validator_cli.jar ...` run becomes an OperationOutcome. Traced from source at master `26ac536`, and the version and engine paths are identical at 7.0.3 unless noted. Not confirmed by running the jar.

Contents: pipeline overview · 1 argument rewriting · 2 settings · 3 FHIR version · 4 engine build · 5 IG loading · 6 sources · 7 per-resource validation · 8 profile order · 9 element walk · 10 terminology · 11 references · 12 Bundles · 13 result and exit code · how flags enter the chain.

## Pipeline overview

```text
args
 └─ CLI.replaceDeprecatedArgs      old flags → subcommands, presets expanded
     └─ picocli parse              failure → exit 1
         └─ coordinateFhirSettings fhir-settings, then -no-http-access / SSRF
             └─ ValidationEngineCommand.call
                 ├─ determine FHIR version
                 ├─ ValidationService.buildValidationEngine
                 │    core package → tx cache → tx server → hl7.terminology
                 │    → extensions → -ig list → fetcher + policy → snapshots
                 └─ ValidateCommand.call
                      ├─ load -expansion-parameters, fetch -profile
                      └─ validateSources → ValidationEngine.validate
                           ├─ parseSources
                           ├─ round 1: load sources as potential dependencies
                           └─ round 2: for each source
                                ├─ -native (stub)
                                ├─ InstanceValidator.validate
                                ├─ -level filter
                                ├─ -matchetype comparison
                                └─ messagesToOutcome
                      render → count errors → exit code
```

## 1. Argument rewriting

`CLI.parseArgsAndExecuteCommand` runs before picocli sees anything:

1. Exact-match replacement of deprecated arguments.
2. `insertCodeGenIfNecessary`: `-package-name` with no `re-package`, `codegen` or `ig-codegen` prepends `codegen`.
3. Preset expansion (`-ips`, `-cda` and others).

An `IllegalArgumentException`, including a wrapped picocli failure, gives exit 1.

## 2. Settings

Settings load first, then command-line overrides. Web access comes from settings, then `-no-http-access`, then `-ssrf-protection-enabled`.

Web access has two independent checks per request (`ManagedHTTPClient`):

| Check | Rule | Exemption |
|---|---|---|
| Scheme | HTTPS only. `makeSecureRef` also upgrades `http://` to `https://`. | The URL is left unchanged for `localhost`, `local.fhir.org`, `127.0.0.1`, `[::1]`, `*.localhost`, and `allowHttp: true` servers. A configured server's own setting decides whether its scheme passes. |
| Address | Rejects non-public addresses: private ranges, loopback, link-local, cloud metadata. Applies at each redirect, and to literal IPs. | `allowPrivateNetwork: true` on that server, or `-ssrf-protection-enabled false`. |

So keeping a local URL as `http://` is not enough. An internal or local terminology server needs a `servers` entry, usually with both flags.

## 3. FHIR version

`ValidationEngineCommand.getValidationEngine`:

```text
ValidationEngineOptionsConvertor.convert()
  -version set              → statedFHIRVersion = that
  -ig is a core package     → removed from igs; statedFHIRVersion = implied
                              conflict → IllegalArgumentException

if -version was NOT given:
  fhirVersion = determineVersion(igs, sources, recursive, infer=true)
statedFHIRVersion = fhirVersion      ← overwrites the value from a core -ig
```

`inferFhirVersion` defaults to `true` on `ValidationEngineParameters`. The command's `inferFhirVersion()` returning `false` only means it does not force it.

`determineVersion`, when inferring:

| Evidence found | Result |
|---|---|
| Nothing | `5.0.0` (R5) |
| One version | That version |
| Several versions | `IllegalArgumentException`: specify `-version` |

Evidence comes from `scanForVersions`:

- Each remaining `-ig`: `version.info` or the package's `fhirVersion`.
- Each source: only the **first 2048 bytes**, looking for the text `fhirVersion` with a semver value, then for `http://hl7.org/fhir/3.0`, `1.0`, `4.0` or `1.4`.

Consequences:

| Command | FHIR version |
|---|---|
| `patient.json` (plain) | R5 |
| `patient.json -ig hl7.fhir.us.core#6.1.0` | R4 from the package |
| `patient.json -ig hl7.fhir.r4.core#4.0.1` | R5: the core package left the IG list and the value is overwritten |
| `structdef.json` with `fhirVersion` near the top | That version |
| R4 IG plus R5 IG, no `-version` | Error |

The third row looks like a bug upstream rather than a design choice, and it needs a runtime check (`scripts/verify-behaviors.sh`).

## 4. Engine build

`ValidationService.buildValidationEngine`, in order:

1. Load the core package. For `dev` that is `hl7.fhir.r5.core#current`, otherwise `packageForVersion#currentVersion`.
2. Set up the terminology cache: `-txCache`, else the shared per-release folder under `[tmp]/default-tx-cache` (`[tmp]` follows `tempPath` in settings). `-clear-tx-cache` clears it here.
3. Connect the terminology server. A null URL (`-tx n/a`) means no server at all.
4. Load IGs (section 5).
5. Apply engine settings: native, certs, matchetypes, language, locale, SNOMED edition, binding and display flags, AI service.
6. Install the fetcher and policy advisor:

| Configuration | Fetcher | Reference policy |
|---|---|---|
| Default | `StandAloneValidatorFetcher` | `IGNORE` |
| `-check-references` | `StandAloneValidatorFetcher` | `CHECK_VALID` |
| `-disable-default-resource-fetcher` | none; `DisabledValidationPolicyAdvisor` | from that advisor |

`-advisor-file` wraps the current advisor with a rules advisor. Otherwise a `BasePolicyAdvisorForFullValidation` is installed. That base advisor also supplies implied profiles for Observations in R4 and later (section 8).

7. `prepare()` generates missing snapshots. Failures are logged as a process note, and show up later if that profile is used.

After the engine is built, `ValidateCommand` loads `-expansion-parameters`, then fetches each `-profile` not already known. `loadProfile` accepts a file or URL.

## 5. IG loading

`loadIgsAndExtensions` loads, in order:

1. `hl7.terminology`.
2. `hl7.fhir.uv.extensions`, unless the context is R5.
3. Each `-ig`, in command order.

`IgLoader.loadIg`:

- Optional `[version]` prefix.
- If it looks like a package reference and no such file exists, the package cache manager loads it. A `.tgz` file is tried as a package too.
- For packages, non-core dependencies load first and recursively, then the package with a version-appropriate loader. A package from another FHIR version is converted on load, so mixing versions may work but is not equivalent to native validation.
- For folders and other sources, `loadIgSource` is used. Inside a folder, `package.tgz`, `igpack.zip` or `validator.pack` wins over scanning files.
- ImplementationGuide resources found in that **non-package** branch are recorded, which later enables global profiles (section 8). The package branch does not record them, so a package IG's global profiles are not applied `[src]`.
- The `[version]` prefix only affects the non-package branch.

Package source order for `-ig` with no version: the latest in the cache, else the latest online.

## 6. Sources

`ValidationEngine.validate`:

- `parseSources` resolves files, folders, wildcards and URLs. `asBundle` is true when there is more than one source argument or more than one resolved file.
- If no resolved file needs processing, it returns `null`: no output and no error count.
- **Round 1** loads every source and offers it to the loader, ignoring failures. So in `validate a-profile.json b-instance.json`, conformance resources in the batch are available to the others.
- **Round 2** validates each file.

## 7. Per-resource validation

`ValidationEngine.validate(location, ...)`:

1. `-native`: `SchemaValidator.validateSchema`, which is a stub that only adds a "not done yet" information message.
2. `getValidator` creates a fresh `InstanceValidator` and copies instance options with `initializeFromParameters`.
3. `asSdList(profiles)`: an unknown profile throws `Unable to resolve profile`.
4. `InstanceValidator.validate`, stream form:
   - A logical-model profile sets the parser's logical model; more than one is an error.
   - The parser runs with `ValidationPolicy.EVERYTHING`, so syntax problems become messages with line and column.
   - Implied profile from the parser, if any, is added.
   - Each parsed fragment is validated. Multi-entry formats such as NDJSON produce several.
5. `removeMessagesBelowLevel` for `-level`.
6. `-matchetype`: on the first one, **all non-fatal messages are removed**, then the comparison adds its own.
7. `messagesToOutcome`: duplicates removed, "All OK" added if empty.

`InstanceValidator.validate`, element form, is the main entry point:

- No `-profile`: `validateResource` against the base definition.
- With `-profile`: profiles are expanded with imposed profiles, then `validateResource` runs once **per profile**.
- Then `-hintAboutNonMustSupport` usage checks, `codingObserver.finish`, removal of retracted messages, and AI checks.
- `BoundsExceededException` and `TimeoutException` are caught here and become warnings.

## 8. Profile order

`validateResource` handles ids (`-resource-id-rule`, length, characters), checks the matchetype extension, and checks the type matches the definition. If the resource is a Bundle and the profile is for another type, validation moves to the **first entry** if it matches. Finally it calls `start`, then checks contained resources are referenced.

`start` runs, for one definition:

| Step | Source | Failure if not found |
|---|---|---|
| 1 | Base or `-profile` definition (`startInner`) | n/a |
| 2 | Imposed profiles of that definition | warning |
| 3 | Each `meta.profile` | warning (`VALIDATION_VAL_PROFILE_UNKNOWN*`) |
| 4 | Imposed profiles of those | warning |
| 5 | Global profiles of IGs recorded in section 5, for this resource type | warning |

So an unresolvable `meta.profile` is a **warning, not an error**, and the resource still gets a clean exit. Use `-profile` when conformance to it must be enforced, because an unknown `-profile` stops the run.

For an unknown `meta.profile`, the validator asks the fetcher whether it can fetch it, and may try the canonical URL. Results are cached per run.

`startInner` caches outcomes per resource and definition, so repeated checks do not duplicate work. Specials run only once, on the base pass (`checkSpecials`):

- Policy advisor implied profiles, such as vital signs for Observation.
- Canonical resource rules (status consistency, `-no-experimental-content`, HL7 publisher).
- A type-specific validator: Bundle, Observation, Questionnaire, QuestionnaireResponse, Measure, MeasureReport, CapabilityStatement, CodeSystem, ConceptMap, OperationDefinition, SearchParameter, StructureDefinition, StructureMap, ValueSet, ViewDefinition, Parameters, ImplementationGuide.

## 9. Element walk

`validateElement`, recursively:

1. Duplicate `id` check.
2. Fixed and pattern values.
3. Child definitions, including slices and choice types.
4. `assignChildren`: maps instance children to definitions and evaluates slicing (`matchSlice`).
5. `checkCardinalities`.
6. `checkChild` for each child, dispatching by type: primitive, identifier, Coding, Quantity, CodeableConcept, Reference, Extension, contained Resource, then recursion.
7. `checkInvariants`, unless `-no-invariants`.
8. Content reference profiles, and CDA template checks where relevant.

Extensions (`checkExtension`): a known definition checks context, modifier flag and value type. An unknown URL is allowed if `-extension` matches or is `any`, or for built-in exceptions, or for example domains with `-allow-example-urls`. Otherwise it is an **error**. An allowed unknown extension gets a hint.

## 10. Terminology

For a coded element with a binding, the validator resolves the value set, then checks the code through the worker context, which can draw on loaded content, the terminology cache and the server. `-tx-routing` shows which server answered.

| Mode | Behaviour |
|---|---|
| Default | Ecosystem routing through `ecosystem.hl7.org/tx-reg`, with `tx.fhir.org` as the default server. |
| `-tx <url>` | That server only; the ecosystem is off. |
| `-tx <url> -ecosystem <url>` | Master only: ecosystem on, with your server replacing `tx.fhir.org`. |
| `-tx n/a` | No server and no ecosystem. |

Failure handling:

- A server failure during validation becomes a **warning** on that element. An unreachable server at startup throws, unless running without terminology is allowed.
- `-tx n/a` means external codes are not validated, and slicing that depends on terminology may fail too.
- A server that does not meet the tooling's conformance checks is refused unless `-authorise-non-conformant-tx-servers` is set.
- A wrong display is an error unless `-display-issues-are-warnings` is set.
- Above `-codesystem-validation-size-limit`, codes are not checked and a hint is issued.

Unknown code system severity (`InstanceValidator`):

| Binding | Severity |
|---|---|
| Required, HL7 code system | Error |
| Required, other, with `-unknown-codesystems-cause-errors` | Error |
| Required, other | Warning |
| Extensible, or no binding | Warning |

Results are cached, so a run may pass offline only because of an earlier online run. Use `-clear-tx-cache` or a dedicated `-txCache` for reproducible results.

## 11. References

`checkReference`:

1. Empty reference: needs an identifier or display, otherwise a warning.
2. `localResolve` looks in contained resources and the enclosing Bundle.
3. Policy: contained and internal targets are always `CHECK_VALID`. External ones ask the advisor.
4. If the policy checks existence, the fetcher is called:

| Target | Resolution |
|---|---|
| Absolute URL | HTTP GET as JSON, then XML. Subject to SSRF rules. |
| Relative, http `-resolution-context` | Joined to that base, then GET. |
| Relative, `file:` context | Tries `Type/id`, with `.json` and `.xml`, then `Type-id.json` or `.xml`, then scans the folder. |
| Relative, no context | Throws; becomes a hint and then a `REFERENCE_REF_CANTRESOLVE` error. |

5. `-allow-example-urls` accepts `example.org` and `acme.com`.
6. Target type is checked against the allowed types.
7. For `CHECK_VALID`, the target is validated against target profiles.
8. Conditional references are only valid inside a transaction.

With default policy `IGNORE`, step 4 is skipped and the target's existence is never checked. Declared type checks (`Reference.type`) still apply.

## 12. Bundles

`BundleValidator`, which also drives `-bundle`:

- Checks Bundle type rules, `fullUrl` and id consistency.
- References are resolved across entries before walking (`resolveBundleReferences`).
- For each entry with a resource, the rule counter by type is updated (from 0), then each `-bundle` rule that matches runs that profile with `startInner`.
- Specials for the entry run there too.
- Signatures are validated with `-cert` keys.

Rule matching:

| Rule | Meaning |
|---|---|
| `Composition:0` | First Composition |
| `Patient` | Every Patient |
| `3` | Fourth entry of any type |

## 13. Result and exit code

`validateSources` renders output, then counts `error` and `fatal` issues:

| Result | Exit |
|---|---|
| Any error or fatal issue | 1 |
| Warnings or hints only | 0 |
| No sources resolved to process | 0, no output |
| Parse failure, engine load failure, infrastructure exception | 1 |
| Message or time limit reached, no errors | 0 |

The terminology cache is saved in a `finally` block, so failed runs keep what they learned.

## How flags enter the chain

| Layer | Set when | Examples | Scope |
|---|---|---|---|
| Web and settings | Before engine build | `-fhir-settings`, `-no-http-access`, proxies | Process |
| Engine (`ValidationEngineParameters`) | Once, at build | `-version`, `-ig`, `-tx`, `-txCache`, `-sct`, `-check-references`, `-advisor-file`, `-native`, `-language` | Whole run or server lifetime |
| Instance (`InstanceValidatorParameters`) | Per validation | `-profile`, `-bundle`, `-level`, `-best-practice`, `-extension`, `-questionnaire`, `-jurisdiction` | One run, or one HTTP request |
| Post-processing | After validation | `-level` filter, `-output`, `-output-style` | Output |

This table explains `server` behaviour. Engine options are fixed at startup, and instance options come from each request.
