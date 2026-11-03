# CLI Versus This Service's HAPI Chain

This service does not call `validator_cli.jar` or `ValidationEngine`. It uses HAPI FHIR 8.12.1 with core 6.9.12: `FhirValidator`, `FhirInstanceValidator` and a `ValidationSupportChain` (`src/main/java/nzi/fhir/validator/core/validator/SnapshotBuilder.java`).

Both reach the same `InstanceValidator` logic in `org.hl7.fhir.core`, so structural and profile rules are broadly shared. They differ in how definitions, terminology and options are supplied, so identical input can give different results.

## This service's chain

Order in `SnapshotBuilder.assembleWith`, where the first support that answers wins:

1. `DefaultProfileValidationSupport`: core definitions for the version.
2. `CustomProfileValidationSupport`: profiles from `ProfileStore`.
3. `CustomNpmPackageValidationSupport`: IG packages.
4. `InMemoryTerminologyServerValidationSupport`: in-memory terminology.
5. `CommonCodeSystemsTerminologyService`: common code systems.
6. `SnapshotGeneratingValidationSupport`: generates missing snapshots.

Profiles requested on a call go in as validation options (`ValidatorSnapshot.validate`).

## Differences

| Concern | CLI | This service |
|---|---|---|
| FHIR version | `-version`, or scan then R5 | Chosen per validator through `SupportedFhirVersion` |
| Definitions | Package cache, `-ig`, fetching on demand | Chain above, from loaded packages |
| Terminology | Remote server and ecosystem by default | In-memory only: there is no remote terminology support in the chain |
| Large or external code systems (SNOMED CT, LOINC) | Checked by server | Cannot be fully checked in memory |
| References | `-check-references` and fetcher | Decided by HAPI defaults and the chain |
| Unknown `meta.profile` | Warning, with possible fetch | Decided by HAPI configuration |
| Options | CLI flags | Setters on `FhirInstanceValidator` |
| Lifecycle | New engine per run, or `server` | Snapshots reassembled after profile changes, as chains cache conformance data |
| Core version | Latest release | 6.9.12 |

## Using the comparison

- When the service and CLI disagree, check the terminology and core version first.
- To reproduce a service result in the CLI, pin the closest jar (6.9.12) and use `-tx n/a` as the nearest approximation. This is only approximate: in-memory validation still evaluates some code systems and value sets locally, while `-tx n/a` has no server.
- CLI flags do not exist on the HAPI path. Look for the matching setter before proposing one, and verify it against the HAPI version in `pom.xml`.
- HAPI's `ValidationSupportChain` and this skill's "validation chain" are different things. The first is a lookup chain for definitions and terminology. The second is the full CLI execution flow in `validation-chain.md`.
