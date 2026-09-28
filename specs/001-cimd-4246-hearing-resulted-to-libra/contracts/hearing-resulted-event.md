# Contract: `public.events.hearing.hearing-resulted` (consumed)

| Item | Value |
|---|---|
| Publisher | `cpp-context-hearing` (`hearing-event-processor/src/yaml/public-publications-descriptor.yaml`) |
| Transport | Artemis topic `public.event`, JMS property `CPPNAME = public.events.hearing.hearing-resulted` |
| Subscription | durable, `service-cp-crime-results-enforcementworkflow.hearing-resulted`, selector `CPPNAME = 'public.events.hearing.hearing-resulted'` |
| Schema | `cpp-context-hearing/hearing-event/hearing-event-processor/src/yaml/json/schema/public.events.hearing.hearing-resulted.json` → core `hearing.json` (`cpp-platform-core-domain/criminal-court-public-model`) |
| Required top level | `hearing`, `isReshare`, `sharedTime`, `hearingDay` (`additionalProperties: true`) |
| Sample | `cpp-platform-core-domain/DesignSchemas/public/sample/hearing.events.hearing-resulted-firstHearing.json` |

## Fields relied on

| Path | Use | Note |
|---|---|---|
| `isReshare` | skip when true | R2 |
| `hearingDay` | `dateOfHearing` | R14 |
| `sharedTime` | persisted | |
| `hearing.id` | idempotency key | R17 |
| `hearing.courtCentre.code` | `courtHearingLocation` | R15 |
| `hearing.prosecutionCases[].id` | idempotency key; linked-application check | |
| `…prosecutionCaseIdentifier.prosecutionAuthorityOUCode` | Enforcement filter (`GAPGD00`) | R1 |
| `…prosecutionCaseIdentifier.caseURN` | `caseUrn`, unchanged | R8 |
| `…defendants[].id` | idempotency key | |
| `…defendants[].prosecutionAuthorityReference` | `prosecutorDefendantId`, unchanged (**defendant level**, not `prosecutionCaseIdentifier.prosecutionAuthorityReference`) | R9 |
| `…defendants[].personDefendant.personDetails.*` | defendant details | data-model §2 |
| `…defendants[].offences[].judicialResults[]`, `…defendants[].defendantCaseJudicialResults[]`, `hearing.defendantJudicialResults[]` (by `masterDefendantId`) | judicial results | R16 |
| `judicialResult.judicialResultTypeId` (required UUID) | reference-data lookup key | R4 |
| `judicialResult.orderedDate` | `on` date for the lookup | |
| `hearing.courtApplications[].courtApplicationCases[].prosecutionCaseId` | out-of-scope guard | R3 |

The consumer projection ignores unknown properties, so additive upstream changes are safe.
