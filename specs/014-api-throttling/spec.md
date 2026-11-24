# Feature Specification: Validation Rate Limits per API Key and per Organisation

**Feature Branch**: `014-api-throttling`

**Created**: 2026-11-23

**Status**: Draft

**Input**: User description: "I want to implement API throttling against the endpoint \"/{version}/validate\". API call
count should be counted per api key basis but also per organization in total. This feature should not applicable if
FV_GLOBAL_DISABLED_ACL_SECURITY is true. Number of API calles per minute should be configurable through Postgresql
database, there could be default values for trial API key."

**Background**: `specs/005-organisation-ownership/spec.md` (organisations own API keys),
`specs/008-api-key-hardening/spec.md` (every validation needs a key; usage attribution; the well-known trial
organisation and its shared trial validator key), `specs/009-fhir-version-acl-toggles/spec.md`
(`FV_GLOBAL_DISABLED_ACL_SECURITY`: no endpoint needs a key).

## Context

Every validation request is tied to an API key, and every key belongs to an organisation (specs 005 and 008).
Validation is the most expensive thing the service does, yet nothing stops one key, or one organisation with many
keys, from sending as many validation requests as it likes. A single busy or misbehaving client can therefore use up
the capacity that every other client shares. The free trial offering makes this worse: its one shared trial key is
handed out widely, so the trial organisation is the most likely source of overload.

This feature caps the number of validation requests per minute, both for each API key and for each organisation as
a whole. Operators set the caps; the values live in the service's shared database, so every instance applies the
same caps. The trial key and the trial organisation come with sensible default caps out of the box.

When access control is switched off (`FV_GLOBAL_DISABLED_ACL_SECURITY=true`), requests carry no key, so there is
nothing to attribute them to; the feature does nothing in that mode. Keys of the system organisation (the operator's
own organisation, which owns the fixed system administrator key) are never throttled either.

## Clarifications

### Session 2026-11-23

- Q: What applies to a key or organisation without a cap of its own? → A: A service-wide default cap, stored in the
  shared database, applies unless the key or organisation has its own cap (one default for keys, one for
  organisations).
- Q: Are ADMIN keys (including the fixed system administrator key) throttled on the validation endpoint? → A: Yes,
  like any other key; a key's role does not exempt it. (Narrowed by the next answer: keys of the system organisation
  are exempt.)
- Q: Do the caps apply to keys of the system organisation? → A: No. Throttling does not apply at all to a key that
  belongs to the system organisation, whatever its role: its requests are not counted, not checked and never refused
  for a cap.
- Q: How exact must the per-minute cap be when several instances run behind a load balancer? → A: Approximate: across
  instances, served requests in any rolling 60 s may exceed the cap by at most 10% of the cap (at least 1 request);
  instances share their counts about once per second; a single instance enforces the cap exactly.
- Q: What happens when an instance cannot share its request counts with the others (e.g. the database is briefly
  unreachable)? → A: Degrade to local: each instance keeps enforcing the full cap on its own counts until sharing
  resumes, and logs and counts the event; validation is neither left unlimited nor refused.
- Q: Which starting caps should a fresh installation or the upgrade install? → A: Trial key 30/min, trial
  organisation 60/min; service-wide default 600/min per key and 1200/min per organisation.
- Q: Should successful validation responses tell clients how much of their allowance is left? → A: Yes: every
  validation response to a throttled key carries the cap, the remaining allowance and the seconds until reset, for
  whichever of the key's or organisation's cap is tighter.

### Session 2026-11-24 (convergence, T056)

- Q: How are the two exceptions to the fleet bound found while implementing resolved: a cap smaller than the number of
  instances, and the first second after an instance joins? → A: The join case is fixed in code: a starting instance is
  not ready (and gets no requests) until every peer counts it. A cap smaller than the number of instances is an
  accepted exception: the fleet may exceed it by up to the number of other instances.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A key cannot exceed its per-minute validation cap (Priority: P1)

An operator gives an API key a cap of N validation requests per minute. A client using that key sends validation
requests. The first N within any one-minute period are served as usual. Further requests in that period are refused
with a "too many requests" response that tells the client how long to wait. Once the period has moved on, the
client's requests are served again.

**Why this priority**: this is the core protection. It alone stops one runaway client from starving everyone else,
and it is the building block for the organisation cap.

**Independent Test**: set a cap of 5 on a key, send 6 validation requests within a few seconds (5 served, 1
refused with a wait hint), wait for the hinted time, send one more (served).

**Acceptance Scenarios**:

1. **Given** a key with a cap of 5 per minute and no other recent use, **When** it sends 5 validation requests
   within one minute, **Then** all 5 are served normally.
2. **Given** the same key has just had 5 requests served, **When** it sends a 6th within the same minute, **Then**
   the request is refused as "too many requests" (429), the response says the key's cap was reached, and it tells
   the client how many seconds to wait before retrying.
3. **Given** a key was refused, **When** the client waits the advised time and retries, **Then** the request is
   served.
4. **Given** two keys of different organisations, each with its own cap, **When** one key reaches its cap, **Then**
   the other key is unaffected.
5. **Given** a refused request, **When** the refusal happens, **Then** no validation work is done for it and it does
   not count towards any cap.
6. **Given** a key with a cap of 5 and no other recent use, **When** its first validation request is served,
   **Then** the response shows a cap of 5, 4 remaining and the seconds until the allowance next grows; the 429 for
   the 6th request shows 0 remaining.

---

### User Story 2 - An organisation's keys together cannot exceed the organisation's cap (Priority: P1)

An operator gives an organisation a cap of M validation requests per minute. The organisation has several keys.
Requests from all its keys together are served until M is reached within a minute; then every key of that
organisation is refused until the period moves on, even a key that is still under its own cap.

**Why this priority**: without it, an organisation can get around a per-key cap simply by creating more keys. The
user asked for both levels explicitly.

**Independent Test**: give an organisation a cap of 6 and two keys each with a cap of 5; send 4 requests with each
key within a few seconds (6 served in total, 2 refused with a message naming the organisation cap).

**Acceptance Scenarios**:

1. **Given** an organisation with a cap of 6 and two keys with caps of 5 each, **When** the keys send 4 requests
   each within one minute, **Then** exactly 6 are served and 2 are refused as "too many requests".
2. **Given** a request refused because of the organisation cap, **When** the client reads the response, **Then** it
   says the organisation's cap was reached (not the key's) and tells the client how long to wait, without revealing
   anything about the organisation's other keys.
3. **Given** a request that would fit the organisation cap but not its key's cap, **When** it arrives, **Then** it is
   refused because of the key cap, and it does not use up any of the organisation's allowance.
4. **Given** an organisation with no organisation cap of its own, **When** its keys send requests, **Then** the
   service-wide default organisation cap applies, alongside each key's cap.

---

### User Story 3 - Operators manage caps in the shared database and the trial offering has defaults (Priority: P2)

An operator sets, changes, removes and lists the caps of keys and organisations with the service's administrative
command line, the same way they already manage organisations and keys. The caps are stored in the shared database, so
every running instance applies the same values, and a change takes effect on all instances without a restart. On a
fresh installation the trial key and the trial organisation already have default caps, so the free trial is
protected from the first start.

**Why this priority**: caps must be adjustable per customer without redeploying; the trial defaults make the
protection work out of the box. Stories 1 and 2 can be tested with caps written directly to the database, so this
story is not needed for the first slice.

**Independent Test**: on a fresh installation, list caps (trial key and trial organisation show their defaults);
change the trial key's cap; within one minute, on a second instance, the new cap is enforced.

**Acceptance Scenarios**:

1. **Given** a fresh installation, **When** the operator lists caps, **Then** the trial validator key and the trial
   organisation show the default caps (see Assumptions).
2. **Given** an operator changed the trial key's cap, **When** the service restarts or is upgraded, **Then** the
   operator's value is kept, not reset to the default.
3. **Given** two running instances, **When** the operator changes a key's cap, **Then** both instances enforce the
   new cap within 60 seconds, without a restart.
4. **Given** an operator tries to set a cap that is zero, negative or not a whole number, **When** the command
   runs, **Then** it is rejected with a clear message and nothing changes.
5. **Given** an operator removes a key's or organisation's own cap, **When** that key or organisation then sends
   requests, **Then** the service-wide default cap applies to it (FR-006).
6. **Given** a key and an organisation without caps of their own, **When** the operator changes the service-wide
   default key cap or organisation cap, **Then** the new default is enforced for them on every instance within 60
   seconds; keys and organisations with their own caps are unaffected.
7. **Given** a fresh installation, **When** the operator lists caps, **Then** the service-wide default key cap and
   organisation cap are shown with their initial values (see Assumptions).
8. **Given** a key or organisation that does not exist, **When** an operator tries to set its cap, **Then** the
   command fails with a "not found" message.

---

### User Story 4 - No throttling when access control is disabled (Priority: P2)

An operator runs the service with `FV_GLOBAL_DISABLED_ACL_SECURITY=true` (for example on a private network or for
local development). Validation requests are never refused for exceeding a cap, whatever caps are stored in the
database, and no request counting takes place.

**Why this priority**: explicitly requested; without it, the disabled-ACL mode, which has no keys, would behave
unpredictably.

**Independent Test**: store a cap of 1 for the trial key and organisation, start the service with access control
disabled, send 20 validation requests within a few seconds (all served).

**Acceptance Scenarios**:

1. **Given** access control is disabled, **When** any number of validation requests arrive within a minute,
   **Then** none is refused for exceeding a cap.
2. **Given** access control is disabled, **When** a request carries an API key anyway, **Then** it is still not
   throttled.
3. **Given** caps stored while access control was enabled, **When** the service is restarted with access control
   disabled and later enabled again, **Then** the stored caps are unchanged and apply again.

---

### User Story 5 - Keys of the system organisation are never throttled (Priority: P2)

The operator validates with a key of the system organisation (the fixed system administrator key, or any other key
the operator created for the system organisation), for example for bulk checks or smoke tests after a deployment.
These requests are never refused for a cap and are not counted, whatever default caps are set.

**Why this priority**: the system organisation is the service operator itself; throttling it would only get in the
way of operating the service. It is a small exception on top of stories 1 and 2.

**Independent Test**: set the service-wide defaults to 1 per minute, send 20 validation requests within a few
seconds with a system-organisation key (all served) and 2 with a key of another organisation (1 served, 1 refused).

**Acceptance Scenarios**:

1. **Given** default caps of 1 per minute, **When** a system-organisation key (ADMIN or VALIDATE) sends 20
   validation requests within one minute, **Then** all 20 are served.
2. **Given** an ADMIN key of an organisation other than the system organisation, **When** it exceeds its cap,
   **Then** its next request is refused like any other key's.
3. **Given** an operator tries to set a cap for the system organisation or one of its keys, **When** the command
   runs, **Then** it fails with a message that the system organisation is never throttled, and nothing changes.
4. **Given** the operator lists caps, **When** the listing includes the system organisation or its keys, **Then**
   they are shown as "not throttled".

---

### Edge Cases

- **Both caps reached**: when a request exceeds both its key cap and its organisation cap, the refusal names the key
  cap and gives the longer of the two waits, so a retry after the advised wait is not refused again for the other
  cap.
- **Unauthenticated or forbidden requests**: requests refused for a missing, invalid, expired or revoked key, or for
  a deactivated organisation, are refused as before (401/403) and never count towards a cap.
- **Requests that fail validation**: a served request counts towards the caps whatever its outcome (a validation
  result with errors, a 400 for a bad IG reference or profile, a 5xx). Only "too many requests" refusals do not
  count.
- **Many instances**: caps apply to the whole service, not to each instance. Because instances share their counts
  about once per second rather than on every request, a key whose requests are spread over several instances may get
  slightly more than its cap: at most 10% over (at least 1 request), e.g. at most 11 for a cap of 10 or 6 for a cap of
  5. The acceptance scenarios of User Stories 1, 2 and 5 assume a single instance, where the cap is exact. A cap
  smaller than the number of instances is the accepted exception: it may be exceeded by up to the number of other
  instances. A starting instance gets no requests until every running instance counts it (it is not ready before), so
  a join doesn't break the bound.
- **Bursts at minute boundaries**: the cap applies to any rolling 60-second period, so a client cannot get twice its
  cap by sending a burst just before and just after a full minute.
- **Cap lowered below current usage**: if an operator lowers a cap below what the key has already used in the last
  minute, further requests are refused until usage falls below the new cap; requests already served are unaffected.
- **Shared counting temporarily unavailable**: if the service cannot read or update the shared request counts (for
  example the database is briefly unreachable), each instance keeps enforcing the full cap using its own counts and
  the last caps it read, so a client can get at most the cap times the number of instances per minute. Validation is
  never refused just because sharing failed. The instance logs a warning when sharing stops and when it resumes, and
  records a metric so operators notice. When sharing resumes, the fleet-wide bound of FR-005 applies again within 60
  seconds.
- **Revoked key or deactivated organisation**: its caps are kept in the database but are irrelevant, because its
  requests are already refused before throttling.
- **ADMIN keys**: an ADMIN key of any organisation other than the system organisation is subject to the same caps
  as any other key (its own cap, or the default) and counts towards its organisation's cap. Its role does not exempt
  it; an operator who needs more sets a higher cap for that key or organisation.
- **System organisation keys**: every key of the system organisation, including the fixed system administrator key
  and any VALIDATE key created for the system organisation, is never throttled and its requests are not counted,
  whatever default caps are set.
- **Key moved between organisations**: if a key's owning organisation changes, the exemption follows the key's
  current organisation, within the same 60-second propagation window as cap changes.
- **Default removed**: if an operator removes a service-wide default, keys (or organisations) without a cap of
  their own are no longer limited at that level.
- **Other endpoints**: package reads, administrative endpoints, health and metrics are never throttled by this
  feature.

## Requirements *(mandatory)*

### Functional Requirements

**Scope**

- **FR-001**: The service MUST apply caps only to the validation endpoint (`/{version}/validate`) for every supported
  FHIR version. No other endpoint is throttled by this feature.
- **FR-002**: When `FV_GLOBAL_DISABLED_ACL_SECURITY` is true, the service MUST NOT throttle, count or refuse any
  request because of a cap, whatever caps are stored.

**Counting and enforcement**

- **FR-003**: Each validation request that passes authentication and authorisation MUST be checked against both the
  cap of the calling key and the cap of the key's organisation, before any validation work starts.
- **FR-004**: A request MUST be served only if it fits within both caps. A served request MUST count towards both
  the key's and the organisation's usage. A refused request MUST NOT count towards either.
- **FR-005**: A cap MUST limit the number of served validation requests in any rolling 60-second period, counted
  across all running instances of the service together. On a single instance the cap MUST be exact. Across several
  instances, the served count MUST NOT exceed the cap by more than 10% of the cap, rounded up (at least 1 request);
  instances MUST share their counts at least once per second, and the cap check MUST NOT wait on the shared database
  for each request.
- **FR-006**: A key without a cap of its own MUST be limited by the service-wide default key cap, and an
  organisation without a cap of its own by the service-wide default organisation cap. A key's or organisation's own
  cap always replaces the default, whether it is higher or lower. When a default is not set, keys (or organisations)
  without their own cap are not limited at that level.
- **FR-006a**: The caps MUST apply to every key that may call the validation endpoint, whatever its role (ADMIN or
  VALIDATE), except keys of the system organisation (FR-006b).
- **FR-006b**: When the calling key belongs to the system organisation, the service MUST NOT check, count or refuse
  the request because of a cap; the service-wide defaults MUST NOT apply to the system organisation or its keys.
- **FR-007**: The order of checks MUST be: authentication and authorisation (existing), then caps, then the request
  body and validation. A request refused for a cap MUST NOT have its body parsed or validated.

**Refusal response**

- **FR-008**: A request over a cap MUST be refused with status 429 (too many requests), in the service's standard
  JSON error format, with a message stating whether the key's or the organisation's cap was reached and what the cap
  is.
- **FR-009**: A 429 response MUST tell the client, in seconds, how long to wait before a retry can succeed (a
  standard retry-after hint), and that wait MUST be enough for the retry not to be refused for the same cap again,
  assuming no other requests from the same key or organisation in the meantime.
- **FR-010**: A 429 response MUST NOT reveal the identity or usage of other keys of the organisation, nor of any
  other organisation.
- **FR-010a**: Every validation response (served or refused with 429) to a key that is subject to caps MUST tell the
  client, in the widely used rate-limit response headers, the cap, the remaining allowance in the current rolling
  minute and the seconds until the allowance next grows, for whichever applicable cap (key or organisation) leaves the
  least remaining. Across several instances the remaining allowance is approximate (FR-005). Responses to
  system-organisation keys (FR-006b) and responses while access control is disabled (FR-002) MUST NOT carry these
  headers.
- **FR-011**: The published API contract MUST document the 429 response of the validation endpoint and the
  rate-limit headers of FR-010a.

**Configuration**

- **FR-012**: Caps MUST be stored in the service's shared database: at most one cap per API key, at most one cap
  per organisation, one service-wide default key cap and one service-wide default organisation cap, each a whole
  number of validation requests per minute, at least 1.
- **FR-013**: Operators MUST be able to set, change, remove and list caps for keys, for organisations and for the two
  service-wide defaults with the administrative command line. The listing MUST show, for a given key or
  organisation, which cap is in effect and whether it is its own or the default; for the system organisation and its
  keys it MUST show "not throttled". Setting a cap for an unknown key or organisation MUST fail; setting a cap for
  the system organisation or one of its keys MUST fail with a message that they are never throttled; an invalid value
  MUST be rejected without changing anything.
- **FR-014**: A change to a cap MUST be enforced by every running instance within 60 seconds, without a restart.
- **FR-015**: On first installation (or the upgrade that introduces this feature), the service-wide defaults, the
  trial validator key and the trial organisation MUST receive their initial caps: 600/min default key cap, 1200/min
  default organisation cap, 30/min for the trial validator key and 60/min for the trial organisation. Later starts and
  upgrades MUST NOT overwrite caps an operator has changed or removed.
- **FR-016**: Each change to a cap MUST be logged with the operator action, the key or organisation id (or "default
  key"/"default organisation"), and the old and new values. API key secrets MUST NOT be logged.

**Observability**

- **FR-017**: The service MUST expose a metric counting requests refused for a cap, split by which cap (key or
  organisation) was reached, without one metric series per key or organisation.
- **FR-018**: Each refusal MUST be logged (at a level that does not flood logs under sustained overload, e.g. one
  summary per key per minute) with the key id and organisation id and the request's correlation id.
- **FR-018a**: A request refused with 429 MUST NOT be recorded as validation usage (spec 008's usage records). It is
  counted by the refusal metric (FR-017) and the refusal log (FR-018) instead, so a client that keeps hitting its cap
  cannot turn refusals into usage writes. This amends spec 008, whose rule that every authorised refused validation
  request gets a usage record no longer applies to 429s.
- **FR-019**: While an instance cannot share its counts, it MUST keep enforcing every cap on its own counts with the
  last caps it read, MUST NOT refuse requests because sharing failed, and MUST expose a metric showing that it is
  enforcing locally (and how many count-sharing attempts failed) (see Edge Cases).

### Key Entities

- **Key rate cap**: the maximum number of validation requests per minute for one API key. Belongs to exactly one
  key; optional; stored in the shared database. Kept when the key is revoked.
- **Organisation rate cap**: the maximum number of validation requests per minute for all keys of one organisation
  together. Belongs to exactly one organisation; optional; stored in the shared database.
- **Default caps**: the service-wide default key cap and default organisation cap, each optional, stored in the
  shared database; they apply to every key or organisation without a cap of its own.
- **Request usage**: the record of served validation requests per key and per organisation over the last 60
  seconds, shared by all instances. Short-lived: it only needs to cover the most recent minute.
- **Trial defaults**: the default key cap for the trial validator key and the default organisation cap for the
  trial organisation, installed once.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: For any key or organisation with a cap of N, the number of validation requests served in any rolling
  60-second period never exceeds N with one instance, and never exceeds N plus 10% of N (rounded up, at least N + 1)
  with 2 or 3 instances running behind a load balancer, also while an instance joins. Accepted exception
  (clarification 2026-11-24): a cap smaller than the number of instances (e.g. 1 on 3 instances) may be exceeded by up
  to the number of other instances.
- **SC-002**: Clients within their caps see no change in results, and the cap check adds no more than 5 ms to the
  95th-percentile response time of a single-resource validation.
- **SC-003**: 100% of refusals tell the client which cap was reached and how long to wait; a client that waits the
  advised time and retries is served in at least 99% of cases (assuming no other traffic from the same key or
  organisation).
- **SC-004**: A cap change made by an operator is enforced on every running instance within 60 seconds.
- **SC-005**: With access control disabled, 100% of validation requests are served regardless of stored caps.
- **SC-007**: 100% of validation requests made with a key of the system organisation are served without a cap
  refusal, however many are sent per minute and whatever default caps are set.
- **SC-006**: A single trial client sending as fast as it can cannot get more than the trial organisation's cap
  served per minute, so validations by other organisations keep their normal response times during the test.
- **SC-008**: The memory used for request counting stays bounded: at most about 60 MB per instance at the configured
  maximum of tracked keys and organisations (100 000 by default), and a few MB in typical use. No other memory grows
  with request volume.

## Assumptions

- **Trial defaults** (operators can change them; FR-015): the trial validator key gets a cap of 30 validation
  requests per minute and the trial organisation a cap of 60 per minute (the organisation cap is higher so operators
  can add more trial keys without changing it). Confirmed in clarification 2026-11-23.
- **Initial service-wide defaults** (FR-006, FR-015): 600 validation requests per minute per key and 1200 per minute
  per organisation. They are deliberately generous so that the upgrade does not start refusing existing customers;
  operators tighten them, or set per-customer caps, as needed.
- **Unit**: caps are expressed per minute only; other periods (per second, per day, monthly quotas) are out of scope.
- **What counts**: one validation request counts as one, whatever its size (a bundle with many entries counts as
  one).
- **Degrade to local**: when the shared counts cannot be read or updated, each instance falls back to enforcing caps
  on its own counts (FR-019); this weakens fleet-wide accuracy but neither removes the caps nor blocks validation.
- **Shared state**: the shared database is the only coordination point between instances (constitution Principle
  II); no new infrastructure is introduced for this feature.
- **Allowance headers**: the remaining allowance shown (FR-010a) does not reveal which cap (key or organisation) is
  the tighter one, nor anything about other keys; a client sees only its own effective allowance.
- **No self-service**: organisations cannot view or change their own caps through the HTTP API; caps are managed by
  operators only.
- **Release**: ships in the next 1.x minor release; adding a 429 response to the validation endpoint is a
  non-breaking addition to the contract. The database change goes through the normal versioned migration.
  *Release reset (2026-11-24, PR #106)*: all releases were folded into one 1.0.0, which includes this feature; its
  schema is part of the baseline migration `V18__baseline_1_0.sql`.
- **Existing behaviour unchanged**: authentication, authorisation and validation results are unchanged, and so is
  usage accounting (spec 008) for every request that is not refused with 429 (see FR-018a). Throttling only adds the
  refusal path.
