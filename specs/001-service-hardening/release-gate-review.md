# Release Gate Review: findings for the reviewer

**Checklist**: [checklists/release-gate.md](./checklists/release-gate.md) · **Prepared**: 2026-10-26, after the 1.0.0 release
**Reviewed against**: spec.md (with the 2026-10-25 clarifications), plan.md, research.md, data-model.md, contracts/,
traceability.md, docs/upgrade-1.0.md and, where an item asks about behaviour, the code.

These are proposed verdicts. Marking checklist items stays with the reviewer. Verdicts:

- **Satisfied**: the criterion is met as the artifacts stand. Proposed: mark `[x]`.
- **Partial**: the requirement exists, but only in the plan or contracts, or with a hole. The fix is usually a short
  spec edit.
- **Gap**: no artifact states the requirement. Either add it or record an explicit deferral.
- **Conflict**: two artifacts disagree.

| Verdict | Count | Items |
|---------|-------|-------|
| Satisfied | 13 | CHK006, 009, 010, 013, 015, 019, 020, 021, 022, 025, 032, 034, 044 |
| Partial | 23 | CHK001, 005, 007, 008, 011, 012, 014, 016, 017, 018, 023, 026, 031, 033, 035, 037, 039, 041, 042, 045, 048, 049, 050 |
| Gap | 13 | CHK002, 003, 004, 027, 028, 029, 030, 036, 038, 040, 043, 046, 047 |
| Conflict | 1 | CHK024 |

The checklist marks CHK019, CHK020, CHK015 and CHK032–CHK034 as highest risk. All six are now Satisfied, resolved by
the 2026-10-25 clarifications (CHK033 only partly: see below).

## Most important open items

1. **CHK024 (Conflict)**: contracts/container.md §1 says both platforms are "built and smoke-tested in CI on every
   PR". CI smoke-tests amd64 only and only builds arm64. The plan's QEMU startup smoke for arm64 was never
   implemented. The contract overstates what is tested.
2. **CHK036 (Gap)**: nothing specifies admin operations under overload. In the code, admission control
   (`AdmissionController`) applies only to validation, so `include-ig` and uploads are never refused for load.
3. **CHK038 (Gap)**: nothing specifies what happens when an IG's declared FHIR version doesn't match the validator it
   is included into. The code checks this for profiles (`ProfileService`) but not for IGs.
4. **CHK002 / CHK043 (Gap)**: beyond the bootstrap key, no artifact or doc says how API keys are created, rotated or
   revoked (`api_keys.revoked_at` exists, so today it is SQL by hand). There is no threat model.
5. **CHK046 / CHK047 (Gap)**: the 64-character label assumption has no source. Registry behaviour (404 semantics,
   `versions` vs `dist-tags`) is not an assumption, although T126 showed results depend on it.

## Per item

### Requirement Completeness

| Item | Verdict | Evidence | Proposed resolution |
|------|---------|----------|---------------------|
| CHK001 | Partial | The spec edge case requires deterministic handling of cycles and conflicting versions and reporting the choice. The rules themselves (exact version used; missing version → highest stored; conflict → highest; cycles cut) are only in research R6 | Add the four R6 rules to FR-005 |
| CHK002 | Gap | Only the bootstrap admin key (`FV_ADMIN_API_KEY`) is specified. data-model §1.4 has `revoked_at` and a 30 s cache, but no requirement or doc covers creating, rotating or revoking other keys | Add an FR, or record a deferral plus a documented SQL procedure for now |
| CHK003 | Gap | No audit trail requirement. `updated_at` and `revision` record that something changed, not who changed it | Record a deferral (new feature) or add an FR |
| CHK004 | Gap | Constitution (line 149) forbids logging full resource bodies at INFO or above. FR-030 covers credentials only | Extend FR-030 to restate the PHI rule |
| CHK005 | Partial | contracts/configuration.md has `FV_VALIDATION_MAX_BUNDLE_ENTRIES` (10000 → 413) and the body limit. FR-010 bounds pending work only | Add bundle and body limits to FR-010 or FR-014 |
| CHK006 | Satisfied | `FV_HTTP_REQUEST_TIMEOUT_MS` (default 60000, 503 `not-ready` when exceeded) is separate from `FV_VALIDATION_TIMEOUT_MS` (30000) in contracts/configuration.md | — |
| CHK007 | Partial | CORS is only in contracts (http-api.md headers; configuration.md: off by default, `*` refused in prod) | Accept as contract-level, or add one line to the spec |
| CHK008 | Partial | `FV_PG_SSL_MODE` (default `prefer`) is in the configuration contract. The spec has no transport requirement, and `prefer` allows plaintext | Decide whether production should require `require`/`verify-full`; state it in FR-027 |
| CHK009 | Satisfied | CHANGELOG 1.0.0 Breaking table: every status and body change, the removed config files and properties, port 54329 → 5432. Plus docs/upgrade-1.0.md | — |
| CHK010 | Satisfied | traceability.md records history rewriting as deferred (D4). docs/upgrade-1.0.md step 0 requires a new password and says the committed one is public | — |

### Requirement Clarity

| Item | Verdict | Evidence | Proposed resolution |
|------|---------|----------|---------------------|
| CHK011 | Partial | "Promptly" has no time bound. `Retry-After` is "seconds" in the contract without values; the code sends 1 (overload) and 5 (not ready, timeout) | Put "refused without queuing" and the `Retry-After` values in FR-010 / http-api.md |
| CHK012 | Partial (decision) | FR-009 is SHOULD, with no rationale. The cache is implemented (`FV_PACKAGE_CACHE_MAX_MB`) | Either raise to MUST, which the code already meets, or state why it is optional |
| CHK013 | Satisfied | FR-011 now says readiness is false until every supported version is usable; a version being rebuilt stays ready | — |
| CHK014 | Partial | US1-AS2 points at SC-002. FR-002 says "within the propagation window" without defining it | Write "within 10 seconds (SC-002)" into FR-002 |
| CHK015 | Satisfied | Clarification (anonymous by default) and FR-012 now agree with R8 | — |
| CHK016 | Partial | FR-017 requires "an explicit documented default" but doesn't name it. R11 / http-api.md do: `ValidationResponse` JSON | Name the default in FR-017 |
| CHK017 | Partial | FR-013 says "approved sources" only. configuration.md defines them as hosts (or base URLs whose host is allowlisted). R9 covers redirects (at most 3, each hop re-checked) | Add host semantics and redirect re-checking to FR-013 |
| CHK018 | Partial | Non-SemVer labels (`current`, `dev`) sort below every SemVer label, but only research R7 says so | Add that ordering to FR-025 |

### Requirement Consistency

| Item | Verdict | Evidence | Proposed resolution |
|------|---------|----------|---------------------|
| CHK019 | Satisfied | US4-AS3 and FR-018 now say 200 for invalid, 400 for unparseable, matching R12 | — |
| CHK020 | Satisfied | The edge case now makes the version unavailable (503) instead of serving an old snapshot, matching data-model STALE and FR-002 | Optional: say in SC-001 that it counts answered validations |
| CHK021 | Satisfied | US6-AS1 permits ("may exist only as") a dev image; R16 chooses not to build one. No contradiction | — |
| CHK022 | Satisfied | Assumptions, FR-021, openapi.yaml (`FhirVersion` pattern STU3/R4/R4B/R5) and the V3 seed rows agree. R6 is disabled everywhere | — |
| CHK023 | Partial | Health, readiness and metrics are anonymous in Assumptions, R8 and the contract. The read endpoints (dependencies, conformance) follow `FV_AUTH_ANONYMOUS_VALIDATE`, but FR-012 doesn't mention them | Add the read endpoints to FR-012 |
| CHK024 | **Conflict** | container.md §1: "both built and smoke-tested in CI on every PR". ci.yml: `container` (amd64 smoke) and `arm64-build` (build only). The plan's arm64 QEMU startup smoke doesn't exist | Correct the contract to "built on every PR; smoke-tested on amd64", or add the arm64 startup smoke. **Resolved 2026-10-26**: `scripts/startup-smoke.sh` runs in the `arm64-build` job, and container.md now states each platform's smoke scope |
| CHK025 | Satisfied | FR-019 covers all endpoints. http-api.md gives dependencies 404/500 as `Problem` and deprecates conformance | — |

### Acceptance Criteria Quality

| Item | Verdict | Evidence | Proposed resolution |
|------|---------|----------|---------------------|
| CHK026 | Partial | Assumptions give a reference size (≤ 50 KB). The resource, profiles and IGs are defined only in perf/README.md and the k6 scripts | Reference the perf fixture from SC-003 |
| CHK027 | Gap | "Baseline" in SC-007 is undefined | Define it (e.g. heap after warm-up, idle) |
| CHK028 | Gap | SC-008 names neither the load level nor the request mix | Define both, e.g. the k6 profile used for SC-003 |
| CHK029 | Gap | FR-031 has no success criterion. `ObservabilityIT` tests it, but the spec has no measurable outcome | Add an SC (named metrics present; every request log line has the correlation id) |
| CHK030 | Gap | "Developer machine" in SC-010 is unspecified (CPU, memory, warm or cold image cache) | Specify it; the image pull dominates on a cold cache |
| CHK031 | Partial | The size target and `--read-only` compatibility are contract-only (container.md §1). The target is now ≤ 550 MB (measured 493 MB), not the 350 MB the checklist cites | Accept as contract-level; note the checklist figure is outdated |

### Scenario Coverage

| Item | Verdict | Evidence | Proposed resolution |
|------|---------|----------|---------------------|
| CHK032 | Satisfied | Clarification, FR-027 and docs/upgrade-1.0.md (dump, restore, count check), rehearsed by `scripts/upgrade-rehearsal.sh` | — |
| CHK033 | Partial | Rolling back from 1.0.0 to 0.1.x is covered (upgrade guide "Rollback": keep the old volume; 0.1.x can't use V3/V4). Nothing covers reverting a later 1.x release whose migrations have run | Add a rollback rule for 1.x migrations (e.g. migrations stay backward compatible within 1.x) |
| CHK034 | Satisfied | Clarification and Assumptions: mixed 0.1.x/1.0.0 fleets aren't supported; stop, migrate, start | — |
| CHK035 | Partial | FR-002 requires 503, readiness false, retry with backoff and recovery. `/health` names the failure; `fv_snapshot_build_failures_total` exists. Backoff bounds and alerting guidance are unspecified | Specify the backoff bounds; point to the metric for alerting |
| CHK036 | Gap | Nothing specifies it. The code admits only validations, so admin operations bypass the limit | Specify the intended behaviour (likely: not counted, but bounded by the `fv-packages` pool). **Resolved 2026-10-26**: the `fv-packages` queue turned out to be unbounded (constitution VI), so FR-010 now requires a separate admin limit, implemented as `FV_ADMIN_MAX_PENDING` (503 `overloaded` beyond it) |
| CHK037 | Partial | `FV_PACKAGE_REGISTRY_ENABLED=false` means offline (cache only), and R6 rule 3 falls back when registries are disabled. FR-025 and FR-026 don't cover offline | Add the offline behaviour to FR-025/FR-026 |

### Edge Case Coverage

| Item | Verdict | Evidence | Proposed resolution |
|------|---------|----------|---------------------|
| CHK038 | Gap | Unspecified. Profiles are checked (`ProfileService`: 400 on a mismatched `fhirVersion`); including an IG isn't | Specify (likely 400 on mismatch) and decide whether the code should change |
| CHK039 | Partial | The spec edge case covers concurrent IG changes only. data-model §1.3 gives last-writer-wins for both kinds (row lock on the revision update) | Extend the edge case to profiles |
| CHK040 | Gap | No limit on profiles or IGs per version. Only metrics exist (`fv_profile_count`, `fv_snapshot_package_size_bytes`) | Record as a deferral or set a limit (constitution VI asks for bounds) |
| CHK041 | Partial | An invalid `X-Request-Id` is replaced (http-api.md). A reused id is accepted, but nothing says so | State that ids are not checked for uniqueness |

### Non-Functional Requirements

| Item | Verdict | Evidence | Proposed resolution |
|------|---------|----------|---------------------|
| CHK042 | Partial | Plan risks: heap ≥ 2× the largest snapshot. container.md: `FV_MEMORY_LIMIT` 2g (OOM measured at 1g). Neither is an operator requirement in the spec or README | Move the sizing guidance into operator docs |
| CHK043 | Gap | No threat model or trust-boundary list. The Assumptions say only that health/metrics "should be reachable only from the operator's network" | Write a short trust-boundary section |
| CHK044 | Satisfied | container.md §2 invariants: every port loopback; pgAdmin password required (`${VAR:?}`); FR-033 says "by default" | — |
| CHK045 | Partial | The contract CI table has the gates (Trivy fails on CRITICAL; the push job builds arm64, so a failure blocks it). FR-035 names only tests and the smoke test | Add the scan threshold to FR-035 |

### Dependencies & Assumptions

| Item | Verdict | Evidence | Proposed resolution |
|------|---------|----------|---------------------|
| CHK046 | Gap | The Assumptions claim 64 characters covers all observed labels, without a source or measurement | Measure against the registry listing, or soften the claim |
| CHK047 | Gap | Registry behaviour isn't an assumption. T126 (latest from `versions` rather than `dist-tags`) shows results depend on it | Add an assumption on the registry API |
| CHK048 | Partial | The Assumptions say no new infrastructure (true). The new runtime libraries (Micrometer, Prometheus registry, contextual logging) are justified only in R13 | Optional: list them in the Assumptions |

### Traceability

| Item | Verdict | Evidence | Proposed resolution |
|------|---------|----------|---------------------|
| CHK049 | Partial | traceability.md covers every finding. Two have no FR and no explicit deferral: D12 (Makefile; "Makefile review") and P7 (query shape, duplicate services; "code review only") | Record both as closed by review without an FR |
| CHK050 | Partial | FRs without an acceptance scenario or success criterion: FR-009, FR-031, FR-034, FR-035, and FR-032's non-root and multi-arch parts. FR-022 (edge case) and FR-029 (US6-AS3, SC-008) are covered. FR-023 is covered only for B11 (US5-AS4), not for read-endpoint 404s | Add scenarios or criteria, or accept test coverage in traceability.md |
