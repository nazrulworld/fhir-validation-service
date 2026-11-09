# Research: Terminology Servers per IG Context, Ecosystem Routing, Included-Only Contexts

**Feature**: `specs/007-terminology-routing/spec.md` | **Date**: 2026-11-09

Findings come from the code at `9536ad6`, the HAPI FHIR 8.12.1 jars (`javap`), and live answers of the official
registry (`https://ecosystem.hl7.org/tx-reg`) fetched on 2026-11-09. Recorded answers are kept as test fixtures
(R14).

---

## R1. Where routing lives: our own `IValidationSupport`, not HAPI or core

**Decision**: Write `EcosystemRoutingTerminologySupport implements IValidationSupport` in `core/terminology/`. It
picks a server for each call and delegates to a pooled, HAPI-provided `RemoteTerminologyServiceValidationSupport`
for that server (one per base URL and FHIR version, shared by all snapshots).

**Rationale**:
- HAPI 8.12.1 has no ecosystem mode. `FhirInstanceValidator` wraps the chain in `WorkerContextValidationSupportAdapter`,
  whose `getTerminologyClientManager()` returns `null`. Core's `TerminologyClientManager` is never used.
- `RemoteTerminologyServiceValidationSupport` takes one fixed base URL. That is the same as the CLI's `-tx <url>`
  without routing, so mode `server` can use it directly, and mode `ecosystem` can wrap one per chosen server.
- Delegating keeps HAPI's request building and its parsing of `$validate-code` answers, including issue mapping and
  display-mismatch handling.

**Alternatives considered**:
- Driving core's `TerminologyClientManager` directly: rejected. It needs an `IWorkerContext` that HAPI doesn't give
  us, and it brings its own caches (`system-map.json`) that ignore which server answered.
- One `RemoteTerminologyServiceValidationSupport` per context, rebuilt with a new URL for each call: rejected. It
  isn't thread-safe to change its base URL, and it would rebuild HTTP clients constantly.

## R2. Which operations the remote modules answer (FR-014)

**Decision**: The routing module and the `server`-mode module are both wrapped in `TerminologyOnlySupport`, a thin
delegating `IValidationSupport` that answers only:
- `isCodeSystemSupported`;
- `isValueSetSupported` (needed so the chain dispatches `validateCodeInValueSet`, see R4);
- `validateCode`;
- `validateCodeInValueSet`;
- `lookupCode`.

Every `fetch*` method, `expandValueSet` and `translateConcept` returns `null`, the interface default.

**Rationale**: `RemoteTerminologyServiceValidationSupport` implements `fetchCodeSystem` and `fetchValueSet` (seen
in `javap`). Left in the chain, it would fetch conformance resources over the network and break spec 003 FR-002.
Hiding them in one wrapper makes the rule hold for both modes and is easy to test.

**Alternatives considered**: subclassing and overriding the fetch methods. Rejected because a future HAPI method
would be inherited silently, while the wrapper only forwards what it lists.

## R3. Value set definitions go with the request (clarification Q2, FR-014)

**Decision**: Rely on HAPI's own behaviour and extend it in one place:
- `RemoteTerminologyServiceValidationSupport.validateCodeInValueSet(..., IBaseResource valueSet)` already sends both
  `url` and the `valueSet` resource parameter when the chain passes the resource
  (`buildValidateCodeInputParameters`, `javap` offsets 77 and 162). The chain passes the ValueSet it fetched from the
  context's packages, so the IG's definition reaches the server.
- `IgAwareRemoteTerminologySupport extends RemoteTerminologyServiceValidationSupport` overrides the protected
  `buildValidateCodeInputParameters`. It adds one `tx-resource` parameter for each CodeSystem from the context's
  packages that the value set includes and that is defined by the IG (not the core spec). Servers then don't answer
  "unknown code system" for IG-local systems mixed with LOINC or SNOMED CT in one value set. `tx-resource` is
  supported by tx.fhir.org and FHIRsmith, and the CLI sends it the same way.
- Value sets no package in the context defines (only referenced by URL) are sent as `url` only, so the server's
  copy is used.

**Rationale**: This gives membership decided by the IG's content, as the clarification asks, with the smallest
change: one overridden protected method.

**Verification result** (TerminologyRoutingIT, 2026-11-09): the first bullet above was wrong. HAPI 8.12.1's
`validateCodeInValueSet` sends **only** the `url` when the ValueSet has one, and drops the resource (bytecode offsets
43-57). `IgAwareRemoteTerminologySupport` therefore also overrides `validateCodeInValueSet`: for a ValueSet the chain
passes as a resource (one the context's packages or the core specification define), it calls
`invokeRemoteValidateCode(system, code, display, null, valueSet)`, so the definition goes as `valueSet` without `url`,
plus the `tx-resource` CodeSystems. The test asserts both parameters.

**Alternatives considered**: sending only `url` (rejected by Q2); sending the whole package's terminology on every
call (too large, and needless for most value sets).

## R4. Order of modules in the chain, and declining locally decidable codes (FR-013)

**Decision**: The terminology module goes right after the package and common-code-system modules, and before
`SnapshotGeneratingValidationSupport`:

```
DefaultProfileValidationSupport
CustomNpmPackageValidationSupport
InMemoryTerminologyServerValidationSupport
CommonCodeSystemsTerminologyService
TerminologyOnlySupport(SERVER or ECOSYSTEM module)   ← only when mode != NONE
SnapshotGeneratingValidationSupport
```

The terminology module also **declines** (returns `null` / `false`) for:
- a code system that a package in the context defines with `content = complete`;
- a code system that `CommonCodeSystemsTerminologyService` supports;
- a value set whose included systems are all of those kinds.

This makes FR-013 hold even when an earlier module returns `null` instead of an answer.

**Verification** (task-level, before the module is finished): a fixture test validates three codes against a
context in ecosystem mode with a stub server: one from a complete IG CodeSystem, one from UCUM, one from LOINC. It
asserts that only the LOINC call reaches the stub. If `InMemoryTerminologyServerValidationSupport` answers an
unknown-system error before our module is asked, the module moves before it and keeps the decline rule above. That
order is already safe, because of the decline rule.

**Verification result** (TerminologyRoutingIT, 2026-11-09): `InMemoryTerminologyServerValidationSupport` answers
`validateCodeInValueSet` for an IG value set it can't expand (local system plus LOINC) with an error, before a later
module is asked. The terminology module therefore sits **before** it, right after the package module; the decline rule
keeps local and common code systems in-process (asserted: 0 server calls for a complete local CodeSystem and UCUM).

## R5. Per-request terminology time budget (clarification Q3, FR-019a)

**Decision**: Use a `TerminologyBudget` held in a `ThreadLocal` and opened by `ValidatorSnapshot.validate`:
- It is opened with `FV_TX_REQUEST_BUDGET_MS` (default 10 000) around the `FhirValidator.validateWithResult` call,
  and cleared in `finally`.
- It is **not** a wall-clock deadline. It counts only the time spent waiting on terminology calls: each outgoing call
  (server call or `/resolve` lookup) measures its elapsed time and `charge(elapsedMs)`s it, and
  `remainingMs = budget − spent`. Local validation work, local terminology and cache hits therefore never use it up,
  as FR-019a requires, however large the Bundle.
- Every outgoing call gets connect and read timeouts of `min(FV_TX_TIMEOUT_MS, remainingMs)`, so one call can
  overrun the budget by at most its own timeout (SC-005a).
- When the budget left is 0, no call is made. The module returns a "not checked" result (R7), and a per-request flag
  records that the budget was used up.
- Warm-up validations open a budget of 0, so warm-up never calls a server.

**Rationale**:
- HAPI validation runs synchronously on the validation worker thread. This is constitution Principle I, which already
  puts it in `executeBlocking`. So a thread-local is exactly request-scoped.
- The bundle validator runs entries on the same thread: concurrent bundle validation is off by default and is not
  turned on by this service.
- Codes answered locally or from the cache never reach the module's network path, so they don't count against the
  budget, as the spec requires.

**Alternatives considered**:
- Passing the budget through `ValidationSupportContext`: rejected, because HAPI creates that object and there's no
  hook for request data.
- A global per-server rate limiter: rejected. It doesn't bound one request's wall time, which is what Q3 asked for.

## R6. Caching code results without keeping failures (FR-017, FR-018, FR-019)

**Decision**:
- **Code results:** keep `ValidationSupportChain(CacheConfiguration, modules)` per snapshot, with
  `setCacheSize(FV_TX_CACHE_SIZE)` (default 5000) and `setCacheTimeout(FV_TX_CACHE_TTL)` (default 10 min).
- **Failures:** HAPI caches `validateCode` results (`ValidationSupportChain$ValidateCodeKey`, an expiring cache).
  A degraded answer (server failed, budget spent, server not allowed, no list) would therefore be served for 10
  minutes. To prevent that, a validation that produced any degraded answer calls `chain.invalidateExpiringCaches()`
  on its snapshot after it finishes. Only the expiring caches are cleared. Conformance resources sit in HAPI's
  non-expiring cache, so the cost is only re-asking successful codes after a failure, which is rare.
- **Routing decisions:** a Caffeine cache in `RegistryResolver`, keyed by (FHIR version, system), bounded to 10 000
  entries. A success is kept 24 h, a failure 5 min (per-entry expiry through `Expiry`).
- **Allowlist:** held as an immutable object, replaced on refresh.

**Rationale**: This is the smallest way to honour FR-019, "unavailable is a warning", without stale warnings
outliving the outage. A separate result cache in our module would duplicate HAPI's.

**Alternatives considered**:
- Turning off the chain cache: rejected. Fetch caching matters for hot-path performance.
- Throwing from the module on failure: rejected. The exception would reach `FhirInstanceValidator` and become an
  error or a 500.

## R7. How "not checked" and "unavailable" are reported (FR-019, FR-019a, edge cases)

**Decision**: On failure, the module returns
`new CodeValidationResult().setSeverity(IssueSeverity.WARNING).setMessage(...)` with no `code`, plus a
`CodeValidationIssue` with code `NOT_FOUND`, coding `not-found` (the same shape HAPI uses for "unknown code
system"). The message texts are fixed:
- `"Terminology server unavailable; code {system}#{code} was not checked"`, for a timeout, connection failure, open
  circuit or 5xx;
- `"Terminology time budget for this request was used up; code {system}#{code} was not checked"`;
- `"No permitted terminology server for {system}; code was not checked"`, when no server was chosen, the server is
  not allowed, or there is no list.

The validator turns a warning result into a warning issue at the element, so the status stays 200.

**Verification result** (TerminologyFailureIT, 2026-11-09): a code-less `CodeValidationResult` with severity
`WARNING` and the fixed message is reported by HAPI 8.12.1 as a `warning` at the coded element, also for a required
binding; the fallback below was not needed. Asserted for: server down, timeout, open circuit, redirect, spent budget,
server not on the allowlist, and a context built while its server was not allowed.

**Verification (original plan)**: an IT asserts that the element gets severity `warning` (not `error`) for each of the three
causes. If HAPI 8.12.1 upgrades a code-less result to an error for required bindings, the module instead returns
`null`, so HAPI reports its own "code system unknown" warning, and our message is added as an `information` issue
by the post-processing in `ValidatorSnapshot`. This fallback is decided by the test, not guessed.

## R8. The registry's discovery answer: real shape and version matching (FR-007, FR-008, FR-009)

**Findings** (`GET /tx-reg/?fhirVersion=R4`, `Accept: application/json`, 2026-11-09):
- Top level: `last-update`, `master-url`, `results[]`.
- Each entry has `server-name`, `server-code`, `registry-name`, `registry-code`, `registry-url`, `url`, `fhirVersion`
  (**`"4.0.1"`, not `"R4"`**), `error` (`null` when healthy), `last-success` (ms), `systems`, `authoritative[]`
  (wildcard patterns such as `http://hl7.org/*`), `authoritative-valuesets[]`, `security` (`open`, `api-key`, ...)
  and `open` (boolean).
- R4 lists 15 servers, 14 of them `open`; R5 lists 5. **STU3 (`R3`) and R4B list none.**
- Some listed URLs are plain `http://` (`http://tx.fhir.org/r4`, `http://tx.hl7europe.eu/r4`). tx.fhir.org answers
  over HTTP without redirecting.

**Decisions**:
- The query parameter is `SupportedFhirVersion` → `R3 | R4 | R4B | R5`. An entry matches when the major.minor of its
  `fhirVersion` equals the version's (3.0, 4.0, 4.3, 5.0).
- Entries are kept when `error == null`, `security == "open"` and the version matches. Unknown fields are ignored;
  missing required fields drop the entry.
- URLs are matched exactly after normalising (lower-case scheme and host, no default port, no trailing `/`). The
  service calls the URL **as listed**, including `http://`. Upgrading the scheme would break the exact-match rule and
  hosts without TLS. Only codes, systems, displays and value set or CodeSystem definitions are sent, never the
  validated resource. The upgrade note states this.
- **STU3 and R4B**: with no listed servers, saving `server` or `ecosystem` for those versions is refused with 400
  `tx-server-not-registered` once a list exists. This follows from the strict rule; the upgrade note states it.

## R9. The registry's resolve answer and server choice (FR-016)

**Findings** (`GET /tx-reg/resolve?fhirVersion=R4&url=http://loinc.org&usage=validation`):
- The answer is `{formatVersion: "1", registry-url, candidates[]}`. **`authoritative` is absent** when no server
  claims the system.
- Each candidate has `server-name`, `url`, `fhirVersion`, `security`, `open`, `access_info` and `content`.
- For LOINC, five open R4 servers report `complete`, among them `http://tx.fhir.org/r4`, listed first.

**Decision**: The choice extends spec FR-016 with a deterministic tie-break, the same as core's `chooseServer`:
1. the first allowlisted entry of `authoritative[]`, if present;
2. among allowlisted candidates with `content = complete`: the context's own server if it is one, otherwise
   `tx.fhir.org` for the version if it is one, otherwise the first in registry order;
3. the same rule for `content = fragment`;
4. otherwise the context's own server, or the default server (`tx.fhir.org` for the version) if it is allowlisted;
5. otherwise none.

`not-present`, `example` and `supplement` candidates are ignored. When the context has its own server, every
mention of the default server is replaced by it before the steps above. A `formatVersion` other than `"1"`, or an
unparseable body, counts as a failed lookup (R6: kept 5 min).

**Transport**: routing lookups happen inside the synchronous validation chain on the worker thread. They therefore
use a synchronous Apache `HttpClient` with the same `NetworkGuard`, timeouts and disabled redirects as the server
clients (R11), rather than the Vert.x `WebClient`, because waiting on a Vert.x future would need the forbidden
`.get()`/`.join()`. Only the periodic discovery fetch (R10), which runs on a timer, uses `WebClient`.

**Rationale**: Preferring the default server among equals avoids spreading one context's traffic over many
national servers at random, and keeps results stable between runs.

## R10. Allowlist loading, storage and the built-in fallback list (FR-006, FR-007, FR-012, FR-012a)

**Decision**:
- **Registry address:** a `static final` constant in `TxRegistry`, `https://ecosystem.hl7.org/tx-reg`. There is no
  config key. Tests use a package-private constructor (R14).
- **Refresh:** at start, and then every `FV_TX_REGISTRY_REFRESH_MS` (default 6 h, bounds 5 min to 7 days).
  - The refresh runs as a Vert.x timer, and the fetch uses the Vert.x `WebClient` (non-blocking; Principle I).
  - Each enabled version is fetched one after another. A failed fetch keeps that version's previous list.
- **Shared storage:** new table `tx_registry_snapshot`, one row per FHIR version (data-model §3). After a
  successful fetch, the instance upserts the row only if its `fetched_at` is newer (`ON CONFLICT ... WHERE`). At
  start, the instance loads the rows before its first fetch, so a restart with the registry down still has a list.
- **Cross-instance consistency** (Principle II): each instance fetches on its own, so two instances may disagree
  for at most one refresh interval. That is acceptable, because both lists come from the same registry and each is
  enforced locally on every call. No `NOTIFY` is needed.
- **Built-in fallback list:** the enum `BuiltInTxServer`, `{TX_FHIR_ORG_R4("http://tx.fhir.org/r4", R4),
  TX_FHIR_ORG_R5("http://tx.fhir.org/r5", R5)}`. These are the default servers the registry lists today.
  **Changed after implementation (2026-11-09):** the built-in addresses are `https://tx.fhir.org/r4` and `/r5`. Because
  the registry lists `http://tx.fhir.org/r4` and matching is exact, the https addresses are allowed only while the
  built-in list applies, and `TxServerAllowlist.defaultServer` accepts the registry's entry with the same host and path
  under the other scheme, so routing still prefers tx.fhir.org and falls back to it.
  - It is used only while no list exists in memory or in the table, for the version in question.
  - Once a list is obtained, it alone applies (spec edge case).
  - It is fixed in source and changes only through a release.
- **Health:** `/health` (detail) gains `terminology.allowlist` per version, with `source` (`registry`,
  `stored-registry`, `built-in`), `fetchedAt`, `registryLastUpdate` and `servers` (count). Readiness doesn't depend
  on it, because terminology is optional.

**Alternatives considered**:
- `LISTEN/NOTIFY` to share one fetch: rejected as needless coordination.
- An operator-configurable fallback: rejected by the strict rule.

## R11. The address check, redirects and the HTTP client of the terminology calls (FR-011, FR-019)

**Decision**:
- Each pooled server client gets its own `ApacheRestfulClientFactory` (HAPI's default client factory) on the
  snapshot's `FhirContext`:
  - `setConnectTimeout(FV_TX_TIMEOUT_MS)` and `setSocketTimeout(FV_TX_TIMEOUT_MS)`; R5's per-request budget lowers
    the socket timeout further through an interceptor.
  - `setServerValidationMode(NEVER)`, so no `/metadata` call is made.
  - An `HttpClient` built with `disableRedirectHandling()`, a public-address `DnsResolver` (below), and a pool of
    `FV_TX_MAX_CONNECTIONS_PER_SERVER` (default 10).
- **Address check:** the custom `DnsResolver` resolves the host name and rejects the call when any address is
  non-public (`SafePackageFetcher.isNonPublic`, made package-visible to `core.terminology` through a small shared
  `NetworkGuard` class).
  - Because the check runs in the resolver the connection uses, it also covers DNS rebinding.
  - A rejected call counts as `refused` (FR-026) and is reported as "No permitted terminology server".
- **Circuit breaker:** one per server, in `TerminologyClients`. After 5 consecutive failures it opens for 60 s, then
  lets one trial call through (half-open). It is a small hand-written class (about 40 lines). There is no new
  dependency (Principle VIII).

**Rationale (original)**: The `FhirContext`'s shared factory must not change, because profile validation uses the same
context. A separate factory per client isolates timeouts and redirect rules.

**Verification result** (TerminologyFailureIT, 2026-11-09): HAPI builds **every request** of a generic client through
`FhirContext.getRestfulClientFactory()`, not through the factory the client was created with, so a per-client factory
was silently bypassed (no timeout, no guard). The decision changes: `TerminologyClients` installs one guarded
`ApacheRestfulClientFactory` per `FhirContext` (`ctx.setRestfulClientFactory`) whose `getNativeHttpClient()` always
returns the one guarded HTTP client (redirects off, `NetworkGuard` DNS resolver, timeouts from the budget interceptor,
at most `FV_TX_MAX_CONNECTIONS_PER_SERVER` connections per server). This is safe: the service makes no other HAPI client
calls (packages are fetched by `SafePackageFetcher`, profiles never leave the process). Asserted: a 3 s server is cut at
the 500 ms timeout.

## R12. Terminology setting: storage, change path and rebuild (FR-001 to FR-005)

**Decision**:
- **Storage:** two columns on `fhir_validator_snapshots` (V9): `tx_mode` (default `'NONE'`) and `tx_server`
  (nullable). There is one row per included context already, so this is the natural place.
- **Reading:** `ConfigRevisionRepository.SELECT` reads the columns. `ConfigRow` gains
  `Map<String, TerminologySettings> terminology`, and a context without a row gets `NONE`. `ConfigSync.resolveAll`
  attaches the setting to each `ResolvedContext` (new component `terminology`), and `sameAs` compares it. The
  existing `applyPinned` diff therefore rebuilds exactly the changed context; the old snapshot serves until the new
  one is active. This is the spec 004 swap path, unchanged.
- **Changing on include:** `POST /{version}/include-ig` accepts an optional `terminology` object. It is applied
  only when the context is newly included. For an existing context it is ignored with a warning, as `shareable` is
  today.
- **Changing later:** `PATCH /{version}/include-ig?ig=` accepts an optional `terminology` object, next to the
  access fields. It uses the same lock, the same owner/system check and the same 404 rule as access changes.
  - It bumps the revision and notifies the new kind `IG_TERMINOLOGY`.
  - `ConfigSync` handles the new kind like `IG`, through `runSync` → `applyPinned`.
- **Checks at save time** (FR-002, FR-010a, FR-012a), in this order:
  1. the shape of the setting (400 `invalid-request`);
  2. URL normalisation;
  3. the allowlist: `tx-server-not-registered` (400) when a list exists, or `tx-registry-unavailable` (503,
     `Retry-After: 60`) when no list exists and the server is not built in.
- **Upgrade:** the column default `'NONE'` gives FR-005 and keeps rolling upgrades safe. 3.x instances don't read
  the columns.

**Alternatives considered**: a separate table `context_terminology`. Rejected, because it duplicates the key and
the delete-on-exclude logic of `fhir_validator_snapshots`.

## R13. Removing on-demand contexts (FR-021 to FR-025)

**Decision**:
- **`ValidatorRegistry`:**
  - Remove the `onDemand` map, `Kind.ON_DEMAND`, `maxOnDemand`, `buildWaitMs`, `waitFor`, `makeRoomLocked`,
    `evictOne` and the on-demand branch of `snapshot(key)`.
  - `snapshot(key)` serves base and pinned keys only. Any other key fails with
    `ProblemException.igNotIncluded(key)`: 400 `invalid-request`, "IG package P#v is not included for R4; include it
    first (POST /R4/include-ig)".
  - In `applyPinned`, an excluded context is dropped (no longer demoted). A request already holding the snapshot
    finishes normally.
- **`HeapBudget`:** with nothing evictable left, `evictor()` is removed. The include-time 413 check stays.
- **`IgReference.resolveIn`** gains the version's `PinnedAccess` (the included keys):
  - **Versionless** (`name` or `latest`): the highest *included* version the caller may use (FR-023). If none is
    included but a stored, visible version exists, the answer is 400 `invalid-request`, not included. Otherwise it
    is 404.
  - **Exact version:** not visible or not stored → 404 as today. Visible but not supporting the FHIR version → 400
    as today. Visible and supported but not included → 400 not included. So a 404 never turns into a 400 for an
    invisible package.
- **`ResolvedContext.forKey`:** drop the on-demand branch; `of(igPackage, view)` stays only for tests.
  `ResolvedContext.missing` keeps its include-time meaning.
- **`FhirValidationService.checkAccess`:** unchanged, except that the on-demand fallback for resolved packages goes
  away, because `peekResolvedPackages` always has pinned contexts.
- **Spec 005 FR-013** (access check before an on-demand build) disappears with the path. The access check itself
  stays before serving.
- **Configuration:** `FV_IG_MAX_ON_DEMAND` and `FV_IG_BUILD_WAIT_MS` are removed from `ConfigLoader`'s entries.
  `ConfigLoader` keeps a `DEPRECATED` list and logs one `WARN` per key set in the environment (FR-025). The values
  are ignored.
- **Status, metrics, docs:**
  - `ValidatorRegistry.details()` no longer reports `kind: on-demand`.
  - The metrics' `kind="on_demand"` series are no longer emitted.
  - The README and `openapi.yaml` text about on-demand building is replaced.
- **Tests:**
  - `OnDemandContextIT`, `OwnershipOnDemandIT` and `ContextEvictionIT` are rewritten into
    `IncludedOnlyValidationIT`, which covers spec US5 scenarios 1-6.
  - Tests that relied on on-demand contexts include the IG first.

**Rationale**: Contexts are now created only by include. That's the precondition for the per-context terminology
setting, and it removes unbounded, request-triggered builds.

**Release**: a breaking change to validation behaviour → **4.0.0** (constitution IV), with an upgrade note.

## R14. Testing the network parts without the network

**Decision**:
- `TxRegistry`, `TxServerAllowlist` and `TerminologyClients` each have a package-private constructor taking the
  registry base URL and a `NetworkGuard`. Production code has no other way to set them, and no config key exists.
- Tests use:
  - `StubTxRegistry`, a Vert.x HTTP server serving recorded `/tx-reg/?fhirVersion=` and `/tx-reg/resolve` answers
    (the 2026-11-09 recordings under `src/test/resources/tx-reg/`, with URLs rewritten to the stub);
  - `StubTerminologyServer`, a Vert.x server answering `$validate-code` and `$lookup`. It counts hits, records
    request bodies, and can delay, fail, redirect or return 5xx.
- `NetworkGuard.allowLoopbackForTests()` exists only in the test-only constructor path.
- A unit test asserts that the production `ServiceRegistry` wiring uses `TxRegistry.OFFICIAL` and the strict guard,
  by reflection on the built objects.

## R15. Performance, memory and capacity (constitution VI)

- **Mode `none` / base context:** the chain is identical to today; the only addition is one `ThreadLocal` set and
  clear per validation. Expected p95 change: within noise. The PR carries the `perf/validate.js` before/after result
  (hot path).
- **Terminology modes:**
  - A cached code costs one Caffeine lookup.
  - An uncached code costs one HTTP round trip, typically 50-300 ms to tx.fhir.org. It is bounded by
    `FV_TX_TIMEOUT_MS` (default 5 000) and by the 10 s request budget.
  - Because validation workers block on these calls, the plan documents that `FV_VALIDATION_WORKERS` may need
    raising for terminology-heavy traffic.
  - `perf/validate-terminology.js` (new) measures a coded Observation against a stub server with 100 ms latency.
- **Memory:**
  - Per snapshot: at most `FV_TX_CACHE_SIZE` code results, about 1 KB each, so about 5 MB at the default.
  - Process-wide:
    - the routing cache: 10 000 entries, about 2 MB;
    - the allowlist: under 50 entries per version;
    - the client pool: at most `servers × versions` clients, each with up to 10 pooled connections.
  - `HeapBudget`'s per-snapshot estimate adds the code-cache bound for contexts not in mode `none`.
- **Bounds:** every new size and timeout is a `ConfigLoader` entry with min/max bounds (data-model §5).
