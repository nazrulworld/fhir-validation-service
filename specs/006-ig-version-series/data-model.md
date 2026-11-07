# Data Model: IG Version Series

**Feature**: `006-ig-version-series` | **Date**: 2026-11-08

This feature adds no persistent data. There's no Flyway migration and no new table or column. The shared
configuration still stores concrete `name#version` keys only (FR-014). The entities below exist only for the
length of a request.

## 1. VersionSeries (value object, `core.npm`)

| Field | Type | Notes |
|-------|------|-------|
| `raw` | String | The value as received, for example `1.2.X`. Used in messages and `requestedVersion`. |
| `fixed` | `BigInteger[]` (length 1 or 2) | The numeric leading parts, for example `[1, 2]` for `1.2.x` and `[1]` for `1.x.x`. |

**Validation rules** (FR-001, FR-002; research R2):

- Detection: any dot-separated part equals `x`/`X` → it's a series. Otherwise it isn't (exact version or `latest`).
- Grammar: `N.x`, `N.x.x` or `N.N.x`, where `N` is one or more digits. At most three parts. Anything else that
  contains an `x` part → 400 `invalid-request`.
- The 1-64 character, no-whitespace label limit still applies. The grammar implies it.

**Behaviour**:

- `matches(v)`: parse `v` with the `PackageVersionComparator` SemVer parser. Missing parts count as `0` and build
  metadata is ignored. It's true when the parse succeeds and `core[i] == fixed[i]` for each fixed part. A
  pre-release such as `1.2.7-ballot` matches `1.2.x`. A non-SemVer label (`current`, `2020Sep`) never matches.
- `highest(candidates)`: `PackageVersionComparator.max(candidates.filter(matches))`. A release is preferred and a
  pre-release is used only when no release matches (FR-004). The result is deterministic (FR-005).

**Examples**

| Series | Candidates | Highest |
|--------|------------|---------|
| `1.2.x` | `1.2.0`, `1.2.5`, `1.20.1` | `1.2.5` |
| `1.x` / `1.x.x` | `1.2.3`, `1.3.0`, `2.0.0` | `1.3.0` |
| `1.2.x` | `1.2`, `1.1.9` | `1.2` |
| `1.2.x` | `1.2.0`, `1.2.1-ballot` | `1.2.0` |
| `1.2.x` | `1.2.1-ballot` | `1.2.1-ballot` |
| `1.2.x` | `current`, `1.3.0` | none |

## 2. Candidate versions (per request)

| Endpoint | Candidate set | Source |
|----------|---------------|--------|
| `POST /igs/register`, registry answered with a match | Union of every version listed by the registries that answered | Registry listings (`versions` keys, or `dist-tags.latest`) |
| `POST /igs/register`, fallback | Stored versions of `name` that the caller may use | Packages table joined with organisations (`storedVersionsWithAccess`) |
| `POST /{version}/include-ig` | Stored versions of `name` that the caller may use | The same catalog-with-access read in the include transaction (`visible`) |
| `POST /{version}/validate?ig=` | None. A series is rejected with 400 (FR-018) | n/a |

## 3. Resolution outcome

| Field | Meaning |
|-------|---------|
| concrete version | The chosen `version`. From here on, the request is handled exactly as an exact-version request. |
| warning | Registration fallback only. Says whether no registry could be consulted or none lists a version in the series. |
| requestedVersion | Echo of the request's version value (`latest`, exact or series), added to the response. |

**State transitions**: none. A series never becomes stored state. Including the same series later can give a
different concrete version. That adds a new snapshot, and existing ones are unchanged (spec 004: include adds).
