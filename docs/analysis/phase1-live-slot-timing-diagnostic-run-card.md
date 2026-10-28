# Phase 1 — LIVE_SLOT Timing Diagnostic Run Card

## Status

```text
FIELD RUN #2: CLOCK_VALIDATED (…-165716) — absolute 20ms decoupled @ 2-source
3-SOURCE SOAK: PASS / CLOSED (…-170738)
playout scheduling root cause: CLOSED
20ms absolute clock: VALIDATED @ 2-source + 3-source
pipelineLock @ 3-source: ACCEPTABLE
AudioTrack underrun: OPEN / independent
4-source scaling: AUTHORIZED
8–10 source scaling: HOLD
fixApplied: true (AbsoluteMediaPlayoutClock)
```

### Path note (attribution continuity)

```text
This diagnostic reuses Slice 5b Attempt #5 media path:
  sender seq = MONOTONIC
  mediaTimeMs = rxWallMs at admit
  soak harness live-edge resync (≠ Profile 03)

Reason: attribute the OPEN late/underrun from #5 on the same regime.
Wall-clock LIVE_SLOT sender seq remains HOLD for this attribution cut.
```

### Frozen attribution

```text
Q1 late:     PIPELINE_SCHEDULING
Q2 underrun: PCM_SUPPLY_GAP
Q3:          STRONG_COOCCURRENCE

Evidence: talkback/logs/live-slot-timing-20260904-163209/
```

## Phase posture (frozen)

```text
功能闭环             PASS
3-source sustained RX PASS
REORDER lock          CLOSED
CPU budget signal     HEALTHY so far
playout timing        OPEN
late/underrun          OPEN
8–10 source scaling   HOLD until timing attribution
```

## Why now

Slice 5b Attempt #5 (`slice5b-mcast-soak-20260904-160350`) closed the REORDER death-spiral harness issue.  
**Do not call this “3-source stability PASS”.**

Open anomalies from #5 (now interpretable):

```text
lateForPlayoutCount = 5051
underrunDelta         = 2689
playoutBudget P99     ≈ 8.2 ms
playoutBudget >20ms   = 1
```

`budget P99 ≪ 20ms` argues **against** receiver CPU/decode/mix capacity as the primary bottleneck.  
Next work is **timing attribution**, not source-count scaling.

## Purpose

Attribute `late` and `underrun` along the playout chain:

```text
network arrival time (rxWallMs)
  → media slot / playout deadline mapping (mediaSlot, mediaTimeMs, usefulDeadlineMs)
  → PCM mix/write cadence (~20 ms)
  → AudioTrack buffer / underrun
```

## Questions to answer

| # | Question | Hypothesis axis |
|---|----------|-----------------|
| Q1 | Is `late=5051` real network lateness, or slot/deadline mapping too tight? | Compare `rxWallMs` vs `mediaTimeMs` vs `usefulDeadlineMs` at admit |
| Q2 | Is `underrunΔ=2689` AudioTrack buffer/cadence config, or pipeline not supplying PCM every 20 ms? | Correlate write timestamps vs nominal slot period |
| Q3 | Are `late` and `underrun` temporally correlated? | Co-occurrence / lag histogram per cycle |

## Scope

```text
Topology: M01/S1 + M02/S2 + M03/S3 → M04 (same as Slice 5/5b)
Duration: 60–120 s observation (match soak cadence)
Slot clock: LIVE_SLOT (wall elapsed / 20 ms) with shared baseSeq
Harness: soak live-edge resync MAY remain enabled (≠ Profile 03 change)
```

### In scope

- Per-packet / per-cycle timing samples on receiver
- Arrival → slot → deadline → write timestamp trail
- Late vs underrun co-occurrence metrics
- AudioTrack write interval distribution

### Out of scope

```text
4 / 8 / 10 source synthetic scaling
Profile 03 jitter/reorder contract changes
capacity gates / G-RES-3
unicast fallback / ADR-0060 cutover
reopening Slice 5b REORDER lock
```

## Instrumentation (to add)

Receiver report additions (observation only):

```text
timingDiagnostic:
  lateSamples:            { count, bySource }
  arrivalToDeadlineUs:    { p50, p95, p99, max }
  writeIntervalUs:        { p50, p95, p99, max }   # consecutive successfulWrites
  lateUnderrunCooccurrence:
    cyclesWithLate:       N
    cyclesWithUnderrun:   N
    cyclesWithBoth:       N
    lateBeforeUnderrunMs: histogram (if correlated)
  perCycleTrail (sampled):  # cap e.g. 500 rows
    rxWallMs, mediaSlot, mediaTimeMs, usefulDeadlineMs,
    admitDisposition, writeWallMs, underrunDelta
```

Sender: unchanged LIVE_SLOT (`baseSeq + floor(elapsedMs/20)`).

## Execution (planned)

Reuse USB-swap pattern from Slice 5b; new step script or extend soak script with `-DiagnosticMode LIVE_SLOT_TIMING`.

```powershell
# after harness impl:
.\scripts\adr0060-phase1-live-slot-timing-step.ps1 -Step prep -SenderDurationSec 120
.\scripts\adr0060-phase1-live-slot-timing-step.ps1 -Step start-receiver      # M04 USB
.\scripts\adr0060-phase1-live-slot-timing-step.ps1 -Step start-senders-m01-m03
.\scripts\adr0060-phase1-live-slot-timing-step.ps1 -Step start-sender-m02
.\scripts\adr0060-phase1-live-slot-timing-step.ps1 -Step wait-and-pull
```

## Adjudication (attribution, not gates)

```text
PASS attribution clarity:
  Q1/Q2/Q3 answered with evidence trail
  dominant bucket identified:
    NETWORK_LATE | DEADLINE_MAPPING | WRITE_CADENCE | AUDIOTRACK_BUFFER | MIXED

FAIL attribution:
  late/underrun remain high with no dominant bucket
```

## References

- Slice 5b closure: `talkback/docs/analysis/phase1-slice5b-three-source-soak-run-card.md`
- Slice 5b evidence: `talkback/logs/slice5b-mcast-soak-20260904-160350/`
- Slice 5 LIVE_SLOT definition: `talkback/docs/analysis/phase1-slice5-three-source-network-run-card.md`
