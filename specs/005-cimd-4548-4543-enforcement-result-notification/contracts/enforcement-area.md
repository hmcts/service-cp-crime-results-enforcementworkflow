# Contract: `enforcement_area` reference-data lookup (CIMD-4389) — OPEN DEPENDENCY, not yet confirmed

**This is not a confirmed contract.** No contract document, OpenAPI spec, or schema for this lookup has been
published anywhere this feature's research could find. CIMD-4389 (the ticket referenced by spec.md's Terms
section for "Division Code") is the only named source, and it has not been read as part of this feature's
research — only referenced. Everything below is this feature's own assumption of the shape it needs, written so
`EnforcementAreaClient` has something concrete to implement against, per constitution Principle IX ("an open
business or contract question MUST NOT block an iteration silently... recorded as an explicit assumption with
safe interim behaviour... with a named owner"). **Owner: the reference-data team (plan.md Dependency D1).**
Confirm this shape against the real contract before `EnforcementAreaClient` is considered final — do not ship
against this assumption unconfirmed.

## Assumed request

`GET` by Division Code (the locally-derived prefix of `relatedReferenceNumber` — research.md R6), analogous to
`ReferenceDataClient`'s existing `GET /result-definitions/{id}` pattern in this codebase
(`src/main/java/uk/gov/hmcts/cp/client/ReferenceDataClient.java`):

```text
GET {ENFORCEMENT_AREA_URL}/reference-data-query-api/query/api/rest/enforcement-area/internal/{divisionCode}
```

(The exact path is a placeholder following this codebase's existing reference-data URL convention — not
confirmed.)

## Assumed response

| Field | Assumed meaning | Used by this feature? |
|---|---|---|
| `divisionCode` | echo of the lookup key | No — never trusted back as authoritative; `area` is always computed locally from the Confiscation set (FR-003) |
| `teamEmail` | the resolved team inbox address | Yes — `team_email` on `enforcement_result_notification` |
| `areaLabel` (if present) | a human label, e.g. "Enforcement"/"Confiscation" | No — FR-003 explicitly requires CP to compute area from the Confiscation Division Code set itself, never to trust this field |

## Assumed error handling (mirrors `ReferenceDataClient`'s existing pattern)

| Outcome | Handling |
|---|---|
| 404 / confirmed no match | `Optional.empty()` → row transitions to `INBOX_NOT_FOUND` (FR-006), not cached |
| Any other HTTP error, timeout, connection failure | `EnforcementAreaUnavailableException` (unchecked) → **transient**, retried like every other external call in this codebase — must **not** collapse into `INBOX_NOT_FOUND`, which is reserved for a confirmed no-match only |

## What is genuinely unknown (do not guess further than this document already has)

- The real request path, query parameters, and response field names.
- Whether the lookup is keyed by Division Code at all, or by some other field this feature doesn't yet know
  about.
- Whether there is a bulk/list variant (this feature only ever needs one Division Code per call).
- Rate limits, caching guidance, or an SLA.

If CIMD-4389 or an existing contract/OpenAPI spec for this lookup is found before implementation starts, replace
this entire file's content with the real contract — do not merge the assumption and the confirmed contract
together.
