# RCA-003 IC — UVCP residency decoupling

**Status:** IMPLEMENTATION AUTHORIZED (narrow)  
**Date:** 2026-08-11  
**Contract:** [rca-003-r5-failed-media-residency-clear-contract.md](./rca-003-r5-failed-media-residency-clear-contract.md) (R5.1–R5.3 ACCEPTED)  
**Ownership:** [rca-003-r4-conference-media-unavailable-ownership-trace.md](./rca-003-r4-conference-media-unavailable-ownership-trace.md)

## Goal

> Let UVCP consume **current media availability**, not **FAILED_MEDIA incident residency**.

```text
FAILED_MEDIA ≠ DEGRADED
FAILED_MEDIA ≠ CURRENT_UNAVAILABLE
```

## In scope

| Change | Detail |
|--------|--------|
| `MediaUsabilityFact.currentUnavailable(mediaState)` | Live path only: RECONNECTING / FAILED |
| `conferenceMediaUnavailable` (UVCP input) | Uses `currentUnavailable` only — **not** residency OR |
| Unit / state-matrix tests | IC matrix below |

## Out of scope

```text
Recovery / ICE / reconnect / Phase-2 / ownership / acceptance
Clear FAILED_MEDIA earlier / change ADR-0045 CLEAR predicate
UI string / pill chrome redesign
Timer / retry budget
```

## ADR note

ADR-0030 equated `mediaUnavailable(P) ⇔ failed-media residency` for presence.  
This IC **splits UVCP media-axis input** from residency (R5.3). Residency remains for diagnostics / clear policy. Formal ADR-0030 clarification may follow; UVCP must not wait on that to stop projecting incident as current health.

## Expected matrix

| Inputs | Pill / UVCP |
|--------|-------------|
| residency=true, media RECONNECTING/FAILED (ICE down) | DEGRADED or RECONNECTING (via **current** unavailable + recovering) |
| residency=true, media CONNECTED, receivePathLive=true, !recovering | **CONNECTED / healthy** |
| obligation/recovering=true, media unavailable | SYNCING / RECONNECTING (progress) |
| current available, !recovering | CONNECTED |

## Deploy

Unit green → gray install → observe sticky degraded without new WiFi RCA.
