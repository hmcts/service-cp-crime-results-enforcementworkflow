# Enforcement Workflow (service)

`service-cp-crime-results-enforcementworkflow`

A Common Platform (CP) Spring Boot service that owns **enforcement workflow** — the orchestration
and processing that sits behind CP's enforcement functionality.

## Responsibilities

This service is the home for enforcement workflow logic in CP. Its core responsibilities are to:

- **Consume resulted enforcement hearings** and drive the downstream enforcement workflow.
- **Enrich and map** the resulted-hearing data into the shape the GoB (General of the Bench)
  enforcement system expects.
- **Call the Enforcement Staging Service synchronously** to push the enriched result into GoB and
  receive the resulting account state.
- **Publish the GoB account state** so downstream processing — Notice of Warning / fine
  notifications (NOWs) and outstanding-fines reporting — can be generated.

It is intended to be the single owning service for enforcement workflow across CP. Over time,
**enforcement-related logic currently living in the results service, the SJP flow, and the
enforcement function apps will be migrated into this service**, consolidating that functionality
behind one boundary (strangler-fig).

> Owned by the **cp-case-ingestion-and-material** team.

API contract: [`api-cp-crime-results-enforcementworkflow`](https://github.com/hmcts/api-cp-crime-results-enforcementworkflow).

> ⚠️ **Scaffold.** Created from the HMCTS template
> [`service-hmcts-crime-springboot-template`](https://github.com/hmcts/service-hmcts-crime-springboot-template).
> The domain implementation (result consumption, enrichment, GoB staging client, account-state
> publication) is not yet built.

## Tech stack

- **Java 25**, **Spring Boot 4**, **Gradle**
- Observability: Spring Boot Actuator, OpenTelemetry, Prometheus
- Hosting: Azure (App Insights, ACR/AKS via the ADO mirror pipeline)

## Prerequisites

- ☕️ **Java 25 or later** on your `PATH`
- ⚙️ **Gradle** (the wrapper pins the version — `gradle/wrapper/gradle-wrapper.properties`)

```bash
java -version
gradle -v
```

## Build & test

```bash
gradle build      # compile + checks + unit/integration tests
gradle test       # unit and integration tests only
```

### Static analysis (PMD)

```bash
gradle pmdTest
```

## CI/CD

GitHub Actions workflows live in `.github/workflows`:

- `ci-draft.yml` — build/verify on PRs and branch pushes.
- `ci-released.yml` — on a **published GitHub Release** (`release: [published]`), publishes the
  artefact and triggers the Docker build/deploy via `ci-build-publish.yml` (with a Trivy image scan
  and a release-notes appender that records the published image coordinates).
- `code-analysis.yml`, `codeql.yml`, `secrets-scanner.yml`, `auto-merge-dependabot.yml`.

`main` and `team/*` branches are protected and require at least one approving review.

## Contributing

See [CONTRIBUTING.md](.github/CONTRIBUTING.md). Branch naming: `team/<topic>`.

## License

MIT — see [LICENSE](LICENSE).
