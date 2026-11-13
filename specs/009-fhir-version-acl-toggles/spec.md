# Feature Specification: Operator Switches for Enabled FHIR Versions and Global Access Control

**Feature Branch**: `009-fhir-version-acl-toggles`

**Created**: 2026-11-12

**Status**: Draft

**Input**: User description: "@var/security_enables,disables.txt. Release version should be 4.x.x as more changes are
coming". The input file holds two feature notes: (A) restrict the FHIR versions the service loads with
`FV_ENABLED_FHIR_VERSIONS`; (B) turn API access control off or on for the whole service with
`FV_GLOBAL_DISABLED_ACL_SECURITY`.

**Background**: `specs/004-ig-scoped-validation/spec.md` (validator contexts, core-only base contexts),
`specs/005-organisation-ownership/spec.md` (organisations, ownership), `specs/008-api-key-hardening/spec.md`
(well-known organisations and keys, admin network allowlist, usage accounting, revoked key values).

## Context

Today every instance loads the core specification of every supported FHIR version (STU3, R4, R4B, R5). It also builds
every stored validator context. Operators who only serve one or two versions pay for the others in startup time and
memory, and cannot stop clients from adding contexts for versions they do not want to run.

Every endpoint is also protected by API keys. That is right for a public deployment. But it gets in the way when the
service runs inside a closed network, behind a gateway that already authenticates callers, or on a developer machine.
There, operators want one switch that removes the key requirement from every endpoint. Administrative endpoints can
still be limited to trusted networks. Ownership, auditing and usage accounting must keep
working, and switching back must not need manual database changes.

Both switches are off by default. When an operator does not set them, the service behaves exactly as in 4.1.

**Release**: ships in the next 4.x minor release (4.2.0). More changes are planned before 5.0. This feature adds
settings and one data migration. It changes no existing default, path, payload or status code, so it is not a breaking
change. Turning off a version with the new setting is an operator choice. The service still supports every version
(constitution Principle III).

**Identifiers used in this spec**: the system organisation `00000000-0000-0000-0000-100000000001` owns the
`system administrator` key (ADMIN) `00000000-0000-0000-0000-200000000001`. The trial organisation owns the
`trial validator` key (VALIDATE) `00000000-0000-0000-0000-200000000002` (spec 008). This spec calls these two keys the
**fixed callers**.

## Clarifications

### Session 2026-11-13 (forced sharing in public mode)

- Q: While access control is off, what happens when a request asks for private (non-shareable) content? → A: It is
  stored as shareable (FR-034): including a validator context, registering, downloading or uploading an IG package,
  and PATCH of a context or a package. Changing private content to shareable still works. Everything in public mode
  runs as one of two fixed callers, so private content would only be hidden from the very clients that use it.

### Session 2026-11-13 (code review of PR #72)

- Q: Should a start with different served versions be refused like an access-control mode change? → A: Yes (FR-033):
  the served set is stored, and a different one needs `FV_FHIR_VERSIONS_CHANGE_CONFIRMED`.
- Q: Should a fixed key revoked while access control is disabled stay revoked across restarts? → A: Yes. Its value
  is still replaced at every start, but the revocation is kept and logged at WARN.

- Q: Which fixed caller should the package reads run as while access control is off? → A: The `trial validator`
  (FR-018, US4-5). As the `system administrator` they disclosed every organisation's private packages to anonymous
  callers from any network, contrary to FR-031; the admin network allowlist doesn't cover them.

### Session 2026-11-12

- Q: When a disabled FHIR version is enabled again, do the contexts it deactivated come back? → A: Yes, automatically
  at the next startup (FR-014). The service records why a context is inactive, so only contexts deactivated by the
  version setting are reactivated.
- Q: Does the admin network allowlist (`FV_AUTH_ADMIN_CIDRS`) still apply while access control is off? → A: Yes
  (FR-030). Turning off access control removes the key check only; ADMIN endpoints stay limited to the listed
  networks when an allowlist is configured.
- Q: When access control is off, which validator contexts can a validation request use? → A: The normal ownership
  rules apply (FR-031). Validation runs as the `trial validator`, so it can use shareable contexts and contexts the
  trial organisation owns. Private contexts of other organisations answer 404, as in 4.1.
- Q: What should an instance do at startup if its access-control mode differs from the stored mode? → A: Refuse to
  start unless the operator also sets a one-time confirmation, `FV_ACL_MODE_CHANGE_CONFIRMED=true` (FR-029). With it,
  the instance switches as specified.
- Q: What can an administrator do with a validator context that is inactive because its FHIR version is disabled? →
  A: Every existing admin action works on it: exclude (remove), PATCH (terminology, sharing) and ownership transfer
  (FR-032). Changes are stored and apply when the context becomes active again; nothing is built while the version
  is disabled.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Serve only the FHIR versions the operator chooses (Priority: P1)

An operator who only serves R4 sets the enabled versions to `R4`. The instance loads only the R4 core specification
and only R4 validator contexts. It becomes ready as soon as those have loaded. Requests for any other version are
answered as for a version the service does not serve.

**Why this priority**: this is the main value of part A. It cuts memory and startup time, and sets what the
deployment offers.

**Independent Test**: start an instance with the setting `R4` and check the health report, the loaded contexts and the
response to an R5 validation request. Then start it with the setting unset and check that all versions load as in 4.1.

**Acceptance Scenarios**:

1. **Given** the setting is unset or empty, **When** the instance starts, **Then** the core specification of every
   supported version loads and every stored validator context loads, as in 4.1.
2. **Given** the setting is `R4`, **When** the instance starts, **Then** only the R4 core specification is loaded, and
   the instance reports ready only after it has loaded.
3. **Given** the setting is `r4, R5 ,R4`, **When** the instance starts, **Then** it accepts the value: R4 and R5 are
   enabled (spaces trimmed, case ignored, duplicate ignored).
4. **Given** the setting is `R4,X1`, **When** the instance starts, **Then** startup fails with an error naming `X1` as
   the invalid value and listing the accepted values.
5. **Given** the setting is `R4` and the R4 core specification fails to load, **When** the instance starts, **Then**
   startup fails. It never reports ready.
6. **Given** the setting is `R4`, **When** a client sends a validation request for R5, **Then** the request is refused
   with the same response the service gives today for a version it does not serve.

---

### User Story 2 - Refuse new validator contexts for disabled versions (Priority: P1)

With only R4 enabled, an administrator tries to add a validator context for an R5 IG. The service refuses with a
client error. The message names R5 as the version needed and lists the enabled versions. Nothing is stored and nothing
is built.

**Why this priority**: without it, the version restriction can be bypassed at runtime, and a stored context that can
never load would wait in the database.

**Independent Test**: with the setting `R4`, add an R5 context (refused, nothing stored), then add an R4 context
(accepted and active).

**Acceptance Scenarios**:

1. **Given** the setting is `R4`, **When** an administrator adds a validator context for R5, **Then** the response is
   422 with a message naming R5 and listing `R4` as enabled, and no context is stored or built on any instance.
2. **Given** the setting is `R4`, **When** an administrator adds a validator context for an R4 IG, **Then** it
   succeeds as today and the stored context is active.
3. **Given** the setting is unset, **When** an administrator adds a context for any supported version, **Then** the
   behaviour is unchanged from 4.1.

---

### User Story 3 - Deactivate stored contexts when a version is turned off (Priority: P2)

The operator changes the enabled versions from `R4,R5` to `R5` and restarts. On startup the service marks every stored
R4 validator context as inactive in one step and does not build it. It logs a warning for each one and a summary count.
R5 contexts are not touched.

**Why this priority**: it keeps the stored state consistent with the setting after a configuration change. It matters
less than P1 because it only affects deployments that change the setting.

**Independent Test**: store R4 and R5 contexts, restart with `R5`, and check the stored state, the loaded contexts and
the log lines.

**Acceptance Scenarios**:

1. **Given** R4 and R5 contexts are stored and active, **When** the instance restarts with the setting `R5`, **Then**
   every R4 context is marked inactive and not loaded, and every R5 context is still active and loaded.
2. **Given** step 1 has happened, **When** the startup log is read, **Then** it has one warning per deactivated context
   with its FHIR version, IG package and owner, and one summary line with the count.
3. **Given** marking the contexts inactive fails part-way, **When** startup goes on, **Then** no context has changed
   (all or nothing) and startup fails.
4. **Given** a 4.1 database, **When** it is upgraded, **Then** every existing validator context is active.
5. **Given** R4 contexts were deactivated because R4 was turned off, **When** the instance restarts with R4 enabled
   again, **Then** those contexts are active again and loaded, without any administrator action, and each
   reactivation is logged at INFO with a summary count.
6. **Given** R4 is disabled and an R4 context is inactive, **When** an administrator excludes it, changes its
   terminology or sharing, or transfers its ownership, **Then** the change succeeds and is stored, the context stays
   inactive and is not built, and a later reactivation uses the changed settings.

---

### User Story 4 - Run every endpoint without access control (Priority: P2)

An operator runs the service inside a closed network and sets access control to disabled. Every endpoint answers
without any key. The `Authorization` header is ignored, whatever it holds. Each request runs as one of the two fixed
callers, so ownership, the audit trail and usage accounting work as they do today. The startup log and the health
report say plainly that access control is off.
ADMIN endpoints stay limited to the admin network allowlist when one is configured.

**Why this priority**: this is the main value of part B. It is P2 because it only applies to closed deployments, and
the default (access control on) does not change.

**Independent Test**: start with access control disabled (and the mode-change confirmation, if the stored mode is
enabled). Call an ADMIN endpoint without a header, call validation
with `Authorization: Bearer garbage`, add a validator context, and check the owners, the usage records and the logs.

**Acceptance Scenarios**:

1. **Given** access control is disabled, **When** an ADMIN endpoint is called without an `Authorization` header,
   **Then** it succeeds, and the audit trail or owner shows the `system administrator` caller (…200000000001).
2. **Given** access control is disabled, **When** any endpoint is called with `Authorization: Bearer garbage` (or a
   valid key, or a malformed header), **Then** the request is never refused because of the header, and the header is
   not read.
3. **Given** access control is disabled, **When** a validation request is sent, **Then** it succeeds and the usage
   record names the `trial validator` caller (…200000000002) and the trial organisation.
4. **Given** access control is disabled, **When** a validator context is added, **Then** it is owned by the system
   organisation (the ADMIN fixed caller's organisation).
5. **Given** access control is disabled, **When** a package read (an IG package's dependencies or conformance
   report) is called, **Then** it runs as the `trial validator`: a shareable package can be read, and another
   organisation's private package answers 404.
6. **Given** access control is disabled, **When** the instance starts, **Then** the log has a prominent warning that
   access control is disabled, that no endpoint needs a key, and whether administrative endpoints are limited to the
   admin network allowlist; the health report shows `security.aclSecurity: disabled`.
7. **Given** access control is disabled and an admin network allowlist is configured, **When** an ADMIN endpoint is
   called from outside the listed networks, **Then** it is refused as in 4.1; from inside, it succeeds without a key.
8. **Given** access control is disabled and organisation A owns a private validator context, **When** a validation
   request names that context, **Then** it is answered 404 as for any caller that may not use it. A shareable context
   from A can be used.
9. **Given** access control is disabled, **When** a validator context is included, or an IG package is registered,
   downloaded or uploaded, with `shareable: false`, **Then** it is stored as shareable and the response's `warnings`
   say that `shareable: false` was ignored (FR-034).
10. **Given** access control is disabled and a private context or package exists (created before the switch, or by
    the command line), **When** it is PATCHed with `shareable: true`, **Then** it becomes shareable; **When** it is
    PATCHed with `shareable: false`, **Then** it is stored as shareable with the same warning (FR-034).

---

### User Story 5 - Make known key values useless while access control is off (Priority: P2)

While access control is off, nobody should hold a working key for the two fixed callers. On every start in disabled
mode the service gives both fixed-caller keys new random values that are never shown. Values set for the admin and
trial keys are ignored, and the log says so without printing them.

**Why this priority**: if access control is turned back on, a key value that was public while the service was open
must not quietly stay valid.

**Independent Test**: start in disabled mode with `FV_ADMIN_API_KEY=abc…` (a valid value) and check that the stored key
does not match that value and the log does not contain it.

**Acceptance Scenarios**:

1. **Given** access control is disabled and `FV_ADMIN_API_KEY` is set, **When** the instance starts, **Then** the
   stored `system administrator` key does not match the configured value, and the log says the value was ignored
   without printing it.
2. **Given** access control is disabled, **When** the instance starts, **Then** both fixed-caller keys have new random
   values that are never logged, returned or stored in readable form.
3. **Given** access control is disabled, **When** the system and trial organisations and the two keys are checked,
   **Then** they exist with the same identifiers as today.

---

### User Story 6 - Switch access control back on without manual steps (Priority: P2)

The operator turns access control back on and restarts. The service sees that the last run had access control off.
It installs the configured admin and trial key values again in one step and logs "API keys re-synchronised from
environment". Those keys work, and everything created while access control was off stays owned by the same callers
and can still be reached with them.

**Why this priority**: switching back has to be safe and must not need database work. Otherwise operators will not
dare to use the switch.

**Independent Test**: run in disabled mode and add a validator context. Restart with access control on, the
mode-change confirmation and both keys configured. Check that the keys work and that the context can be reached with the admin key.

**Acceptance Scenarios**:

1. **Given** the last run had access control disabled, **When** the instance restarts with it enabled, the mode-change
   confirmation set and both key values set, **Then** the stored keys match the configured values, the keys work, and the INFO line "API keys
   re-synchronised from environment" is logged without key values.
2. **Given** a validator context added while access control was off, **When** the admin key is used after the switch,
   **Then** the context is still owned by the system organisation and can be reached.
3. **Given** the last run had access control enabled, **When** the instance restarts with it enabled and the same key
   values, **Then** no re-synchronisation happens and nothing is logged about it.
4. **Given** the last run had access control enabled, **When** the instance restarts with a new configured key value
   (an ordinary rotation), **Then** the key is rotated as in 4.1, and no re-synchronisation message is logged.
5. **Given** access control is unset or `false`, **When** a request without a valid key reaches a keyed endpoint,
   **Then** it gets 401/403 as in 4.1.
6. **Given** the stored mode is enabled, **When** an instance starts with access control disabled and without the
   mode-change confirmation, **Then** startup fails with an error naming the stored mode, the requested mode and the
   confirmation setting, and the stored keys and mode are unchanged.
7. **Given** the stored mode is disabled, **When** an instance starts with access control enabled and without the
   confirmation, **Then** startup fails in the same way and no key is re-synchronised.
8. **Given** the stored mode matches the current mode, **When** an instance starts with the confirmation still set,
   **Then** it starts normally and logs a WARN asking the operator to remove the confirmation setting.

---

### Edge Cases

- **Version setting names a known but unserved version** (e.g. `R6`): startup fails with an error that says the version
  is not served by this release and lists the accepted values.
- **Version setting holds only separators or spaces** (e.g. `" , "`): treated as empty, so all versions are enabled,
  and the log says so.
- **An IG package declares more than one FHIR version**: a validator context is always for one FHIR version (the
  version in the request). Only that version is checked against the enabled set.
- **IG package uploads for a disabled version**: still accepted and stored, as today. Packages are stored data and may
  serve several versions. Only validator contexts are restricted.
- **Reading a deactivated context**: inactive contexts are listed in the health report with their FHIR version, IG
  package, owner and reason (FR-015). Any request for their version gets the "version not served" response (FR-005).
  Administrators can still remove or change them (FR-032).
- **Instances with different version settings in one fleet**: not supported, and prevented at startup (FR-033). An
  instance whose served versions differ from the stored ones refuses to start unless
  `FV_FHIR_VERSIONS_CHANGE_CONFIRMED` is set. During a confirmed change, an instance that deactivates contexts tells
  the other instances, which unload them.
- **Instances with different access-control modes in one fleet**: not supported, and prevented at startup. An
  instance whose mode differs from the stored one refuses to start unless the mode-change confirmation is set
  (FR-029). During a deliberate switch, instances still on the old mode keep running until restarted. Once one
  instance has switched, restarting an old-mode instance fails until its configuration is updated.
- **No stored mode yet** (fresh database, or the first start after the upgrade from 4.1): the instance starts in
  whatever mode it is configured with and needs no confirmation. Its mode is stored.
- **Switching back on with a key value unset**: that fixed caller's key keeps its unknown random value, so it cannot
  be used. A startup warning names the unset variable. The other key is re-synchronised normally.
- **Switching back on with a revoked key value**: the value is not installed (spec 008 rule). The error is logged as
  in 4.1, and that key keeps its unknown random value.
- **Admin network allowlist while access control is off**: `FV_AUTH_ADMIN_CIDRS` still applies (FR-030). An ADMIN
  endpoint called from outside the listed networks is refused as in 4.1, even though no key is needed. When no
  allowlist is configured, ADMIN endpoints are open from anywhere.
- **Access-control value not recognised** (e.g. `maybe`): treated as enabled (secure default). The log notes that the
  value was not recognised.
- **Private content while access control is off**: private packages and contexts of other organisations stay
  private (FR-031). To use them in public mode, their owner marks them shareable. New content can't be made private
  over HTTP in this mode (FR-034); the administrative command line still can.
- **Usage accounting while access control is off**: all validation usage is charged to the trial organisation. Usage
  of different real clients cannot be told apart in this mode. This is expected and documented.

## Requirements *(mandatory)*

### Functional Requirements

#### Part A: Enabled FHIR versions (`FV_ENABLED_FHIR_VERSIONS`)

- **FR-001**: The service MUST accept an enabled-versions setting holding FHIR version names separated by commas. It
  MUST trim spaces around each name, ignore case and ignore duplicates.
- **FR-002**: When the setting is unset or empty (after trimming), every supported FHIR version MUST be enabled, as in
  4.1.
- **FR-003**: A name that is not a version this release serves MUST stop startup with an error naming the bad value
  and listing the accepted values. This includes known but unserved versions such as R6.
- **FR-004**: At startup the service MUST load the core specification only for enabled versions. Readiness MUST stay
  false until every enabled version's core specification has loaded. If any enabled version fails to load, startup
  MUST fail.
- **FR-005**: Requests that name a version which is not enabled (validation, adding a validator context, and every
  other version-scoped endpoint) MUST be refused with the response the service gives today for a version it does not
  serve. The exceptions are FR-008 (adding a context) and FR-032 (changing or removing an inactive context).
- **FR-006**: Every stored validator context MUST carry an active flag. Only active contexts are built, loaded or used
  for validation.
- **FR-007**: The upgrade from 4.1 MUST mark every existing validator context as active.
- **FR-008**: Adding a validator context for a version that is not enabled MUST be refused with 422 in the service's
  standard error format. The message MUST name the version needed and list the enabled versions. A refused request
  MUST leave no stored context and no built context on any instance.
- **FR-009**: Adding a validator context for an enabled version MUST work as in 4.1, and the new context MUST be
  active.
- **FR-010**: At startup, after the enabled versions are known and before contexts are built, the service MUST check
  every stored validator context against the enabled versions. Every active context whose version is not enabled MUST
  be marked inactive and not loaded.
- **FR-011**: The marking in FR-010 MUST happen in one all-or-nothing step. If it fails, startup MUST fail.
- **FR-012**: Each context marked inactive under FR-010 MUST be logged at WARN with its FHIR version, IG package and
  owner, followed by one summary line with the count. When none are deactivated, no warning is logged.
- **FR-013**: Other running instances MUST unload contexts marked inactive within the service's normal configuration
  propagation window.
- **FR-014**: Each inactive context MUST record why it is inactive. At startup, every context that is inactive only
  because its version was disabled MUST be marked active again, and loaded, when that version is enabled again. This
  happens in the same all-or-nothing step as FR-010, and each reactivation is logged at INFO with a summary count.
  Contexts made inactive for any other reason in the future are not touched by this rule.
- **FR-015**: The startup log and the health report MUST show the enabled versions and the number of inactive
  validator contexts.

#### Part B: Global access control switch (`FV_GLOBAL_DISABLED_ACL_SECURITY`)

- **FR-016**: The service MUST accept an access-control switch. `true`, `1` and `yes` (case ignored) mean access
  control is disabled. Any other value, or no value, means access control is enforced, as in 4.1.
- **FR-017**: When access control is disabled, endpoints MUST NOT require authentication, and the `Authorization` header
  MUST NOT be read or checked. A request MUST never be refused because of that header's content.
- **FR-018**: When access control is disabled, each request MUST run as a fixed caller: validation
  (`POST /{version}/validate`) and the package reads (`/igs/{name}/{version}/dependencies`, `/conformance`) run as the
  `trial validator` key (…200000000002), so they see shareable content only (FR-031). Administrative operations run as
  the `system administrator` key (…200000000001).
- **FR-019**: Anything created while access control is disabled (package uploads, IG registrations, validator
  contexts) MUST be owned by the fixed caller's organisation, as if that key had made the request.
- **FR-020**: Usage accounting on validation MUST work as in 4.1 and charge usage to the `trial validator` caller and
  the trial organisation.
- **FR-021**: When access control is disabled, startup MUST create the system and trial organisations and both
  fixed-caller keys exactly as today. It MUST give both keys new random values, made with a cryptographically secure
  generator with at least 256 bits of entropy, on every start in this mode. The values MUST NEVER be logged, returned
  or stored in readable form.
- **FR-022**: When access control is disabled, values set for `FV_ADMIN_API_KEY` and `FV_VALIDATOR_API_KEY` MUST be
  ignored. The startup log MUST say which of them were ignored, without printing their values.
- **FR-023**: When access control is disabled, startup MUST log a prominent WARN that access control is off and no
  endpoint needs a key, saying whether administrative endpoints are limited to `FV_AUTH_ADMIN_CIDRS` (FR-030). The
  health report MUST include `security.aclSecurity: disabled` (and `enabled` otherwise).
- **FR-024**: The service MUST store the access-control mode of each start as a durable, shared setting, updated on
  every start, so that a switch between modes can be detected without ambiguity and audited.
- **FR-025**: When an instance starts with access control enabled and the stored mode says the last start had it
  disabled, the service MUST install the configured admin and trial key values for the two fixed callers in one
  all-or-nothing step. It MUST log "API keys re-synchronised from environment" at INFO, never with key values. The
  rules from 4.1 still apply: an unset value leaves that key unchanged (here: unknown and unusable) with a warning
  naming the variable, and a revoked value is not installed.
- **FR-026**: The re-synchronisation in FR-025 MUST keep the identifiers of the fixed callers and their organisations.
  Everything created while access control was off stays owned by the same callers and can be reached with the
  restored keys.
- **FR-027**: When the stored mode and the current mode are both enabled, startup MUST behave as in 4.1 (including an
  ordinary key rotation from a changed value). No re-synchronisation happens and no message about it is logged.
- **FR-028**: Switching between the two modes in either direction MUST NOT need any manual database change.
- **FR-029**: An instance whose access-control mode differs from the stored mode MUST refuse to start, with an error
  that names both modes and the confirmation setting, unless `FV_ACL_MODE_CHANGE_CONFIRMED` is set to `true`, `1` or
  `yes` (case ignored). A refused start changes no key and no stored mode. With the confirmation, the instance logs a
  WARN naming both modes, switches (FR-021 or FR-025) and stores the new mode. When no mode is stored yet, no
  confirmation is needed. When the confirmation is set but the modes already match, the instance starts normally and
  logs a WARN asking for the setting to be removed.
- **FR-030**: When access control is disabled, the admin network allowlist (`FV_AUTH_ADMIN_CIDRS`, spec 008) MUST
  still be enforced for ADMIN endpoints, using the same client address resolution (including trusted proxies) as in
  4.1. The startup warning in FR-023 MUST say whether ADMIN endpoints are limited to an allowlist or open from
  anywhere.
- **FR-031**: Turning off access control MUST NOT change the ownership and sharing rules of spec 005. Each request in
  disabled mode sees exactly what its fixed caller would see with its key: validation (as the `trial validator`) can
  use shareable contexts and those the trial organisation owns, and private contexts of other organisations stay
  hidden (404).
- **FR-032**: Every existing administrative action on a stored validator context MUST also work when the context is
  inactive because its version is disabled: exclude (remove), PATCH (terminology mode, sharing) and ownership
  transfer, with the same ownership rules and responses as for an active context. Such changes MUST be stored
  without building the context, and the context MUST stay inactive. A later reactivation (FR-014) uses the stored
  settings. Including (adding) a context for a disabled version is still refused (FR-008), also when an inactive
  context for it already exists.
- **FR-033**: The service MUST store the served FHIR versions of each start as a durable, shared setting. An instance
  whose served versions differ from the stored ones MUST refuse to start, with an error naming both and the setting
  `FV_FHIR_VERSIONS_CHANGE_CONFIRMED`, unless that setting is `true`, `1` or `yes`. A refused start changes nothing.
  With the confirmation, the instance logs a WARN naming both, reconciles (FR-010, FR-014) and stores the new set. No
  stored set yet needs no confirmation. The confirmation with unchanged versions logs a WARN asking for it to be
  removed. This mirrors FR-029, so one instance with a stale setting can't switch contexts off for the whole fleet.
- **FR-034**: While access control is disabled, every HTTP request that sets the sharing of a validator context or an
  IG package MUST store it as shareable: `POST /{version}/validator`, `POST /igs/register` (by name or download URL),
  `POST /igs/upload`, `PATCH /{version}/validator` and `PATCH /igs/{name}/{version}`. A requested `shareable: false`
  MUST be ignored, the rest of the request applies (e.g. a transfer), and the response MUST carry the warning
  `shareable=false was ignored: access control is disabled (FV_GLOBAL_DISABLED_ACL_SECURITY), so packages and
  validator contexts are always shareable`. `shareable: true` (private to shareable) works as before. Content that
  is already private stays private until it is changed. The administrative command line is not affected.

- **Enabled FHIR version set**: the FHIR versions an instance serves, taken from configuration at startup. Empty means
  all supported versions.
- **Validator context** (existing): a stored, buildable validator for one FHIR version and one IG package, with an
  owner and a sharing flag. New: an **active** flag and, when inactive, the **reason** (for now only "FHIR version
  disabled"). Inactive contexts are kept but never built or used.
- **Fixed callers**: the `system administrator` key (ADMIN, system organisation) and the `trial validator` key
  (VALIDATE, trial organisation). Requests are attributed to them while access control is off.
- **Access-control mode record**: a durable shared setting holding the mode of the most recent start and when it was
  set. It is used to detect a switch back to enabled.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: With both settings unset, all existing automated tests pass unchanged, and startup time, memory use and
  validation latency stay within 5% of 4.1.
- **SC-002**: With one FHIR version enabled, an instance becomes ready faster and uses less memory at rest than with
  all versions enabled. The memory saved is at least the size of the core specifications that are no longer loaded.
- **SC-003**: 100% of attempts to add a validator context for a disabled version are refused and leave no stored or
  built context.
- **SC-004**: After a restart that disables a version, 100% of that version's contexts are inactive and none of them is
  loaded on any instance. Contexts of other versions are 100% unchanged.
- **SC-005**: With access control disabled, 100% of requests to every documented endpoint succeed without a key,
  including requests with a malformed `Authorization` header. The exceptions are ADMIN requests from outside a
  configured admin network allowlist (FR-030), and requests that fail for a reason other than authentication.
- **SC-006**: With access control disabled, every created resource and every usage record names one of the two fixed
  callers. None is anonymous.
- **SC-007**: After switching access control back on, the configured keys work on the first request after readiness,
  with no manual database change. Every resource created while access control was off can be reached with them.
- **SC-008**: No log line, response or stored readable field contains a key value, in either mode.
- **SC-009**: An operator can tell from the startup log or the health report alone which FHIR versions are enabled,
  how many contexts are inactive, and whether access control is on.
- **SC-010**: 100% of starts whose access-control mode differs from the stored mode without the confirmation fail
  before any key or stored mode is changed.

## Assumptions

- Both settings are configured the same on every instance of a fleet. Mixed fleets are not supported. For the
  access-control mode and for the served versions, a mismatch with the stored value stops startup unless confirmed
  (FR-029, FR-033).
- "Registering a validator" in the input note means adding a validator context (the existing "include IG" operation).
  The FHIR version checked is the version named in that request, which the IG package must already declare (existing
  rule).
- IG package uploads and registrations are not restricted by the enabled-version setting. Only validator contexts and
  version-scoped requests are.
- The two fixed callers are the existing well-known keys from spec 008. No new organisations or keys are added.
- The existing `/health` report is where the new status fields go. No new info endpoint is added.
- The stored access-control mode (FR-024) replaces comparing stored hashes as the switch-back signal. Comparing hashes
  cannot tell a disabled run apart from an ordinary key rotation.
- Random fixed-caller keys keep the expiry and revocation rules of spec 008. They are never written to the
  revoked-values list, because nobody knows them.
- Performance (constitution Principle VI): the validation hot path is unchanged in both modes. Disabling access
  control removes the key lookup from each request, and disabling versions lowers memory at rest and startup time. No
  new caches or worker pools are added.
- Release 4.2.0 is a MINOR release: everything is opt-in and defaults keep 4.1 behaviour. The changelog and README
  document both settings, the security warning for disabled access control, and the upgrade step (active flag set on
  existing contexts).
