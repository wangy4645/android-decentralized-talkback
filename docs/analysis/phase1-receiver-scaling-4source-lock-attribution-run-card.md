# Phase 1 — 4-Source pipelineLock Hold Attribution Run Card

## Status

```text
COMPLETE — LOCK_ATTRIBUTION_COMPLETE (2026-09-04; run `…-172653`)
4-source scaling:              PASS (…-171824)
pipelineLock hold tail:        ATTRIBUTED — PRIMARY_PLAYOUT_MEDIA_WORK
8-source scaling:              HOLD — review AudioTrack hold tail before authorize
10-source:                     HOLD
capacityGates:                 NONE
```

## Frozen posture

```text
4-source scaling              PASS
capsHeld                      PASS
20ms cadence                  PASS
budget P99                    PASS
pipelineLock wait             HEALTHY
pipelineLock hold tail        WATCH / OPEN
8-source scaling              HOLD pending lock attribution
10-source                     HOLD
```

## Purpose

Attribute where **pipelineLock hold p99 ≈16.2ms** is spent at 4-source:

```text
PLAYOUT_DECODE
PLAYOUT_MIX
PLAYOUT_JITTER_PULL
PLAYOUT_AUDIOTRACK
SYNTHETIC_ADMIT / NETWORK_ADMIT
LOCK_UNATTRIBUTED (scope too wide?)
```

**Not** a capacity gate. **Not** a Profile 03 change. **Not** underrun attribution.

## Topology

Same as 4-source scaling:

```text
3 real network sources (M01/M02/M03) + 1 synthetic S4 on M04
RECEIVER_SCALING_LOCK_ATTRIBUTION — NOT 4-device field evidence
```

Duration: **60s** observation (shorter than scaling soak).

## Route after attribution

```text
PRIMARY_PLAYOUT_MEDIA_WORK        -> hold is normal media work; may authorize 8-source
PRIMARY_INGRESS_ADMIT_CONTENTION  -> decouple/narrow admit before 8-source
PRIMARY_LOCK_SCOPE_OVERHEAD       -> shrink critical section before 8-source
```

## Execution

```powershell
.\scripts\adr0060-phase1-receiver-scaling-4source-lock-attribution-step.ps1 -Step prep -SenderDurationSec 60
.\scripts\adr0060-phase1-receiver-scaling-4source-lock-attribution-step.ps1 -Step start-receiver
.\scripts\adr0060-phase1-receiver-scaling-4source-lock-attribution-step.ps1 -Step start-senders-m01-m03
.\scripts\adr0060-phase1-receiver-scaling-4source-lock-attribution-step.ps1 -Step start-sender-m02
.\scripts\adr0060-phase1-receiver-scaling-4source-lock-attribution-step.ps1 -Step wait-and-pull
```

Harness: `ReceiverScaling4SourceLockAttributionRunner` · `PipelineLockAttributionCollector`

### Field result (`receiver-scaling-4source-lock-attribution-20260904-172653`)

```text
interpretation: PRIMARY_PLAYOUT_MEDIA_WORK
playoutTickTotalHoldP99Us ≈ 33.2ms
  PLAYOUT_AUDIOTRACK p99 ≈ 25.7ms  ← dominant
  PLAYOUT_DECODE   p99 ≈  6.8ms
  PLAYOUT_MIX      p99 ≈  0.5ms
  PLAYOUT_JITTER_PULL p99 ≈ 0.5ms
ingressHoldP99Us ≈ 6.1ms (NETWORK_ADMIT + SYNTHETIC_ADMIT)
LOCK_UNATTRIBUTED p99 ≈ 0.5ms — scope NOT too wide
route: normal media work under lock; review AudioTrack tail before 8-source
```

Evidence: `talkback/logs/receiver-scaling-4source-lock-attribution-20260904-172653/`

## References

- 4-source scaling PASS: `talkback/logs/receiver-scaling-4source-20260904-171824/`
- Scaling run card: `talkback/docs/analysis/phase1-receiver-scaling-4source-run-card.md`
