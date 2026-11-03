# ADR 002 — CIMD-4530: GOB account data on the shared public event topic (and at rest in the broker)

- **Status**: Proposed (2026-10-19). Must be Accepted before production use (feature 003 spec FR-020).
- **Feature**: `specs/003-enforcement-nows-trigger` (research R18)
- **Constitution**: Principle IV requires at-rest personal-data decisions to be recorded here, under
  `specs/001-cimd-4246-hearing-resulted-to-libra/adrs/`, before the data is stored.
- **Deciders**: architect, information assurance, tech lead. **Author**: developer (CIMD-4530).

## Context

The architect's design (draft, 19/10/2026) requires the enforcement workflow to send GOB's response to the hearing
NOWS service as a **public event** on Common Platform's Artemis broker. The payload contains GOB's NOWS data items:
defendant account details, contact details, payment and transaction history, impositions and creditors, bank details
for payment, and the GOB account number. Feature 002 handed the same data over by a direct, access-controlled REST
command.

Public events go to the shared `public.event` topic. Delivery is filtered by each subscriber's `CPPNAME` selector, so
only the hearing NOWS service's subscription receives this event, but:

- any context could add this event name to its subscription without a change in the producer;
- the broker persists the message until every matching durable subscription has consumed it (at rest in the broker
  store, and in the dead-letter queue if it fails repeatedly);
- broker access is controlled by broker credentials, not by per-event access rules (CPP event processors have no
  Drools access control).

## Options

1. **Accept the public event with the full data** (architect's design), with controls: synthetic data outside
   production; the event name documented as restricted; no logging of payloads (W, H, broker diagnostics); DLQ
   handling procedure for this event; review of subscriptions to this name.
2. **Public event without the data** (ids only); H fetches GOB data from W over an internal API. Removes the data from
   the broker but adds a synchronous W API and an H client.
3. **Point-to-point queue** for W → H instead of the public topic. No precedent in CPP (no AKS queues for H).

## Decision

Pending. The plan proceeds with option 1 (architect's design); options 2 and 3 are the fallbacks if information
assurance does not accept it.

## Consequences

- Option 1: no further design change; the controls above become tasks (docs, runbook, tests asserting no payload
  logging).
- Option 2 would change contracts `gob-data-public-event.md` and add an API to W.
