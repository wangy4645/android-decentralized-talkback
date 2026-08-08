# RNA Obligation Projection — O2 Desk Review

**Status:** **CLOSED** · **observation complete** · **see** [rna-intent-observation-close.md](./rna-intent-observation-close.md)  
**Date:** 2026-08-08  
**Episode:** `logs/adr0043-appendix-b-20260808-185802/` · M02→M03 · `intentId=R1`  
**Parents:** [rna-negotiation-gate-o1-review.md](./rna-negotiation-gate-o1-review.md) · [rna-intent-lifecycle-contract-review.md](./rna-intent-lifecycle-contract-review.md)

---

## Purpose

Answer O2:

> After R1 reached terminal (`EXPIRED`/`SUPERSEDED`), how do **intent terminal state**, **recovery obligation projection**, and **presentation state** relate?

**Not asking:** why UI didn't recover · whether to change completion predicate · handler fixes.

**Asking:**

```text
intent lifecycle state
        ≠ ?
recovery obligation projection state
        ≠ ?
presentation state (SYNC_PENDING / syncing)
```

---

## Status board

```text
ADR-0043          CLOSED ✅
ADR-0042          REVIEWED ✅

RNA intent
  H1 ownership          CLOSED ✅
  H2 terminal model     CLOSED ✅
  H3 close authority    CLOSED ✅
  O1 negotiation seam   CLOSED (observation) ✅
  O2 projection gap     OBSERVATION COMPLETE (this doc) ✅

RNA observation       CLOSED (desk) ✅
RNA run               NOT AUTHORIZED
Field                 NOT AUTHORIZED
Seam II               FROZEN
```

---

## Three-layer timeline (post-EXPIRED)

```text
19:00:56.935  obligation projection: SYNC_PENDING · DEFERRED_INTENT_UNCOVERED
              intentTerminal=NONE (pre-close)
              uncoveredIntent=true

19:01:06.800  intent lifecycle: EXPIRED (NEGOTIATION_BUDGET_EXHAUSTED)
19:01:06.805  authority: SUPERSEDED · DEFERRED_INTENT_RELEASED
              deferral fields cleared (closeNegotiationIntent contract)

19:01:06.637  presentation: finalPresence=SYNCING (pre-EXPIRED snapshot)
19:01:07+     presentation: "M03 syncing..." persists (~3min in log)

              ❌ no RECOVERY_COMPLETION_DECISION after EXPIRED
              ❌ no RECOVERY_EDGE_STATE after EXPIRED
```

---

## Contract对照表

| Layer | Source of truth | Post-EXPIRED state (log) | Re-projected? |
| ----- | --------------- | ------------------------ | ------------- |
| Intent terminal | `closeNegotiationIntent` → `negotiationIntentTerminalState` | `EXPIRED` @ 19:01:06.801 | N/A (writer fired) |
| Deferral slot | `hasDeferredMediaAction(record)` | cleared via `clearDeferralFields` | **No downstream eval** |
| Completion reason | `CompletionObservationProjection` | last = `DEFERRED_INTENT_UNCOVERED` @ 19:00:56.938 | **Stale** |
| Obligation open | `record.edgeObligationOpen()` | `obligationOpen=true` throughout | **Independent of intent terminal** |
| Edge obligation state | `RecoveryEdgeStateObservation.mapObligationState` | last = `SYNC_PENDING` @ 19:00:56.935 | **Stale** |
| Presentation | meeting pill / rprobe `finalPresence` | `SYNCING` from 19:01:00 through log end | **Driven by open obligation** |

---

## O2-A — Does intent terminal propagate to obligation projection?

### Code contract

`closeNegotiationIntent` (RNA-5 sole terminal writer):

```text
✓ sets negotiationIntentTerminalState
✓ emits NEGOTIATION_RECOVERY_FACT
✓ releaseDeferredIntentSlot + clearDeferralFields (when terminal ≠ EXECUTED)

✗ does NOT call emitCompletionObservation / CompletionObservationProjection
✗ does NOT call markRecovered
✗ explicit: "Must not mutate completion predicate"
```

Budget callback path (`scheduleNegotiationIntentBudget` → `closeNegotiationIntent`) ends at terminal close — **no re-evaluation hook**.

### Log evidence

Last completion chain for M03:

```text
19:00:56.938  RECOVERY_COMPLETION_DECISION reason=DEFERRED_INTENT_UNCOVERED
19:00:56.935  RECOVERY_EDGE_STATE obligationState=SYNC_PENDING intentTerminal=NONE

[10s gap — intent closes]

19:01:06.800–807  EXPIRED + SUPERSEDED + deferral released
                  (no RECOVERY_COMPLETION_* follows)
```

`RecoveryEdgeStateObservation.Signature` material-change set:

```text
obligationState · l2Satisfied · phase · waitingReason · attemptTerminal
(intentTerminal NOT included)
```

Even if re-eval fired, `intentTerminal=EXPIRED` alone would not force `RECOVERY_EDGE_STATE` re-emit unless `waitingReason` or `obligationState` changed.

**O2-A verdict:** **Projection refresh observation gap** — not an intent lifecycle defect. `closeNegotiationIntent(EXPIRED)` ends the deferred negotiation intent; it does **not** announce recovery obligation resolved, close conference recovery, or change presentation state. `EXPIRED ≠ OBLIGATION_RESOLVED` holds.

---

## O2-B — Does obligation projection propagate to presentation?

### What presentation reads

Meeting pill @ 19:01:06.637 (last rprobe before EXPIRED):

```text
module=M03
media=CONNECTED
iceConnectionState=CONNECTED
inRecoveringPeers=true
obligationOpen=true
controllerEdgeRecovering=true
finalPresence=SYNCING
```

Presentation derives `SYNCING` from **open recovery obligation + recovering peer set**, not directly from `intentTerminal` or `uncoveredIntent`.

### Staleness vs correctness

If completion re-eval had run post-EXPIRED (hypothetical, from cleared deferral):

```text
hasUncoveredDeferredIntent = false   (deferral cleared)
obligationOpen = true                (unchanged)
l2Satisfied = true
waitingReason = NONE                 (no blockers in firstBlockingReason)
```

`mapObligationState` would yield:

```text
obligationState = SYNC_PENDING
stateReason = MEDIA_RECOVERED_OBLIGATION_OPEN   (not INTENT_UNCOVERED)
```

So presentation **might still show syncing** — but for a **different reason** (open obligation, not uncovered intent).

**O2-B verdict:** Presentation tracks **obligation openness**, not intent terminal. Current `SYNCING` is **consistent with `obligationOpen=true`** even after intent EXPIRED. The visible symptom mixes **stale completion reason** with **legitimately open obligation**.

---

## O2-C — Core question: should terminal intent trigger obligation re-evaluation?

### Intentional separation (confirmed)

```text
media recovered  ≠  intent executed
intent expired   ≠  obligation resolved
failed attempt   ≠  goal achieved
```

### Three models (NOT adjudicated)

**Model A — separated ownership (current tendency)**

```text
Intent terminal
      |
      X  (no automatic bridge)
      |
Obligation unchanged
```

Intent is a recovery **attempt**. After failure: intent ends; obligation continues waiting for other recovery signals.

- Pros: conservative; avoids false recovered
- Risk: stale completion reason may persist

**Model B — event-driven re-check**

```text
Intent terminal
      |
      v
Obligation re-evaluation (RECHECK only)
```

Terminal intent is an **input event** — recompute whether obligation still holds. **Not** `EXPIRED → RESOLVED`.

- Aligns with event-driven projection architecture
- Does not imply recovery success

**Model C — direct obligation close (EXCLUDED from consideration)**

```text
Intent terminal → close obligation
```

Would violate `failed recovery ≠ recovered`. **Not a candidate** without new ADR and explicit behavior change authorization.

**O2-C verdict:** Current behavior is **consistent with Model A** (separated ownership). Whether Model B should apply is an **open architecture question** — **not enough evidence for behavior change**.

---

## Layer divergence diagram

```text
Intent lifecycle          Recovery obligation           Presentation
─────────────────         ───────────────────          ──────────────
CREATED                   obligationOpen=true          (not yet SYNCING)
    |                     SYNC_PENDING                      |
    |                     reason=INTENT_UNCOVERED           |
    v                           |                           v
EXPIRED (terminal)              |                     SYNCING (~3min)
SUPERSEDED                      |                           |
deferral cleared                |                           |
    |                     obligationOpen STILL true          |
    X── no bridge ──────────────┘                           |
                              stale projection              |
                              (no re-eval)                  |
```

---

## What O2 does NOT claim

```text
❌ "UI bug"
❌ "completion predicate wrong"
❌ "EXPIRED must close obligation"
❌ "need handler fix"
❌ field run authorization
```

---

## Frozen classification (O2 close)

```text
O2 classification:

Observation:
  Terminal intent state does not automatically refresh
  obligation projection.

Current behavior:
  Consistent with separated ownership model (Model A tendency).

Open question:
  Whether terminal intent events should trigger
  obligation re-evaluation (Model B).

Not enough evidence for behavior change.
No ADR.
No runtime authorization.
O3 (successor intent) NOT STARTED — design choice, not observation.
```

---

## RNA observation track close

```text
ADR-0043:  membership authorization     CLOSED ✅
ADR-0042:  transport truth               REVIEWED ✅
RNA desk:   H1–H3 · O1 · O2              OBSERVATION COMPLETE ✅
Seam II:    FROZEN
Field:      NOT AUTHORIZED
RNA run:    NOT AUTHORIZED

Next (only if field evidence or explicit ADR trigger):
  Model A/B decision → ADR candidate OR RNA observation archive
```

---

## One-line statement

> After R1 expired, intent terminal closed correctly under separated ownership; obligation projection was not refreshed (observation gap), while `obligationOpen=true` legitimately sustained presentation `SYNCING` — intent failure and peer-in-recovery are different facts.
