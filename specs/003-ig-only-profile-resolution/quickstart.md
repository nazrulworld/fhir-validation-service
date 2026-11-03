# Quickstart: Validating IG-Only Profile Resolution

This guide proves that the feature works end to end. Contracts: [http-api.md](./contracts/http-api.md),
[export-script.md](./contracts/export-script.md). Data: [data-model.md](./data-model.md).

## Prerequisites

- Docker, for Testcontainers and the compose stack.
- JDK 17 and Maven.
- `curl` and `jq`.
- The fixtures `src/test/resources/fhir/hl7.fhir.dk.core-3.4.0.tgz` and the dk Patient resources under
  `src/test/resources/fhir/resources/dk/`.

## 1. Automated gate

```bash
mvn verify
```

The run must include these new or changed tests, and they must pass:

| Scenario (spec reference) | Expected |
|---|---|
| A single unresolved `?profile=` (US1, scenario 4) | 400 `unknown-profile`, `unresolvedProfiles=[U]` |
| `?profile=known,unknown` (US1, scenario 3; SC-001) | 400, lists only `unknown`, no validation result |
| `?profile=known` against a violating fixture (US1, scenario 1) | 200, `valid:false`, profile issue present, `appliedProfiles=[known]` |
| `?profile=P,Q` (US1, scenario 2) | 200, issues from both, `appliedProfiles=[P,Q]` |
| Duplicate, blank and whitespace entries (edge cases) | Treated as one clean list |
| 11 distinct profiles, or one URL of 1025 characters (FR-006a) | 400 `invalid-request`, URLs not echoed, nothing looked up |
| 10 distinct profiles, and `a,a,…` repeated 20 times (FR-006a boundary) | Accepted: the limit is applied after de-duplication |
| Unresolved profile with `Accept: application/fhir+xml` (FR-006) | 400 `application/problem+json`, not an OperationOutcome |
| `url|version`, matching and non-matching (edge case) | Resolves and 400 respectively |
| Bare id `us-core-patient`-style value (R3) | 400: not an exact canonical |
| Unknown `meta.profile` (US1, scenario 5) | 200, ERROR issue, URL not in `appliedProfiles` |
| A profile only in a stored but not included package (US2, scenario 2) | 400 |
| A canonical URL on a local HTTP server (US2, scenario 3; SC-002) | 400, **0 hits** on that server |
| An unparseable body plus an unknown profile | 400 `invalid-resource` (parse comes first) |
| A newly included IG's profile on a second instance (US2, scenario 4; SC-005) | Usable within the IG propagation window |
| `POST /R4/register-profile` (US3, scenario 1) | 404, no admin key needed |
| `openapi.yaml` (US3, scenario 2) | No `profileApiRegisterProfile`; RouterBuilder mounts with no missing handler |
| `/metrics` (US3, scenario 4) | No `fv_profile_count`; `unknown_profile` outcome present after the 400 above |
| V6 on top of a 1.2.0 schema (FR-013, FR-015) | `fhir_profiles` and `included_profiles` gone; IG, configuration and key counts unchanged |

## 2. Manual check against a running instance

```bash
docker compose up -d                     # 2.0.0 image built from this branch
AUTH="Authorization: Bearer $FV_ADMIN_API_KEY"
curl -H "$AUTH" -F "file=@src/test/resources/fhir/hl7.fhir.dk.core-3.4.0.tgz;type=application/gzip" \
  http://127.0.0.1:8880/igs/upload
curl -H "$AUTH" -H 'Content-Type: application/json' \
  --data '{"igPackageId":"hl7.fhir.dk.core","igPackageVersion":"3.4.0"}' http://127.0.0.1:8880/R4/include-ig

P=http://hl7.dk/fhir/core/StructureDefinition/dk-core-patient
F=src/test/resources/fhir/resources/dk/<a dk Patient fixture>.json

# Resolves from the included IG
curl -s -H 'Content-Type: application/fhir+json' --data @"$F" \
  "http://127.0.0.1:8880/R4/validate?profile=$P" | jq '{valid, appliedProfiles}'
# -> appliedProfiles == [P]

# One unknown among known profiles: no silent drop
curl -s -o /dev/null -w '%{http_code}\n' -H 'Content-Type: application/fhir+json' --data @"$F" \
  "http://127.0.0.1:8880/R4/validate?profile=$P,http://example.org/StructureDefinition/typo"
# -> 400 (body: type unknown-profile, unresolvedProfiles [".../typo"])

# The endpoint is gone
curl -s -o /dev/null -w '%{http_code}\n' -H "$AUTH" -X POST http://127.0.0.1:8880/R4/register-profile
# -> 404
```

## 3. Upgrade rehearsal (US4, SC-007)

```bash
scripts/upgrade-rehearsal-2.0.sh
```

Expected output, in order:
1. 1.2.0 is running, dk-core is included, the dk profile is registered, and baseline issues are recorded.
2. `export-custom-profiles.sh --check` reports `4.0.1 1` and `total 1`.
3. The export writes `local.custom.profiles.r4-1.0.0.tgz` and `MANIFEST.txt`.
4. A `pg_dump` backup is taken, and the 1.2.0 container is **stopped** before anything else starts
   (FR-015b, FR-015c).
5. 2.0.0 starts on the same database. V6 is applied, `fhir_profiles` is absent, and the IG and
   configuration counts are unchanged.
6. The exported package is uploaded and included, and the dk fixtures give the same error-level issues
   and `appliedProfiles` as in step 1.
7. Running `export-custom-profiles.sh` against the upgraded database exits **3** (too late to export).
8. Rollback: the backup is restored into a fresh database, 1.2.0 starts on it, and the dk profile is in
   effect again.
9. `scripts/rolling-restart.sh` refuses to move from a 1.x image to a 2.x image (FR-015c guard).
