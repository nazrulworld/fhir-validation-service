# Feature Specification: Service Hardening — Correctness, Throughput and Safe Administration

**Feature Branch**: `001-service-hardening`

**Created**: 2026-10-25

**Status**: Draft

**Input**: User description: "analysie exiting code. Find potential bugs, flows, perforance issues. Find potential features"

**Evidence**: Every requirement below traces to one or more findings in
[audit-findings.md](./audit-findings.md) (IDs such as B1, P1, F1). Candidate new features found
during the audit (FT1–FT14) are recorded there and are **out of scope** for this spec.

## Clarifications

### Session 2026-10-25

- Q: Who does an IG/profile change apply to? → A: Globally, to every consumer of that FHIR version
  (one configuration per version), and only administrators can change it.
- Q: What happens to the half-built per-client custom validators? → A: Remove them now; specify
  separately later if needed.
- Q: When a submitted resource parses correctly but fails validation, which HTTP status should the
  validate endpoint return? → A: 200 with `valid:false` and the issues; only an unparseable body
  gets 400.
- Q: Should validation requests need an API key by default, or stay open to anonymous callers
  unless an operator turns authentication on? → A: Anonymous by default; operators can require a
  validate-or-admin key via configuration; admin operations always need an admin key.
- Q: What should happen to existing deployments whose data lives in the database bundled inside
  the current container image, once 1.0.0 needs an external database? → A: A documented, tested
  dump/restore migration guide with a count-verification step; no automatic migration.
- Q: If an instance can't apply a committed IG change, what should it do for that FHIR version? →
  A: Stop serving that version (readiness false, 503 for that version), retry with backoff, and
  recover automatically; never serve outdated results.
- Q: During the upgrade to 1.0.0, may old (0.1.x) and new (1.0.0) instances run against the same
  database at the same time? → A: No. Stop all 0.1.x instances, migrate, then start 1.0.0 (short
  documented downtime). Zero-downtime rolling restarts apply to 1.x→1.x only.
- Q: Is an instance ready while a version's validator is being rebuilt for a newer configuration?
  → A: Yes. The previous validator keeps serving during the rebuild, and reporting not-ready would
  take every instance out of the load balancer at the same moment on each IG change. Readiness is
  false only when a version has no usable validator or cannot apply a committed configuration (FR-002).

## User Scenarios & Testing *(mandatory)*

Actors:

- **Validation consumer**: a system or developer submitting FHIR resources for validation.
- **Conformance administrator**: registers IG packages and custom profiles, and chooses which
  ones validation applies.
- **Operator**: deploys and runs the service as several identical instances behind a load
  balancer.

### User Story 1 - Results reflect the configured profiles and IGs, every time, on every instance (Priority: P1)

A conformance administrator registers a custom profile or includes an IG package. From then on,
every validation request for that FHIR version is checked against it, whichever instance handles
the request and whether or not it is the first request after the change. If the administrator
upgrades an IG to a newer version, results reflect only the new version.

**Why this priority**: The service's only job is correct validation. Today the first request after
a profile registration silently skips the profile (B1). Instances disagree with each other for up
to 24 hours or until restart (B2, B3). IG upgrades mix old and new content (B5). Dependency
resolution depends on load order (B6). Consumers get different answers for the same resource.

**Independent Test**: Run two instances. Register a profile that makes a known fixture invalid
through instance A, then immediately validate that fixture through instance B. It must be reported
invalid on the first attempt. Repeat with including an IG, and with upgrading an IG from version
X to Y where Y removes a constraint.

**Acceptance Scenarios**:

1. **Given** a freshly started service and a profile stored earlier, **When** the very first
   validation that requests that profile arrives, **Then** the result includes that profile's
   constraints.
2. **Given** two running instances, **When** a profile is registered or updated through one of
   them, **Then** validations on the other instance reflect the change within the propagation
   window (SC-002) with no restart.
3. **Given** a validator that includes IG `foo#1.0.0`, **When** the administrator includes
   `foo#2.0.0`, **Then** no resource or constraint that exists only in `1.0.0` affects later
   results.
4. **Given** the same set of included IGs, **When** the service is started any number of times
   or on any instance, **Then** the same package versions (including dependencies) are resolved.
5. **Given** an IG declares a dependency with no explicit version, **When** it is included,
   **Then** the dependency is resolved by a documented rule instead of failing.

---

### User Story 2 - Validation throughput uses the capacity it is given, and admin work never stalls it (Priority: P1)

A validation consumer sends many requests at once. Each instance processes validations in parallel
up to a configured limit, so throughput grows with the CPU capacity given to it. While an
administrator uploads or includes a large IG, validation requests and health checks keep being
answered at normal speed.

**Why this priority**: Today each instance runs one validation at a time regardless of cores (P1).
Loading or registering an IG freezes the whole instance, including health probes, for seconds
(P2), which can make orchestrators restart healthy instances. Horizontal scaling is the stated
primary goal of the service (constitution Principle II).

**Independent Test**: Load-test a single instance with concurrent validation requests at 1, 2 and
4 CPU cores and compare throughput. Separately, start including a large IG (US Core class, at
least 15 MB) while sending validation and health requests, and measure their latency.

**Acceptance Scenarios**:

1. **Given** an instance with N available cores and a warm validator, **When** at least N
   concurrent single-resource validations are submitted, **Then** they are processed in parallel
   and throughput meets SC-003.
2. **Given** a large IG is being uploaded, registered or included, **When** validation and health
   requests arrive during that work, **Then** they meet their normal latency targets (SC-004).
3. **Given** the concurrency limit is reached, **When** further requests arrive, **Then** they
   queue up to a configured bound and beyond it are rejected promptly with a "try again later"
   response instead of piling up without limit.
4. **Given** an instance is starting and loading previously included IGs, **When** an
   orchestrator checks it, **Then** liveness reports alive and readiness reports not-ready until
   validators are usable.

---

### User Story 3 - Only authorised administrators can change validation behaviour, and package ingestion cannot harm the service (Priority: P2)

Changing what the service validates against (uploading or registering IGs, including IGs,
registering profiles) needs administrator credentials. Package downloads go only to approved
sources. Oversized or malicious archives are rejected before they consume significant memory.

**Why this priority**: Today any caller can change validation results for every consumer (F2). The
download-by-URL feature can be pointed at internal network addresses (F1). Archives are neither
size-limited on download nor checked for decompression bombs (F3, P4). These are prerequisites
for running the service as a shared fleet.

**Independent Test**: Without credentials, call each administrative operation and expect refusal.
With credentials, register an IG from a non-approved host and expect refusal. Upload a crafted
archive that expands far beyond the limit and expect refusal with the instance remaining healthy.

**Acceptance Scenarios**:

1. **Given** a caller without administrator credentials, **When** they call any operation that
   changes IGs, profiles or validator configuration, **Then** the request is refused and nothing
   changes.
2. **Given** default settings, **When** any caller (with or without credentials) submits a
   resource for validation, **Then** validation works as before. **Given** the operator has turned
   on authenticated validation, **When** a caller without a validate-only or administrator
   credential submits a resource, **Then** the request is refused.
3. **Given** an administrator supplies a download location outside the configured approved
   sources (including private or loopback network addresses), **When** they register from it,
   **Then** the request is refused without any outbound connection being made.
4. **Given** an archive whose compressed size, expanded size, entry count or entry paths exceed
   configured limits, **When** it is uploaded or downloaded, **Then** it is rejected with a clear
   reason and the instance's memory and responsiveness stay within normal bounds.
5. **Given** an administrator includes an IG or registers a profile for a FHIR version, **When**
   the change is accepted, **Then** it applies to every consumer of that FHIR version (one global
   validator configuration per version), and only administrators can make such changes.

---

### User Story 4 - The API behaves predictably for ordinary HTTP clients (Priority: P2)

A validation consumer using an ordinary HTTP client, with default headers such as `Accept: */*` or
a charset parameter on the content type, gets a valid response in the format they asked for. The
status code tells them whether *their resource* is invalid or *the service* failed. Every request
gets a response.

**Why this priority**: Generic header values can be rejected or can leave the request hanging
(B12). An XML response request returns JSON labelled as XML (B13). Service faults are reported as
"resource invalid" (B16). Registering an unknown IG hangs forever (B10). Error bodies come in three
shapes and leak internal exception text (B16, F9). These problems make integrations fragile and
hard to debug.

**Independent Test**: Run a contract test suite that sends each operation with common real-world
header variants, malformed bodies, unknown packages and simulated backend outages, and asserts
status code, body shape, content type and that every request completes within the server's request
timeout.

**Acceptance Scenarios**:

1. **Given** a valid resource, **When** it is submitted with `Accept: */*`, no `Accept`, or a media
   type with parameters (e.g. `; charset=utf-8`), **Then** validation succeeds and the response
   uses the documented default or matching format.
2. **Given** the consumer requests an XML response, **When** validation completes, **Then** the
   body is XML of the documented shape.
3. **Given** the resource parses but fails validation, **When** it is validated, **Then** the
   request succeeds (200) with `valid:false` and the issue details. **Given** the body cannot be
   parsed as FHIR in the declared format, **Then** the response is a client error (400) with a
   fatal issue describing the parse failure.
4. **Given** the service cannot complete validation because of an internal or dependency failure,
   **When** a request arrives, **Then** the response is a server-side error, distinguishable from
   an invalid resource, and contains no stack traces, class names or internal messages.
5. **Given** an administrator registers an IG name/version that no registry knows, **When** the
   request completes, **Then** they receive a "not found" answer promptly instead of a hanging
   connection.
6. **Given** any endpoint returns an error, **When** the consumer parses it, **Then** it follows
   one documented error shape.
7. **Given** a FHIR version that is listed as supported, **When** profiles are registered or
   resources validated for it, **Then** both operations work. A version that is not supported is
   refused consistently by every endpoint and never silently mapped to another version (B15).

---

### User Story 5 - IG package lifecycle handles real-world packages (Priority: P3)

An administrator registers published IG packages, including pre-release versions with long version
labels, from any configured registry. Asking for "latest" gives the highest released version, not
the most recently uploaded one.

**Why this priority**: Version labels longer than 16 characters cannot be stored (B8). "latest" can
resolve to an older version (B7). A package available on the second registry can be reported
missing when the first registry answers "not found" first (B9, B19). The impact is real but
narrower than P1/P2 issues.

**Independent Test**: Register packages with versions such as `4.0.0-ballot-2023` and
`1.1.0-snapshot-01`. Upload `2.0.0`, then `1.5.0`, and ask for latest. Configure two registries
where only the slower one has a package.

**Acceptance Scenarios**:

1. **Given** a package version label of up to 64 characters, **When** it is registered, **Then**
   it is stored and retrievable by that exact label.
2. **Given** versions `2.0.0` and `1.5.0` of a package are stored (in any upload order), **When**
   "latest" is requested, **Then** `2.0.0` is resolved. Pre-release versions are never chosen over
   a released version of the same core version.
3. **Given** several registries are configured, **When** any one of them has the package, **Then**
   registration succeeds, the package is stored once, and "not found" is reported only when none
   has it.
4. **Given** the download-by-URL flow succeeds, **When** the response is returned, **Then** it
   reports the package's actual name and version (B11).

---

### User Story 6 - Operators can run, observe and configure the fleet from the environment (Priority: P3)

An operator deploys several instances against one shared database, configures them only through
environment variables, sees per-instance health, readiness and metrics, and can roll instances
without dropping in-flight requests.

**Why this priority**: The published container image includes its own database, which undermines
shared state (F4). Readiness doesn't exist and liveness is static (F5). Configuration is split
across two mechanisms, with some keys silently ignored (F6). Shutdown drops requests (F7). Password
fragments are logged (B18). These affect day-2 operations rather than validation results.

**Independent Test**: Deploy two instances from the published image against one external database
using only environment variables. Scrape metrics, kill the database and watch readiness flip, then
roll an instance under load and count failed requests.

**Acceptance Scenarios**:

1. **Given** the published image, **When** it is started with database connection settings,
   **Then** it uses the external shared database and does not start a database of its own (an
   all-in-one image may exist only as a separately named development artefact).
2. **Given** the database becomes unreachable, **When** readiness is checked, **Then** it reports
   not-ready, and recovers on its own when the database returns.
3. **Given** an instance receives a termination signal, **When** requests are in flight, **Then**
   it stops accepting new work, finishes in-flight requests up to a configured grace period, and
   then exits.
4. **Given** any configurable setting (port, limits, registries, pool sizes, timeouts), **When** an
   operator sets it through the environment, **Then** it takes effect. No documented setting is
   silently ignored.
5. **Given** any log output, **When** it is inspected, **Then** it contains no credentials or
   credential fragments.

---

### Edge Cases

- An operator upgrades from the previous all-in-one image: following the migration guide, all
  previously registered IGs, profiles and included-IG state are present in the external database
  and in effect after the first 1.0.0 start. Skipping the guide gives an empty but working
  service, and the CHANGELOG warns about this.

- A profile is registered while a validation that needs it is in progress: the in-flight
  validation uses either the old or the new definition consistently, never a partial mix.
- Two administrators include different versions of the same IG at the same moment on different
  instances: the final state is one defined version, the same on every instance.
- Two administrators register the same profile URL for the same FHIR version at the same moment
  with different content: the last committed registration wins, and every instance converges on
  it (FR-002, FR-006).
- An IG declares FHIR versions that don't include the version it is being included into (for
  example an R5 IG included into R4): the inclusion is refused as a client error and nothing is
  committed. A package that declares no FHIR version is accepted.
- A client sends an `X-Request-Id` that doesn't match the accepted format: a new id is generated
  and returned. Ids are not checked for uniqueness, so a reused id is accepted as is.
- An IG's dependency tree contains a cycle or two different versions of the same package: inclusion
  completes deterministically and reports which version was chosen.
- The database is briefly unavailable during startup: the instance keeps retrying, stays alive but
  not ready, and becomes ready once it can load its state.
- A previously included IG has since been deleted from storage, or a rebuild fails for any other
  reason: the process doesn't crash, and other FHIR versions keep being served. The affected
  version is unavailable on that instance (readiness false, 503 for that version, failure named in
  the health details) until a retry succeeds. The instance never serves results from an outdated
  configuration.
- A package registry hangs instead of failing: registration times out within a configured bound
  and the other registries are still tried.
- A request body exceeds the size limit: it is rejected with a "too large" answer before being
  fully read into memory.
- Profile canonical URLs that differ only in punctuation (e.g. `-` vs `.`) are treated as distinct
  profiles (B17).

## Requirements *(mandatory)*

### Functional Requirements

**Correctness and consistency (US1)**

- **FR-001**: Every validation MUST apply all profiles and IGs that were stored before the request
  arrived, including on the first request after startup, registration or cache expiry. A cache miss
  MUST NOT change the result (B1).
- **FR-002**: Changes to profiles, included IGs or validator configuration made through any
  instance MUST become effective on all instances within 10 seconds (SC-002), without a restart
  (B2, B3). An instance that knows of a newer committed configuration but cannot apply it MUST stop
  serving that FHIR version (503 with `Retry-After: 5`, readiness false) and name the failure in its
  health details. It MUST retry with exponential backoff, starting at 1 second and capped at 60
  seconds, count each failed build in a metric operators can alert on, and resume automatically once
  the change is applied. It MUST NOT answer from the outdated configuration.
- **FR-003**: Shared storage MUST remain the single source of truth. Any instance MUST be able to
  rebuild its complete validation state from it, and cached state MUST NOT outlive a change to the
  source (B2, B3, F8).
- **FR-004**: Replacing an included IG version MUST remove all content contributed only by the
  previous version before the new configuration takes effect (B5).
- **FR-005**: IG dependency resolution MUST be deterministic and version-aware. The same inputs
  MUST always resolve to the same package set, and the resolved set MUST be visible to the
  administrator (B6). Resolution at inclusion uses stored packages only, so no registry is contacted
  while the configuration is being changed. Rules: core packages (`hl7.fhir.rX.core`) are skipped; a
  version spelled out on a dependency is used exactly; a missing version, `current` or `latest`
  resolves to the highest stored version under FR-025 ordering; a dependency that is not stored is
  skipped and reported in the response; a package reached with different versions resolves to the
  highest of them, and the choice is reported; dependency cycles are cut. Registries come in
  earlier: registering an IG by name also fetches its dependencies, where an unpinned dependency is
  the highest stored version or, if none is stored and registries are enabled, the registry's latest.
  Uploads and downloads by URL store only the archive itself.
- **FR-006**: A configuration change MUST take effect atomically for validation: each validation
  runs entirely against either the old or the new configuration (B4).

**Throughput and responsiveness (US2)**

- **FR-007**: Each instance MUST process validations concurrently, up to a configurable limit that
  defaults to the number of available cores (P1).
- **FR-008**: Package decompression, parsing and loading MUST NOT delay request handling, health
  or readiness responses on that instance (P2). A validator build that would not fit in the
  instance's memory MUST be refused before it starts (an inclusion as a client error before it is
  committed; an already committed configuration as under FR-002), never end the process. Operator
  documentation MUST state how to size memory for the largest IG set, allowing for the old and new
  validator coexisting during a rebuild.
- **FR-009**: Packages loaded once on an instance MUST NOT be read and decompressed again from
  shared storage for later inclusions on that instance, within a bounded, configurable memory
  budget (P3).
- **FR-010**: Pending validation work MUST be bounded. Above the bound, requests MUST be refused
  immediately, without waiting for capacity, with 503 and `Retry-After: 1`. The size of one
  validation MUST also be bounded: request bodies by a configurable byte limit, and bundles by a
  configurable entry count, both refused as "too large". Administrative operations (uploading, registering or
  including IGs, registering profiles) MUST be bounded by a separate per-instance limit: validation
  load never refuses administrative work, and administrative work never takes validation capacity.
  Above that limit, administrative requests MUST likewise be refused immediately with 503 and
  `Retry-After: 1`, not queued or prioritised. Configuration syncs an instance runs on its own (FR-002) are
  never refused.
- **FR-011**: The service MUST expose separate liveness and readiness signals. Readiness MUST be
  false until a validator for every supported version is usable, false while any version is
  unavailable under FR-002 (a newer committed configuration that cannot be applied), and false while
  the shared database is unreachable. A version that is being rebuilt keeps serving its previous
  validator and stays ready (P5, F5).

**Administration safety (US3)**

- **FR-012**: All operations that change IGs, profiles or validator configuration MUST always
  require administrator authorisation. With no administrator credential configured, they MUST be
  refused. Validation MUST be anonymous by default, and an operator setting MUST be able to require
  a credential with a validate-only (or administrator) permission (F2). Read endpoints that expose
  stored packages (dependency graph, file listing) follow the same rule as validation. Health,
  readiness and metrics are always anonymous (see Assumptions). Cross-origin browser access MUST be
  off by default and limited to operator-listed origins; a wildcard origin MUST be refused in
  production.
- **FR-013**: Package downloads MUST be limited to an operator-configured list of approved
  sources, given as host names and reached over https only (a development-only setting may allow
  http and private addresses for local registries). Requests to other destinations,
  including private, link-local and loopback addresses, MUST be refused before any connection is
  opened (F1). Redirects MUST be limited in number, and every hop MUST be checked against the same
  rules before it is followed.
- **FR-014**: Uploaded and downloaded archives MUST be checked against configurable limits on
  compressed size, expanded size, entry count and expansion ratio. Path-traversal entries MUST be
  rejected. Limits MUST be enforced while reading, not after full buffering (F3, P4).
- **FR-015**: IG and profile changes MUST apply globally: there is exactly one validator
  configuration per supported FHIR version, shared by all consumers. Changing it MUST require
  administrator authorisation (FR-012) and MUST take effect atomically (FR-006) (B4).
- **FR-016**: Partial support for per-client custom validators (requests addressed by a client
  identifier instead of a FHIR version) MUST be removed. The only accepted validator address is a
  supported FHIR version, and any other address MUST be refused as an unsupported version. If
  per-client validators are needed later, they will be specified separately (B14).

**API behaviour (US4)**

- **FR-017**: Validation MUST accept standard HTTP content negotiation, including wildcards, absent
  headers and media-type parameters. It MUST answer in the negotiated format (B12, B13). The default,
  for an absent `Accept`, `*/*` or `application/json`, is the JSON validation response (`valid`,
  `messages`). `application/fhir+json`, `application/fhir+xml` and `application/xml` receive a FHIR
  `OperationOutcome`.
- **FR-018**: Responses MUST distinguish three outcomes: a completed validation, valid or invalid
  (success status with the `valid` flag and issues); a body that cannot be parsed as FHIR (client
  error); and the service being unable to validate (server error) (B16).
- **FR-019**: All endpoints MUST use one documented error format. Error bodies MUST NOT contain
  stack traces, exception class names or internal messages (B16, F9).
- **FR-020**: Every request MUST receive a response. Unknown packages MUST produce a "not found"
  answer, and unexpected failures MUST produce a server-error answer, within the configured request
  timeout (B10, B12).
- **FR-021**: The set of supported FHIR versions MUST be identical across validation, profile
  registration and IG inclusion. Unsupported versions MUST be refused, never silently mapped to
  another version (B15).
- **FR-022**: Profiles MUST be identified by their exact canonical URL and FHIR version.
  Different URLs MUST never share cached state (B17).
- **FR-023**: Endpoint responses MUST report accurate data: the actual package name and version
  (B11), and a 404 for unknown packages on read endpoints (B21). The conformance-report operation
  MUST either describe what it really checks or be removed from the contract (B21).

**Package lifecycle (US5)**

- **FR-024**: Package version labels up to 64 characters MUST be storable and retrievable (B8).
- **FR-025**: "latest" MUST resolve to the highest version under semantic-version ordering,
  preferring released over pre-release versions, regardless of upload order (B7, B19). Labels that
  don't follow semantic versioning (e.g. `current`, `dev`) sort below every semantic version. With
  registries disabled (offline), "latest" considers stored packages only.
- **FR-026**: When several registries are configured, a package MUST be fetched if any registry
  has it, stored once, and reported missing only when all registries report it missing or time out
  (B9, B19, P6). With registries disabled (offline), only stored packages are used: a package or
  dependency that is not stored is reported missing, and no registry is contacted.

**Operability (US6)**

- **FR-027**: The published container image MUST connect to an external shared database and MUST
  NOT embed one (F4). The release MUST include a tested migration guide for deployments of the
  previous image. It moves the data from the bundled database's volume into the external database
  (dump and restore) and ends with a verification step: the numbers of IG packages, profiles and
  validator configuration rows match before and after. No automatic migration is provided. The
  database connection's TLS mode MUST be configurable. The default (`prefer`) allows an unencrypted
  connection, so operator documentation MUST recommend `require` or `verify-full` whenever the
  database is reached over a network other than the local compose network.
- **FR-028**: All settings MUST be supplied through one configuration mechanism that environment
  variables can override. Documented settings that are not honoured MUST be removed or implemented
  (F6).
- **FR-029**: On termination, an instance MUST stop accepting new requests, complete in-flight
  requests within a configurable grace period, and release its connections (F7).
- **FR-030**: Logs MUST NOT contain credentials or fragments of credentials, and the service MUST
  NOT ship with usable default production credentials (B18). Validated resources can contain
  personal health information: their bodies MUST NOT be logged at INFO level or above
  (constitution, Security).
- **FR-031**: The service MUST expose metrics for request rate, latency distribution, validation
  outcome counts, concurrency/queue saturation and cache effectiveness. Each log line produced
  while serving a request MUST carry that request's correlation id (F5).

**Deployment artefacts (container image, compose, CI)**

- **FR-032**: The published container image MUST run only the service, as a non-root user, with
  the service process receiving termination signals directly. Port, JVM options and all settings
  MUST be configurable at run time, and the image build MUST succeed for x86_64 and arm64 (D1, D5,
  D6, D7, D11).
- **FR-033**: The provided local deployment (compose) MUST start a separate shared database and
  one or more service instances against it. It MUST publish no database or admin tooling on
  non-loopback interfaces by default, contain no committed secrets, and offer optional profiles
  for observability tooling and a multi-instance (load-balanced) setup (D2, D3, D4, D9).
- **FR-034**: Container health checks MUST use the readiness/liveness signals (FR-011) so that a
  transient database outage does not cause restart loops (D8).
- **FR-035**: Images MUST be published only after the automated test suite and a container smoke
  test pass. A CRITICAL finding in the image vulnerability scan, or a failed build for either
  platform, MUST block publishing. Release artefacts MUST be usable without a source checkout
  (D10).

### Key Entities

- **Validator configuration**: the set of IG packages (name + exact version, plus resolved
  dependencies) and custom profiles applied for one FHIR version; one global configuration per
  version. Has a version/revision so instances can tell when they are out of date.
- **IG package**: a published conformance package identified by name and version label, with its
  declared dependencies and source (upload, registry, approved URL).
- **Custom profile**: a StructureDefinition identified by canonical URL and FHIR version.
- **Approved package source**: an operator-configured registry or host from which packages may be
  downloaded.
- **Administrator / validation client**: a caller identity with a permission level (admin or
  validate-only). There is no tenant scope.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: In a two-instance test, 100% of validations made after a profile or IG change
  reflect that change, including the first request on each instance (fixture suite, all supported
  FHIR versions).
- **SC-002**: A profile or IG change made on one instance is effective on every other instance
  within 10 seconds, with no restart.
- **SC-003**: On a warm instance, validation throughput with 4 cores is at least 3× the throughput
  with 1 core for single-resource validations. 95% of single-resource validations complete in under
  200 ms at the configured concurrency limit. Reference workload: the `perf/validate.js` load test,
  which validates the 3 KB dk-core Patient fixture against R4 with its declared profile and no
  extra IGs included.
- **SC-004**: While a 20 MB IG is being uploaded or included, 95th-percentile latency of concurrent
  validation requests rises by no more than 20%, and every health/readiness check answers within
  1 second.
- **SC-005**: 0 requests are left without a response in the contract test suite, across all header
  variants, malformed inputs, unknown packages and simulated dependency failures.
- **SC-006**: 0 administrative operations succeed without administrator authorisation, and 0
  outbound connections are made to destinations outside the approved sources, in security tests.
- **SC-007**: A decompression-bomb archive (expanding to more than 100× the configured limit) is
  rejected, and the instance's memory use stays within 10% of its baseline during the attempt. The
  baseline is the heap in use immediately before the attempt, on a warm instance.
- **SC-008**: During a rolling restart between 1.x versions under steady load, fewer than 0.1% of
  requests fail. Steady load: 8 concurrent clients sending the SC-003 reference validation through
  the load balancer for at least 2 minutes, while each instance is restarted in turn
  (`scripts/rolling-restart.sh`). The one-time 0.1.x → 1.0.0 upgrade is excluded: it is a documented
  stop-migrate-start with planned downtime.
- **SC-009**: The 21 bugs (B1–B21), 7 performance issues (P1–P7), 10 flow issues (F1–F10) and 12
  deployment issues (D1–D12) in the audit each have a closing test or a recorded decision to
  defer. Every closed bug has a regression test that fails without the fix.
- **SC-010**: From a clean checkout, a single documented command brings up the database and a
  service instance that reports ready within 2 minutes on a developer machine: at least 4 CPU cores
  and 6 GB of memory available to the container engine (the default instance limit is 4 GB), with
  images already built or pulled (the first build or pull is excluded). A second documented command brings up two load-balanced
  instances sharing one database.
- **SC-011**: The metrics endpoint exposes every metric family FR-031 names (request rate, latency
  distribution, validation outcomes, queue saturation, cache effectiveness), and 100% of log lines
  written while serving a request carry that request's correlation id, in the observability tests.

## Assumptions

- **Scope**: this spec covers fixing the audited defects (bugs, performance, flow/security) and the
  minimum capability each fix needs, such as authorisation and readiness. New product features
  FT1–FT14 in the audit are out of scope and will be specified separately.
- **Tenancy** (clarified 2026-10-25): single-tenant model with one global validator configuration
  per FHIR version. Multi-tenant or per-request IG selection (FT3) and per-client validators are
  out of scope.
- **Deployment scope** (added at planning, 2026-10-25): the container build, compose files and
  CI publishing workflows are in scope alongside the code (FR-032–FR-035, audit section 3b).
- **Delivery**: user stories are independently deliverable and can ship as separate releases in
  priority order. P1 stories come first.
- **Authentication**: a standard credential mechanism suitable for service-to-service calls (API
  keys or OAuth2 client credentials) is acceptable. The exact mechanism is a planning decision. Read
  endpoints for health/readiness/metrics stay unauthenticated, but should be reachable only from
  the operator's network.
- **Infrastructure**: cross-instance propagation uses the existing shared database. No new
  infrastructure component is assumed (constitution Principles II and VIII). Three runtime
  libraries are added: Micrometer with its Prometheus registry (FR-031 metrics) and Vert.x
  contextual logging (FR-031 correlation ids), justified in research R13.
- **Deferred: credential management** (decided 2026-10-27): beyond the bootstrap administrator key
  (`FV_ADMIN_API_KEY`), there is no API for creating, rotating or revoking keys in 1.x. Operators
  manage the `api_keys` table with SQL, following the README procedure.
- **Deferred: audit trail** (decided 2026-10-27): who changed an IG or profile is not recorded in
  the database. Logs carry each change with its correlation id and the resulting revision.
- **Deferred: per-version limits on profiles and IGs** (decided 2026-10-27): there is no limit on
  the number of profiles or included IGs per version. Memory for IG sets is guarded per build
  (FR-008), and the counts are exposed as metrics.
- **Trust boundaries**: (1) clients reach the HTTP API, which may be on an untrusted network:
  administrative operations need an administrator credential (FR-012), and every input is bounded
  (FR-010, FR-014); (2) the health, readiness and metrics endpoints are anonymous and belong on the
  operator's network only; (3) package registries and download URLs are outside the trust boundary:
  only approved https hosts on public addresses are contacted (FR-013), and archives are checked
  before unpacking (FR-014); (4) the shared database is trusted and reached with operator-supplied
  credentials (FR-027, FR-030).
- **Schema rollback within 1.x**: schema migrations within 1.x are additive (new tables, new
  nullable columns), so an earlier 1.x release keeps working against a newer 1.x schema and a
  release can be rolled back without restoring the database. A migration that can't meet this
  requires a MAJOR version. Rolling back from 1.0.0 to 0.1.x follows the upgrade guide (keep the
  old volume).
- **Container targets**: the image size target and read-only root filesystem compatibility are
  contract-level targets (contracts/container.md), not success criteria.
- **Performance**: the performance targets use the constitution's provisional baseline (p95 <
  200 ms, warm validator) on a reference resource size of up to 50 KB.
- **Version labels**: 64 characters is assumed to cover registry version labels in practice
  (published labels are SemVer-like and much shorter). This is not measured against the whole
  registry: a longer label is refused as a client error, never truncated.
- **Package registries**: approved registries implement the FHIR package registry's npm-style
  API: `GET /{name}` lists a package's `versions` (and `dist-tags`), `GET /{name}/{version}`
  returns the archive, and an unknown package answers 404. "latest" is computed from `versions`,
  not taken from `dist-tags`.
- **Compatibility**: changes to status codes and error bodies for existing clients (FR-018,
  FR-019) are treated as breaking API changes. They ship with a MAJOR version bump and a migration
  note per constitution Principle IV.
- **Upgrade to 1.0.0** (clarified 2026-10-25): running 0.1.x and 1.0.0 instances against the same
  database at once isn't supported. The upgrade guide prescribes stopping all 0.1.x instances,
  migrating the data (FR-027), then starting 1.0.0. On startup, 1.0.0 applies schema migrations
  V3/V4 automatically.
- **Supported versions**: STU3, R4, R4B and R5 remain the supported FHIR versions. R6 is not
  supported until separately specified (FR-021).
