# ADR-004: Hearing endpoints require a bearer token the simulator actually issued

## Status

Accepted — reverses the "Security: out of scope" decision recorded in
[the design spec](../superpowers/specs/2026-09-17-enforcement-workflow-simulator-design.md) §2.

## Context

The bundled contract `libra-gateway-hearing-events-v0.4.0.yml` declares both hearing operations as
secured:

```yaml
  "/hearing":        security: [ ClientCredentials: [] ]
  "/hearing/result": security: [ ClientCredentials: [] ]

  securitySchemes:
    ClientCredentials:
      type: oauth2
      flows: { clientCredentials: { tokenUrl: "/auth/token" } }
```

and defines a `401` response — *"Missing, expired or invalid bearer token."*

The simulator did not implement any of it. §2 listed **Security** under Out of scope — "No token
validation, no TLS/mTLS, no WS-Security equivalent. Explicitly deferred." — and §9 stated that
`401`, `403`, `404` and `500` "are not produced — there is no auth". `AuthController` issued an
opaque token that nothing ever checked, and said so in its own javadoc.

That deferral had a real cost, which was not obvious when it was made. The simulator exists so
Common Platform can exercise its side of this integration before the real Libra Gateway is
available. With no auth:

- CP's handling of `401` could not be exercised at all — the path was unreachable.
- CP's token-acquisition and refresh logic was never forced to run. An integration that never
  fetched a token, or fetched one and dropped it, passed every test identically to one that did it
  correctly.
- `expires_in: 3600` was decorative. Nothing distinguished a fresh token from a long-dead one.

A simulator that accepts anything trains the client to send anything. The first time CP meets the
real gateway is then the first time its auth path executes.

## Decision

**`POST /hearing` and `POST /hearing/result` require an `Authorization: Bearer <token>` header
carrying a token this simulator issued from `/auth/token` and has not yet expired.** Anything else
— header absent, a non-`Bearer` scheme, a token never issued, a token past its expiry — is `401`
with the contract's `ErrorResponse` body.

Three sub-decisions, each taken for the cheapest option that still tests something real:

1. **Track issued tokens, do not merely check the header is present.** A presence check would make
   `Authorization: Bearer x` sufficient, which still lets a client pass without ever calling
   `/auth/token`. An in-memory `ConcurrentHashMap` of token → expiry is a few lines more and makes
   the assertion meaningful: the caller completed the client-credentials exchange.

2. **A `HandlerInterceptor`, not a servlet `Filter`.** A filter runs before `@RestControllerAdvice`
   and would have to serialise its own error body, duplicating the `ErrorResponse` shape that
   `GlobalExceptionHandler` already owns — the exact divergence that advice was written to prevent.
   An interceptor's exception reaches the advice through the normal resolver chain, so the `401`
   body is produced by the same code path as every other error, and the new exception type follows
   the `UnknownResultCodeException` idiom already in `api/`.

3. **No `spring-boot-starter-security`.** The starter secures every endpoint by default, which
   would mean re-opening `/auth/token`, disabling CSRF for the form-encoded token call, and
   disabling form login — more configuration, and more framework, than the feature. The contract
   specifies an opaque token, not a JWT, so nothing the starter provides is actually needed.

Credentials on `/auth/token` remain unchecked. The contract declares no client registry and the
simulator has no business holding one; what is being simulated here is *the token exchange
happening*, not *the client being who it claims to be*.

## Consequences

- CP can exercise its `401` handling and its token-refresh path against the simulator. That was
  previously impossible.
- **Every existing integration test broke** and was updated to obtain a real token first —
  `HearingControllerIT`, `HearingResultControllerIT`, `HearingResultFixturePairIT`,
  `AllResultCodesConformanceIT`, `GlobalExceptionHandlerIT`. They fetch one from `/auth/token`
  rather than hard-coding a string, so the tests exercise the same exchange CP has to perform.
- **This breaks any consumer not already sending `Authorization`,** on the deploy that carries it.
  Enforcement is unconditional — there is no opt-out flag. A flag was considered and rejected on
  the grounds that a simulator running with auth disabled is not the simulator anyone is testing
  against, and the flag's off-state would silently become the default everywhere. The ordering
  question this creates — CP client first, or simulator first — is a rollout matter for the
  integration team, recorded in
  [pipeline artifact 004](../artifacts/004-bearer-token-enforcement-plan.html).
- Tokens live in memory only. A simulator restart invalidates every outstanding token, and a
  horizontally-scaled deployment would not share them. Neither matters for a single-instance,
  non-live stub; both would matter if that ever changed, and this is the assumption to revisit
  first if it does.
- The store grows unboundedly within a single run — one entry per `/auth/token` call, ~40 bytes
  each. Expired entries are evicted opportunistically on lookup. For a simulator's traffic this is
  not worth a scheduled sweep; a long-lived instance under heavy load would need one.
- Design spec §2 and §9 were amended, since both now describe behaviour the simulator no longer
  has.
