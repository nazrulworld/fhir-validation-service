# Upgrading to 4.2.0

4.2.0 adds two operator switches (spec 009). It is a **minor release without breaking changes**: with the new
settings unset, the service behaves exactly as 4.1.0.

- `FV_ENABLED_FHIR_VERSIONS` restricts the FHIR versions an instance serves.
- `FV_GLOBAL_DISABLED_ACL_SECURITY` turns API-key access control off for a whole deployment.

## Steps

1. **Back up the database.**
2. **Roll out 4.2.0.** A rolling upgrade is fine. Migrations V14 and V15 run automatically and only add things:
   - V14 adds an active flag (and an inactive reason) to every validator context, and marks every existing context
     active;
   - V15 adds a `service_settings` table, which starts empty.

   4.1 instances ignore both.
3. Only **after every instance runs 4.2**, set the new settings if you want them:
   - Turn on `FV_ENABLED_FHIR_VERSIONS` only then: a 4.1 instance ignores the active flag and would still build every
     context.
   - Turn on `FV_GLOBAL_DISABLED_ACL_SECURITY` only then: a 4.1 instance has no mode guard. It reinstalls the configured
     keys on every start, which would undo the random keys and make the configured key values valid again.

Rollback: stop 4.2.0 and start 4.1.0 against the same database. The added column and table are ignored by 4.1. If
versions were disabled, the contexts marked inactive are built again by 4.1. If access control was disabled, set
`FV_ADMIN_API_KEY` and `FV_VALIDATOR_API_KEY` so 4.1 installs known keys again.

## Serving fewer FHIR versions

`FV_ENABLED_FHIR_VERSIONS` takes a comma-separated list of `STU3`, `R4`, `R4B` and `R5`. Spaces are trimmed, case is
ignored and duplicates are ignored. Unset or empty, every version is served, as before. Any other name (including
`R6`) stops the start with a configuration error naming it.

What changes for the versions that are not listed:

- Their core specification is not loaded, and readiness doesn't wait for them. This saves memory and start time.
- Requests that name them get the same **400 `unsupported-fhir-version`** as an unknown version. The message lists
  the versions that are served.
- `POST /{version}/validator` for them answers **422 `fhir-version-disabled`**, and nothing is stored.
- At startup, their stored validator contexts are marked **inactive** in one transaction and not built. Every
  deactivated context is logged at WARN, followed by a count.
- Inactive contexts can still be excluded (`DELETE`), shared, transferred or given another terminology server
  (`PATCH`), through the API or `fv-admin snapshot share|transfer`. The change is stored and applies later.
- When a version is listed again, its contexts become active again at the next start, with any changes made in the
  meantime. They are logged at INFO.
- `GET /health` reports `fhirVersions.served` and `fhirVersions.inactiveContexts`: a count, how many are private, and
  the first 100 shareable ones (private contexts are counted, never named, on this unauthenticated endpoint). The
  `fv_validator_contexts_inactive` gauge counts them too.

Uploading or registering IG packages is not restricted.

Every instance records its served versions in `service_settings.fhir_versions`. **An instance whose served versions
differ from the stored ones refuses to start** (exit 1, nothing changed) unless `FV_FHIR_VERSIONS_CHANGE_CONFIRMED=true`
is also set, so one instance with a stale setting can't switch contexts off for the whole fleet. The first start after
the upgrade stores the set without needing the confirmation.

**Restricting (or changing) versions in a fleet:**
1. Set `FV_ENABLED_FHIR_VERSIONS` to the same value, and `FV_FHIR_VERSIONS_CHANGE_CONFIRMED=true`, on every instance.
2. Restart them. The first instance to start marks the other versions' contexts inactive, and instances still running
   the old setting drop them within the configuration poll interval.
3. Remove `FV_FHIR_VERSIONS_CHANGE_CONFIRMED` in the next deployment. While it stays set, a start logs a WARN asking for
   it to be removed.

## Turning access control off (public mode)

> **Security warning.** With `FV_GLOBAL_DISABLED_ACL_SECURITY=true` (or `1` / `yes`), **no endpoint needs a key**.
> Anyone who can reach the service can upload and register packages, include and exclude validator contexts, and
> validate. Use it only on a closed network, behind a gateway that authenticates callers, or on a developer machine.

In public mode:

- The `Authorization` header is ignored, whatever it holds.
- Every request runs as one of two fixed callers:
  - validation and the package reads run as the `trial validator` key (`00000000-0000-0000-0000-200000000002`, trial
    organisation), so they see shareable content only;
  - administrative operations run as the `system administrator` key (`00000000-0000-0000-0000-200000000001`, system
    organisation).
- Ownership works as before: anything created is owned by the system organisation.
- Access rules work as before: private content of other organisations stays private (404).
- Usage records are charged to the trial validator and the trial organisation.
- `FV_AUTH_ADMIN_CIDRS` **still applies**: administrative operations from other networks get 403.
- Both fixed keys get new random values (256 bits) at every start. These values are never logged or shown.
- A fixed key you revoke stays revoked across restarts (logged at WARN): revoking `trial validator`
  (`fv-admin key revoke --name "trial validator"`) stops anonymous validation and package reads (403).
- `FV_ADMIN_API_KEY` and `FV_VALIDATOR_API_KEY` are ignored, and their format is not checked.
- The startup log has a prominent WARN. `GET /health` reports `security.aclSecurity: "disabled"`, and
  `fv_acl_security_disabled` is 1.

Every instance records its mode in `service_settings.acl_security`. **An instance whose mode differs from the stored
one refuses to start** (exit 1, nothing changed) unless `FV_ACL_MODE_CHANGE_CONFIRMED=true` is also set. This stops a
single misconfigured instance from replacing the keys the whole fleet uses. A fresh or just-upgraded database has no
stored mode, so its first start needs no confirmation.

**Switching off:**
1. On every instance, set `FV_GLOBAL_DISABLED_ACL_SECURITY=true` and `FV_ACL_MODE_CHANGE_CONFIRMED=true`.
2. Restart all instances. As soon as the first one starts, the fixed keys stop working on the instances not yet
   restarted.
3. Remove `FV_ACL_MODE_CHANGE_CONFIRMED` in the next deployment. While it stays set, a start logs a WARN asking for it
   to be removed.

**Switching back on:**
1. On every instance, unset `FV_GLOBAL_DISABLED_ACL_SECURITY` (or set `false`), set `FV_ACL_MODE_CHANGE_CONFIRMED=true`,
   and set both `FV_ADMIN_API_KEY` and `FV_VALIDATOR_API_KEY`.
2. Restart all instances. The first one installs the configured keys in one transaction and logs
   `API keys re-synchronised from environment`.
3. Remove `FV_ACL_MODE_CHANGE_CONFIRMED`.

Everything created while access control was off stays owned by the system organisation and can be reached with the
restored admin key. No manual database change is needed in either direction.

After switching back:
- A key variable left unset keeps its unknown random value, so that key can't be used until the variable is set. A
  WARN names it.
- A value that was revoked before is never installed again (4.1 rule). An ERROR names it.
