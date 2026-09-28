# Contract: Reference data — get result definition (consumed)

| Item | Value |
|---|---|
| Owner | `cpp-context-reference-data` (`referencedata-query-api`) |
| Definition | `referencedata-query/referencedata-query-api/src/raml/referencedata-query-api.raml:445-470`; response schema `…/src/raml/json/schema/referencedata.get-result-definition.json` |
| Request | `GET {REFERENCE_DATA_URL}/referencedata-query-api/query/api/rest/referencedata/result-definitions/{resultDefinitionId}?on={yyyy-MM-dd}` |
| Headers | `Accept: application/vnd.referencedata.get-result-definition+json`; `CJSCPPUID: {system user uuid}` (PCR `ReferenceDataClient` convention) |
| Path param | `resultDefinitionId` = event `judicialResult.judicialResultTypeId` |
| Query param | `on` = `judicialResult.orderedDate` (fallback `hearingDay`); optional, defaults to today |
| Response fields used | `shortCode` (others ignored) |
| 404 | treated as "no shortCode" → result dropped and logged |
| Caching | in-memory per `(id, on)` in iteration 1; bulk `cacheable` load in iteration 4 |

Open confirmation: that `judicialResultTypeId` equals the reference-data `id` (a strong schema-level inference; gap #3).
