# Quickstart: Validating Multi-IG Validator Contexts

**Feature**: `012-multi-ig-validator-contexts` | Contract: [contracts/http-api.md](./contracts/http-api.md) | Model:
[data-model.md](./data-model.md)

## Prerequisites

- Docker running (Testcontainers PostgreSQL), Java 17, Maven.
- A local instance built from this branch: `mvn -q package -DskipTests`, then run
  `target/fhir-validation-service-1.2.0.jar` against a PostgreSQL database (or `docker compose up -d --wait` with
  `FV_TAG` set to a 1.2.0 image), with `FV_ADMIN_API_KEY` and `FV_VALIDATOR_API_KEY` set (see README).
- Test packages from `src/test/resources` (the `TestPackages` fixtures): two IGs `A` and `B` for R4 that each define a
  profile, plus `C` (conflicting dependency) and two IGs that need different `hl7.terminology.r4` versions.

## Automated checks

```bash
mvn -q verify -Dit.test='MultiIg*IT,IgReferenceTest,DependencyConflictTest,ContextAliasesTest,MigrationUpgradeIT'
mvn -q verify            # full suite: no regression in single-IG behaviour (SC-004)
```

## Manual scenarios

`$ADMIN` and `$KEY` are the admin and validate API keys; `H` is `-H "X-API-Key: …" -H 'Content-Type: application/json'`.

1. **Include two IGs** (US1) — `POST /R4/validator` with `{"igPackages":[{"igPackageId":"A","igPackageVersion":"1.0.0"},{"igPackageId":"B"}]}`.
   Expect 200, `key.igPackage = "A#1.0.0|B#<highest>"`, `aliases = ["B#…|A#1.0.0","A#1.0.0"]`.
2. **Same pair reversed** — include `B` then `A`. Expect 200 reporting the existing key; `/health` still lists one context.
3. **Validate with both** (US2) — `POST /R4/validate?ig=A%231.0.0%7CB%23<v>` with a resource claiming one profile of A and
   one of B. Expect 200, no `unknown-profile`, `X-Validation-Context: A#1.0.0|B#<v>`. Repeat with the reversed order:
   same header.
4. **Not included pair** — `?ig=A%231.0.0%7CC%231.0.0`. Expect 400 invalid-request "not included".
5. **First-IG alias** (US3) — `?ig=A%231.0.0` with no single `A#1.0.0` included: header shows the combined key. Include
   single `A#1.0.0`: same request now reports `A#1.0.0`. `?ig=A` never reports the combined key.
6. **Alias conflict** — include `A#1.0.0 + C#…`. Expect 409 `alias-conflict`.
7. **Dependency conflict** — include two IGs whose trees need `X#1.0.0` and `X#2.0.0`. Expect 400
   `dependency-conflict` with `conflicts[0].package = "X"`.
8. **Exempt base package** — include the two IGs needing different `hl7.terminology.r4` versions. Expect 200 and a
   warning naming the package, both versions and the higher one used.
9. **Manage** (US4) — `PATCH /R4/validator?ig=B%23<v>%7CA%231.0.0` with `{"shareable":false}` → applies to the key.
   `DELETE` with the reversed pair → context and aliases gone; `?ig=A%231.0.0%7CB%23<v>` now 400.
10. **Multi-instance** — run two instances on one database; include on one, validate on the other within the
    propagation window (SC-005).

## Expected results recorded in the PR

- k6 comparison against 1.1.0 for single-IG validation (hot path touched by `IgReference`): p95 within 5 %.
- Build time and heap for one combined context of two included IGs (`perf/results-1.2.0.md`).
