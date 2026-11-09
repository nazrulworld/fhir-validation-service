# Feature Specification: Terminology Servers per IG Context, with Ecosystem Routing, and Included-Only Validation Contexts

**Feature Branch**: `007-terminology-routing`

**Created**: 2026-11-09

**Status**: Draft

**Input**: User description: "@var/terminology_routing_plan.md, moreover the auto inclusion of IG (snapshot building)
should be removed from ValidationApi::handleDoValidation". In summary: (1) let each included IG context name a
terminology server, or route each code system to the server the official HL7 terminology registry names for it,
with the official registry as a strict allowlist of every server the service may contact; (2) stop building
validator contexts on demand when a validation request names an IG package that isn't included.

**Background**: `specs/003-ig-only-profile-resolution/spec.md` (no network access while validating: FR-002,
SC-002), `specs/004-ig-scoped-validation/spec.md` (one context per IG package and FHIR version; included contexts
are pinned, any other stored package is built on demand at first use: FR-010 to FR-018), `specs/005-organisation-ownership/spec.md`
(owner organisation and shareable flag per included context; access rule), `specs/006-ig-version-series/spec.md`.
The technical outline is `var/terminology_routing_plan.md`.

## Context

Today every validator context is fully offline. Codes are checked only against code systems shipped in the IG
packages and a small set of common code systems built into the service. Codes from large external code systems
(LOINC, SNOMED CT, RxNorm and so on) can't be checked, so validation either skips them or reports that they could
not be verified. The official HL7 validator solves this by asking a terminology server, and by default it asks the
HL7 terminology registry which server is responsible for each code system ("ecosystem routing").

Organisations validating against an IG want the same result from this service, but the service is multi-tenant and
must not become a way to make it call arbitrary hosts. The rule chosen is strict: the only terminology servers the
service may ever contact are open servers listed by the official HL7 terminology registry for the matching FHIR
version, with no operator or per-organisation exceptions. Offline remains the default.

Terminology behaviour is a property of the validator context: it is part of what the context is built from, so
changing it rebuilds the context and a built context never changes. That only works cleanly if every non-base
context is one that someone deliberately included and configured. Today a request may name any stored package in
`ig`, and the service silently builds an unpinned, unconfigured context for it on demand. This feature removes that
path: a request naming an IG package is served only by an included context.

## Clarifications

### Decisions taken from the user before specification

- Q: Where does the terminology setting live? → A: On each IG context. It is part of what the context is built
  from, so changing it rebuilds the context; built contexts stay immutable.
- Q: Which servers may be contacted? → A: Only servers listed by the official HL7 terminology registry
  (`https://ecosystem.hl7.org/tx-reg`) for the context's FHIR version, strictly, with no operator or per-context
  exceptions. The default mode is "none", which keeps today's fully offline behaviour.
- Q: Should naming a stored but not-included IG in a validation request still build a context on demand? → A: No.
  On-demand building from validation requests is removed; only included contexts (and the base context) serve
  validation.

### Session 2026-11-09

- Q: When a validation request names an IG package that is stored and visible to the caller but not included for
  that FHIR version, what HTTP answer should it get? → A: HTTP 400 `invalid-request`, with a detail telling the
  client to include the IG first (FR-022).
- Q: When a code must be checked against a value set the IG defines and only a terminology server can decide it,
  should the service send the IG's own value set definition or rely on the server's copy? → A: Send the IG's
  definition whenever a package in the context defines the value set; use the server's copy only for value sets no
  package defines (FR-014).
- Q: Should one validation request have a limit on how much terminology-server work it can cause? → A: Yes, a total
  terminology time budget per request (configurable, default 10 seconds); once spent, remaining codes needing a
  server are reported as not checked (warning) and the request still answers HTTP 200 (FR-019a).
- Q: If the service has never obtained the registry's server list, what happens when an owner saves a "server" or
  "ecosystem" setting? → A: The service ships a built-in fallback list of approved registry servers (fixed in the
  release, not configurable). With no registry list, a server on the built-in list for that FHIR version is accepted
  and used; any other server is refused with HTTP 503 `tx-registry-unavailable` and a retry hint. Saving mode
  `none` always works (FR-012, FR-012a).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Validate codes from external code systems through the registry (Priority: P1)

An organisation includes an IG for R4 and sets its context's terminology mode to "ecosystem". A client validates an
Observation coded with LOINC against that IG. The service asks the official registry which server handles LOINC,
checks the code with that server, and reports a wrong code as an error and an unknown display as the server says,
just as the official HL7 validator would.

**Why this priority**: This is the reason for the feature: code checks for external code systems that packages
don't ship.

**Independent Test**: Include an IG, set its terminology mode to ecosystem, validate one resource with a valid and
one with an invalid LOINC code, and compare the outcomes with the official validator's.

**Acceptance Scenarios**:

1. **Given** an included R4 context in ecosystem mode and a registry naming server S for LOINC, **When** a client
   validates an Observation with an invalid LOINC code, **Then** the outcome contains an error for that code
   reported from S, and the answer is HTTP 200.
2. **Given** the same context, **When** the same code is validated again within the cache lifetime, **Then** the
   result is the same and no new request reaches S.
3. **Given** the same context, **When** a code comes from a code system that a package in the context defines
   completely, **Then** it is checked locally and no terminology server is contacted.
4. **Given** the registry names no allowlisted server for a code system, **When** a code from it is validated,
   **Then** the general server (the context's own server, or the registry's default server) is used, and if there
   is none, the code is reported as not checked (a warning), never as invalid.
5. **Given** the IG defines value set V (unknown to the server, or held there in another version) binding LOINC codes,
   **When** a code is validated against V, **Then** the server receives the IG's definition of V and the result
   follows the IG's version of V.

---

### User Story 2 - Only official, open registry servers are ever contacted (Priority: P1)

A platform operator needs assurance that no organisation can make the service call an internal or arbitrary host.
Whatever a context is configured with, and whatever the registry's answers contain, the service only contacts open
servers that the official registry lists for the same FHIR version, on public addresses.

**Why this priority**: Without it the feature is a server-side request forgery risk in a multi-tenant service; it
must ship with User Story 1.

**Independent Test**: Try to configure contexts with unlisted, private-network and secured servers, and feed a
registry answer that names an unlisted server; confirm every attempt is refused and no request leaves the service.

**Acceptance Scenarios**:

1. **Given** a server not listed by the registry for R4, **When** an owner sets it as a context's terminology
   server, **Then** the request is refused with HTTP 400 `tx-server-not-registered`, naming the registry address,
   and the context is unchanged.
2. **Given** a registry entry whose last check failed, or that needs credentials, **When** it is set as a context's
   server, **Then** it is refused the same way.
3. **Given** a registry routing answer that names a server missing from the registry's server list, **When** a
   code is routed, **Then** that server is skipped and never contacted.
4. **Given** a listed server whose host name resolves to a private or loopback address, **When** any call to it is
   about to be made, **Then** it is refused and the code is reported as not checked.
5. **Given** a terminology server answering with a redirect, **When** a code is checked, **Then** the redirect is
   not followed.
6. **Given** the service has never obtained the registry's server list (unreachable since first start), **When**
   a context in "server" or "ecosystem" mode validates, **Then** only servers on the built-in fallback list for
   that FHIR version are contacted, codes that would need any other server are reported as not checked, and the
   health report says the built-in fallback list is in use.
7. **Given** the service has never obtained the registry's server list, **When** an owner saves a setting naming a
   server on the built-in fallback list, **Then** it is accepted; **When** the server is not on that list, **Then**
   it is refused with HTTP 503 `tx-registry-unavailable` and a retry hint, and the context is unchanged.

---

### User Story 3 - One fixed terminology server per context (Priority: P2)

An organisation wants all codes in its context checked by one particular registered server (for example its
national server), like the official validator's `-tx <url>` option without routing. It sets the context's mode to
"server" with that server's address.

**Why this priority**: A common, simpler alternative to routing; it reuses the allowlist and caching of P1.

**Independent Test**: Set a context to server mode with a registered server, validate coded resources, and confirm
every terminology call goes to that server only.

**Acceptance Scenarios**:

1. **Given** a context in server mode with registered server T, **When** codes from several external code systems
   are validated, **Then** all terminology calls go to T and to no other host.
2. **Given** a context in ecosystem mode with registered server T set as its own server, **When** the registry
   answer names the registry's default server, **Then** T is used in its place.
3. **Given** server mode with no server address, **When** the setting is saved, **Then** it is refused with HTTP
   400 `invalid-request`.

---

### User Story 4 - Changing terminology settings rebuilds the context (Priority: P2)

The owner of an included context changes its terminology mode or server. Every instance rebuilds the context with
the new setting, requests keep being served by the old context until the new one is ready, and answers are never
mixed between the two settings.

**Why this priority**: Required for correctness of cached results and for the immutability of contexts, but builds
on the existing rebuild path.

**Independent Test**: Change a context from none to ecosystem and back, and observe the rebuild on every instance
and the change in outcomes for a coded resource.

**Acceptance Scenarios**:

1. **Given** an included context in none mode, **When** its owner sets ecosystem mode, **Then** every instance
   rebuilds it within the existing propagation window, and later validations check external codes.
2. **Given** a context's terminology setting, **When** the context's information is read, **Then** the setting
   (mode and server) is shown.
3. **Given** a caller whose organisation neither owns the context nor is the system organisation, **When** it
   tries to change the setting, **Then** it is refused as if the context didn't exist (spec 005 rule).
4. **Given** a context whose setting was saved when its server was registered, **When** the server later leaves
   the registry, **Then** the next build of that context fails closed for terminology (no calls to it; codes
   reported as not checked) and the context still serves validation.

---

### User Story 5 - Validation requests no longer build IG contexts on demand (Priority: P1)

A client names an IG package in a validation request. If that package version is included for the FHIR version
(and the caller may use it), the included context validates. If it is stored but not included, the request is
refused with a clear message telling the client the IG must be included first; no context is built. The
administrator, not the validation traffic, decides which contexts exist.

**Why this priority**: Terminology settings exist only on included contexts; an on-demand context would have no
owner-chosen setting. It also removes unbounded, request-triggered builds.

**Independent Test**: Validate with `ig` naming an included package (200), a stored but not-included package
(400 `invalid-request`, no build started), and a package the caller can't see (404 as today).

**Acceptance Scenarios**:

1. **Given** `P#1.0.0` is stored, usable by the caller, and not included for R4, **When** the caller validates
   with `ig=P#1.0.0`, **Then** the answer is HTTP 400 `invalid-request` telling the client to include the IG
   first, and no context build is started on any instance.
2. **Given** `P#1.0.0` is included for R4, **When** the caller validates with `ig=P#1.0.0`, **Then** the included
   context answers as today (HTTP 200, `X-Validation-Context: P#1.0.0`).
3. **Given** `P#1.0.0` and `P#2.0.0` are stored and only `P#1.0.0` is included, **When** the caller validates with
   `ig=P` or `ig=P#latest`, **Then** the version used is `P#1.0.0`, the highest included version the caller may use.
4. **Given** a package the caller may not use, or that isn't stored, **When** it is named in `ig`, **Then** the
   answer stays HTTP 404 `package-not-found`, indistinguishable from not stored (spec 005 FR-012).
5. **Given** an included context that is being rebuilt (stale or not yet ready), **When** it is named, **Then**
   the existing not-ready behaviour applies (HTTP 503 with a retry hint).
6. **Given** an IG is excluded, **When** a request names it afterwards, **Then** the answer is HTTP 400 `invalid-request`
   (not an on-demand build, as spec 004 allowed).

---

### Edge Cases

- **Registry unreachable at a refresh**: the last good server list keeps being used, also across restarts; only
  with no registry list at all is the built-in fallback list used, and servers not on it are not contacted.
- **Built-in fallback list vs registry list**: once a registry list has been obtained it alone decides; a server
  on the built-in list but missing from (or failing in) the registry list is not contacted.
- **Registry list changes**: a server that leaves the list is no longer contacted from the next refresh on, even by
  contexts already built with it; a context whose own server left the list reports the codes as not checked.
- **Terminology server down or slow**: each call is bounded by a timeout; after repeated failures the server is
  skipped for a cool-down period. Affected codes are reported as a warning ("terminology server unavailable"),
  never as an error, and the validation still returns HTTP 200.
- **Routing answer failure** (registry `/resolve` down or malformed): treated as "no routing answer" for that code
  system; the failure is remembered only briefly (minutes), so a short outage isn't kept for a day.
- **Registry answer format changes** (unknown format version): treated as a failure, not guessed.
- **FHIR version mismatch**: a server listed only for R5 is never used for an R4 context, and the reverse.
- **Base context**: always offline; it has no terminology setting.
- **STU3 and R4B**: the official registry lists no servers for these versions, so while a registry list exists,
  every `server` or `ecosystem` setting for them is refused with HTTP 400 `tx-server-not-registered`, and the
  built-in fallback list has no entries for them. They stay in mode `none` until the registry lists a server.
- **Warm-up after build**: the warm-up validation makes no terminology calls.
- **Large resources**: a Bundle with thousands of uncached external codes stops calling servers once its request's
  terminology time budget is spent; the rest are reported as not checked, not as invalid.
- **Mode none**: zero outbound requests while validating, exactly as today (spec 003 SC-002 still holds for it).
- **Upgrade**: existing included contexts get mode none and behave exactly as before.
- **Requests currently relying on on-demand contexts**: after upgrade they receive HTTP 400 `invalid-request` until the IG is
  included; the release notes call this out as a breaking change.
- **`ig` with a version series** (`1.2.x`): still 400 `invalid-request` (spec 006 FR-018), unchanged.

## Requirements *(mandatory)*

### Functional Requirements

**Terminology setting per context**

- **FR-001**: Every included IG context MUST have a terminology setting with a mode of `none` (default),
  `server` or `ecosystem`, and an optional server address. The base context MUST always behave as `none` and has
  no setting.
- **FR-002**: Mode `server` MUST require a server address; mode `ecosystem` MAY have one, which then replaces the
  registry's default server wherever a routing answer names it; mode `none` MUST NOT have one.
- **FR-003**: The terminology setting MUST be settable when an IG is included and changeable afterwards by the
  context's owner organisation or the system organisation (spec 005 FR-019 rule); any other caller MUST be refused
  as if the context didn't exist.
- **FR-004**: The setting MUST be stored with the shared configuration, shown wherever the context's information is
  shown, and MUST be part of what a context is built from: changing it MUST rebuild the context on every instance
  within the existing propagation window, with the old context serving until the new one is ready.
- **FR-005**: Upgrading MUST give every existing included context mode `none`.

**Allowlist from the official registry**

- **FR-006**: The registry address MUST be fixed to the official HL7 terminology registry
  (`https://ecosystem.hl7.org/tx-reg`) and MUST NOT be configurable by operators, organisations or contexts.
- **FR-007**: The service MUST obtain the registry's server list for each supported FHIR version at start and then
  periodically (configurable interval, default 6 hours), and MUST keep the last good list in memory and in shared
  storage so that it survives restarts while the registry is unreachable.
- **FR-008**: A server MUST be allowlisted for a FHIR version only when the registry lists it for that FHIR version,
  its last registry check succeeded, and it needs no credentials (open access). Servers needing credentials are out
  of scope.
- **FR-009**: Matching MUST be exact on the normalised base address (scheme, host, port and path; scheme
  lower-case; no trailing slash).
- **FR-010**: The allowlist MUST be enforced (a) when a setting is saved, refusing an unlisted server with HTTP 400
  `tx-server-not-registered` naming the registry address, (b) when a context is built, and (c) immediately before
  every outbound terminology call, including every server named in a routing answer.
- **FR-011**: Every outbound terminology call MUST be refused when the server's address resolves to a private,
  loopback, link-local or otherwise non-public address, and terminology calls MUST NOT follow redirects.
- **FR-012**: The service MUST ship a built-in fallback list of approved servers from the official registry, per
  FHIR version, fixed in each release and not configurable by operators, organisations or contexts. It MUST be used
  as the allowlist only while no registry list has ever been obtained (none in memory or shared storage); as soon
  as one is obtained, the registry list alone applies. Built-in entries are subject to the same FHIR version match,
  public-address and no-redirect rules (FR-009, FR-011). While the fallback list is in use, contexts MUST NOT
  contact any server outside it, and the health report MUST state that the built-in fallback list is in use.
- **FR-012a**: While no registry list has ever been obtained, saving a setting whose server is not on the built-in
  fallback list for the FHIR version MUST be refused with HTTP 503 `tx-registry-unavailable` and a retry hint
  (instead of 400 `tx-server-not-registered`), leaving the context unchanged. Saving mode `none` MUST always be
  accepted.

**Routing and checking codes**

- **FR-013**: Codes MUST first be checked locally (code systems defined completely in the context's packages, and
  the built-in common code systems); only codes that can't be decided locally MAY reach a terminology server.
- **FR-014**: Terminology servers MUST only be used to check codes (code validation, code validation in a value
  set, code lookup, and whether a code system or value set is supported, the last only so that value set checks
  reach the right module; no definition is ever fetched). Profiles, value sets and code systems MUST still come
  only from the context's packages (spec 003 FR-002 holds). When a code is checked against a value set that a
  package in the context defines, the request to the server MUST carry that package's value set definition, so
  membership is decided by the IG's content; the server's own copy MAY be used only for value sets no package in the
  context defines.
- **FR-015**: In mode `server`, every terminology call MUST go to the context's server.
- **FR-016**: In mode `ecosystem`, the server for a code system MUST be chosen in this order: an allowlisted
  server the registry names as authoritative; else an allowlisted candidate server, preferring complete content
  over fragments and ignoring example, supplement or not-present content; else the context's own server, or the
  registry's default server; else none (the code is reported as not checked). Servers for another FHIR version, or
  needing credentials, MUST be skipped.
- **FR-017**: Routing answers MUST be remembered per FHIR version and code system: successful answers for 24 hours,
  failed lookups for at most 5 minutes.
- **FR-018**: Code check results MUST be cached per context (configurable size and lifetime, defaults 5000 entries
  and 10 minutes) and MUST be discarded when the context is rebuilt, so results are never shared between settings.
- **FR-019**: Each terminology call MUST be bounded by a configurable timeout. A server that keeps failing MUST be
  skipped for a cool-down period. An unavailable server MUST produce a warning ("terminology server unavailable")
  on the affected element, never an error, and the validation MUST still complete with HTTP 200.
- **FR-019a**: Each validation request MUST have a total terminology time budget (configurable, default 10 seconds)
  covering all of its terminology server calls. Once the budget is spent, no further server calls MUST be made for
  that request; remaining codes that need a server MUST be reported as not checked (warning, stating that the
  request's terminology time budget was used up), and the validation MUST still complete with HTTP 200. Locally
  decided and cached codes MUST NOT count against the budget.
- **FR-020**: This feature amends spec 003 SC-002 and spec 004 FR-013: for a context in mode `server` or
  `ecosystem`, validation MAY wait on terminology servers for at most the configured timeout per call; for mode
  `none` and the base context, the existing "no outbound requests, no network waits" rules are unchanged.

**Included-only validation contexts**

- **FR-021**: A validation request naming an IG package MUST be served only by an included context for that FHIR
  version. Validation requests MUST NOT start building a context (this removes spec 004 FR-010 to FR-012, FR-014 to
  FR-016 for on-demand contexts, and the on-demand parts of spec 005 FR-013 and FR-015).
- **FR-022**: When `ig` names a stored package version the caller may use but that isn't included for the FHIR
  version, the request MUST be refused with HTTP 400 `invalid-request`, whose detail names the concrete
  `name#version` and tells the client to include the IG first (no separate problem type). For a versionless `ig`
  (`name` or `name#latest`) with no included version, the detail names the highest stored version the caller may
  use.
  When the package isn't stored or the caller may not use it, the answer MUST stay HTTP 404 `package-not-found`
  (spec 005 FR-012: no disclosure).
- **FR-023**: `ig=name` and `ig=name#latest` MUST resolve to the highest included version of `name` the caller may
  use; if none is included, FR-022 applies.
- **FR-024**: The request check order of spec 004 FR-009 MUST be kept, with "context readiness and preparation"
  replaced by "context readiness" (an included context not yet ready answers 503 with a retry hint, as today).
- **FR-025**: Status reports MUST no longer list on-demand contexts; the related limits (maximum on-demand contexts,
  preparation wait for on-demand builds) MUST be retired, and a configuration that still sets them MUST start with a
  logged deprecation warning rather than fail.

**Observability**

- **FR-026**: The service MUST expose counts of terminology calls per server and outcome (success, failure,
  timeout, refused by allowlist), cache hits, and the age of the registry server list; client-supplied codes MUST
  only be logged at debug level.

### Key Entities

- **Terminology setting**: belongs to one included context (IG package version and FHIR version); mode (none,
  server, ecosystem) and optional server address; part of the context's build identity.
- **Registry server list**: per FHIR version, the open, healthy servers the official registry lists, with the time
  the registry last checked them and the time the service obtained the list; the last good list is kept in shared
  storage.
- **Built-in fallback list**: per FHIR version, approved registry servers fixed in the release; the allowlist only
  until a registry list has been obtained.
- **Routing decision**: per FHIR version and code system, the chosen server (or none) and when the decision
  expires.
- **Included context** (existing, spec 004/005): now the only kind of IG context; gains the terminology setting.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: For a reference set of R4 resources with LOINC and SNOMED CT codes, a context in ecosystem mode
  reports the same code errors as the official HL7 validator in its default (ecosystem) mode.
- **SC-002**: In security tests (unlisted servers, private addresses, secured servers, unlisted servers inside
  routing answers, redirects), 0 outbound requests reach a host outside the registry's open server list for the
  context's FHIR version (or outside the built-in fallback list while no registry list has been obtained).
- **SC-003**: A context in mode none, and the base context, make 0 outbound requests while serving validation
  (spec 003 SC-002 kept for them).
- **SC-004**: A repeated validation of the same coded resource within the cache lifetime makes 0 additional
  terminology calls.
- **SC-005**: With the terminology server unreachable, validation still answers HTTP 200 within the configured
  timeout plus normal validation time, with the affected codes reported as warnings.
- **SC-005a**: No validation request spends more than its terminology time budget (default 10 seconds) plus one
  per-call timeout waiting on terminology servers, whatever the number of codes it contains.
- **SC-006**: A change of terminology setting is in effect on every instance within the existing propagation
  window (`001-service-hardening` SC-002).
- **SC-007**: 0 context builds are started by validation requests; a request naming a stored, usable, not-included
  IG is answered HTTP 400 `invalid-request` in the same time as any other request-shape refusal (no wait on a build).
- **SC-008** (performance, constitution VI): for mode `none` and the base context, single-resource validation p95
  stays within run-to-run noise of 3.2.1 (target < 200 ms on a warm context) and throughput is unchanged. For
  terminology modes, a code answered locally or from the cache adds no measurable latency; an uncached code adds one
  terminology round trip, bounded by the per-call timeout (default 5 seconds) and by the request's terminology
  budget (default 10 seconds).
- **SC-009** (memory, constitution VI): each context not in mode `none` uses at most about 5 MB more heap at the
  default cache size (5000 code results); the process-wide routing cache, allowlist and server connections add at
  most about 5 MB in total; mode `none` contexts and the base context use no additional memory. Removing on-demand
  contexts lowers peak memory, because only included contexts are ever built.

## Assumptions

- Only the five terminology operations listed in FR-014 are needed; expanding value sets on a server is out of
  scope.
- Servers needing an API key or token are out of scope for this version; supporting them later means storing
  credentials per context.
- The registry's discovery and resolve answers follow the published registry interface (format version "1"); any
  other format is treated as unavailable.
- The base context stays offline; giving it a terminology setting is out of scope.
- The not-included answer (HTTP 400 `invalid-request`) is only returned when the caller may already see the package,
  so it discloses nothing new; packages the caller can't see still get 404 `package-not-found`.
- Removing on-demand contexts is a breaking change for clients that relied on naming any stored package; it ships in
  a new major release with an upgrade note.
- A documentation note amends the constitution's offline statement for terminology-enabled contexts.
- Tests use a stub registry and stub terminology server on the local machine through a test-only construction path;
  production has no setting that changes the registry address or permits private addresses.
