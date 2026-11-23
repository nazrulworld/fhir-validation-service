# Contract: HTTP API changes (`openapi.yaml`, 1.4.0)

**Feature**: `014-api-throttling` | **Operation**: `validationApiValidate` (`POST /{version}/validate`) only (FR-001)

The `openapi.yaml` change lands before (or with) the code (Principle IV). The change is **additive**: a new status and
new response headers. Paths, parameters and request and response bodies are unchanged.

## 1. When the rules apply

| Situation | Counted | Can get 429 | `RateLimit-*` headers |
|---|---|---|---|
| Access control disabled (`FV_GLOBAL_DISABLED_ACL_SECURITY=true`) | no | no | no |
| Key of the system organisation (any role) | no | no | no |
| Any other key (ADMIN or VALIDATE) with an effective cap at ≥ 1 level | yes | yes | yes |
| Any other key with no effective cap at either level (defaults removed, no own caps) | no | no | no |
| Request refused by auth (401, 403) or still starting (503 from the auth gate, or from the throttle until it is ready: see §2) | no | — | no |
| Any operation other than `POST /{version}/validate` | no | no | no |

## 2. Check order

`auth (401/403)` → **throttle (429; or 503 `not-ready` until the instance is ready: the caps are loaded and its
peers count it, after two sync ticks, T056)** → contract validation (400) → content negotiation and parsing
(406/415/400) → admission (503) → validation (200/4xx/5xx).

- A 429 is answered without parsing or validating the body.
- A 429 is not written to `validation_usage` (research R9).

## 3. New response: 429 `rate-limited`

```yaml
components:
  headers:
    RetryAfter:
      description: Seconds to wait before a retry of this request can be admitted.
      schema: { type: integer, minimum: 1 }
    RateLimitLimit:
      description: >-
        The per-minute validation cap that currently leaves the least allowance for this API key (its own cap or its
        organisation's). Not sent for keys that are not rate limited.
      schema: { type: integer, minimum: 1 }
    RateLimitRemaining:
      description: >-
        Validation requests still allowed in the current rolling 60-second window, after this request. Approximate
        when the service runs as several instances.
      schema: { type: integer, minimum: 0 }
    RateLimitReset:
      description: Seconds until the allowance next grows.
      schema: { type: integer, minimum: 0 }
  responses:
    TooManyRequests:
      description: >-
        rate-limited: the API key's or its organisation's per-minute validation cap was reached. Nothing was
        validated and the request does not count towards any cap. Retry after the Retry-After seconds.
      headers:
        Retry-After:        { $ref: '#/components/headers/RetryAfter' }
        RateLimit-Limit:     { $ref: '#/components/headers/RateLimitLimit' }
        RateLimit-Remaining: { $ref: '#/components/headers/RateLimitRemaining' }
        RateLimit-Reset:     { $ref: '#/components/headers/RateLimitReset' }
      content:
        application/problem+json:
          schema:
            $ref: '#/components/schemas/Problem'
          example:
            type: rate-limited
            status: 429
            title: Too many requests
            detail: "This API key's rate limit of 30 validation requests per minute was reached; retry after 12 s"
            scope: key
            limit: 30
            retryAfterSeconds: 12
            correlationId: 3f0c6f0e-8c1e-4b7a-9d55-0d2f6c1b9a10
```

Problem extension members. They are added to `components/schemas/Problem` as optional properties, described as
"present only for type rate-limited", in the same way `unresolvedProfiles` is documented today:

| Member | Type | Values |
|---|---|---|
| `scope` | string | `key` or `organisation` |
| `limit` | integer | the cap that was reached |
| `retryAfterSeconds` | integer | the same value as the `Retry-After` header |

`detail` texts:
- key: `This API key's rate limit of <n> validation requests per minute was reached; retry after <s> s`
- organisation: `This organisation's rate limit of <n> validation requests per minute was reached; retry after <s> s`

No other key, organisation, usage figure or instance detail appears (FR-010).

## 4. Changes to `/{version}/validate`

- `responses` gains `'429': { $ref: '#/components/responses/TooManyRequests' }`.
- `'200'` gains the headers `RateLimit-Limit`, `RateLimit-Remaining` and `RateLimit-Reset` (the `$ref`s above).
- The description gains one paragraph:

  > Requests are limited per API key and per organisation, as validation requests per rolling minute (operator
  > configured). Responses to a limited key carry RateLimit-Limit, RateLimit-Remaining and RateLimit-Reset; above the
  > cap the answer is 429 rate-limited with Retry-After. Keys of the system organisation, and every request while
  > access control is disabled, are not limited.

- The other 4xx/5xx responses of this operation also carry the `RateLimit-*` headers when the key is limited. This is
  stated in the description; the per-response header declarations are only added to `200` and `429`, to keep the
  spec readable.

## 5. Header semantics

Values are computed by the throttle for the request that is being answered:

- **Limit**: the effective cap of the level (key or organisation) with the smaller remaining allowance. On a tie, the
  key.
- **Remaining**: `max(0, cap − windowed count after this request)` at that level. On one instance it is exact. With
  several instances it may lag the fleet by up to one sync interval, and the fleet bound of FR-005 still holds.
- **Reset**: seconds until the oldest counted request leaves the window, i.e. when Remaining next grows. It is 0 when
  nothing is counted.
- **Retry-After** (429 only): seconds until a retry can be admitted. When both levels refuse, it is the larger of the
  two waits. When the refusal comes from the multi-instance burst guard (research R4), it is
  `ceil(2 × FV_THROTTLE_SYNC_INTERVAL_MS / 1000)` (2 at the default), the time an admission needs to be confirmed.

## 6. CORS

`Access-Control-Expose-Headers` gains `RateLimit-Limit`, `RateLimit-Remaining` and `RateLimit-Reset`. `Retry-After`
is already exposed.

## 7. Contract tests

`ValidationThrottleIT` (Testcontainers, one instance) covers:
- caps of 5 per key and 6 per organisation (US1, US2);
- the headers on a 200 and on a 4xx (a malformed resource);
- the problem shape and `Retry-After`;
- no headers for a system-organisation key (US5) or in disabled mode (US4).

The existing `ContractAssertions` checks every response against `openapi.yaml`, which fails if a 429 or a header is
not declared.
