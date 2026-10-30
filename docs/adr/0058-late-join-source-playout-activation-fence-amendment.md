# ADR-0058 Amendment — Late-Join Source Playout Activation Fence (F9.4)

**Status:** ACCEPTED — CONTRACT ONLY  
**Parent:** [0058-late-join-source-timeline-alignment-amendment.md](./0058-late-join-source-timeline-alignment-amendment.md) · F9.2/F9.3 merged  
**Issue track:** Issue A · session `716f5009-131e-4552-93df-6b11839e9b5f` · field `issue-a-f93-field-20260921-170121`  
**Date:** 2026-09-21

---

## Context

F12 traced first-break after F9.3 field PASS:

```text
member installed → Top-K eligible → shared pull advances nextExpected
→ wire packets arrive (incoming ≈ nextExpected + 8)
→ reorder cap = 4 → DROP → buffer empty → PLC loop
```

Root class: **activation ordering defect** (not reorder window too small, not F9.2 transform error, not F9.3 regression).

M03 locally QUEUED 20483–20488; M02 observer had **zero M03 QUEUED** before sustained REORDER. Last `nextExpected` writer before first gap>4: **pull consumer**.

---

## Decision — F9.4: Late-Join Source Playout Activation Fence

### Core invariant

> Late-join remote incarnation must **not** have `nextExpected` / expiration frontier advanced by shared playout consumer until ingress establishes a **trusted source playout origin** sufficient to begin consumption without leapfrogging in-flight startup packets.

### Readiness (derived, not persisted)

```text
NOT_PLAYOUT_READY
  → ingress / buffer / alignment OK
  → shared consumer MUST NOT pull / advance nextExpected / frontier

PLAYOUT_READY
  → F9.1 + F9.2 consumer path OK
```

**Not** `buffer >= N frames`. Readiness is authority semantics via existing facts:

```text
alignment established (F9.2)
AND earliest trusted QUEUED ingress present
AND nextExpected anchored to ingress live edge:
    nextExpected >= earliestBuffered
    nextExpected <= latestBuffered + MAX_REORDER_PACKETS
```

Session-coincident sources (`offset = 0`): PLAYOUT_READY once first QUEUED (established path).

### Top-K unchanged

```text
Top-K selected ≠ source playout-ready
```

Execution pipeline skips consumption for NOT_PLAYOUT_READY members; Top-K policy untouched.

---

## Explicitly out of scope

```text
NO reorder cap 4 → 8/16
NO jitter 120 ms change
NO F9.1/F9.2/F9.3 semantic change
NO alignment re-anchor
NO Top-K policy change
NO prefix eviction (F11.2 HOLD)
NO new observability
NO field (until production authorized)
```

---

## Desk (AUTHORIZED)

`Profile01F94LateJoinSourcePlayoutActivationFenceDeskTest`  
Reference: `LateJoinSourcePlayoutActivationFence`

| # | Behavior |
|---|----------|
| 1 | F12-isomorphic legacy: consumer before wire → gap=8 → REORDER |
| 2 | Before PLAYOUT_READY: gated cycle does not advance M03 nextExpected |
| 3 | First trusted QUEUED → PLAYOUT_READY derived |
| 4 | After ready: subsequent in-order ingress QUEUED |
| 5 | Established M01/M02 unaffected |

---

## Implementation gate

```text
F9.4 contract + desk          AUTHORIZED
Production wiring           HOLD
```

Production slice (when authorized): gate `pullSlotForSharedMixPlayout` per-member on derived readiness; do not alter Top-K.
