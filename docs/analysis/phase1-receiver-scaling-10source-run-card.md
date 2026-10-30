# Phase 1 — 10-Source Receiver Scaling Observation Run Card

## Status

```text
CLOSED (2026-09-07)
10-source structural:           PASS (…-105628)
promotion live-edge fence:      IMPLEMENTED + FIELD VERIFIED (…-111214)
Phase 1 receiver correctness:   CLOSED @ N=10
capacityGates:                  NONE
```

## Summary

Receiver media engine validated at design ceiling **N=10** (MaxJitterSources). Structural caps, 20ms cadence, lock model, and Profile 03 temporal semantics (120ms playout deadline on promotion) all hold.

**10人会议接收媒体引擎主线已跑通并收口。**

## Topology

```text
RECEIVER SCALING OBSERVATION
3 real network sources + 7 synthetic sources (S4–S10 on M04)
NOT 10-device field evidence
```

## Structural PASS (…-105628)

```text
TopK max=4 · liveDecoders max=5 · mix max=4
MaxJitterSources=10 occupied
write cadence p50=20ms · budget p99≈9.4ms · lock hold p99≈11.0ms
fairness 10/10 · AudioTrack outside pipelineLock
```

## Temporal PASS (…-111214, post fence)

```text
promotionLiveEdgeFenceCount=55 · staleDiscarded=1269
oldestRetainedAgeMs max<=120ms
promotionToFirstValidPlayoutMs p99=20ms
caps / cadence / lock — no regression
```

Pre-fix (…-105628) exposed implementation gap: stale non-TopK backlog played on promotion. Fixed by `PROMOTION_LIVE_EDGE_FENCE` (Profile 03 deadline at Top-K entry; no contract change).

`promotionFirstPulledAgeMs` anomaly on …-105628 / …-111214 was collector scope (counted late-discard pulls); behavior proven by `oldestRetainedAgeMs` + `promotionToFirstValidPlayoutMs`.

## Frozen posture

```text
4-source / 8-source / 10-source structural    PASS
receiver structural scaling                   COMPLETE @ N=10
promotion live-edge fence                     IMPLEMENTED + FIELD VERIFIED
Phase 1 receiver correctness                CLOSED
underrun                                      observe-only (independent)
```

## Next (Phase 2 — authorized)

**Conference Session Media Wiring** — see `phase2-conference-session-media-wiring-run-card.md`

Real session lifecycle → `AdmittedMediaSource` → multicast sender/receiver. Real Mesh E2E **after** session wiring. No 10-phone requirement for Phase 2.

## Field evidence

- Structural: `talkback/logs/receiver-scaling-10source-20260907-105628/`
- Fence: `talkback/logs/receiver-scaling-10source-20260907-111214/`
- Fence run card: `talkback/docs/analysis/phase1-promotion-live-edge-fence-run-card.md`

## References

- 8-source: `talkback/logs/receiver-scaling-8source-20260907-104400/`
- 4-source post-fix: `talkback/logs/receiver-scaling-4source-20260907-102750/`
