# GROUP → CONFERENCE Transition — Implementation Mapping

**ID:** group-conference-transition-implementation-mapping  
**Date:** 2026-08-24  
**Version:** v0.1  
**Type:** Implementation Mapping · Lifecycle Contract  
**Status:** **FROZEN** (mapping only)  
**Implementation authorization:** **NONE**

**Parent:** [GROUP → CONFERENCE Transition Contract Review](./group-conference-transition-contract-review.md) (**ACCEPTED v0.1**)

This document maps INV-T1..T5 to existing code touchpoints. It does **not** authorize implementation, prescribe class designs, or amend upstream contracts.

---

## 1. Separation (baseline)

```text
Control admission     ≠  Media realization     ≠  Native cleanup
```

```text
SWITCH_TO_CONFERENCE
    ↓
SESSION_CREATED / MEETING_START          ← control admission (T1 · T4)
    ↓
┌──────────────────────────────────────┐
│ Conference realization (per module)    │  GROUP cleanup (async)
│ READY | PENDING | FAILED               │
└──────────────────────────────────────┘

Factory entry (per moduleId): single owner — unchanged
```

**Case C** changes **lifecycle dependency**, not **runtime ownership**. It does **not** permit concurrent mutation of the same factory entry.

---

## 2. INV → code mapping

| INV | Touchpoints | Minimal action | Explicitly out |
|-----|-------------|----------------|----------------|
| **T1** | `TalkViewModel.joinMeeting` · `TalkbackCoordinator.meshCallInternal` | Control admission must not depend on GROUP `MEDIA_RELEASED`, channel idle, or realization complete. Current overlap with GROUP release is **intentional**. | START `HangupMediaBarrier` · new teardown gates |
| **T2** | `MediaSessionManager.requestEngine` · existing pending queue / defer facts | Engine acquisition exposes **`PENDING → READY \| FAILED`**. Forbidden: sync throw as the only pending exit. | New ownership or parallel state authority in `SessionMediaRegistry` |
| **T3** | `beginConferenceInviteWhenEngineReady` · per-module continuation · conference batch projection | Each remote module gets an **independent** continuation. Batch state is **projection only** (counts / UI / governance), not a sync barrier. | Per-module Factory · `await all peers → invite` |
| **T4** | `meshCallInternal` · `channelGovernance` / MEETING_START transition | Control transition stays live under native cleanup latency. `PENDING` affects **that module's** realization only. | Expanding `HangupMediaBarrier` · A0.7 watchdog to START path |
| **T5** | Conference invite path (`sendConferenceInviteWithEngine` chain) | `requestEngine → onReady → createOffer → invite` on a later coordinator turn. | Coordinator sync wait · sync `create()` while pending |

---

## 3. T2 — state authority

```text
requestEngine(moduleId, scope, onReady, onFailed?)
    ↓
PENDING (queue / record)
    ↓
MediaSessionManager lifecycle state + existing facts   ← sole authority
    ↓
READY | FAILED
```

- **`SessionMediaRegistry`** MAY forward `requestConferenceEngine` / `requestGroupEngine` and hold **no** parallel ownership state machine.
- **`EngineOwnershipGate`** remains the factory-entry mutator gate (frozen); it is **not** superseded by a Registry layer.

Forbidden future shape:

```text
MediaSessionManager  ↔  SessionMediaRegistry  ↔  EngineOwnershipGate
         (three-way state adjudication)
```

---

## 4. T3 — batch semantics

Per-module (control adjudication):

```text
M02 → READY    → invite continuation
M03 → PENDING  → resume on fact (no sync block on M02 decision)
M04 → FAILED   → explicit per-module fact
```

Conference-level batch:

```text
= projection / aggregation (sent · pending · failed)
≠ await-all-peers barrier
≠ Case A serialization
```

Shared native/runtime domain **may** cause cross-module `PENDING` or `DOMAIN_BLOCKED`; impact must be **explicit and attributed** (per transition contract Q4). Mapping does **not** promise per-module runtime isolation.

---

## 5. Minimal implementation surface (candidate cut)

Only defers work that **requires a ready engine**:

```text
dispatchConferenceInvitesWhenEngineReady
    ↓
beginConferenceInviteWhenEngineReady        (per module)
    ↓
requestConferenceEngine(onReady, onFailed)  (Registry → MediaSessionManager)
    ↓
sendConferenceInviteWithEngine
```

**In scope of this cut:** START-path conference invite / bootstrap that today sync-calls `createOffer` before engine ready.

**Out of scope:** Conference topology · membership · anchor · Failure Domain · Factory partition · MEETING_END hangup ordering (hangup-gate §5.1).

---

## 6. InviteDispatcher disposition

Do **not** delete immediately.

1. Make **`PENDING`** the normal engine-not-ready path (T2 · T5).  
2. **Retire** use of `InviteDispatcher` **3s retry + `TRANSPORT_NOT_READY`** as compensation for engine provisioning.  
3. Retain retry only for ** genuine transport failure**, not pending acquisition.

---

## 7. Non-implications (MUST NOT derive from this mapping)

```text
PENDING  →  independent native isolation (P0.1g)              ✗
PENDING  →  concurrent mutation of the same factory entry     ✗
PENDING  →  Conference-wide availability failure              ✗
```

---

## 8. Frozen during mapping phase

```text
HangupMediaBarrier (START expansion)
A0.7 watchdog strengthening
EngineOwnershipGate semantic changes
Factory / D-β / D-γ
ADR-0056 · Failure Domain · Option Z / CFC-1 amendments
```

Local prototype (`engine-ready continuation`) is **aligned with this mapping**; field deploy requires separate **Implementation Authorization**.

---

## 9. Next gates

```text
Transition Contract Review     ACCEPTED v0.1
Implementation Mapping         FROZEN v0.1 (this document)
Implementation Authorization   AUTHORIZED v0.1 — see [authorization](./group-conference-transition-implementation-authorization.md)
Minimal PR
Field: GROUP → CONFERENCE product loop
```

---

## Revision history

| Version | Date | Change |
|---------|------|--------|
| v0.1 FROZEN | 2026-08-24 | Chat mapping frozen; parent contract ACCEPTED; **no implementation authorization** |
