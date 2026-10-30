# Phase 1 Slice 5b — Three-Source 120s Soak Run Card

## Status

```text
Slice 5:   PASS (ALIGNED_SLOT closure)
Slice 5b:  CLOSED — 3-SOURCE SUSTAINED TIMING PASS
REORDER lock: CLOSED
playout scheduling root cause: CLOSED
20ms absolute clock: VALIDATED @ 2-source + 3-source
pipelineLock contention: ACCEPTABLE @ 3-source
AudioTrack underrun: OPEN / independent (observe-only)
4→8→10 scaling: 4-source AUTHORIZED → see receiver-scaling run card
capacityGates: NONE
```

### Frozen verdict (clock soak — authoritative close)

```text
RUN_ID: slice5b-mcast-soak-20260904-170738
3-SOURCE 120s SUSTAINED TIMING: PASS

writeIntervalUs p50=20ms p99=24ms
mixCycles=6304 mix3=4628 writes=6304
late=738  budget P99≈8.4ms
underrunΔ=162 (observe-only — not fail)
pipelineLock wait p99≈2.3ms hold p99≈8.8ms
```

Evidence: `talkback/logs/slice5b-mcast-soak-20260904-170738/`

### Attempt log

| # | runId | result |
|---|-------|--------|
| 1–4 | … | REORDER / slot issues (superseded) |
| 5 | `…-160350` | SOAK_PRIMARY_PASS — REORDER unlock |
| clock@2src | `live-slot-timing-…-165716` | 20ms cadence PASS |
| **6** | `…-170738` | **3-SOURCE SUSTAINED TIMING PASS** |

## Purpose (historical)

Prove **sustained** 3-source multicast media pipeline after Slice 5 convergence:

```text
M01/S1 + M02/S2 + M03/S3 → M04
120s soak + AbsoluteMediaPlayoutClock
```

**Closed:** REORDER lock, playout scheduling, 3-source sustained timing.  
**Open:** AudioTrack underrun (independent track).

## Harness notes (frozen)

```text
CONTINUOUS_SOAK = monotonic mediaSlot + soak-harness live-edge resync
playout = AbsoluteMediaPlayoutClock (ABSOLUTE_20MS_NO_BURST)
pipelineLock on receive ∩ playout (contention observe-only)
Profile 03 caps unchanged
```

## Execution (historical)

```powershell
.\scripts\adr0060-phase1-slice5b-soak-step.ps1 -Step prep -SenderDurationSec 120
.\scripts\adr0060-phase1-slice5b-soak-step.ps1 -Step start-receiver      # M04 USB
.\scripts\adr0060-phase1-slice5b-soak-step.ps1 -Step start-senders-m01-m03
.\scripts\adr0060-phase1-slice5b-soak-step.ps1 -Step start-sender-m02   # swap to M02
.\scripts\adr0060-phase1-slice5b-soak-step.ps1 -Step wait-and-pull      # M04 USB for pull
```

## Next

`talkback/docs/analysis/phase1-receiver-scaling-4source-run-card.md`
