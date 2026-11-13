# Contract: Operations (settings, startup behaviour, logs, metrics, upgrade)

**Feature**: `009-fhir-version-acl-toggles` | 4.2.0

## 1. Environment variables (also `FV_CONFIG_FILE` paths)

| Variable | Path | Values | Default | Effect |
|---|---|---|---|---|
| `FV_ENABLED_FHIR_VERSIONS` | `fhir.enabledVersions` | comma list of `STU3`, `R4`, `R4B`, `R5` (trimmed, any case, duplicates ignored) | unset = all | Versions this instance serves. Must be the same on every instance. |
| `FV_GLOBAL_DISABLED_ACL_SECURITY` | `auth.globalDisabledAclSecurity` | `true`/`1`/`yes` = disabled; anything else = enforced | enforced | Turns off API-key checks for every endpoint. Must be the same on every instance. |
| `FV_ACL_MODE_CHANGE_CONFIRMED` | `auth.aclModeChangeConfirmed` | same as above | false | Allows a start whose access-control mode differs from the stored one. Remove it after the switch. |

Existing settings with new behaviour:
- `FV_ADMIN_API_KEY`, `FV_VALIDATOR_API_KEY`: ignored, and not format-checked, while access control is disabled.
- `FV_AUTH_ADMIN_CIDRS`: still applies while access control is disabled.

## 2. Startup outcomes

| Situation | Outcome | Exit |
|---|---|---|
| `FV_ENABLED_FHIR_VERSIONS=R4,X1` | `Configuration error: FV_ENABLED_FHIR_VERSIONS: 'X1' is not a FHIR version this service serves (accepted: STU3, R4, R4B, R5)` | 1, before HTTP starts |
| `FV_ENABLED_FHIR_VERSIONS=R6` | `Configuration error: FV_ENABLED_FHIR_VERSIONS: 'R6' is known but not served by this release (accepted: STU3, R4, R4B, R5)` | 1 |
| An enabled version's core package fails to load | instance never ready; warm-up error as in 4.1 | as 4.1 |
| Context (de)activation transaction fails | `Startup failed: validator context activation could not be stored: …` | 1 |
| Stored ACL mode ≠ configured mode, no confirmation | `Startup failed: access control mode change refused: stored mode is <a>, this instance is configured <b>. To switch, set FV_ACL_MODE_CHANGE_CONFIRMED=true on the instances being switched (see docs/upgrade-4.2.md)` | 1, nothing changed |
| No stored mode (fresh database or first 4.2 start) | starts in the configured mode, stores it | — |

## 3. Log lines (level, logger `nzi.fhir.validator.Bootstrap` unless noted)

Key values never appear in any of these lines (SC-008).

| When | Level | Message (shape) |
|---|---|---|
| Every start | INFO | `FHIR versions served: R4, R5 (FV_ENABLED_FHIR_VERSIONS)` or `… : STU3, R4, R4B, R5 (all; FV_ENABLED_FHIR_VERSIONS not set)` |
| Each deactivated context | WARN | `Validator context deactivated: R4 hl7.fhir.us.core#6.1.0 (owner acme): FHIR version R4 is not enabled` |
| After deactivations | WARN | `3 validator context(s) deactivated because their FHIR version is not enabled` |
| Each reactivated context | INFO | `Validator context reactivated: R4 hl7.fhir.us.core#6.1.0 (owner acme)` |
| After reactivations | INFO | `3 validator context(s) reactivated` |
| Disabled mode, after the ACL step | WARN | `ACCESS CONTROL IS DISABLED (FV_GLOBAL_DISABLED_ACL_SECURITY): no endpoint needs a key; administrative endpoints are limited to FV_AUTH_ADMIN_CIDRS` / `… open from any network` |
| Disabled mode, key variable set | INFO | `FV_ADMIN_API_KEY is ignored: access control is disabled` (same for `FV_VALIDATOR_API_KEY`) |
| Mode switch confirmed | WARN | `Access control mode changes from <a> to <b> (FV_ACL_MODE_CHANGE_CONFIRMED)` |
| Confirmation set, modes match | WARN | `FV_ACL_MODE_CHANGE_CONFIRMED is set but the mode is unchanged (<a>); remove it so an accidental mode change is refused` |
| Disabled → enabled | INFO | `API keys re-synchronised from environment` |
| Disabled → enabled, variable unset | WARN | `FV_VALIDATOR_API_KEY is not set: the 'trial validator' key keeps an unknown value and can't be used until it is set` |
| Unrecognised flag value | WARN | `FV_GLOBAL_DISABLED_ACL_SECURITY='maybe' is not recognised; access control stays enabled` |

## 4. Metrics (Prometheus, `/metrics`)

| Name | Type | Meaning |
|---|---|---|
| `fv_acl_security_disabled` | gauge | 1 while access control is disabled |
| `fv_validator_contexts_inactive` | gauge | stored contexts with `is_active = false` |

Existing per-version meters are registered for served versions only.

## 5. Administrative command line

There are no new commands. `fv-admin snapshot share|transfer <version> …` accepts any supported version, including
one that isn't served, so FR-032 holds on the command line too. It doesn't read `FV_ENABLED_FHIR_VERSIONS`.

## 6. Upgrade and switching (`docs/upgrade-4.2.md`)

**Upgrade 4.1 → 4.2**:
- V14 and V15 run automatically and are additive, so a rolling upgrade is fine.
- During the roll, a 4.1 instance ignores `is_active`. Turn on `FV_ENABLED_FHIR_VERSIONS` only after every instance
  runs 4.2.
- Turn on `FV_GLOBAL_DISABLED_ACL_SECURITY` only after every instance runs 4.2. A 4.1 instance has no mode guard: it
  reinstalls the configured keys on every start, which would undo the random keys and make the configured key values
  valid again.

**Restrict versions**:
1. Set `FV_ENABLED_FHIR_VERSIONS` on every instance.
2. Restart them. The first instance to start deactivates the other versions' contexts. The others see nothing to do.

**Turn access control off**:
1. Set `FV_GLOBAL_DISABLED_ACL_SECURITY=true` and `FV_ACL_MODE_CHANGE_CONFIRMED=true` on every instance.
2. Restart all of them. Instances still on the old mode lose their fixed keys as soon as the first switched instance
   starts.
3. Remove the confirmation in the next deployment.

**Turn it back on**:
1. Set `FV_GLOBAL_DISABLED_ACL_SECURITY=false` (or unset it), `FV_ACL_MODE_CHANGE_CONFIRMED=true`, and both key
   variables.
2. Restart all instances.
3. Remove the confirmation.

Resources created while access control was off are owned by the system organisation (admin operations) and can be
reached with the restored admin key.
