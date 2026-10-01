# ADR-005: The simulator logs full hearing request and response bodies at INFO

## Status

Accepted. This is a deliberate, scoped deviation from `context/logging-standards.md` ("Never log").

## Context

Testers using Postman against the simulator need to see exactly what was posted to
`POST /hearing` and `POST /hearing/result`, and exactly what came back. That includes 400/401
rejections, which are produced before the controller runs. Until now the simulator logged only
a one-line summary (`caseUrn`, `idempotencyKey`).

`context/logging-standards.md` forbids logging:

- full HTTP request or response bodies;
- PII (names, addresses, dates of birth);
- hearing dates;

and says non-compliant services fail code review. The hearing bodies contain all three:
`defendantDetails`, `parentGuardianDetails`, `employerDetails` and `dateOfHearing`.

What makes this different from a real service:

- The simulator only handles **synthetic** data. It stands in for Libra Gateway in local and dev
  testing and is never the system of record.
- `LiveEnvironmentGuard` stops it starting if any of `prod`, `production`, `live`, `perf` or
  `preprod` is an active profile (ADR-001).
- Nothing packages it into the service's container image (ADR-001).

## Decision

- `HearingTrafficLoggingFilter`, registered only on `/hearing` and `/hearing/result`, logs:
  - one INFO line per request (`Hearing request received`), written before processing;
  - one INFO line per response (`Hearing response sent`), including the status.
- Bodies are embedded as **nested JSON objects** (`requestBody` / `responseBody`) using logstash
  `StructuredArguments.raw`, so they can be queried with `jq`. A body that isn't valid JSON is
  logged as a plain string. An empty body is left out.
- Each line also carries `httpMethod`, `path` and, when supplied, `correlationId`.
- The `Authorization` header is **never** logged. Neither is any other header.
- `logback.xml` gains the `<arguments/>` provider so structured arguments appear as top-level JSON
  fields. It is added after `logLevel`; the rest of the template config is unchanged.

The deviation applies to the simulator subproject only. The enforcement workflow service itself
remains fully bound by the logging standard.

## Consequences

- Every simulator log stream contains synthetic PII-shaped data and hearing dates. This is
  acceptable only because of the guards above. **If the simulator is ever allowed to run
  against real data, or in a shared environment where real data could reach it, this ADR must be
  revisited first.**
- A reviewer applying the logging standard mechanically will flag the filter; this ADR is the
  answer.
- The filter buffers each full request and response body in memory. That's fine for the
  simulator's small payloads, but it is one more reason not to copy this filter into the service.
- If full bodies later turn out to be too noisy, they can move to DEBUG without changing the
  filter's structure.
