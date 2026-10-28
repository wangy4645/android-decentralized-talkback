# ADR-0058 Amendment — Shared Mix Timeline Normalization (F9.3)

**Status:** ACCEPTED — CONTRACT ONLY  
**Parent:** [0058-late-join-source-timeline-alignment-amendment.md](./0058-late-join-source-timeline-alignment-amendment.md) · F9.2 merged  
**Issue track:** Issue A · session `ab697b18-f218-4a91-bb7f-5255e6b78973` · field `issue-a-f92-field-20260921-162152`  
**Date:** 2026-09-21

---

## Context

F9.2 closed false-LATE / 667-slot jump by mapping shared mix slot M to source slot S at pull:

```text
shared M  →  sourceSlotForMix(M)  →  pull S
```

Field cycle 2800 shows a remaining domain crossing in **resolver**:

```text
target M              = 23281
legacy resolved       = 22847   (M03 raw S)
gap                   ≈ 434 slots ≈ 8.7 s
```

`resolvePlayoutMixSlot()` compares raw S to target M and takes `min(raw S)` across sources. Late-join M03 S≈2284x numerically wins over M01/M02 S≈2328x even though normalized content is session-coincident.

Same cycle also splits pull vs decode:

```text
pull:   sessionPlayoutTargetSlot = 23281
decode: resolved.slot            = 22847
```

---

## Decision — F9.3: Shared Mix Timeline Normalization

### Core invariant

> Cross-source slot compare/filter only in shared mix domain M. Raw source-domain S must not participate in cross-source min/max or direct compare with session target M.

### Transform (same immutable F9.2 alignment)

```text
offset = mixReferenceSlot - sourceReferenceSlot

sourceSlotForMix(M) = M - offset          // F9.2 — unchanged
mixSlotForSource(S) = S + offset          // F9.3 — inverse
```

No second offset table. No alignment re-anchor.

### Resolver path (frozen)

```text
buffered S
  → eligible S  (nextExpected filter stays in S domain)
  → candidate M = mixSlotForSource(S)
  → filter candidate M <= target M
  → cross-source min(candidate M)
  → resolvedMixSlot M
  → sourceSlotForMix(M) for pull
```

### Pull/decode coherence (frozen)

One authoritative **resolved M** per mix cycle. Pull, decode, and mix must not diverge to two different shared positions.

---

## Explicitly out of scope (this amendment)

```text
NO F9.2 pull semantics change
NO ingress / playoutExpirationFrontier change
NO alignment establishment / re-anchor
NO 120 ms / reorder policy change
NO Top-K / mixer DSP change
NO prefix eviction / consumed-prefix cleanup (F11.2 — post-first-break hygiene; separate track)
NO new observability
NO field (until production implementation authorized)
```

---

## Desk (AUTHORIZED)

`Profile01F93SharedMixTimelineNormalizationDeskTest`

| # | Behavior |
|---|----------|
| 1 | Field-isomorphic cycle 2800 — resolved M must not drop to raw 2284x |
| 2 | Cross-source min(normalized M), not min(raw S) |
| 3 | offset=0 identity mapping |
| 4 | F9.2 composition round-trip S→M→S |
| 5 | pull/decode single shared M |

Reference: `SharedMixPlayoutSlotResolver` (contract implementation).  
Legacy repro: `legacy_cycle2800_rawSourceMinSelects22847NotSessionLive` (GREEN pre-impl).

---

## Implementation gate

```text
F9.3 contract + desk          AUTHORIZED
Production wiring change      HOLD until desk RED/GREEN gate satisfied
Prefix cleanup                HOLD
```

Production slice (when authorized):

1. `ConferenceSessionMediaWiring.resolvePlayoutMixSlot` → `SharedMixPlayoutSlotResolver`
2. `executeTimedMixCycle` decode `mediaSlot` aligned to resolved M (same as pull target)
3. Do **not** bundle prefix eviction

---

## Evidence pointer

Field cycle 2800 (M02 shadow):

```text
funnelPlayoutTargetSlot=23281
funnelResolvedMixSlot=22847
funnelSource2Id=M03 funnelSource2NextExpectedSlot=22848
```

F11.1/F11.2 CLOSED — no further RCA required before implementation.
