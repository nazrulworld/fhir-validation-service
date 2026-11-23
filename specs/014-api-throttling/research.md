# Research: Validation Rate Limits per API Key and per Organisation

**Feature**: `014-api-throttling` | **Date**: 2026-11-23 | **Spec**: [spec.md](./spec.md)

The Technical Context had no open unknowns after `/speckit-clarify`. The decisions below settle the design questions
that the spec deliberately left to planning.

---

## R1. Where the check runs in the request pipeline

**Decision**: a new root handler, `ThrottleHandler`, mounted in `HttpVerticle` **right after**
`services.auth().rootHandler()` and **before** `validationApi().usageFallbackHandler()`. It acts only on
`POST /{version}/validate` (the same path pattern `ValidationApi` already uses). It is not mounted at all when access
control is disabled.

Order after the change: request id → in-flight → request timeout → CORS → body → client address → **auth** →
**throttle** → usage fallback → admin admission → contract validation → `ValidationApi`.

**Rationale**:
- FR-007: authentication and authorisation come first. The `Caller` (key id, organisation id, `system` flag) is then in
  the routing context, so the throttle needs no lookup of its own.
- The request is refused before contract validation, JSON/XML parsing and the validation worker pool. The body handler
  has already buffered the bytes (it runs before auth today and is capped by `FV_HTTP_MAX_BODY_MB`). Buffering is not
  parsing, so FR-007 holds.
- Running before `usageFallbackHandler` means a 429 does **not** create a `validation_usage` row (see R9).

**Alternatives considered**:
- *Inside `ValidationApi.validate`*: rejected. Contract validation and body parsing would already have run.
- *A Vert.x `RateLimiter`/third-party library (Bucket4j, Resilience4j)*: rejected. None shares state through
  PostgreSQL, and Principle VIII forbids a dependency that doesn't earn its place. Bucket4j's JDBC back end does a
  database round trip per request, which clarification Q1 ruled out.

## R2. Who is throttled

**Decision**: the handler skips the request (no count, no headers) when any of the following is true:
1. the service runs with `FV_GLOBAL_DISABLED_ACL_SECURITY=true` (FR-002). The handler is not mounted, and
   `ThrottleService` is not started;
2. `Caller.system()` is true, meaning the key belongs to the system organisation (FR-006b). `Caller.of` already
   derives `system` from `Organisations.SYSTEM_ID`;
3. the request is not `POST /{version}/validate` (FR-001).

Every other authenticated caller, ADMIN or VALIDATE, is throttled (FR-006a).

**Key moved between organisations (spec Edge Case)**: no command moves a key today. The CLI has `key
create|revoke|list` only, and the fixed keys' owners are constants. If a move is added later, the auth cache already
carries the key's current organisation in `Caller`, so the exemption follows within `FV_AUTH_CACHE_TTL_MS`. That
feature would then have to cap `FV_AUTH_CACHE_TTL_MS` or invalidate the cache to keep the spec's 60 s.

**Rationale**: all three facts are already known in memory, so the checks cost nothing. When access control is
disabled, requests run as the fixed `trial validator` caller (spec 009 FR-018). Not mounting the handler is the
simplest way to make sure that caller's caps never apply in that mode (US4 AC1/AC2).

## R3. Counting: a rolling window of one-second buckets

**Decision**: each tracked subject (one key or one organisation) has a `Window`: a ring of 61 one-second buckets
indexed by epoch second.
- Each bucket holds two counts: `local`, admitted by this instance, and `remote`, the sum over the other instances as
  last read from the database.
- The windowed count is the sum of the 60 buckets before the current second plus the current bucket. Using whole
  seconds makes the window up to one second longer than 60 s. The count can therefore only be too high, never too low,
  so it never admits more than an exact rolling window would (FR-005, single instance exact).
- `Retry-After` / `RateLimit-Reset` is the number of seconds until enough of the oldest non-empty buckets leave the
  window to free one slot. It is computed from the ring and is exact for one instance (FR-009, SC-003). When both the
  key and the organisation refuse, the response uses the larger of the two waits (Edge Cases).

**Memory**: about 61 × 2 ints plus overhead, roughly 0.6 KB per subject. Windows live in a bounded map (Caffeine:
`maximumSize = FV_THROTTLE_MAX_TRACKED`, default 100 000; `expireAfterAccess` = 2 min). That is at most about 60 MB in
the worst case and a few MB in practice. Evicting the window of a subject that is still active only forgets its own
recent counts. Its remote counts come back on the next read, and the eviction is counted in a metric. Principle VI
(bounded caches) holds.

**Concurrency**: windows are touched from every HTTP event loop (`FV_HTTP_INSTANCES`). Each `Window` uses a short
`synchronized` critical section with no I/O. A request locks its key window, then its organisation window, always in
that order. Organisation windows are never held while a key window is acquired, so the locks cannot deadlock. The check
and the increment of both windows happen under both locks, so concurrent requests cannot both take the last slot
(SC-001).

**Alternatives considered**:
- *Token bucket*: rejected. Its refill semantics don't match "N per rolling 60 s", and a client that waited can burst
  past the spec's wording.
- *Fixed calendar minute*: rejected by the spec's Edge Cases (no doubled bursts at minute boundaries).
- *Sliding log of timestamps*: rejected. Its memory is O(cap) per subject: 1200 timestamps per organisation.

## R4. Sharing counts across instances (the clarified "approximate, ≤ 10 % over" bound)

**Decision**: every instance runs one `ThrottleSync` timer every `FV_THROTTLE_SYNC_INTERVAL_MS` (default **1000 ms**,
bounded 200–1000 ms, so FR-005's "at least once per second" always holds). On each tick it does three things:
1. **Flush.** It writes its own `local` count of every bucket that changed since the last flush. This is one
   statement: `INSERT … SELECT * FROM unnest(…) ON CONFLICT (scope, subject_id, epoch_second, instance_id) DO UPDATE
   SET count = EXCLUDED.count` into the UNLOGGED table `throttle_counts`. It writes the **absolute** count, not a
   delta, so a repeated or retried flush is harmless.
2. **Heartbeat.** It upserts its row in the UNLOGGED table `throttle_instances` with `seen_at = now()`, using database
   time so clock skew doesn't matter. It reads `peers`, the number of *other* instances seen in the last 5 s.
3. **Read.** It runs `SELECT scope, subject_id, epoch_second, sum(count) … WHERE epoch_second >= $now - 5 AND
   instance_id <> $me GROUP BY 1, 2, 3` and **sets** (not adds) the matching `remote` buckets. Only the last 5 seconds
   are read, because older buckets are final and already held locally. Every subject in the result gets a window,
   even one this instance has not served yet. Over consecutive ticks the instance therefore holds the fleet-wide
   history of every subject active anywhere, within the `FV_THROTTLE_MAX_TRACKED` bound. The first request a key sends
   to this instance already sees that key's fleet-wide usage, and no request ever waits on the database.
   **At start**, one full read (`epoch_second >= $now - 60`) fills the windows before the handler admits anything
   (R8), so a freshly started instance does not begin with empty windows.

A tick never overlaps the previous one: if a tick is still running, the next one is skipped and counted. Ticks use the
shared pool through the async client and never block an event loop (Principle I).

**Admission rule** (this is what bounds the overshoot to the clarified 10 %): let `V` be the windowed count this
instance sees (local + remote), `cap` the effective cap, `p` the live peers, and `B = max(1, ceil(cap / 10))` the
allowed overshoot.
- **p = 0** (one instance, or degraded, see R5): admit iff `V + 1 ≤ cap`. This is exact.
- **p ≥ 1**: each instance limits its *unconfirmed* admissions per subject to `r = max(1, floor(B / p))`. An admission
  is unconfirmed until a flush containing it has succeeded and one more sync interval has passed, so every live peer
  has read it. The instance admits iff `V + 1 + p·r ≤ cap + B` **and** its own unconfirmed count is `< r`.
  - What peers can have admitted without this instance seeing it is at most `p·r`, so the true fleet total after
    admitting is at most `V + 1 + p·r ≤ cap + B`. That is the SC-001 bound.
  - When `p·r ≤ B`, which is every cap ≥ 10·p, the rule admits up to the full cap with no under-admission.
  - For tiny caps with several instances (e.g. cap 5, 3 instances: `B = 1`, `p = 2`, `r = 1`), it admits up to
    `cap + B − p·r` = 4 fleet-wide. The bound is kept by admitting slightly *fewer*, never more. This is documented.
  - `r` also limits how fast one instance admits a subject: about `r` per two intervals, i.e. at least 30·r per
    minute. For the defaults with up to 3 instances, that is at least the cap (trial key 30/min: `r = 1` → 30/min per
    instance).
- A refusal caused only by the `r` limit, while the window still has room, answers 429 with `Retry-After:
  ceil(2 × FV_THROTTLE_SYNC_INTERVAL_MS / 1000)`, which is 2 s at the default. Confirming an admission takes a
  successful flush plus one more sync interval, which is up to two intervals. A shorter wait could be refused again,
  which would break FR-009 and SC-003.

**Limits found while implementing** (documented in `docs/operations.md`):
- A cap smaller than the number of instances (e.g. cap 1 on 3 instances) has `cap + B − p·r < 1`. The threshold is
  floored at 1 so the subject is not refused for ever, and the fleet may then exceed such a cap by up to `p`.
  Accepted in the spec (clarification 2026-11-24).
- `p` is each instance's own view. In the first sync interval after an instance joins, the existing instances still
  count it out. **Fixed (T056)**: a starting instance is not ready until it has completed two sync ticks
  (`ThrottleSync.warm()`); by then every peer has read its heartbeat and reserves room for it, and the load balancer
  sends it no requests before. `ThrottleSimulationTest#aJoiningInstanceDoesNotBreakTheBound` admits 34 against a bound
  of 33 without this, and the parameterised cases now let the last instance join after two minutes.

**Verification**: `ThrottleSimulationTest`, a pure unit test with a deterministic clock and scripted sync delays,
drives 1–3 simulated instances for the caps {1, 5, 10, 30, 60, 600, 1200}. It covers bursts and round-robin, random
and sticky load balancing. It asserts that the admitted count in every rolling 60 s window is `≤ cap` for one instance
and `≤ cap + B` for 2–3 instances. `ThrottleMultiInstanceIT` runs the same check with two real `ThrottleService`s on
Testcontainers PostgreSQL (SC-001).

**Clock skew**: bucket seconds come from each instance's clock. Reading the last 5 s absorbs skew of up to about 3 s.
Instances are assumed to run NTP, the same assumption as `validation_usage.started_at`. Heartbeats use database time.

**Database load**: three statements per tick per instance. With 3 instances that is about 9 statements per second,
whatever the request rate. Rows are about 50 B. A subject that is active every second keeps at most 120 rows per
instance in the table. Each instance deletes rows older than 120 s every 30 s (`DELETE … WHERE epoch_second < $now -
120`). Concurrent deletes from several instances are harmless.

**Why UNLOGGED**: the counts live at most 2 minutes. Losing them in a crash or a failover only resets windows,
temporarily in the clients' favour. UNLOGGED tables write no WAL, so the once-per-second churn doesn't bloat
replication or backups.

**Alternatives considered**:
- *A database round trip per request (`UPDATE … RETURNING`)*: exact, but rejected in clarification Q1 (latency, and
  load that grows with request rate).
- *Leasing blocks of tokens from a database row*: no overshoot at all, but with small caps a block is one token, which
  brings back a round trip per request. Leases also fit a rolling window poorly.
- *`LISTEN/NOTIFY` for counts*: rejected. The notification payload is limited to 8 KB, there is one notification per
  change, and Principle II says new shared state goes through tables.
- *Redis*: rejected by Principle II/VIII. It would be new infrastructure, and PostgreSQL is enough at this rate.

## R5. When sharing fails (clarification Q2: degrade to local)

**Decision**: if a flush, heartbeat or read fails, or the last successful read is older than 3 sync intervals, the
instance enters **local mode**:
- `p` is treated as 0, so each instance enforces the full cap on its own counts plus the remote buckets it already
  holds, which age out normally. Nothing is refused because sharing failed (FR-019).
- It logs one WARN when local mode starts and one INFO when it ends (with its duration), and sets the gauge
  `fv_throttle_local_mode` to 1/0.
- It increments `fv_throttle_sync_failures_total{step=flush|heartbeat|read}` on each failure.
- Ticks keep running. The first fully successful tick ends local mode, and the fleet bound applies again within
  60 s, once the stale local buckets have aged out (spec Edge Case).

The worst case in local mode is cap × number of instances per minute, as the spec states.

## R6. Where caps are stored

**Decision**:
- A nullable column **`rate_limit_per_minute INTEGER`** on `api_keys` and on `organisations`, with `CHECK (… IS NULL
  OR … >= 1)`. NULL means "no cap of its own; the default applies".
- The two service-wide defaults are rows in the existing `service_settings` table: `throttle_default_key_per_minute`
  and `throttle_default_org_per_minute`. The value is a positive integer as text, checked by a new CHECK constraint.
  An absent row means "no default" (FR-006: not limited at that level).
- New CHECK constraints keep the system organisation out:
  - `organisations`: `id <> '…100000000001' OR rate_limit_per_minute IS NULL`;
  - `api_keys`: `owner <> '…100000000001' OR rate_limit_per_minute IS NULL`.
  The CLI checks first and gives a clear message (US5 AC3). The constraints are defence in depth.

**Rationale**:
- A cap belongs to exactly one key or organisation (FR-012), so a column is the simplest model: no join table and no
  orphans. A cap stays with a revoked key (Key Entities).
- `service_settings` already holds service-wide shared settings with `updated_at`/`updated_by`, which covers the audit
  of FR-016 together with the CLI log line.

**Alternatives considered**: a separate `rate_limits(scope, subject_id, per_minute)` table. It would need a polymorphic
foreign key, which can't be expressed, or two tables. Rejected as more moving parts for the same data.

## R7. Seeding the initial caps (FR-015, clarification Q3)

**Decision**:
- **`V18__validation_rate_limits.sql`** adds the columns, constraints and UNLOGGED tables. It also:
  - inserts `throttle_default_key_per_minute = 600` and `throttle_default_org_per_minute = 1200` into
    `service_settings` (`ON CONFLICT DO NOTHING`);
  - sets `rate_limit_per_minute = 60` on the trial organisation (`…100000000002`, seeded by V15, so it always exists);
  - sets `30` on the trial validator key (`…200000000002`) **if that row exists**. The update matches nothing on a
    fresh database.
- `ApiKeyRepository.upsertWellKnown` inserts the trial validator key with `rate_limit_per_minute = 30`. Its `ON
  CONFLICT (id) DO UPDATE SET …` list does **not** include the column. A key first installed after V18 therefore gets
  30, and an operator's later change or removal (NULL) survives every restart and every key rotation (US3 AC2). The
  `system administrator` key is inserted with NULL, as the constraint requires.
- `ConstantMigrationConsistencyTest` is extended so that the ids and values in V18 match `Constant`.

**Rationale**: migrations run exactly once, so their seeds never overwrite operator changes. The single insert-only
path covers the one row that may not exist yet when V18 runs.

## R8. Making cap changes reach every instance (FR-014: within 60 s)

**Decision**: `ThrottleCaps` is an immutable snapshot of every non-NULL key cap, every non-NULL organisation cap and the
two defaults. `ThrottleSync` reloads it every `FV_THROTTLE_CAPS_REFRESH_MS` (default **10 000 ms**, bounded
1 000–30 000) with one query, and swaps it in atomically (a `volatile` reference). The query is a `UNION ALL` of the
two tables `WHERE rate_limit_per_minute IS NOT NULL`, plus the two settings rows. The hot path does two map lookups
and never touches the database.

- The effective key cap is `keyCaps.get(keyId)`, else `defaultKey`, else unlimited.
- The effective organisation cap is `orgCaps.get(orgId)`, else `defaultOrg`, else unlimited.
- If a subject has no effective cap at either level, it is not counted at that level.

The worst-case propagation is the refresh interval plus one tick, about 11 s, well inside 60 s. If a refresh fails,
the last snapshot stays and the failure is counted and logged. The first snapshot is loaded in warm-up. Until it is
loaded, the handler answers 503 `not-ready` (`Retry-After: 5`), as the auth gate already does.

**Alternatives considered**:
- *Carry caps in `KeyInfo` through the auth cache*: rejected. The cache TTL (`FV_AUTH_CACHE_TTL_MS`) may be up to 1 h,
  which breaks FR-014, and the defaults and organisation caps would still need their own path.
- *`LISTEN/NOTIFY` on cap change*: faster, but a second subscriber connection buys little over a 10 s poll. The poll
  is also the safety net ConfigSync needs anyway. It can be added later without a contract change.

## R9. Usage accounting for refused requests

**Decision**: a 429 is **not** written to `validation_usage`. It is counted in
`fv_throttle_refused_total{scope}` and summarised in the log (R11).

**Rationale**: a client that keeps hitting its cap would otherwise turn every refused request into a usage row and a
queue slot. Database writes would grow with abuse, which is exactly what the throttle exists to stop. A refused
request does no validation work, so it has no cost to attribute (spec 008's purpose). Spec 008's convergence
behaviour ("authorised validate requests the router refuses still get a usage record") is narrowed. The throttle runs
before the usage fallback, so throttled requests are excluded from that rule. This is recorded in the changelog and
in spec 008's amendment note.

## R10. HTTP contract: status, problem type and headers

**Decision**:
- **429** `application/problem+json`. `type: "rate-limited"`. `detail` names the cap that was reached: "This API key's
  rate limit of 30 validation requests per minute was reached; retry after 12 s", or "This organisation's rate
  limit …".
  - Extension members: `"scope": "key" | "organisation"`, `"limit": <int>`, `"retryAfterSeconds": <int>`.
  - Nothing about other keys appears (FR-010).
- **Headers** (FR-010a) use the three fields of draft-ietf-httpapi-ratelimit-headers (versions up to -07), which
  GitHub, Kong, Envoy and others widely implement:
  - `RateLimit-Limit`: the effective cap that leaves the least remaining;
  - `RateLimit-Remaining`: the remaining allowance in the window after this request, floored at 0;
  - `RateLimit-Reset`: seconds until the allowance next grows.
  - On a 429, add `Retry-After` (seconds), as RFC 9110 defines.
  - The headers are set by `ThrottleHandler` before `ctx.next()`, so 200s, 4xx and 5xx all carry them.
    `ErrorResponder` adds headers and never clears ones already set.
  - When the key and organisation remaining counts are equal, the key's values are used. The headers never say which
    cap was used (spec Assumptions).
  - A subject with no effective cap at either level gets no headers.
- **CORS**: `CorsConfig.exposedHeaders` gains the three `RateLimit-*` names (`Retry-After` is already exposed).
- **`openapi.yaml`**:
  - new component response `TooManyRequests` and header components `RateLimitLimit`, `RateLimitRemaining`,
    `RateLimitReset` and `RetryAfter`;
  - `/{version}/validate` lists `'429'` and the three headers on `'200'`;
  - the operation description gains one sentence about caps.
  This is an additive change: new optional headers and a new status the server may now return (Principle IV). The
  changelog calls out 429 under **Added** and **Changed**.

**Alternatives considered**: the newer draft (-08 and later) single `RateLimit` / `RateLimit-Policy` structured fields.
Rejected for now because client and library support is still thin. The three-field form is what integrators parse
today.

## R11. Observability (FR-016–FR-019)

**Decision**: these metrics carry no per-key or per-organisation tags (FR-017):

| Metric | Type | Tags |
|---|---|---|
| `fv_throttle_refused_total` | counter | `scope` = key \| organisation, `reason` = cap \| unconfirmed |
| `fv_throttle_checked_total` | counter | (none) |
| `fv_throttle_tracked_subjects` | gauge | (none) |
| `fv_throttle_evictions_total` | counter | (none) |
| `fv_throttle_peers` | gauge | (none) |
| `fv_throttle_local_mode` | gauge 0/1 | (none) |
| `fv_throttle_sync_failures_total` | counter | `step` = flush \| heartbeat \| read \| caps \| cleanup |
| `fv_throttle_sync_seconds` | timer | (none) |
| `fv_throttle_ticks_skipped_total` | counter | (none) |

**Logs**:
- **Refusals**: one INFO line per subject per minute at most: `throttle: key <keyId> (org <orgId>) refused <n>
  request(s) in the last minute (cap <c>, scope key); first request id <id>`. The first refusal in a minute logs at
  once. Later refusals in that minute are counted and reported on the next line. This keeps the log bounded under
  sustained overload (FR-018). The tracking state lives in the subject's `Window`, so it stays bounded.
- **Local mode**: a WARN when it starts and an INFO when it ends (R5).
- **CLI changes**: `cli: limit set key=<name> id=<uuid> old=<v|none> new=<v>` (FR-016). Secrets are never logged;
  the CLI never sees key values.

## R12. Operator command line (FR-013)

**Decision**: a new command group **`limit`** in `AdminCli`, implemented in `LimitCommands`:
- `limit set (--key <name> | --org <short-name> | --default-key | --default-org) --per-minute <n>`;
- `limit remove (--key <name> | --org <short-name> | --default-key | --default-org)`;
- `limit list [--org <short-name>]`.
The full syntax, output and exit codes are in [contracts/operations.md](./contracts/operations.md) §2.

**Rules**:
- `--per-minute` must be a whole number from 1 to 1 000 000. Any other value is a usage error, exit 2 (US3 AC4).
- An unknown key or organisation is not found, exit 3 (AC6).
- The system organisation or one of its keys is a conflict, exit 4, with "The system organisation and its keys are
  never rate limited" (US5 AC3).
- A revoked key may still have a cap set. It is harmless, and it lets an operator prepare a cap before un-revoking.

**Rationale**: the group follows `org`/`key`/`usage`. One verb with mutually exclusive targets keeps the surface
small.

## R13. Performance budget (SC-002: ≤ 5 ms added at p95; Principle VI)

**Decision**: the hot path does the following, with no I/O and no allocation beyond the response header strings:
- two `ConcurrentHashMap`/Caffeine lookups for the caps;
- two Caffeine `get` calls for the windows;
- two synchronized ring updates;
- three `putHeader` calls.

The expected cost is in microseconds. The PR carries `perf/results-1.4.0.md`, which runs the existing k6 single
resource scenario against 1.3.0 and 1.4.0 with the default caps raised so they don't refuse, plus one run at the cap
to measure 429 throughput. Principle VI requires this for a change on the hot validation path.

## R14. Release

**Decision**: **1.4.0** (MINOR), migration **V18**. This is the first feature after 1.3.0, so it gets a new minor
version rather than a re-publish of 1.3.0. The changelog lists the new 429, the headers, the `limit` CLI group, the
metrics, the defaults and the spec 008 narrowing (R9). There are no deployments yet, so no rolling upgrade or rollback
notes are needed.
