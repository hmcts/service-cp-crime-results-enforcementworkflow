# Interface Contracts: CIMD-4246

**Plan**: [../plan.md](../plan.md)

| Interface | Direction | Contract | Status |
|---|---|---|---|
| `public.events.hearing.hearing-resulted` | Artemis → workflow | `cpp-context-hearing/.../public.events.hearing.hearing-resulted.json` (core `hearing.json`) | existing, read-only |
| Reference data `GET …/result-definitions/{id}?on=` | workflow → reference data | `referencedata-query-api.raml:445-470`, response `referencedata.get-result-definition.json` (`shortCode`) | existing, read-only |
| Gateway `POST /hearingResulted` | workflow → gateway | `api-cp-crime-results-enforcementgateway/src/main/resources/openapi/openapi-spec.yml` (`postHearingResulted`: 200 `HearingResultedResponse`, 400/502 `ErrorResponse`) | **added locally**, to be published |
| APIM `POST {cppi-v4}/hearingResulted` (`libra-hearingresulted`) | gateway → APIM | the same body as the Libra `HearingResultedRequest`; header `Ocp-Apim-Subscription-Key` | **to be requested** |
| Libra `POST /hearing/result` | APIM → Libra | `libra-gateway-hearing-events-openapi-v0.4.0.yml` (local amendment) | Libra-owned |

Per-interface detail:
- [hearing-resulted-event.md](hearing-resulted-event.md): inbound public event (consumed).
- [reference-data-result-definition.md](reference-data-result-definition.md): shortCode lookup (consumed).
- [gateway-post-hearing-resulted.md](gateway-post-hearing-resulted.md): workflow → gateway (**new**, exposed by the gateway).
- [apim-libra-hearingresulted.md](apim-libra-hearingresulted.md): gateway → APIM → Libra (**new** APIM operation).

The authoritative machine-readable contracts stay in their owning repos and are **not duplicated** here. These files record how this feature uses them.
