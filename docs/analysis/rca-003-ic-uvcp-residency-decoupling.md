# RCA-003 IC — UVCP residency decoupling

**Status:** **IMPLEMENTED / PENDING FIELD VERIFY** (PR [#157](https://github.com/wangy4645/android-decentralized-talkback/pull/157))  
**Date:** 2026-08-11  
**Contract:** [rca-003-r5-failed-media-residency-clear-contract.md](./rca-003-r5-failed-media-residency-clear-contract.md) (R5.1–R5.3 ACCEPTED)  
**Ownership:** [rca-003-r4-conference-media-unavailable-ownership-trace.md](./rca-003-r4-conference-media-unavailable-ownership-trace.md)  
**Entry:** [rca-003-presentation-convergence-entry.md](./rca-003-presentation-convergence-entry.md)

## Goal

> Let UVCP consume **current media availability**, not **FAILED_MEDIA incident residency**.

```text
FAILED_MEDIA ≠ DEGRADED
FAILED_MEDIA ≠ CURRENT_UNAVAILABLE
```

Value is not “UI polish” — it splits:

```text
Incident State     → diagnostics / history only
Current Availability → UVCP → Pill
```

> 事故发生过，不代表现在不可用。

## In scope

| Change | Detail |
|--------|--------|
| `MediaUsabilityFact.currentUnavailable(mediaState)` | Live path only: RECONNECTING / FAILED |
| `conferenceMediaUnavailable` (UVCP input) | Uses `currentUnavailable` only — **not** residency OR |
| Unit / state-matrix tests | Lab matrix (below) |

## Out of scope

```text
Recovery / ICE / reconnect / Phase-2 / ownership / acceptance
Clear FAILED_MEDIA earlier / change ADR-0045 CLEAR predicate
UI string / pill chrome redesign
Timer / retry budget
New flap matrix / re-proof of protocol chain
```

## ADR-0030 (defer formal)

Do **not** open a large ADR now. Interim note only:

```text
FAILED_MEDIA residency is diagnostic incident state.
It MUST NOT be interpreted as current media availability.
```

Formal ADR-0030 clarification **after** gray PASS (avoid ownership/lifecycle churn before projection evidence).

## Lab matrix (unit)

| Inputs | Pill / UVCP |
|--------|-------------|
| residency=true, media RECONNECTING/FAILED (ICE down) | DEGRADED or RECONNECTING |
| residency=true, media CONNECTED, receivePathLive=true, !recovering | **CONNECTED / healthy** |
| obligation/recovering=true, media OK | SYNCING |
| current available, !recovering | CONNECTED |

## Gray field (three observations only — no redesign)

### Case 1 — recovery succeeds

```text
NETWORK_LOST → EDGE_RECOVERED → MEDIA CONNECTED → pill healthy
```

Check: `FAILED_MEDIA=true` must **not** keep pill degraded.

### Case 2 — truly not recovered

```text
NETWORK_LOST → attempt timeout → no EDGE_RECOVERED
→ pill degraded / reconnecting
```

Must **not** become healthy solely because residency is no longer OR'd into UVCP.

### Case 3 — historical residue (core #157 accept)

```text
FAILED_MEDIA residency=true
+ iceConnected=true
+ receivePathLive=true
→ healthy
```

## Deploy sequence

```text
merge #157 → one gray install → natural / existing scenes
→ watch EDGE_RECOVERED · MEDIA CONNECTED · pill
→ PASS → RCA-003 close
```

Do **not** announce RCA-003 VERIFIED before Case 1–3 evidence.
