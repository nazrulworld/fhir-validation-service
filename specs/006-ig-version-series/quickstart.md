# Quickstart: Validate IG Version Series

**Feature**: `006-ig-version-series` | Contract: [contracts/http-api.md](./contracts/http-api.md) | Model:
[data-model.md](./data-model.md)

## Prerequisites

- Docker, for Testcontainers PostgreSQL.
- JDK and Maven, as for the rest of the project.
- For the manual run: the service running at `$BASE` (for example `http://localhost:$FV_HTTP_PORT`), an
  administrator API key in `$KEY` (sent as `Authorization: Bearer`), and
  `FV_PACKAGE_REGISTRY_ENABLED=true` with a reachable registry (default `FV_PACKAGE_SOURCES`).

## 1. Automated checks

```bash
# Pure unit tests: grammar, matching, ordering, ?ig= parsing
mvn -q test -Dtest='VersionSeriesTest,IgReferenceTest,PackageVersionComparatorTest'

# Integration (real PostgreSQL, stubbed registry). *IT classes run under surefire too.
mvn -q test -Dtest='RegisterSeriesIT,IncludeSeriesIT,IncludeOwnershipIT,RegisterLatestIT,IncludeExcludeIT'

# Contract tests
mvn -q test -Dtest='IgPackageApiContractTest,ValidationApiContractTest'
```

Expected: all green. `RegisterLatestIT` and `IncludeExcludeIT` pass unchanged (FR-016, SC-006).

## 2. Manual scenarios

| # | Request | Expected |
|---|---------|----------|
| 1 | `POST /igs/register` `{"name":"hl7.fhir.us.core","version":"6.1.x"}` | 200. `version` = highest `6.1.*` in the registry, `requestedVersion` = `6.1.x`, `downloaded` true on first call. |
| 2 | Repeat 1 | 200, the same `version`, `downloaded` false. |
| 3 | `POST /igs/register` `{"name":"hl7.fhir.us.core","version":"6.x"}` | 200, highest `6.*.*` release. |
| 4 | `POST /R4/include-ig` `{"igPackageId":"hl7.fhir.us.core","igPackageVersion":"6.1.x"}` | 200. `key.igPackage` = highest stored `6.1.*`, `requestedVersion` = `6.1.x`. |
| 5 | `POST /R4/include-ig` `{"igPackageId":"hl7.fhir.us.core","igPackageVersion":"99.x"}` | 404 `package-not-found`, naming `hl7.fhir.us.core#99.x`. No registry traffic in the logs. |
| 6 | `POST /igs/register` `{"name":"hl7.fhir.us.core","version":"1.x.3"}` | 400 `invalid-request`, explaining the series form. |
| 7 | `POST /R4/validate?ig=hl7.fhir.us.core%236.1.x` with any resource | 400 `invalid-request`: "needs an exact version". |
| 8 | Restart with `FV_PACKAGE_REGISTRY_ENABLED=false`, repeat 1 | 200 with the stored version and the warning "No package registry could be consulted; using the highest stored version in series 6.1.x". |

Example:

```bash
curl -s -X POST "$BASE/igs/register" -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
  -d '{"name":"hl7.fhir.us.core","version":"6.1.x"}' | jq '{version, requestedVersion, downloaded, warnings}'
```

## 3. Multi-organisation check (SC-007)

Use two test packages, `p` and `q` (for example from `TestPackages`):

1. As organisation B, upload a private `p#1.2.9` and a private `q#1.2.9` (`shareable=false`).
2. As the system organisation, upload a shareable `p#1.2.4`.
3. As organisation A, include `p` with `1.2.x`. Expected: `p#1.2.4`, and `1.2.9` appears nowhere in the response.
4. As organisation A, include `q` with `1.2.x`. Expected: 404 `package-not-found` naming `q#1.2.x`, with no mention
   of `1.2.9` or organisation B.
