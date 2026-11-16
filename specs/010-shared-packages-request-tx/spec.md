# Feature Specification: Shared Package Definitions and Per-Request Terminology Settings

**Feature Branch**: `010-shared-packages-request-tx`

**Created**: 2026-11-16

**Status**: Draft

**Input**: User description: "Read files @var/shared_package_resources_research.md and @var/terminology_decoupling_plan.md
and create specification. Release version should be 1.x.x, despite if there has breaking changes."

**Background**: `specs/004-ig-scoped-validation/spec.md` (validator contexts, package replacement FR-025),
`specs/007-terminology-routing/spec.md` (terminology settings, allowlist, code cache), the research note
`var/shared_package_resources_research.md` and the plan `var/terminology_decoupling_plan.md`.

## Context

A validator context is expensive. Building one reads every package it contains and turns each file into in-memory
definitions. Two problems follow.

1. **Memory.** The same package is turned into definitions again for every context that contains it. Large
   packages that almost every context contains, such as the HL7 terminology package and the HL7 extensions package,
   and shared IGs such as US Core, therefore exist once per context. Measured memory use is 3.4 to 9 times the size
   of the unpacked packages. Only the raw package files are shared between contexts today; the core specification's
   definitions are already shared, and that works.
2. **Rebuilds for a terminology change.** The terminology setting (`none`, `server` or `ecosystem`, plus a server) is
   part of what a context is built from (spec 007 FR-004). Changing it rebuilds the whole context, which costs up to
   minutes of CPU and a large amount of memory, although no definition changes. It also means one context can only
   ever validate with one setting.

This feature makes a context hold definitions only, shares the definitions of a package between all contexts that
contain the same package content, and applies the terminology setting for each request. The context's setting
becomes the **default**: a caller can choose another allowed server, or no server, for one request, without a
rebuild.

**Release**: ships as **1.1.0** (a 1.x minor release), as the maintainer asked. The validate endpoint gains two
optional query parameters; without them, results are unchanged. Two behaviours of spec 007 change: a terminology
setting change no longer rebuilds the context (FR-004), and cached code results are separated by terminology scope
and context build rather than only by context (FR-018). Neither changes a path, payload or status code of an existing request. If planning finds a
change that is breaking under constitution Principle IV, it MUST be recorded under Complexity Tracking in `plan.md`
with the maintainer's approval and shipped in 1.x with a **Breaking** section and an upgrade guide, never as 2.0.

## Clarifications

### Session 2026-11-16 (decisions taken while writing the spec)

- Q: Which problem type answers a request that names a server that is not allowed? → A: The existing
  `tx-server-not-registered` (HTTP 400) from spec 007 FR-010, not a new type. While no registry list has ever been
  obtained, the existing 503 `tx-registry-unavailable` applies (spec 007 FR-012a). One rule for saved settings and
  request overrides is easier for callers.
- Q: Are the two parts one feature or two? → A: One feature with independent stories. Both change what a context is
  made of and how it is built, and share one release.

### Session 2026-11-16 (clarify)

- Q: Should sharing package definitions between contexts be on by default in 1.1.0? → A: Always on, with no
  operator switch (FR-022). The release is gated on the differential check against 1.0.0 (SC-004).
- Q: Who may choose a terminology server or mode for one request? → A: Any caller allowed to validate against the
  context (FR-009). No operator or per-context switch; the registry allowlist bounds the reachable servers.
- Q: What default size should the one per-instance code cache have? → A: 50,000 entries (FR-013); the setting
  `FV_TX_CACHE_SIZE` and its bounds (100 to 1,000,000) are unchanged.
- Q: How should a response show the terminology setting actually used? → A: Response headers only (`X-Tx-Mode`,
  `X-Tx-Server`, `X-Tx-Source`) on both response forms; the body and the OperationOutcome are unchanged (FR-010).
- Q: Do the memory and build-time targets gate the release? → A: No. The release gate is at least 20% less memory
  for the second context than 1.0.0; 40% memory and 50% build time are targets. Measured values are recorded in the
  PR (SC-005, SC-006). SC-004 (identical results) stays a hard gate.
- Q (implementation finding): Which IG pair do the sharing tests use? → A: DK Core 3.5.0 and MedCom Core 4.0.0
  (`hl7.fhir.dk.core#3.5.0`, `medcom.fhir.dk.core#4.0.0`), which share 10 packages (8.3 of 8.6 MB). US Core 7.0.0 and
  DK Core share only one tiny package, because every release pair depends on different terminology and extensions
  versions; they would measure nothing (US3, SC-004, SC-005).
- Q (planning finding): May two contexts share a cached code result for the same server? → A: No. A result can
  depend on the context's own code systems and value sets, so the cache key includes the context build (FR-012).

### Session 2026-11-16 (requirements review, checklists/review.md)

- Q: Which responses carry the `X-Tx-*` headers? → A: Every 200 validate response only (FR-010); refusals carry
  none.
- Q: What does `X-Tx-Server` show in mode `ecosystem`? → A: `tx` if given, else the context's server, else nothing;
  the registry's own default server is not reported, because routing picks a server per code system (FR-010).
- Q: Is a separate rate limit needed for caller-chosen servers? → A: No. The per-request time budget, admission
  limit, connections per server, cool-down after failures and the allowlist bound it (FR-009a).
- Q: What if sharing causes a result difference in production, given there is no switch? → A: Accepted risk. The fix
  is a 1.1.x patch that keeps the affected definitions private to each context; no hidden switch (Assumptions).
- Q: What happens during a rolling upgrade from 1.0.0? → A: 1.0.0 instances ignore `tx`/`txMode` and still rebuild on
  a setting change. Callers rely on the parameters only once every instance runs 1.1.0; the upgrade notes say so
  (FR-028a).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Change a context's terminology setting without a rebuild (Priority: P1)

An organisation that owns a context switches its terminology server from server A to server B. The next validation
request uses server B at once. The context is not rebuilt, so there is no wait, no CPU spike and no extra memory,
and no answer cached from server A is ever given for server B.

**Why this priority**: today every terminology change costs a full rebuild of minutes. This is the change that
makes a context a pure set of definitions, which all the other stories build on.

**Independent Test**: set a context to server A and validate a resource that needs a server. Change the setting to
server B and validate again. The second request reaches server B, no build is started or reported on any instance,
and the response does not reuse server A's answer.

**Acceptance Scenarios**:

1. **Given** a context set to `server` A with a warm code cache, **When** its owner changes the setting to `server`
   B, **Then** the next request on every instance (within the existing propagation window) reaches B, no context
   build is started, and no result cached from A is served.
2. **Given** a context set to `server` A, **When** its owner changes the setting to `none`, **Then** the next
   request makes no outbound terminology call, without a rebuild.
3. **Given** a context set to `none`, **When** its owner changes the setting to `ecosystem`, **Then** the next
   request routes codes through the registry, without a rebuild.
4. **Given** a context's server is removed from the registry allowlist, **When** the next request needs that server,
   **Then** terminology for that request fails closed exactly as spec 007 describes today (codes reported as not
   checked, definitions unaffected, HTTP 200), without a rebuild.

---

### User Story 2 - Choose the terminology setting for one request (Priority: P2)

A caller validates against a context and, for this request only, names a terminology server (`tx`) and/or a mode
(`txMode` = `none`, `server` or `ecosystem`). The context's own setting stays the default for every other request.
The same context serves all these requests without a rebuild.

**Why this priority**: callers today must ask the context owner to change the setting, or get a second context
built, just to check codes against another server or to validate offline. With a per-request choice, a single
context serves every terminology need.

**Independent Test**: on a context set to `server` A, send three requests on the same context: no parameters,
`tx=B`, and `txMode=none`. They reach A, reach B, and make no outbound call respectively. No build happens, and no
answer cached from A is served for B.

**Acceptance Scenarios**:

1. **Given** a context set to `server` A, **When** a request has neither `tx` nor `txMode`, **Then** it uses A,
   exactly as today.
2. **Given** a context set to `server` A, **When** a request has `tx=B` (B allowed), **Then** it uses `server` B for
   that request only.
3. **Given** a context set to `server` A, **When** a request has `txMode=none`, **Then** it makes no outbound
   terminology call.
4. **Given** a context with no setting (or the core-only base context), **When** a request has `txMode=ecosystem`,
   **Then** codes are routed through the registry, using its default server.
5. **Given** any context, **When** a request has `tx` set to a server not on the allowlist for the context's FHIR
   version, **Then** the request is refused with HTTP 400 `tx-server-not-registered` before validation starts, and
   no outbound call is made to that server.
6. **Given** any context, **When** a request has `txMode=none&tx=…`, or `txMode=server` with no server in the
   request or the context, or an unknown `txMode`, or a malformed or overlong `tx`, **Then** it is refused with
   HTTP 400 `invalid-request` and a curated message that does not echo the URL.
7. **Given** a request whose effective setting differs from the context default, **When** the response is returned,
   **Then** the response headers (and the usage record) show the effective setting and that it came from the
   request, and the response body is the same as it would be without the headers.

---

### User Story 3 - Contexts share the definitions of common packages (Priority: P3)

An operator runs several contexts for one FHIR version, for example DK Core and MedCom Core, which both contain DK Core
itself and the same versions of the HL7 terminology and extensions packages. Each package is turned into definitions once per instance and shared by every
context that contains the same package content. A context then costs about its own unique packages, plus small
per-context indexes, rather than all of its packages again.

**Why this priority**: it is the largest memory saving and makes more contexts per instance (and a future
multi-IG context) affordable. It is ranked below the terminology stories because results must stay exactly the
same, which needs a careful check (SC-004) before release.

**Independent Test**: include DK Core 3.5.0 and MedCom Core 4.0.0 as separate contexts on R4, on 1.0.0 and on this release. Compare
memory per context and the validation results of a fixed corpus (valid examples and invalid variants), run
concurrently on several threads. Memory per extra context drops; results are identical.

**Acceptance Scenarios**:

1. **Given** context X is built with package P, **When** context Y with the same package P (same content) is built
   on the same instance, **Then** Y reuses P's definitions without parsing P again.
2. **Given** P is replaced with new content under the same `name#version` (spec 004 FR-025), **When** contexts are
   rebuilt, **Then** they use the new content, and no context ever sees definitions of the old content mixed with
   the new.
3. **Given** contexts X and Y share P, **When** X is dropped or replaced, **Then** Y keeps working unchanged, and
   P's shared definitions are released once no context uses them any more.
4. **Given** a profile in P whose full form can only be completed with the help of other packages in the context,
   **When** X and Y complete it differently, **Then** each context sees only its own completed form; one context's
   result never leaks into another.
5. **Given** the same corpus, **When** it is validated by this release (sharing always on) and by 1.0.0 (no
   sharing), sequentially and concurrently, **Then** every OperationOutcome is identical.

---

### User Story 4 - Operators see and budget the shared memory (Priority: P4)

An operator can see how many package definition sets are shared, how often a build reused one, and how much memory
the shared sets hold. The memory budget for builds counts only what a build actually adds, so builds are not refused
for memory that is already in use and shared.

**Why this priority**: needed to run the feature safely, but it adds no validation behaviour.

**Independent Test**: build two contexts that share a large package. The metrics show one shared entry with one
reuse. The second build is admitted with a budget for its unique packages only.

**Acceptance Scenarios**:

1. **Given** a second context whose packages are all already shared, **When** it is built, **Then** the memory
   check reserves only the per-context part, and the build is not refused for lack of memory that a full reparse
   would have needed.
2. **Given** any running instance, **When** metrics are read, **Then** they report shared entries, reuses (hits),
   fresh parses (misses) and the estimated memory held by shared entries.

### Edge Cases

- Two contexts containing the same package are built at the same time: the package is parsed once; the second build
  waits for that parse and reuses its result, and never parses it in parallel.
- A parse of a shared package fails (corrupt file, out of memory): nothing half-parsed is kept. Each build waiting
  for it tries the parse in turn and fails the same way it would today.
- The last context using a package is dropped while another build is about to use it: the build either reuses the
  entry or parses the package again; it never sees a released or partial entry.
- A request names the same server as the context default (`tx=A` on a context set to A): it is treated as the
  default; the response shows the source as `request`.
- A request names a server in a different form (trailing slash, upper-case scheme): it is normalised with the same
  rules as saved settings before the allowlist check and the cache key.
- `txMode=ecosystem&tx=B`: B replaces the registry's default server for that request; B must be on the allowlist.
- A request override on a context whose own server has been removed from the allowlist: the override is used; the
  context's denied state applies only when the request uses the context default. Naming the removed server again in
  `tx` is refused with 400 `tx-server-not-registered`, like any other server that is not allowed.
- The registry list has never been obtained (fallback list in use): a `tx` not on the built-in fallback list is
  refused with 503 `tx-registry-unavailable`; `txMode=none` is always accepted.
- Warm-up of a new context runs with no terminology server, so warm-up never makes an outbound call, whatever the
  context default.
- A failing server named by one caller's override must not stop the context default server, or another context's
  server, from being used: failure tracking (cool-down) is per server, not per context.
- The per-request terminology time budget (spec 007 FR-019a) applies to the effective setting, whether it comes
  from the context or the request.
- A context's setting changes while a request is running: the running request finishes with the setting it started
  with.
- `tx=` or `txMode=` with an empty value (after trimming): refused with 400 `invalid-request`.
- A server removed from the allowlist while a request is running: the request keeps its setting, but the remaining
  calls to that server are refused before they are made and reported as not checked (warnings).
- Memory looks short for a build while shared entries that no context uses any more are not yet collected: a full
  collection runs before the build is refused, so released entries count as free memory.
- Rolling upgrade with 1.0.0 and 1.1.0 instances side by side: see FR-028a.

## Requirements *(mandatory)*

### Functional Requirements

**Contexts hold definitions only**

- **FR-001**: A context MUST be built only from its definitions (core specification, packages and dependencies).
  The terminology setting MUST NOT be part of what a context is built from, and changing it MUST NOT start a build
  on any instance. This amends spec 007 FR-004 (and its research note R12): a setting change takes effect on the
  next request on every instance within the existing propagation window.
- **FR-001a**: The answers of the context endpoints (include, change) MUST stay as in 1.0.0. A change of the
  terminology setting alone MUST NOT start a build; a "still applying" warning on such a change MAY only refer to the
  configuration reaching other instances, never to a build.
- **FR-002**: The configured setting of a context MUST still be stored, validated, owned and shown as spec 007
  describes (FR-002 to FR-005); it becomes the context's **default** setting.
- **FR-003**: Each validation request MUST use exactly one **effective** setting from start to end, fixed when the
  request starts. A setting change during the request MUST NOT affect it. The allowlist is still checked before
  every outbound call (spec 007 FR-010c): a server removed during a request gives "not checked" warnings for its
  remaining calls, without changing the request's setting.
- **FR-004**: Context warm-up MUST run with mode `none` and make no outbound terminology call.

**Per-request choice**

- **FR-005**: The validate endpoint MUST accept two optional query parameters: `tx` (a terminology server base
  address) and `txMode` (`none`, `server` or `ecosystem`, case-sensitive). The HTTP contract MUST document them.
- **FR-006**: The effective setting MUST be derived from the context default D (mode `none` for the core-only base
  context and for a context with no setting) and the request as follows:

  | txMode    | tx     | effective setting                                                                  |
  |-----------|--------|------------------------------------------------------------------------------------|
  | absent    | absent | D                                                                                  |
  | absent    | url    | D's mode if D is `server` or `ecosystem`, else `server`; server = url              |
  | server    | url    | `server` url                                                                       |
  | server    | absent | `server` with D's server if D has one; else 400 `invalid-request`                  |
  | ecosystem | url    | `ecosystem` with url replacing the registry's default server                       |
  | ecosystem | absent | `ecosystem` with D's server if D is `ecosystem`, else `ecosystem` (registry default) |
  | none      | absent | `none`: no outbound terminology call, even when D is `server` or `ecosystem`       |
  | none      | url    | 400 `invalid-request`                                                              |

- **FR-007**: `tx` MUST follow the same address rules as a saved setting (spec 007): http or https only, at most
  512 characters, no query, fragment or user information, surrounding whitespace trimmed, normalised the same way
  (spec 007 FR-009). A malformed `tx`, an unknown `txMode`, a value that is empty after trimming, or a repeated
  parameter MUST be refused with HTTP 400 `invalid-request` and a curated message that never echoes the address.
  (`tx-server-not-registered` names the normalised server the caller sent, as spec 007 does for saved settings; that
  is the caller's own input.) The `tx` value MUST be logged at DEBUG level only.
- **FR-008**: A server named in the request MUST be checked against the allowlist for the context's FHIR version on
  every request, before validation starts. An unlisted server MUST be refused with HTTP 400
  `tx-server-not-registered` (spec 007 FR-010), and while no registry list has ever been obtained, a server not on
  the built-in fallback list MUST be refused with HTTP 503 `tx-registry-unavailable` (spec 007 FR-012a). No
  outbound call MUST be made in either case. The 503 MUST carry the same retry hint as for saved settings. The
  public-address and no-redirect rules (spec 007 FR-011) and the per-call allowlist check (spec 007 FR-010c) still
  apply.
- **FR-008a**: A validate request MUST be checked in this order: (1) the existing checks of the FHIR version, media
  types, `profile` and the shape of `ig`; (2) the shape of `tx` and `txMode`; (3) a request body is present;
  (4) `ig` resolution and access (404, 424); (5) the effective setting and the allowlist (FR-006, FR-008);
  (6) context readiness (503 `not-ready`); (7) validation. So a caller who may not use a context never gets a
  terminology answer about it.
- **FR-009**: Using `tx`/`txMode` MUST need no extra permission beyond the right to validate against the context
  (spec 005). There MUST be no service-wide or per-context switch that turns overrides off. The validated resource
  MUST never be sent to the server (constitution, Security & Compliance); only codes, and the package definitions
  spec 007 FR-014 already sends, leave the service.
- **FR-009a**: No separate rate limit applies to caller-chosen servers. Outbound calls stay bounded by the
  per-request terminology time budget (spec 007 FR-019a), the validation admission limit, the connection limit per
  server, the cool-down after failures (spec 007 FR-019) and the allowlist.
- **FR-010**: Every 200 validate response (JSON summary and OperationOutcome form alike) MUST report the effective
  setting in response headers only: `X-Tx-Mode` (`none`, `server` or `ecosystem`), `X-Tx-Server` and `X-Tx-Source`
  (`request` or `context`). `X-Tx-Server` is the normalised server address: for `server`, the server used; for
  `ecosystem`, the `tx` value if given, else the context's server, else absent (the registry's own default server is
  not reported); absent for `none`. Refusals (4xx, 503) carry none of the three headers. Browsers MUST be able to
  read the headers (they are exposed through CORS). The response body, including the list of issues, MUST NOT
  change. The usage record MUST carry the same three values; they are empty for a request refused before
  validation starts. Context details MUST still show only the configured default.

**Allowlist on saved settings**

- **FR-011**: The allowlist check for a context's default setting MUST run when the setting is saved (unchanged),
  when it is applied on an instance, and whenever the allowlist changes. A default server that is no longer allowed
  MUST make terminology for requests using the default fail closed as spec 007 describes today (codes not checked,
  warning, HTTP 200), without a rebuild. This amends spec 007 FR-010(b), which ran the check at build time.

**Code results and server health**

- **FR-012**: Cached code check results MUST be separated by **terminology scope** and by context. The terminology
  scope of a request is `none`, `denied` (a context default that is no longer allowed), `server` with its address,
  or `ecosystem` with its default server (or none). The cache key MUST include the context (its current build), the
  terminology scope, FHIR version, code system, code, display and value set. Two requests MUST never share a result unless they used the same server and the same
  context build. The context is part of the key because a result can depend on the context's own code systems and
  value sets, which are sent to the server or checked locally. This amends spec 007 FR-018
  (results were separated by context and dropped at rebuild). A "not checked" answer MUST never be served from the
  cache to a later request.
- **FR-013**: The code cache MUST be one cache per instance, configured by spec 007's settings
  (`FV_TX_CACHE_SIZE`, bounds 100 to 1,000,000; `FV_TX_CACHE_TTL_MS`, default 10 minutes). The default size MUST
  change from 5000 (per context) to 50,000 (per instance), and the changelog and `.env.example` MUST say so,
  including an upgrade note that an explicitly set `FV_TX_CACHE_SIZE` now bounds the whole instance instead of each
  context. The cache is budgeted at 1 KB per entry (spec 007 research R15), 50 MB at the default. A degraded request
  MUST drop the cached entries of its own terminology scope only. Cache hit and miss metrics MUST keep working.
- **FR-014**: Definition lookups MUST stay cached for the life of a context build, as in 1.0.0, when code results
  move out of the context. Their cost is covered by the latency criterion SC-007.
- **FR-015**: Server failure tracking (timeouts, cool-down, connection limits) MUST be per server, so a failing
  server named by one request or context cannot block another server.
- **FR-016**: The per-request terminology time budget (spec 007 FR-019a) and the "not checked" warnings MUST apply
  to the effective setting unchanged.

**Shared package definitions**

- **FR-017**: Within one instance, the definitions of a package MUST be created once per package **content**
  (identified by `name#version` and the content hash) and shared by every context, of the same FHIR version, that
  contains that content. Different content under the same `name#version` (spec 004 FR-025) MUST be a different
  entry. A package with no recorded content hash MUST NOT be shared: it is parsed for its own context, as in 1.0.0.
- **FR-018**: Concurrent builds needing the same package content MUST never parse it at the same time: at most one
  parse runs at once, and a build that waited reuses the result. A failed parse MUST NOT leave an entry that a later
  build would reuse; each waiting build then tries the parse in turn and fails the way it would on its own.
- **FR-019**: A shared entry MUST stay available while any context uses it and MUST be released once none does; it
  MUST NOT be evicted while in use, and no reference counting by hand is required of operators.
- **FR-020**: A profile is **complete as shipped** when the package file already contains its full form (snapshot).
  Profiles whose full form a context completes during its build, and profiles that are still incomplete after the
  build, MUST be private to that context; only profiles complete as shipped MUST be shared. Every other resource
  type (value sets, code systems, search parameters and the rest) MUST be shared as parsed. Nothing a context derives
  from its own other packages may become visible to another context.
- **FR-021**: The order in which definitions are found, and which definition wins when two packages define the same
  canonical URL, MUST be the same as today (each context keeps its own lookup indexes, built in the same order).
- **FR-022**: Sharing MUST always be on. There MUST be no operator setting to turn it off. A release MUST NOT ship
  unless the differential check of SC-004 passes.
- **FR-023**: Validation results MUST be identical to 1.0.0 (no sharing), including under concurrent load
  (constitution Principle III). This covers every request without `tx`/`txMode`, in every mode; for `server` and
  `ecosystem` it holds given identical server answers (checked with a stub server).

**Memory budget and observability**

- **FR-024**: The memory check before a build MUST reserve memory only for package content not already shared on
  the instance, plus the per-context part (indexes, completed definitions, caches). The estimate of the per-context
  part MUST be documented in the plan and MUST NOT be lower than the per-context part measured for SC-005. The
  terminology code cache MUST be budgeted once per instance, not per build.
- **FR-025**: The service MUST expose metrics for shared package entries (count, unpacked bytes, and estimated
  retained memory using the factor measured for SC-005), reuses (hits), parses (misses) and parse
  failures, and for the code cache by FHIR version and outcome (hits, misses, drops). No server address may appear
  in a metric label, apart from spec 007's host-only call metrics (spec 007 FR-026).
- **FR-026**: Log lines about a build MUST state, per package, whether its definitions were reused or parsed.

**Contract and release**

- **FR-027**: The HTTP contract MUST add `tx` and `txMode` to the validate operation and the three response
  headers of FR-010, and MUST document the error cases of FR-006 to FR-008a. Requests without the new parameters
  MUST give the same status and body as in 1.0.0. The only observable differences are: no rebuild on a setting change
  (FR-001); the code cache's scope and size, so hit rates may differ (FR-012, FR-013); and the three new headers
  (FR-010).
- **FR-028**: The feature MUST ship as version 1.1.0, with a changelog entry describing the amended spec 007
  behaviours. Any change judged breaking MUST follow the Principle IV exception (Complexity Tracking, maintainer
  approval, **Breaking** section, `docs/upgrade-1.1.0.md`) and MUST NOT bump the major version.
- **FR-028a**: The release notes MUST describe a rolling upgrade from 1.0.0: instances still on 1.0.0 ignore `tx` and
  `txMode` and still rebuild a context when its setting changes. Callers should rely on `tx`/`txMode` only once every
  instance runs 1.1.0.

### Key Entities

- **Context default setting**: the terminology mode and optional server stored with a context (spec 007). No longer
  part of the context's build identity.
- **Request terminology override**: the optional `tx` and `txMode` of one validate request.
- **Effective terminology setting**: the mode, server and source (`request` or `context`) used for one request,
  fixed at its start.
- **Shared package definitions**: the definitions of one package content (`name#version` + content hash) for one
  FHIR version, created once per instance and used by every context that contains it; released when unused.
- **Context-private definitions**: definitions a context completed itself, or that remain incomplete, plus the
  context's lookup indexes. Never shared.
- **Terminology scope**: the identity of a request's effective setting for caching: `none`, `denied`, `server` with
  its address, or `ecosystem` with its default server (or none).
- **Code result cache entry**: a cached code check result, keyed by context build, terminology scope, FHIR version,
  code system, code, display and value set. "Not checked" answers are never served from it.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Changing a context's terminology setting takes effect on the next request on every instance within the
  existing propagation window, with zero context builds and no change in instance memory caused by a build.
- **SC-002**: On one context, requests with no override, with `tx=B`, and with `txMode=none` reach A, reach B, and
  make zero outbound calls respectively, with zero builds, and 0 results cached for one server are served for
  another.
- **SC-003**: Every row of the resolution table and every listed invalid input gives the stated effective setting
  or error; a request naming an unlisted server makes zero outbound calls.
- **SC-004**: A fixed corpus validated on R4 by this release and by 1.0.0, sequentially and with at least 8
  concurrent threads across both contexts, gives identical OperationOutcomes: each issue's severity, code, location
  and message are equal, in the same order (the OperationOutcome's id, meta and narrative are ignored). The 1.0.0
  results are recorded once from the v1.0.0 tag and kept with the tests. The corpus is every example in the DK Core
  3.5.0 and MedCom Core 4.0.0 packages, plus at least 50 invalid variants covering wrong codes, missing required elements, slicing,
  extensions and profiles that don't match, value set and code system checks, and a canonical URL defined by two
  packages of one context.
- **SC-005**: With DK Core 3.5.0 and MedCom Core 4.0.0 as separate R4 contexts, the memory held by the second context is at least
  20% lower than on 1.0.0 (release gate); the target is 40% lower. "Memory held by the second context" is the
  increase in live heap after a full collection when the second context is added to an instance that already has
  the first, with the same JVM settings on both releases. The measured value is recorded in the PR.
- **SC-006**: Building a second context whose packages are all already shared takes less time than building it on
  1.0.0; the target is 50% less. It is measured on a warm instance, with the packages already in the raw package
  cache and the first context already built, from the start of the build until the context is ready. The measured
  value is recorded in the PR (not a release gate).
- **SC-007**: p95 latency of single-resource validation on a warm context, with no terminology server, stays within
  5% of 1.0.0 (constitution Principle VI), measured with the existing validation benchmark (`perf/validate.js`)
  using the same load profile and container limits as the 1.0.0 baseline.
- **SC-008**: After every context that uses package P is excluded and a full collection has run, the shared-entries
  metric no longer counts P within one minute.

## Assumptions

- The terminology setting choices and server rules of spec 007 (modes, address rules, registry allowlist, built-in
  fallback, budgets, timeouts) are kept; only where and when they are applied changes.
- Shared entries are per instance and in memory only; they are derived caches that each instance rebuilds from
  stored packages (constitution Principle II). No new shared infrastructure is needed; the only database change is
  additive: three nullable usage-record fields (FR-010).
- Only contexts of the same FHIR version share package definitions; packages are not converted between versions.
- The core specification's definitions are already shared per FHIR version and stay as they are.
- Definitions complete as shipped do not hold context-dependent data after a validation. Where the validation
  engine stores derived data on a definition during validation, that data depends only on the definition itself.
  This assumption MUST be confirmed by the differential check (SC-004) before release, since sharing cannot be
  turned off. Beyond SC-004 it is an **accepted risk**: if sharing is found to cause a result difference in
  production, the fix is a 1.1.x patch release that keeps the affected definitions private to each context. There is
  no hidden switch.
- Any caller that may validate against a context may also choose a terminology setting for that request; no new
  permission or organisation-level restriction is added. Operators who want to block overrides can rely on the
  allowlist, which already bounds the reachable servers.
- A combined multi-IG context is out of scope; this feature only removes obstacles to it.
- Version 1.1.0 is the release target, as the maintainer asked; the major version stays 1.
