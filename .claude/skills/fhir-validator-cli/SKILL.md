---
name: fhir-validator-cli
description: Expert reference for the official HL7 FHIR Validator (validator_cli.jar, org.hl7.fhir.core), covering every argument, every subcommand including `server` and `client`, and how the validation chain behaves internally for different argument combinations. Use this skill whenever the user mentions the FHIR validator, validator_cli, `java -jar validator_cli.jar`, validator flags such as -ig, -profile, -tx, -version, -bundle, -level, -check-references or -advisor-file, an OperationOutcome produced by the validator, unexpected validation errors or passes, IG/package loading, terminology server routing, or building a CI validation step, even if they do not say "CLI". Also use it to compare the CLI with HAPI FhirInstanceValidator / ValidationSupportChain behaviour in this service.
---

# FHIR Validator CLI

Baseline: **7.0.3** (latest release, 2026-10-21), compared against `master` at `26ac536` (7.0.4-SNAPSHOT, 2026-10-31). This repo's HAPI stack ships core **6.9.12**. The validator is tested on Java 17, 21 and 25.

The validator releases about weekly and options change between releases, so check the running version before relying on a version-sensitive detail. See the differences table in `references/validate-options.md`.

## Evidence levels

This skill combines the 7.0.3 source, upstream tests, and the Confluence "Using the FHIR Validator" page (export of 2026-11-01). Its behaviour claims have **not been confirmed by running the jar**.

The page is good for intent and guidance, but it is partly stale and lists flags the CLI no longer defines. Where they disagree, prefer the source for what a released jar does, and say that they disagree. All known cases are in `references/docs-vs-source.md`.

When a user's result contradicts this skill, trust the observed output, and run `scripts/verify-behaviors.sh` to find out which claim is wrong.

- `[src]`: read directly from the code path.
- `[inferred]`: reasoned from framework behaviour, such as picocli rules. Check before stating it as fact.

## How to work with this skill

1. Identify the mode:
   - Validating instances: the default command.
   - Long-running service: `server` and `client`.
   - Other tools: `convert`, `snapshot`, `fhirpath`, `transform` and so on.
2. Pin the inputs that decide the outcome: FHIR version, IGs and profiles, terminology mode, reference checking, and what counts as failure in CI.
3. Read only the reference you need:

| Need | Read |
|---|---|
| What a flag does, its values, default, or version availability | `references/validate-options.md` |
| A subcommand, including `server`, `client`, `convert`, `snapshot` | `references/commands.md` |
| Why a result happened, or the order of internal steps | `references/validation-chain.md` |
| A ready command for a situation, and what it triggers | `references/scenarios.md` |
| CLI versus this service's HAPI chain | `references/hapi-comparison.md` |
| A flag or statement from the Confluence page that does not behave as written | `references/docs-vs-source.md` |

4. When giving a command, state which version of FHIR it validates against and whether it needs network access. Those two facts cause most surprises.

## Command shape

```bash
java -jar validator_cli.jar [subcommand] <sources...> [options]
```

- With no subcommand, the tool validates.
- Options use a single dash (`-ig`, not `--ig`). Parsing is picocli, and an unknown option fails with exit code 1 `[src]`.
- Put the subcommand **first**. Only inherited options apply when written before it: `-locale`, `-fhir-settings`, `-debug-log`, `-trace-log`, `-proxy`, `-https-proxy`, `-auth`, `-no-http-access`, `-ssrf-protection-enabled`, `-authorise-non-conformant-tx-servers`. Anything else, including `-version` and `-ig`, binds to the parent command and does not reach the subcommand `[inferred]`.
- Repeatable options: `-ig`, `-profile`, `-extension`, `-bundle`, `-cert`, `-matchetype`, `-usage`, `-launch-context`, `-po`. Repeating a single-value option such as `-version` is a parse error `[inferred]`.

A safe default for a reproducible run:

```bash
java -jar validator_cli.jar patient.json \
  -version 4.0.1 \
  -ig hl7.fhir.us.core#6.1.0 \
  -profile http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient \
  -output result.json
```

## Traps to check first

These cause most wrong conclusions. Details and code paths are in `references/validation-chain.md`.

| Trap | What actually happens |
|---|---|
| `-version` omitted | The tool scans `-ig` packages and the first 2 KB of each source. A plain R4 instance has no version marker, so it falls through to **R5** `[src]`. |
| `-ig hl7.fhir.r4.core#4.0.1` without `-version` | Core packages are removed from the IG list, and the stated version is then overwritten by the scan. With a plain instance this gives R5, not R4 `[src]`. Always pass `-version`. |
| Help text defaults | `-questionnaire` is documented as default `none`, but the effective default is `check` `[src]`. |
| External references | Not checked by default. `Patient/does-not-exist` passes unless `-check-references` is set, and relative targets also need `-resolution-context` `[src]`. |
| `-tx n/a` | No terminology server: terminology answers are unavailable, so a "pass" says less about codes `[src]`. |
| Private or local `-tx` | SSRF protection blocks non-public addresses, including loopback, unless that server is in `fhir-settings.json` with `allowPrivateNetwork: true`. Plain HTTP also needs `allowHttp: true`, and other `http://` URLs are upgraded to `https://` `[src]`. |
| `-native` | A stub: it only adds "not done yet" messages, so it performs no schema validation `[src]`. |
| Flags from older guides | `-check-references-to`, `-txcache`, `-current`, `-lang`, `-coding-display` and `-security-only` are not defined `[src]`. |
| Results differ by machine | `-jurisdiction` defaults from the system locale, which can change jurisdiction-specific bindings. Set `-jurisdiction uv` for reproducible runs `[src]`. |
| Global profiles | Applied only from IGs loaded as files or folders, not packages. Name the profile with `-profile` `[src]`. |
| `-tx <server>` and routing | Naming a server turns the terminology ecosystem off, so every request goes to that server. To keep routing, master adds `-ecosystem`; 7.0.3 has no such option `[src]`. |
| Exit code | 1 only for `error` or `fatal` issues. Warnings exit 0. Timeouts and message limits are warnings, so a truncated run can exit 0 `[src]`. |
| Wildcard matching nothing | In an existing directory, nothing is validated and the run exits 0 `[src]`. Confirm the file count in CI. |
| `-check-display` | Stored but never read at 7.0.3, so it has no effect. Values are still case-sensitive, so `check` throws. Wrong displays are errors by default; use `-display-issues-are-warnings` to downgrade them `[src]`. |
| `-show-message-ids` and `-crumb-trails` | Both are rewritten to `-verbose`, which turns on both behaviours `[src]`. |
| Server started with `-profile`, `-level` and similar | `/validateResource` builds instance options from the request query only, so these startup flags are not applied to requests `[src]`. |
| Using `-matchetype` | Non-fatal validation messages are discarded before comparison `[src]`. |

## Reading results

The validator produces a process log and a set of issues. Check the log for package loading and terminology problems, because those can make issues misleading.

| Severity | Meaning |
|---|---|
| Error | The instance does not conform to the applicable rules. |
| Warning | The validator cannot determine validity, or the data carries a known risk. |
| Best practice | Advice from the specification authors, not a conformance failure. |
| Information | How validation ran, or something worth checking. |

A warning is not a pass. It can mean a definition was unavailable, an extensible binding needs human review, or a rule needs judgment. Review errors and warnings against the specifications too, as the validator can be wrong and some invalidity cannot be detected automatically.

- Default output is text, sent through the logger to stdout.
- `-output x.json` writes JSON and any other extension writes XML, unless `-output-style` is set `[src]`.
- More than one resolved source produces a `collection` Bundle of OperationOutcomes, one per file. This applies when a single folder or wildcard expands to several files too `[src]`.
- A clean run contains one `information` issue ("All OK").
- For CI, read severity from the OperationOutcome or use `-output-style compact` or `eslint-compact`, and do not grep log lines.

## Production and privacy

- `tx.fhir.org` is not offered for production and has no SLA. Production use needs your own terminology server, and HL7 advises a commercial support relationship.
- By default only the concepts being validated go to `tx.fhir.org`, which logs the concept and IP briefly. Inspect traffic with `-txLog`, or use your own `-tx` if that is a concern.
- `-check-references` and `-ai-service` send more data out. `CodeableConcept.text` can contain personal data.
- Upstream says to use the current validator for every FHIR release, and that older versions may stop working. New versions can report new errors, so a pinned CI jar needs a planned upgrade path, with `-advisor-file` for errors you cannot fix immediately.
