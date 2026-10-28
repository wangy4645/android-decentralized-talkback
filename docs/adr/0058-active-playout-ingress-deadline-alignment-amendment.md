# ADR-0058 Amendment — Active Playout Ingress Deadline Alignment (Layer B)

**Status:** ACCEPTED — CONTRACT ONLY (no production implementation in this amendment)  
**Parent:** [0058-late-join-source-timeline-alignment-amendment.md](./0058-late-join-source-timeline-alignment-amendment.md)  
**Issue track:** Issue A · A3 root domain frozen · session `a9d84526-4e01-4dce-9ccc-f4176c232d1d`  
**Date:** 2026-09-21

---

## Context

Layer A (ADR-0058 F1–F8) fixed **source-local sequence → shared session slot domain** for remote incarnation first admission. Field A3 confirmed M01 **first packet** aligns (`slot=20480`, `nextExpected=20480`, `QUEUED`).

Field counterexample (~527 ms / ~956 ms) shows Layer B failure:

```text
t0:       slot=20480  nextExpected=20480  QUEUED
~527ms:   slot=20490  nextExpected=20483  LATE
~956ms:   slot=20506  nextExpected=20483  gap=23

RTP/slot monotonic · admission clean · AEAD clean
```

Root: `PerIncarnationIngressTimeline` derives useful deadline from **source-local wall anchor** (`originWall + (seq - firstSeq) × 20ms + jitter`), which diverges from **session playout clock** while playout advances independently.

This is **not** generic “late-join alignment.” First-packet slot mapping is correct; subsequent in-order packets become artificially `LATE_FOR_PLAYOUT`.

---

## Decision — F9: Active Playout Ingress Deadline Alignment

For an admitted packet already mapped to session media slot **S**, its **useful deadline** MUST be determined by the **session playout domain**, not by an independent source-local wall-clock extrapolation.

### Semantic invariant

```text
packet mapped to session slot S
session currently expects / plays around P

packet admissibility (useful deadline, late fence)
    determined by S relative to P
    + frozen jitter / PLL delay policy (120 ms contract)

NOT by
    firstPacketWallClock
    + (sourceSeq - firstSeq) × 20ms
    alone
```

**Freeze semantics only.** Exact deadline formula is an implementation detail **provided** F9 and forbidden changes below hold.

### Preserved

| Item | Status |
|------|--------|
| 120 ms jitter contract | **FROZEN** — MUST NOT widen for field pass |
| `LATE_FOR_PLAYOUT` fence | **RETAINED** — genuinely stale packets still reject |
| RTP → `mediaSlot` mapping (`mediaSlot = sequence`) | **UNCHANGED** |
| Layer A per-incarnation slot bootstrap | **UNCHANGED** |
| Session playout slot domain (F7) | **UNCHANGED** |

### Forbidden fixes

```text
❌ widen 120 ms jitter window
❌ disable LATE_FOR_PLAYOUT
❌ use arrival time to override media time
❌ special-case remote source as never-late
❌ change RTP → mediaSlot mapping
❌ re-anchor wall clock on every received packet (destroys late fence)
```

Required direction: **session-clock alignment** for useful deadline, not unbounded sliding source deadline.

---

## Layer model (frozen)

```text
Layer A (ADR-0058):  source-local sequence → shared session slot domain
Layer B (this F9):   mapped session slot S → useful deadline → session playout clock domain
```

---

## Desk fixtures (mandatory before production PASS)

Field-isomorphic harness MUST reproduce the A3 counterexample on **current** implementation before any production fix is authorized.

### Primary — active playout + remote incarnation sustain

```text
active playout session
→ remote incarnation first packet aligned / QUEUED
→ advance wall + playout ~1 s
→ feed monotonically increasing RTP / slots
```

**Current (broken) behavior — desk MUST reproduce:**

```text
first packet QUEUED
→ later ordinary in-order packet LATE_FOR_PLAYOUT
→ nextExpected falls behind playout live edge
```

**Post-fix behavior — desk MUST require:**

```text
first packet QUEUED
→ subsequent in-order packets remain eligible / QUEUED
→ no artificial REORDER_DISPLACEMENT_EXCEEDED
```

### Boundary A — genuinely old packet

Stale packet far behind session playout live edge → **still `LATE_FOR_PLAYOUT`** (fence preserved).

### Boundary B — incarnation replacement

Same moduleId, new incarnation after active playout → **fresh alignment**; MUST NOT inherit prior incarnation ingress timeline state (`ingressTimelines` cleared on revoke per existing wiring).

---

## Authorization matrix

| Item | Status |
|------|--------|
| A3 root domain | **FROZEN** |
| This contract (F9) | **ACCEPTED** |
| Field-isomorphic desk fixture | **AUTHORIZED** |
| Targeted observability | **NO** |
| New field | **NO** |
| Production implementation | **HOLD** until desk reproduces counterexample |
| Mixer / AudioTrack investigation | **NO** |

---

## Consequences

- Implementation locus: `PerIncarnationIngressTimeline` / ingress deadline owner relative to `playoutReferenceMs` and session playout slot domain.
- Existing `Profile01LateJoinIngressTimelineTest` proves Layer A first-packet only; insufficient for Layer B regression.
- Production change authorized only after desk RED at field-isomorphic counterexample, then GREEN on F9 contract tests.
