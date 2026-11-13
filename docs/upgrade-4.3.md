# Upgrading to 4.3.0

4.3.0 retires every release before 4.0.0. For a 4.x deployment it is a **minor release without breaking changes**:
the API, configuration and data are unchanged, and nothing needs to be done beyond rolling it out.

- Database migrations V1 to V9 are squashed into one migration, `V9__baseline_4_0.sql`, which builds the 4.0.0 schema.
  Only a new database runs it.
- A database whose schema predates 4.0.0 is refused at startup (see below).
- The upgrade guides and tooling for releases before 4.0 are removed. Releases 0.1.0 to 3.2.1 (their GitHub
  releases, tags and container images) are being withdrawn.

## Steps (from 4.0, 4.1 or 4.2)

1. **Back up the database.**
2. **From 4.0 or 4.1, follow [upgrade-4.1.md](upgrade-4.1.md) and [upgrade-4.2.md](upgrade-4.2.md) as well.**
   4.3 adds no migrations of its own, but their steps still apply. For example, every 4.0 instance must be stopped
   before the first newer one starts.
3. **Roll out 4.3.0.** A rolling upgrade is fine.

   An existing 4.x database keeps its migration history exactly as it is. 4.3 no longer ships the original V1 to V9
   files, so it validates those versions leniently and logs this once per start:

   ```
   Database keeps the original V1-V9 history; retired versions validated leniently
   ```

   V10 onwards are validated as before: a changed or failed later migration still stops the start.

Rollback: stop 4.3.0 and start 4.2.0 against the same database. The history was not changed, so 4.2 validates and
starts as before.

## A database from before 4.0.0

4.3.0 cannot upgrade it. The start fails, without retrying, with:

```
Database schema is from a release before 4.0.0 (migration N); this release cannot upgrade it. Upgrade with 4.2.0
first (FV_TAG=4.2.0), then start this release (docs/upgrade-4.3.md)
```

Nothing is changed in the database. To upgrade:

1. Set `FV_TAG=4.2.0` and follow the upgrade guides attached to the
   [4.2.0 release](https://github.com/nazrulworld/fhir-validation-service/releases/tag/v4.2.0) from your release
   onwards (`upgrade-2.0.md` before leaving 1.x: it deletes custom profiles; `upgrade-4.0.md` from 3.x;
   `upgrade-4.1.md` from 4.0).
2. Start 4.2.0 once, so the database reaches the 4.2 schema.
3. Then follow the steps above to move to 4.3.0.
