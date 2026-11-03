# Subcommands

Registered on `ValidateCommand`. Subcommands that extend `ValidationEngineCommand` also accept the **engine** options (`-version`, `-ig`, `-tx`, `-txCache` and so on, listed in `validate-options.md`), and they build a full engine first. Those marked "no engine" do not. All commands accept the inherited options.

Most of these return `null` for instance options, so flags such as `-profile` have no effect on them. `server` is the exception.

Contents: server · client · conversion and generation · transforms · language tools · packages · testing and maintenance.

## server

Runs the validator as a long-lived HTTP service with one engine. Extends the engine command.

```bash
java -jar validator_cli.jar server 8080 -version 4.0 -ig hl7.fhir.us.carin-bb#1.1.0
```

| Argument | Details |
|---|---|
| `<port>` | Required first positional, 1 to 65535. |
| `-allowNetworkAccess` | Binds all interfaces. Default is loopback only. There is no authentication, so securing it is on you. |
| `-txTests` | Enables `/txTest`, which writes to the temp directory. For build pipelines only. |
| Engine options | Fixed at startup. |
| Instance options | Accepted, but see below. |

Engine options apply for the server's lifetime. **`/validateResource` builds its instance parameters only from the request's query string**, so startup flags like `-profile`, `-level`, `-best-practice`, `-extension` and `-bundle` do not apply to requests `[src]`. Send them per request.

Endpoints (`FhirValidatorHttpService`):

| Path | Purpose |
|---|---|
| `/validateResource` | Validate a POST body |
| `/fhirpath`, `/fhirpath/check` | Evaluate or check FHIRPath |
| `/matchetype` | Compare with a matchetype |
| `/testdata` | Test data generation |
| `/loadIG` | Load an IG into the running engine |
| `/convert`, `/snapshot`, `/narrative`, `/transform`, `/version`, `/compile` | Tool operations |
| `/openapi.json`, `/docs`, `/redoc` | API description |
| `/txTest` | Terminology tests, with `-txTests` |
| `/stop` | POST shuts down the JVM |

`/stop` has no authentication. With `-allowNetworkAccess`, anyone who can reach the port can stop the server. Put a proxy or network policy in front of it.

`/validateResource` behaviour:

- Format comes from `Content-Type`: `xml` means XML, otherwise JSON.
- Response format comes from `Accept`: `xml` means XML, otherwise JSON.
- An unknown query parameter returns 400.
- A `profile` not loaded on the server returns 400 with `Unable to resolve profile`. Load it first through `/loadIG` or `-ig`.
- Validation issues return **200** with an OperationOutcome. Read severities, not the status code.
- An infrastructure failure returns 500.

Query parameters (`ParamNames`):

| CLI option | Query parameter |
|---|---|
| `-jurisdiction` | `jurisdiction` |
| `-expansion-parameters` | `expansionParameters` |
| `-html-output` | `htmlOutput` |
| `-output-style` | `outputStyle` |
| `-r5-bundle-relative-reference-policy` | `r5BundleRelativeReferencePolicy` |
| `-questionnaire` | `questionnaire` |
| `-level` | `level` |
| `-best-practice` | `bestPractice` |
| `-html-in-markdown` | `htmlInMarkdown` |
| `-check-display` | `checkDisplay` |
| `-resource-id-rule` | `resourceIdRule` |
| `-assumeValidRestReferences` | `assumeValidRestReferences` |
| `-hintAboutNonMustSupport` | `hintAboutNonMustSupport` |
| `-want-invariants-in-messages` | `wantInvariantsInMessages` |
| `-no-invariants` | `noInvariants` |
| `-unknown-codesystems-cause-errors` | `unknownCodeSystemsCauseErrors` |
| `-forPublication` | `forPublication` |
| `-no_unicode_bidi_control_chars` | `noUnicodeBidiControlChars` |
| `-verbose` | `verbose` |
| `-show-message-ids` | `showMessageIds` |
| `-allow-example-urls` | `allowExampleUrls` |
| `-showReferenceMessages` | `showReferenceMessages` |
| `-security-checks` | `securityChecks` |
| `-no-experimental-content` | `noExperimentalContent` |
| `-tx-routing` | `txRouting` |
| `-implicit-fhirpath-string-conversions` | `implicitFhirpathStringConversions` |
| `-allow-double-quotes-in-fhirpath` | `allowDoubleQuotesInFhirpath` |
| `-max-validation-messages` | `maxValidationMessages` |
| `-validation-timeout` | `validationTimeout` |
| `-codesystem-validation-size-limit` | `codeSystemValidationSizeLimit` |
| `-extension` | `extension` (repeat) |
| `-profile` | `profile` (repeat) |
| `-bundle <rule> <profile>` | `bundleValidationRule` and `bundleValidationProfile`, paired by position |

Boolean parameters take `true` or `false`. `-usage` and `-launch-context` have no query parameter.

```bash
curl -s -X POST 'http://localhost:8080/validateResource?profile=http%3A%2F%2Fhl7.org%2Ffhir%2Fus%2Fcore%2FStructureDefinition%2Fus-core-patient&level=warnings' \
  -H 'Content-Type: application/fhir+json' --data-binary @patient.json
```

## client

Thin HTTP client for a running `server`. It has **no engine** and accepts instance options only.

```bash
java -jar validator_cli.jar client -port 8080 patient.json
java -jar validator_cli.jar client -port 8080 -profile http://example.org/StructureDefinition/p patient.json
java -jar validator_cli.jar client -port 8080 -stop
```

| Argument | Default | Details |
|---|---|---|
| `<files...>` | none | Files to POST. With no files and no `-stop`, it exits 0 without doing anything. |
| `-hostname <h>` | `localhost` | Host. |
| `-port <n>` | `80` | Port. Remember to set it, as servers rarely use 80. |
| `-host <h[:port]>` | none | Host and port together; takes precedence over `-hostname` and `-port`. Scheme is forced to `http`. |
| `-stop` | off | POST to `/stop`. A 200 means shutdown was accepted, not finished. |
| Instance options | as validate | Sent as query parameters. |

Limitations `[src]`:

- `Content-Type` is always `application/fhir+json`, so **XML files are sent labelled as JSON**. Use `curl` with the right header for XML.
- Always HTTP, so TLS needs another client.
- It exits at the **first** file with errors, and later files are not sent.
- Connect timeout is 10 seconds.
- The in-source help mentions `client ... stop`. The real option is `-stop`, and a bare `stop` is treated as a file.
- `-output` is not a `client` option, so passing it is a parse error `[inferred]`.
- `-usage` and `-launch-context` are accepted but never sent. `-output-style` and `-html-output` are sent, but the client always renders the response with the default text renderer.
- `-show-message-ids` is rewritten to `-verbose` here too.

Exit codes: 0 clean, 1 on errors or connection failures.

## Conversion and generation

| Command | Usage | Arguments and rules |
|---|---|---|
| `convert` | `convert <sources...> -version <v> -output <f>` | Exactly one of `-output` (single source) or `-outputSuffix` (several). Format follows the output extension. |
| `to-version` | `to-version <target> <source> -version <from> -output <f>` | Cross-version conversion. One source. `-output` required by the service. Output extension `.json` gives JSON, else XML. By default, internal routines handle canonical resources and [interversion structure maps](https://github.com/FHIR/packages/tree/master/interversion) handle the rest, with maps as the fallback. `-do-native` tries internal routines first for everything; `-no-native` never tries them. |
| `snapshot` | `snapshot <sources...> -version <v> -output <f>` | Exactly one of `-output` or `-outputSuffix`. |
| `narrative` | `narrative <source> -version <v> -output <f>` | Generates narrative. `-ig` supplies profiles. |
| `spreadsheet` | `spreadsheet <sources...> -version <v> -output <f>` | Renders canonical resources as a spreadsheet. |
| `compare` | `compare -left <p> -right <p> -dest <dir> -ig <ig>` | Compares two profiles and their sub-profiles. `-dest` must exist, and contents may be overwritten. Start at `index.html`. Loads `hl7.fhir.pubpack`. `-version` treats both as that version, which lets you compare across versions. The union is what a consumer of both may read. The intersection is what a producer for both may write, and an empty one means no instance can satisfy both. |
| `fhirpath` | `fhirpath "<expr>" <source> -version <v>` | Evaluates against one source. |

Older-style `-outputSuffix` writes `<source>.<suffix>`.

## Transforms

| Command | Usage | Arguments |
|---|---|---|
| `transform` | `transform <map> <source> -output <f> -ig <maps>` | Map as first positional or `-map`, not both. One source. Requires a terminology server, so not `-tx n/a`. `-log` writes a map log. |
| `compile` | `compile <map> -output <f>` | Map as positional or `-map`. No sources allowed. `-output` required. |

In `transform`, `compile` and `to-version`, some failures are logged as `...Failure` by the service without rethrowing, so **a failure may exit 0** `[src]`. Check that the output file exists.

## Language tools

| Command | Usage |
|---|---|
| `lang-transform extract` | `lang-transform extract <sources> -src-lang en -tgt-lang de -output <f>` |
| `lang-transform inject` | `lang-transform inject <sources> -input <translations> -output <f>` |
| `lang-regen` | `lang-regen <core-repo> <ig-publisher-repo> <fhirsmith-repo>`. Maintainer tool, no engine. |

## Packages

| Command | Usage | Arguments |
|---|---|---|
| `install` | `install -ig <pkg>` | Installs into the package cache. Sources are not used. |
| `re-package` | `re-package <pkg[,pkg]> -output <f>` | See below. |
| `scan` | `scan <sources> -output <dir> -version <v> -ig <ig>` | Hidden. Validates against every loaded IG and writes a `scan.html` matrix. `-output` must exist. |
| `preloadCache` | `preloadCache` | Hidden, no engine. Downloads **all** registry packages. Very large. |
| `codegen` | `codegen -package-name <p> -output <dir>` | Java from profiles. `-option` and `-options` take `narrative`, `meta`, `contained`, `all-elements`. |
| `ig-codegen` | `ig-codegen <pkg...> -package-name <p> -output <dir> -config <dir>` | 7.0.3+, no engine. `-fhir-version r5` (default) or `r6`. `-test-package-name` and `-test-output` go together. |
| `factory` | `factory <control-file> -version 4.0 -ig <ig>` | Generates test instances from a [test factory](https://build.fhir.org/ig/FHIR/ig-guidance/testfactory.html) control file. Runs with the current folder set to the factory's folder, which affects liquid and data paths. |

`re-package` options:

| Option | Details |
|---|---|
| `-output <f>` | Required. `.zip`, `.tgz` or folder. Files are unique per resource type, id and version, so ids can repeat across versions. |
| `-outputSuffix <s>` | Suffix for generated files. |
| `-mode <m>` | Repeatable: `tx`, `cnt`, `api`, `expansions`. Default `tx`, `cnt`, `api`. |
| `-scope <s>` | `ig`, `igs` or `core`. |
| `-pin` | Pin version-less references, which suits uploading to a RESTful server. |
| `-expansion-parameters <f>` | Fixed code system and value set versions. Engine and instance option as in validate. |
| `-expand` | Expand ValueSets. |
| `-format <f>` | `json` (default) or `xml`. |
| `-package-name <id>` | Id for `package.json`. |
| `-ignore-list <urls>` | Canonicals to exclude. |
| `-include-list <urls>` | Canonicals to include. |
| `-include-conforms-to <bool>` | Include `conformsTo` targets from invariants. |

## Testing and maintenance

| Command | Usage | Arguments |
|---|---|---|
| `tests` | `java -Djava.locale.providers=COMPAT -jar validator_cli.jar tests <fhir-test-cases-dir>` | The `COMPAT` property is required for consistent date formats. `-test-modules`, `-test-classname-filter`, `-txCache`. No engine. |
| `txTests` | `txTests -tx <url>` | Terminology ecosystem tests: `-output`, `-test-version` (not a FHIR version), `-filter`, `-suite`, `-externals`, `-input`, `-mode`, `-tester`. No engine. |
| `aiTests` | `aiTests -source <file>` | `-config`, `-run-tests`. No engine. |
| `special` | `special <mode>` | For example `r4r5tests`, with `-target`, `-source`, `-filter`. No engine. |
| `help` | `help <command>` | Subcommand help. |
