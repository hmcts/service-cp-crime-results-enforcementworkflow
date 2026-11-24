# Contract: PDF rendering via System Doc Generator BYO-FS (research R2)

Source: `hmcts/cpp-context-system-doc-generator`, branch `team/CHD-2786` (**not yet merged to `main`** at time of
writing — go-live gate, spec.md Assumptions): `systemdocgenerator-query/systemdocgenerator-query-api/src/raml/json/
schema/systemdocgenerator.query.document.json`,
`systemdocgenerator-integration-test/.../it/GenerateDocumentWithUriIT.java`. Owned by the System Doc Generator team;
this service is a client, not an owner, of this contract.

## Outbound: `generate-document` command

| Field | Rule |
|---|---|
| `templateIdentifier` | required; the Docmosis template id for the notification type (FIRST_SHARE / AMEND_RESHARE) |
| `conversionFormat` | required; `pdf` |
| `requestedTime` | required; ISO-8601 date-time |
| `payloadFileUri` **or** `payloadFileServiceId` | exactly one required (query schema `oneOf`); this service always uses `payloadFileUri` (BYO-FS), never the legacy Material-service-id path |
| `destinationFileUri` | optional; when supplied (this service always supplies it), the rendered PDF is written there instead of a generated `documentFileServiceId` |

`payloadFileUri` points at a blob this service writes the render payload (JSON) to **before** calling
`generate-document`; `destinationFileUri` points at a blob this service only computes the URL for — it never
writes to it, the doc generator does. Both are two blob **names** (path-prefixed `result-notification/requests/`
and `result-notification/documents/`) inside the **one** Blob container this service owns, not two separate
containers — confirmed against the working precedent in `cpp-context-staging-dvla` branch `team/CHD-2786`'s
`DocumentGeneratorService` (research.md R8). Both are accessed by `DefaultAzureCredential`/Workload Identity —
no SAS tokens or connection strings in any real environment (CLAUDE.md hard rule; a test-only Azurite
connection-string, gated by `azurite-enabled`, is the sole exception).

**Completion is event-driven, not polled** (correction from an earlier draft of this contract): the doc
generator announces completion with a public Artemis event —
`public.systemdocgenerator.events.document-available` (success) / `...generation-failed` (failure) — matched by
`sourceCorrelationId` and `originatingSource`. This service reuses its existing JMS/Artemis consumer
infrastructure for this; the query endpoint below is the doc generator's own API for other clients, not the
mechanism this service uses to learn of completion.

## Query: `GET .../systemdocgenerator-query-api/.../document/{documentId}` — not used by this service's own flow

This service does **not** poll this endpoint as its completion mechanism (see the event-driven note above) — the
row's `DOC_REQUESTED → DOC_READY`/`DOC_FAILED` transition is driven entirely by the Artemis event. This endpoint
is documented here only for completeness (e.g. manual diagnostics/support use) and because the event payload's
shape mirrors it:

| Outcome | Fields present |
|---|---|
| In progress | neither `generatedTime` nor `failedTime` set yet |
| Success | `destinationFileUri`, `generatedTime` |
| Failure | `failedTime`, `reason` |

**`documentId` derivation when addressed by `payloadFileUri` alone** (no `payloadFileServiceId`): `documentId =
UUID.nameUUIDFromBytes(payloadFileUri, UTF_8)` — a deterministic id derived from the URI string, not a value the
`generate-document` response returns. Confirm the Artemis event carries the same correlation (`sourceCorrelationId`)
rather than requiring this derivation — D8 in plan.md, to verify against `team/CHD-2786`'s current HEAD.

## Known gaps to carry into implementation (not blocking the spec)

- Branch `team/CHD-2786` is active development (observed commits after the URI path landed, e.g. tag propagation,
  metadata-gap fixes) — re-check this contract against the branch's HEAD, not this snapshot, before implementation
  starts, and again before go-live if the branch is still unmerged.
- The URI-addressed integration test (`GenerateDocumentWithUriIT`) is currently `@Disabled` on this branch — treat
  the BYO-FS path as unverified end-to-end by its own owning team until that changes, and raise it with them rather
  than assuming it is release-ready once merged.
