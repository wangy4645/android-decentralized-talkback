# RCA5-A — Playout/Ingress Pipeline Lock (FIELD PENDING)

## Problem

M02 field session `4500221a` (run `164655`): shadow ingress active, jitter decode live, then
`SHADOW_PLAYOUT_TICK_FAILED reason=ConcurrentModificationException`, playout cycles stopped,
RC1 cutover acquired production AudioTrack with `successfulProductionWrites=0`.

Repro stack (unit test, pre-fix):

```text
PerIncarnationJitterBuffer.bufferedSlots (bySlot.keys)
  ← MediaExecutionPipeline.bufferedSlots
  ← ConferenceSessionMediaWiring.resolveEarliestBufferedMixSlot
  ← Profile01ShadowPlayoutClockSeam.onPlayoutTick (pipeline lock)

concurrent: Profile01ShadowMulticastReceiveSeam → admitProtectedDatagram (no lock)
  ← PerIncarnationJitterBuffer.admit (bySlot mutation)
```

## Fix (RCA5-A)

Serialize `ConferenceSessionMediaWiring.admitProtectedDatagram` under the existing
`withSessionPipelineLock(sessionId)` — same domain as shadow playout tick.

No Profile03 algorithm change. No jitter recovery / scheduler redesign.

## OBS (minimal)

- `SHADOW_PLAYOUT_TICK_FAILED` now includes `stackTrace`
- `SHADOW_PLAYOUT_BUFFER_STARVATION` rate-limited when `resolveEarliestBufferedMixSlot` is null

## Regression

`Profile01ShadowRca5ConcurrencyReproTest` — dual-thread ingress + playout; asserts zero CME and
continued mix cycles under load.

## Field gate (RCA5 closure)

```text
sustained ingress → no CME → playout cycles grow → cutover → successfulProductionWrites > 0 → M02 audible
```

If no CME but starvation persists → RCA5-B2 (`nextExpectedSlot` / late discard / lifecycle).
