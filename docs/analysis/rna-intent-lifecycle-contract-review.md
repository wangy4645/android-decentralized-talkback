# RNA Intent Lifecycle — Contract Review

**Status:** **CLOSED** · **see** [rna-intent-observation-close.md](./rna-intent-observation-close.md)  
**Date:** 2026-08-08  
**Episode:** `logs/adr0043-appendix-b-20260808-185802/` · M02→M03 · `intentId=R1`  
**Parents:** [rna-intent-lifecycle-hypothesis.md](./rna-intent-lifecycle-hypothesis.md) · [rna-intent-lifecycle-observation-analysis.md](./rna-intent-lifecycle-observation-analysis.md)

---

## Purpose

Confirm lifecycle **ownership model** for R1 — not find bugs, not authorize field runs.

```text
Question: Does the system have named owners for
  intent creation · terminal transition · close/cover authority?
```

---

## Contract对照表

| 项 | 已知（log + code） | 待确认 / 开放 |
|----|-------------------|---------------|
| Intent creation | `DEFERRED_INTENT_CREATED R1` @ 19:00:56.798 | — |
| Creation producer | `ConferenceEdgeRecoveryController` ICE-restart dispatch path | — |
| Lifecycle store | `DeferredIntentAuthority` (`CREATED`…) | — |
| Intent ID allocator | `allocateIceRestartIntentId` → `R{n}` on edge record | — |
| Owner (authority) | `DeferredIntentAuthority` — ADR-0022 §E.16.1 | — |
| Gate block | `OFFER_AWAITING_ANSWER` — negotiation prerequisite missing | whether answer ever arrived |
| `uncoveredIntent` meaning | `hasDeferredMediaAction(record)` — deferral slot open | **not** authority EXECUTED state |
| `intentTerminal` projection | `record.negotiationIntentTerminalState ?: "NONE"` | emission may lag (see H2) |
| Terminal event @ 19:01:06 | `NEGOTIATION_INTENT_CLOSE_REQUEST terminal=EXPIRED` | — |
| Authority terminal | `DEFERRED_INTENT_SUPERSEDED` R1 `CREATED→SUPERSEDED` | — |
| Cover path (`EXECUTED`) | `markExecuted` + `closeNegotiationIntent(EXECUTED)` | **did not fire for R1** |
| Close without cover | `closeNegotiationIntent(EXPIRED)` clears deferral | obligation may remain open |
| Completion writer | `CompletionObservationProjection` — **reads** only | does not close intent |
| Recovery fact writer | `emitNegotiationRecoveryFactForTerminalClose` | observation only |

---

## H1 — Intent owner (R1 belongs to whom?)

### Creation chain (code)

```text
ConferenceEdgeRecoveryController.dispatchIceRestart(...)
        |
        v
probeIceRestartGate → NOT executable (OFFER_AWAITING_ANSWER)
        |
        v
allocateIceRestartIntentId(record) → R1
recordMediaActionDeferred(NEGOTIATION_SETTLING)
onNegotiationGateDeferred { ... }
        |
        v
DEFERRED_INTENT_CREATED (log)
DeferredIntentAuthority.registerCreated(R1, fenceArmed=true)
scheduleNegotiationIntentBudget(R1, 10s)
```

**Producer:** `ConferenceEdgeRecoveryController` (negotiation stabilization gate defer path).  
**Lifecycle owner:** `DeferredIntentAuthority` — sole mutator of `ExecutionState`.

```kotlin
// DeferredIntentAuthority.kt — ADR-0022 §E.16.1
// Owns: supersede legality, SUPERSEDED terminal, releaseIntent facts
// Does NOT own: CompletionPolicy / RECOVERED
```

### R1 is not a generic recovery flag

`gateBlock=OFFER_AWAITING_ANSWER` implies:

```text
recovery obligation
      |
      waiting for negotiation artifact (remote answer)
```

Not: "media recovery failed."

**H1 verdict:** R1 has **named owners** — creation by edge controller, lifecycle by `DeferredIntentAuthority`.

---

## H2 — Terminal transition (NONE vs SUPERSEDED/EXPIRED)

### Observed R1 timeline

```text
19:00:56.798  CREATED (authority CREATED, gateBlock=OFFER_AWAITING_ANSWER)
19:00:56.799  NEGOTIATION_INTENT_BUDGET_ARMED budgetMs=10000
19:00:56.935  intentTerminal=NONE · uncoveredIntent=true · SYNC_PENDING
19:01:06.800  NEGOTIATION_INTENT_CLOSE_REQUEST terminal=EXPIRED
19:01:06.805  DEFERRED_INTENT_SUPERSEDED CREATED→SUPERSEDED
```

### Terminal model (code — not Case B)

`DeferredIntentAuthority.ExecutionState`:

```text
CREATED → HELD_DISPATCH → EXECUTED
                        → SUPERSEDED
```

Negotiation terminal writer: `closeNegotiationIntent` (sole writer):

```text
terminal ∈ { EXECUTED, EXPIRED, SUPERSEDED, BLOCKED_BY_GLARE }
record.negotiationIntentTerminalState = terminal
```

**SUPERSEDED / EXPIRED are first-class terminal states** — not orphan log events.

### `intentTerminal=NONE` while lifecycle advanced — Case A (projection timing)

`RECOVERY_EDGE_STATE` prints:

```kotlin
intentTerminal = record.negotiationIntentTerminalState ?: "NONE"
```

At 19:00:56.935 terminal not yet closed → **NONE is accurate for that instant**.

`RecoveryEdgeStateObservation` material-change `Signature` **does not include** `intentTerminal`:

```kotlin
Signature(obligationState, l2Satisfied, phase, waitingReason, attemptTerminal)
// intentTerminal omitted
```

After 19:01:06 close, `negotiationIntentTerminalState=EXPIRED` on record, but **no subsequent `RECOVERY_EDGE_STATE` with `intentTerminal=EXPIRED` observed** in this log — likely because no material signature change triggered re-emit.

**H2 verdict:** Terminal authority **exists** and fired (EXPIRED). `intentTerminal=NONE` during SYNC_PENDING is **timing + observation gap**, not missing terminal model.

---

## H3 — Close authority (does cover authority exist?)

### Two distinct closures

| Path | Mechanism | Effect on `uncoveredIntent` | Effect on recovery completion |
|------|-----------|----------------------------|------------------------------|
| **Cover** | `markExecuted` + `closeNegotiationIntent(EXECUTED)` | clears deferral | may unblock toward EXECUTED terminal |
| **Discard** | `closeNegotiationIntent(EXPIRED/SUPERSEDED)` | `clearDeferralFields` | **does not** call `markRecovered` |

```kotlin
// closeNegotiationIntent — explicit:
// Must not call markRecovered or mutate completion predicate.
```

### What `uncoveredIntent` actually means

```kotlin
hasUncoveredDeferredIntent = hasDeferredMediaAction(record)
// mediaActionDisposition == DEFERRED && mediaActionOwner.isAssigned()
```

**Not** "authority state == EXECUTED".  
Completion waits on **open deferral slot**, not directly on `DeferredIntentAuthority.ExecutionState`.

### R1 path taken

```text
CREATED
  → blocked on OFFER_AWAITING_ANSWER
  → no NEGOTIATION_CAN_EXECUTE / no drain / no EXECUTED
  → budget exhausted
  → EXPIRED close (discard, not cover)
```

**Close authority exists.** R1 was **terminated without cover**.

**H3 verdict:** System has close authority; R1 never received **cover** authority (`EXECUTED`). EXPIRED is terminal discard, not obligation satisfaction.

---

## gateBlock=OFFER_AWAITING_ANSWER — contract implication

R1 creation trigger:

```text
ICE_RESTART_GATE_BLOCKED reason=OFFER_AWAITING_ANSWER
```

**O1 answered** in [rna-negotiation-gate-o1-review.md](./rna-negotiation-gate-o1-review.md):

```text
Answer never materialized (Case 2: CALL_REJECT/BUSY, not SDP answer)
NEGOTIATION_CAN_EXECUTE never fired → budget EXPIRED is RNA-5-consistent
```

---

## Separation from ADR-0043 / ADR-0042 (reconfirmed)

```text
ADR-0043: membership GROUP_RESYNC authorization — PASS, orthogonal
ADR-0042: transport SENT truth — reviewed, not primary stall signal here
RNA:      intent R1 lifecycle — CREATED → blocked → EXPIRED (no EXECUTED)
```

---

## Open questions (post-review)

| # | Question | Status |
|---|----------|--------|
| O1 | Why `OFFER_AWAITING_ANSWER` persisted through L2 recovery? | **CLOSED** — [rna-negotiation-gate-o1-review.md](./rna-negotiation-gate-o1-review.md) |
| O2 | After EXPIRED + deferral clear, why obligation/UI stayed SYNC_PENDING ~3min? | **CLOSED** — [rna-obligation-projection-o2-review.md](./rna-obligation-projection-o2-review.md) · Model A/B **not adjudicated** |
| O3 | Should EXPIRED trigger successor intent or obligation close? | **NOT STARTED** — design choice; requires ADR if pursued |

---

## Decision tree outcome

```text
Lifecycle ownership model:     CONFIRMED (named components)
Cover authority:               EXISTS (EXECUTED path — not taken)
Terminal authority:            EXISTS (EXPIRED fired for R1)
Projection gap:                CONFIRMED (intentTerminal emission)
Design gap vs expected RNA-5:  OPEN (O1–O3)
```

**Next (when authorized):** directed observation targeting O1 — negotiation gate unblock chain for R1.  
**Not yet:** run card · field · completion predicate change.

---

## Status board (unchanged)

```text
ADR-0043       CLOSED ✅
ADR-0042       REVIEWED ✅
RNA intent     HYPOTHESIS OPEN · CONTRACT REVIEW COMPLETE
RNA run        NOT AUTHORIZED
Field          NOT AUTHORIZED
```

---

## One-line statement

> R1 lifecycle has named owners; terminal authority fired EXPIRED without EXECUTED cover — `uncoveredIntent` tracks deferral slot, not authority state; remaining gap is why negotiation prerequisite never cleared before budget exhaustion.
