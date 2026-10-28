# ADR-0058 Amendment — Late-Join First Consumption Origin (F9.5)

**Status:** ACCEPTED — CONTRACT + PRODUCTION WIRED  
**Parent:** [0058-late-join-source-playout-activation-fence-amendment.md](./0058-late-join-source-playout-activation-fence-amendment.md) · F9.4 merged  
**Issue track:** Issue A · F13 field `issue-a-f94-field-20260921-173218` · session `7a384ca8-df59-4717-b7f7-30f1faa82777`  
**Date:** 2026-09-21

---

## Context

F13 CLOSED — C1b confirmed. Writer chain:

```text
trusted QUEUED → nextExpected=20481 → PLAYOUT_READY
shared M=20497, offset=18 → mapped S=20479
pullSlot(20479) → empty/PLC → advanceExpectedTo(20480)
→ nextExpected regression 20481 → 20480 (< earliestBuffered=20481)
→ F9.4 NOT_READY → activation deadlock
```

Two distinct contract layers; monotonicity alone is insufficient.

---

## Decision — F9.5: Late-Join First Consumption Origin

### Invariant A — Consumer cursor monotonicity

Same incarnation:

```text
nextExpected_after >= nextExpected_before
```

Naive `advanceExpectedTo(S)` must behave as:

```text
nextExpected := max(currentNextExpected, S)
```

Incarnation reset via new buffer/lifecycle only — not in-place cursor regression.

### Invariant B — Activation origin compatibility

On **first** consumer pull after NOT_READY → READY:

```text
requestedSourceSlot must not precede trusted ingress consumption origin
firstConsumerS = max(sourceSlotForMix(M), nextExpected)
```

At activation: `nextExpected == earliestBuffered` (F13: 20481).  
**Steady state:** F9.2 mapping unchanged — no permanent clamp.

### Explicitly out of scope

```text
NO third runtime fence
NO F9.4 rollback
NO reorder / Top-K change
NO production wiring (until desk GREEN + separate authorization)
NO new field
```

---

## Desk (AUTHORIZED)

`Profile01F95LateJoinFirstConsumptionOriginDeskTest` — F13-isomorphic + three boundaries.

---

## Production (WIRED)

- [PerIncarnationJitterBuffer.advanceExpectedTo] — Invariant A monotonic max
- [MediaExecutionPipeline.pullSlotForSharedMixPlayout] — Invariant B first-post-READY clamp (once until consumer advance)

Field HOLD until authorized.
