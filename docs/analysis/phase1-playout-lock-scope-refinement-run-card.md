# Phase 1 — Playout Lock Scope Refinement

## Status

```text
VERIFIED — post-fix 4-source + lock attribution PASS
fix: PLAYOUT_LOCK_SCOPE_REFINEMENT_AUDIOTRACK_OUTSIDE
4-source reval: PASS (…-102750)
lock attribution: COMPLETE (…-103244) — AUDIOTRACK outside lock
8-source: HOLD until separately authorized
```

## Change

```text
inside pipelineLock:
  jitter pull · Top-K · decode · mix · sealed immutable PCM block · state/metrics snapshot

outside pipelineLock:
  AudioTrack.write · playout write metrics
```

## Frozen (unchanged)

```text
20ms absolute clock · jitter/reorder/late · TopK=4 · MaxLiveDecoders=5 · MixMaxSources=4
PCM content · mix algorithm · AudioTrack buffer/config · Profile 03 · underrun semantics
```

## Field verification

### 4-source observation `receiver-scaling-4source-20260907-102750`

```text
scalingPass=true capsHeld=true
lock hold p99: 16247 → 10276µs
audioTrackWrite outsideLock=true (p99≈23.6ms, not in hold)
writeInterval p50=20ms  budget p99≈10.8ms
TopK max=4 liveDecoders max=4
```

### Lock attribution `…-lock-attribution-20260907-103244`

```text
PLAYOUT_AUDIOTRACK p99: 25708µs inside → 413µs outside
primary inside lock: PLAYOUT_DECODE (~8.1ms)
interpretation: PRIMARY_PLAYOUT_MEDIA_WORK_INSIDE_LOCK
```

Evidence:
- `talkback/logs/receiver-scaling-4source-20260907-102750/`
- `talkback/logs/receiver-scaling-4source-lock-attribution-20260907-103244/`

## Implementation

- `PipelineLockPlayoutRefinement.kt`
- Applied in: `ReceiverScaling4SourceRunner`, `ReceiverScaling4SourceLockAttributionRunner`, `Slice5bMulticastSoakRunner`, `LiveSlotTimingDiagnosticRunner`
