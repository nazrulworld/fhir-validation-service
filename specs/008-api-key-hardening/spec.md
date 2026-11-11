# Feature Specification: API Key Hardening, Key-Only Validation, Usage Accounting and Well-Known Organisations

**Feature Branch**: `008-api-key-hardening`

**Created**: 2026-11-11

**Status**: Draft

**Input**: User description: "@var/api_key_hardening_plan.md". In summary: (1) harden API keys (cheap rejection of
malformed tokens, an admin network allowlist, trusted-proxy client address resolution, key expiry, last-used address
audit); (2) make every validation and package-read request require a key; (3) measure each validation cycle (time,
CPU, allocated memory) and attribute it to the calling key and organisation; (4) give the system and trial
organisations fixed well-known identifiers and install an operator-supplied trial validator key.

**Background**: `specs/001-service-hardening/spec.md` (API keys, roles, bootstrap key),
`specs/005-organisation-ownership/spec.md` (organisations, ownership of keys, IGs and contexts),
`specs/007-terminology-routing/spec.md`. The technical outline is `var/api_key_hardening_plan.md`.

## Context

API keys are bearer secrets with a role (ADMIN or VALIDATE) and an owning organisation. Today a leaked key works
from anywhere, forever, until someone revokes it. Any random bearer string costs a key lookup. The service does not
understand reverse proxies, so it cannot tell where a request really came from. Validation is anonymous by default,
and nothing records who used how much CPU, memory or time, so the cost of the service cannot be attributed to the
organisations that use it.

Separately, the system organisation's identifier does not match the well-known identifier the project has now
fixed, and there is no well-known trial organisation for a free validation offering.

Per-key IP binding was considered and dropped. Administrative traffic is restricted at the network level instead.

This feature ships in the next 4.x minor release, although some of its changes are breaking (validation needs a
key; the anonymous-validation setting is removed; the database upgrade requires stopping every instance). More
breaking changes are planned for 5.0. The release notes and upgrade notes call out every break.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Validation always requires a key (Priority: P1)

An operator runs the service publicly. A client that sends a validation request, or reads an IG package's
dependencies or conformance resources, without a valid VALIDATE or ADMIN key is refused. A client with a valid key
gets exactly the same result as before. The old setting that allowed anonymous validation no longer has any effect.

**Why this priority**: every other part of this feature (usage attribution, trial offering) depends on each
validation being tied to a key. It also closes the largest open door.

**Independent Test**: start the service, send a validation request without a key (refused), with a VALIDATE key
(accepted), with an ADMIN key (accepted).

**Acceptance Scenarios**:

1. **Given** a running service, **When** a validation request arrives without a key, **Then** it is refused as
   unauthenticated (401).
2. **Given** a running service, **When** a package dependencies or conformance read arrives without a key, **Then**
   it is refused as unauthenticated (401).
3. **Given** a valid VALIDATE or ADMIN key, **When** it is used for a validation request, **Then** the request is
   served with the same result as before this feature.
4. **Given** the old anonymous-validation setting is still present in the environment, **When** the service
   starts, **Then** it logs a warning that the setting is ignored, and validation still requires a key.
5. **Given** the published API contract, **When** a client reads it, **Then** the validation and package-read
   operations declare that they need a key and can answer 401.

---

### User Story 2 - Well-known organisations and the trial validator key (Priority: P1)

An operator wants to offer free trial validation. On startup the service guarantees that the system organisation
and the trial organisation exist with their fixed well-known identifiers. When the operator supplies a trial
validator key in configuration, the service installs it as a VALIDATE key owned by the trial organisation. That key
can validate but cannot reach administrative operations.

**Why this priority**: once validation needs a key (US1), the trial key is how a public, free offering keeps
working. The well-known identifiers make ownership predictable across installations.

**Independent Test**: start the service on an empty database with a trial validator key configured; check both
organisations exist with the well-known identifiers; validate with the trial key (accepted); call an admin
operation with it (forbidden); restart and check nothing changed.

**Acceptance Scenarios**:

1. **Given** an empty database, **When** the service starts, **Then** the system organisation and the trial
   organisation (short name `trial`, "Free trial validation service", non-commercial) exist with their well-known
   identifiers.
2. **Given** a trial validator key in configuration, **When** the service starts, **Then** that key exists with its
   fixed identifier, the name "trial validator", the VALIDATE role and the trial organisation as owner.
3. **Given** the trial validator key, **When** it is used for a validation, **Then** the request is accepted; **When**
   it is used for an administrative operation, **Then** the request is forbidden (403).
4. **Given** no trial validator key in configuration, **When** the service starts, **Then** it logs a warning that
   validation is key-only and no trial key is installed.
5. **Given** an existing organisation already uses the short name `trial` with a different identifier, **When** the
   service starts, **Then** startup fails with a clear error instead of continuing silently.
6. **Given** a malformed trial validator key in configuration, **When** the service starts, **Then** startup fails
   with a clear error.
7. **Given** a started service, **When** it is restarted any number of times, **Then** the organisations and
   seeded keys are unchanged.
8. **Given** configuration dumps or startup logs, **When** they are inspected, **Then** the trial key secret never
   appears.

---

### User Story 3 - Upgrading a 4.0 database re-keys the system organisation (Priority: P1)

An operator upgrades an existing 4.0 installation. The upgrade moves the system organisation to its new well-known
identifier, and every key, IG package and validator context it owned follows it. Nothing is lost or orphaned.
Because 4.0 instances still use the old identifier, the upgrade needs every instance stopped first. The upgrade
notes say so.

**Why this priority**: without it, existing installations cannot move to this release.

**Independent Test**: load a 4.0 schema with data owned by the system organisation, run the upgrade, check every
owner reference points at the new identifier and the old identifier no longer exists.

**Acceptance Scenarios**:

1. **Given** a 4.0 database with keys, IG packages and contexts owned by the system organisation, **When** the
   upgrade runs, **Then** all of them are owned by the system organisation under its new identifier.
2. **Given** the upgraded database, **When** new rows are created without an explicit owner, **Then** they default
   to the new system identifier.
3. **Given** the upgraded database, **When** someone tries to create a second system organisation or reuse the old
   identifier as the system organisation, **Then** the database refuses it.
4. **Given** the upgrade notes, **When** an operator reads them, **Then** they state that every instance must be
   stopped before upgrading.

---

### User Story 4 - Admin network allowlist and trusted proxies (Priority: P2)

An operator restricts administrative operations to a set of internal networks. Administrative requests from
anywhere else are forbidden for every key, including the bootstrap key, and they are refused before any key lookup.
Behind a reverse proxy the service uses forwarded client addresses, but only when the request arrives from a proxy
the operator trusts; a client cannot fake its address by sending forwarded headers itself.

**Why this priority**: it limits the damage of a leaked admin key, which is the most powerful credential.

**Independent Test**: configure an allowlist and a trusted proxy; send admin requests with a valid admin key from
inside, from outside, through the trusted proxy, and directly with a spoofed forwarded header.

**Acceptance Scenarios**:

1. **Given** an admin allowlist, **When** an admin request with a valid admin key comes from outside it, **Then** it
   is forbidden (403) and no key lookup takes place.
2. **Given** an admin allowlist, **When** an admin request with a valid admin key comes from inside it, **Then** it
   is served.
3. **Given** no admin allowlist, **When** an admin request comes from any address, **Then** the network is not
   restricted (only the key decides).
4. **Given** a trusted proxy list, **When** a request arrives from a trusted proxy with forwarded headers, **Then**
   the client address is the right-most forwarded address that is not itself a trusted proxy.
5. **Given** a trusted proxy list, **When** a request arrives directly from an untrusted peer with forwarded
   headers, **Then** the headers are ignored and the peer's address is used.
6. **Given** the resolved client address, **When** the allowlist, the last-used audit and usage accounting need an
   address, **Then** they all use that same resolved address.

---

### User Story 5 - Key expiry and last-used address (Priority: P2)

An administrator creates a key that expires at a given time. After that time the key no longer works, even if the
service had recently cached it as valid. The key list shows each key's expiry and the client address it was last
used from, so a stolen key used from a new place can be spotted.

**Why this priority**: it caps how long a leaked key stays useful and makes misuse visible.

**Independent Test**: create a key with an expiry a few seconds ahead; use it (accepted); wait past the expiry; use
it again (refused); list keys and see the expiry and last-used address.

**Acceptance Scenarios**:

1. **Given** a key with an expiry in the future, **When** it is used, **Then** it is accepted.
2. **Given** a key that has expired, **When** it is used, **Then** it is refused (401), even if it was recently
   used and cached as valid.
3. **Given** a key without an expiry, **When** it is used at any time, **Then** expiry never refuses it.
4. **Given** a key that has been used, **When** an administrator lists keys, **Then** the list shows its expiry (or
   none) and the client address it was last used from.
5. **Given** the seeded system and trial keys, **When** they are listed, **Then** they carry the well-known
   far-future expiry.

---

### User Story 6 - Usage accounting per key and organisation (Priority: P2)

An operator wants to know which organisation and key used how much of the service. Each validation, whether it
succeeds, finds issues, fails or times out, produces one usage record attributed to the key and its organisation.
The record contains the time spent in each phase, CPU time, memory allocated, outcome and size. The operator
chooses where records go: nowhere (the default), a dedicated log stream, the database, or both. Recording never
slows down or blocks a validation.

**Why this priority**: it enables cost attribution and fair-use policies for the trial offering, but the service is
usable without it.

**Independent Test**: enable the database sink, validate with a known key, and check one usage record exists with
that key and organisation, a total time not less than the validation time, and CPU time greater than zero; enable the log sink and check one structured line appears on the
usage log stream.

**Acceptance Scenarios**:

1. **Given** the database sink, **When** a validation completes, **Then** exactly one usage record exists for it,
   attributed to the calling key and its organisation, with total time not less than validation time and CPU time
   greater than zero.
2. **Given** any sink, **When** a validation fails with an error or times out, **Then** a usage record is still
   produced with the matching outcome.
3. **Given** the log sink, **When** a validation completes, **Then** one structured (JSON) line appears on the
   dedicated usage log stream, which operators can route separately from the application log.
4. **Given** no sink configured, **When** validations run, **Then** no usage records are stored or logged.
5. **Given** the usage store is unavailable or the in-memory buffer is full, **When** validations run, **Then** they
   are served normally, records that cannot be kept are dropped and counted, and a warning is logged without
   endless retries.
6. **Given** the service shuts down, **When** requests have drained, **Then** buffered usage records are written
   before exit.
7. **Given** the metrics endpoint, **When** it is scraped, **Then** it shows validation duration, CPU time and
   allocated memory by FHIR version and outcome, and the number of dropped usage records, without any per-key
   labels.
8. **Given** a key is deleted, **When** its past usage records are inspected, **Then** they remain, keep their
   organisation, and no longer reference the key.

---

### User Story 7 - Cheap rejection of malformed tokens (Priority: P3)

A client (or an attacker) sends bearer values that cannot possibly be valid keys. The service refuses them at once
without looking them up, so floods of random strings cost almost nothing.

**Why this priority**: a small, cheap defence that lowers load under abuse.

**Independent Test**: send a request with a malformed bearer value and check it is refused (401) and no key lookup
happened.

**Acceptance Scenarios**:

1. **Given** a bearer value that does not have the shape of a key, **When** it is sent, **Then** the request is
   refused (401) and no key lookup takes place.
2. **Given** a well-formed but unknown key, **When** it is sent, **Then** it is refused (401) as before.

---

### User Story 8 - Usage summary command (Priority: P3, optional)

An administrator runs a command that summarises stored usage for one organisation or one key since a given time:
number of validations, outcomes, total and average time, CPU time and allocated memory.

**Why this priority**: convenient, but the same information can be queried from the store directly.

**Independent Test**: with stored usage records, run the summary for an organisation and a key and compare the
totals with the records.

**Acceptance Scenarios**:

1. **Given** stored usage records, **When** the administrator asks for a summary by organisation or key since a
   time, **Then** the totals match the stored records for that scope and period.

---

### Edge Cases

- An expired key is still in the lookup cache: it is refused anyway, because a cached entry never outlives the
  key's expiry.
- The admin allowlist or the trusted proxy list contains an invalid network: the service refuses to start and names
  the bad entry.
- IPv6 peers and IPv4-mapped IPv6 peers are matched correctly against IPv4 and IPv6 networks, including whole-range
  (`/0`) and single-host (`/32`, `/128`) entries.
- Both standard forwarded headers are present: only the configured one is read. A proxy that appends to one passes a
  client's copy of the other through unchanged, so reading it would let any client choose its address.
- Every hop in the forwarded chain is a trusted proxy, or the header is missing: the client is unknown, never a
  proxy's own (often internal) address, so administrative calls are refused.
- The usage store is down: records are dropped and counted, a warning is logged, and nothing retries forever.
- The usage buffer still holds records at shutdown: they are written after the request drain.
- The runtime cannot measure per-thread CPU time or allocation: those values are recorded as -1 (unknown), and the
  rest of the record is still produced.
- The trial validator key is malformed: startup fails with a clear message.
- The old anonymous-validation setting is still configured: it is ignored with a startup warning.
- A short name `trial` is already taken by another organisation: startup fails loudly.
- A request refused by authentication or authorisation has no usage record; those refusals are visible in the HTTP
  metrics.
- A seeded key (system administrator, trial validator) is revoked by name like any other key. Its value is never
  installed again, also after the key was rotated, so an instance still configured with a leaked value can't bring it
  back; a new value replaces it. An instance started without the variable leaves the stored
  key unchanged, so differently configured instances can't revoke each other's keys (code review).
- The upgrade could not rename the old bootstrap key because the name or the secret is already used by another key:
  startup fails with a message naming the conflicting key, never the secret.

## Requirements *(mandatory)*

### Functional Requirements

**A. Key hardening**

- **FR-001**: The service MUST refuse (401) any bearer value that is not shaped like a key, without looking it up.
- **FR-002**: The service MUST support an optional allowlist of networks for administrative operations. When it is
  set, administrative requests from outside it MUST be refused (403) for every key, including the bootstrap key,
  before any key lookup. When it is not set, the network MUST NOT be restricted.
- **FR-003**: The service MUST support an optional list of trusted proxy networks and a setting naming the one
  forwarding header those proxies write (`X-Forwarded-For` by default, or `Forwarded`); the other header MUST never be
  read. It MUST use forwarded client addresses only when the immediate peer is a trusted proxy, taking the right-most
  forwarded address that is not a trusted proxy. When there is no such address (no header, every hop trusted, an
  entry that can't be parsed) or the peer's own address can't be parsed, the client is unknown: administrative calls
  MUST be refused, and an unknown address MUST never replace a recorded one. Otherwise it MUST use the immediate peer's
  address.
- **FR-004**: The same resolved client address MUST be used by the admin allowlist, the last-used audit and usage
  accounting.
- **FR-005**: Invalid entries in the allowlist or trusted proxy list MUST make startup fail with a message naming
  the entry.
- **FR-006**: A key MAY have an expiry time. An expired key MUST be refused (401). A cached lookup result MUST NOT
  keep a key valid past its expiry.
- **FR-007**: Each successful use of a key MUST record the client address it was used from. The key list (admin
  command line) MUST show each key's expiry and last-used address, and keys MUST be creatable with an expiry.

**B. Key-only validation**

- **FR-008**: Validation requests and package dependency/conformance reads MUST require a VALIDATE or ADMIN key;
  requests without one MUST be refused (401).
- **FR-009**: The anonymous-validation setting MUST be removed. If it is still present at startup, the service MUST
  log a warning that it is ignored.
- **FR-010**: The API contract MUST declare the key requirement and the 401 response on every affected operation,
  and the documentation that describes anonymous validation MUST be updated.

**C. Usage accounting**

- **FR-011**: Every validation request that passes authorisation MUST produce exactly one usage record when a sink
  is enabled, including requests that are rejected before validation, fail with an error, time out or are aborted.
  Requests refused with 401 or 403 produce none.
- **FR-012**: A usage record MUST contain: request id, key identifier and name, organisation, FHIR version, IG
  context, number of profiles, content type, request size, outcome, issue counts by severity, client address, start
  time; the total time from request entry to response end, the time waiting for a validation worker, the time to
  obtain the validator context and the time spent validating; and the CPU time and memory allocated by the
  validation work (-1 when the runtime cannot measure them).
- **FR-013**: The caller's key identity MUST be available to the validation without an extra key lookup.
- **FR-014**: The operator MUST be able to choose the sink: none (default), log, database, or log and database.
- **FR-015**: The log sink MUST write one structured JSON line per record on a dedicated usage log stream that can be
  routed separately from the application log.
- **FR-016**: The database sink MUST buffer records in a bounded queue (default 10 000) and write them in batches
  (default 500 records, or every 1 second, whichever comes first). The queue size, batch size and flush interval
  MUST be configurable.
- **FR-017**: Recording usage MUST NOT block or delay a validation. When the queue is full, records MUST be dropped
  and counted. A failed write MUST log a warning and be counted, and MUST NOT be retried indefinitely.
- **FR-018**: On shutdown, after in-flight requests drain, buffered records MUST be written and the sink closed.
- **FR-019**: Stored usage records MUST keep their organisation and MUST lose only their key reference when the key
  is deleted. They MUST be queryable efficiently by key and time, and by organisation and time.
- **FR-020**: The metrics endpoint MUST expose validation duration by FHIR version, CPU time and allocated memory by
  FHIR version and outcome, the validation count by outcome, and a count of dropped usage records. Metrics MUST NOT be
  labelled by key or organisation. CPU time and allocated memory MUST be measured whatever usage sink is configured.
- **FR-021**: Usage retention is out of scope; the documentation MUST say that operators are responsible for it.
- **FR-022** *(optional)*: An administrative command MAY summarise stored usage per organisation or per key since a
  given time.

**D. Well-known organisations and the trial validator key**

- **FR-023**: The well-known identifiers, names and roles of the system organisation, the trial organisation, the
  system admin key and the trial validator key MUST have a single source of truth in the service. The well-known
  far-future expiry MUST be a valid ISO-8601 instant.
- **FR-024**: The upgrade MUST move the system organisation from its 4.0 identifier to its well-known identifier
  atomically, carrying every key, IG package and validator context it owns; it MUST update the default owner of new
  rows and keep the database rule that there is exactly one system organisation, under the new identifier.
- **FR-025**: Both well-known organisations MUST be created by the database upgrade and ensured again at every
  startup, without overwriting existing data (operator overrides for the system organisation still apply). A clash
  with an existing organisation that already uses the short name `trial` MUST fail startup with an error.
- **FR-026**: When a trial validator key is configured, startup MUST check that it is well-formed and install or
  update it with its fixed identifier, the name "trial validator", the VALIDATE role, the trial organisation as
  owner and the well-known expiry, unless that value was ever revoked: a revoked value of a seeded key MUST never be
  installed again (startup logs an ERROR naming the variable, never the value, and leaves the stored key unchanged), so
  a leaked key can't come back, also after a rotation. When none is configured, startup MUST log a warning. The
  configured secret MUST never appear in configuration dumps or logs.
- **FR-027**: The bootstrap admin key SHOULD get the fixed well-known identifier and the name "system administrator"
  (a revoked value is never installed again, as in FR-026),
  with the existing bootstrap key renamed by the upgrade.
- **FR-028**: Startup seeding and the upgrade MUST be idempotent: repeating them MUST leave organisations and keys
  unchanged.

**Release**

- **FR-029**: The feature MUST ship as the next 4.x minor release. The changelog and the upgrade notes MUST call out
  each breaking change: validation needs a key, the anonymous-validation setting is removed, and the upgrade needs
  all instances stopped.
- **FR-030**: Existing integration tests that relied on anonymous validation MUST use a validator key.

### Key Entities

- **API Key**: a bearer secret with a role (ADMIN or VALIDATE), an owning organisation, a revocation state, and now
  an optional expiry and the client address it was last used from.
- **Organisation**: owner of keys, IG packages and validator contexts. Two have well-known identifiers: the system
  organisation and the trial organisation.
- **Usage Record**: one validation cycle attributed to a key and organisation, with timings, CPU time, allocated
  memory, outcome, issue counts, size and client address. Survives deletion of its key.
- **Client Address**: the address a request is attributed to, resolved once per request using the trusted proxy
  rule.
- **Admin Network Allowlist**: the networks administrative operations may come from.
- **Trusted Proxy List**: the networks whose forwarded headers are believed.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of validation and package-read requests without a valid key are refused.
- **SC-002**: Malformed bearer values cause zero key lookups.
- **SC-003**: 100% of administrative requests from outside a configured allowlist are refused, whatever key they
  carry.
- **SC-004**: A forwarded header sent by an untrusted client never changes the address used for access decisions,
  audit or usage.
- **SC-005**: An expired key is refused for every request made after its expiry, including while it is cached.
- **SC-006**: With a sink enabled, every authorised validation request (including rejections, errors, timeouts and
  aborts) yields exactly one usage record, and its total time is never less than its validation time.
- **SC-007**: Usage recording adds less than 1 ms to p95 validation latency and never blocks a request; the existing
  p95 < 200 ms budget for single-resource validation against a warm validator still holds. Memory used by usage
  buffering stays within the configured queue bound.
- **SC-008**: Upgrading a 4.0 database re-keys 100% of the system organisation's owned keys, IG packages and
  contexts, with zero orphaned rows.
- **SC-009**: Starting the service twice on the same database leaves organisations and seeded keys unchanged.
- **SC-010**: A trial-key holder can complete a validation, and is refused on every administrative operation.

## Assumptions

- Staying on 4.x despite breaking changes is a deliberate decision. The constitution (1.3.0, Principle IV) permits a
  documented break in a MINOR release when `plan.md` records it under Complexity Tracking and the release carries a
  Breaking section and an upgrade guide.
- Per-key IP binding is out of scope; administrative traffic is restricted at the network level.
- "Memory" in usage means bytes allocated during the request, not resident memory, because the heap is shared.
- Client addresses in usage records and the last-used audit are operational data, not PHI; validated resource
  bodies are never recorded.
- Usage retention, billing and quotas are out of scope.
- When no admin allowlist or trusted proxy list is configured, behaviour stays as today (no network restriction, the
  direct peer address is used).
- The existing anonymous "open path" caller remains for endpoints that are public (such as health), but validation
  never sees it.
- Usage records across instances go to the shared database; each instance buffers only its own records, so no
  cross-instance cache consistency is needed.
- The usage summary command is optional and may be deferred.
