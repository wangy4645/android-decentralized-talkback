# Phase 1 — Promotion Live-Edge Fence Run Card

## Status

```text
CLOSED (2026-09-07)
fix: PROMOTION_LIVE_EDGE_FENCE
contract: Profile 03 MaxPlayoutDelayMs=120 (unchanged)
field: receiver-scaling-10source-20260907-111214 — VERIFIED
```

## Fix

On Top-K promotion (`not-selected → selected`), discard jitter frames where:

```text
frame.mediaTimeMs + 120ms < currentPlayoutTime
```

Does **not** `queue.clear()` or jump to newest packet. Preserves reorder window for in-window frames.

Implementation: `MediaExecutionPipeline.selectTopK()` → `applyPromotionLiveEdgeFence()`.

## Field verification (…-111214)

```text
promotionLiveEdgeFenceCount     = 55
promotionStaleFramesDiscarded   = 1269
promotionOldestRetainedAgeMs    max=120ms
promotionToFirstValidPlayoutMs  p99=20ms
structural caps / cadence / lock — no regression
```

## Metrics

```text
promotionLiveEdgeFenceCount
promotionStaleFramesDiscarded
promotionOldestRetainedAgeMs
promotionFirstPulledAgeMs        (first valid pull only; collector fixed post …-111214)
promotionToFirstValidPlayoutMs
```

## Prior failure (pre-fix …-105628)

Stale non-TopK backlog → seconds-old audio on promotion. Root cause: implementation gap on promotion path, not Profile 03 contract gap.

## Frozen

```text
promotion live-edge fence    IMPLEMENTED + FIELD VERIFIED
Phase 1 receiver correctness CLOSED (with N=10 structural ladder)
```
