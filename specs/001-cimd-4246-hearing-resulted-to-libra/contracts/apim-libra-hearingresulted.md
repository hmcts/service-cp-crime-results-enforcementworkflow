# Contract: APIM `libra-hearingresulted` (new; gateway → APIM → Libra)

| Item | Value |
|---|---|
| APIM API | existing `cppi-v4` (same as `libra-confirmedhearing`) |
| Operation | `libra-hearingresulted`: `POST /hearingResulted` |
| Base URL (gateway config `cp.libra.apim-base-url`, env `LIBRA_APIM_BASE_URL`) | per-environment APIM internal gateway host + `/cppi/v4`, supplied by the environment's config (hosts deliberately not recorded here); prd/prp/prx to be confirmed |
| Caller credential | `Ocp-Apim-Subscription-Key` only (`cp.libra.apim-subscription-key`, env `LIBRA_APIM_SUBSCRIPTION_KEY`, placeholder today) |
| Policy (platform team) | **`forward-request timeout="35"`** (seconds; research.md R20, must be less than the gateway's 40s read timeout); OAuth2 client-credentials to Libra (`/auth/token`); rewrite to Libra `POST /hearing/result`; **pass the response status and body through unchanged** |
| Backend contract | `specs/001-cimd-4246-hearing-resulted-to-libra/docs/libra-gateway-hearing-events-openapi-v0.4.0.yml` → `resultHearing` (`/hearing/result`): 200 `HearingResultedResponse`; 400/401/403/404/500 `{errorCode, errorDescription}` |
| Mock | update `service-cp-crime-results-enforcementgateway/docs/LibraApimMockPolicy.md` to return a sample 200 body; or target the CIMD-4372 GoB simulator |
| Status | **to be requested** (tasks.md T007). Gateway handling: any non-2xx (incl. 3xx) or an empty/invalid 2xx body → 502 to the workflow (R24) |

These are assumptions that follow the confirmedHearing pattern (gap #8/#9). Confirm them with the platform team when requesting.
