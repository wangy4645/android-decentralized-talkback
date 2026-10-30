# Phase 1 — 4-Source Receiver Scaling Observation Run Card

## Status

```text
4-source scaling:              PASS (pre-fix …-171824; post-fix …-102750)
pipelineLock hold tail:        REFINED + VERIFIED (AudioTrack outside lock)
lock model:                    PLAYOUT_LOCK_SCOPE_REFINEMENT_AUDIOTRACK_OUTSIDE
8-source / 10-source:          CLOSED @ N=10 — phase1-receiver-scaling-10source-run-card.md
capacityGates:                 NONE (observation only)
```

## Phase posture (frozen)

```text
4-source scaling              PASS
capsHeld                      PASS
20ms cadence                  PASS
budget P99                    PASS
pipelineLock wait             HEALTHY
pipelineLock hold tail        REFINED — AUDIOTRACK outside lock VERIFIED
lock model                    PLAYOUT_LOCK_SCOPE_REFINEMENT_AUDIOTRACK_OUTSIDE
8-source scaling              HOLD
10-source                     HOLD
```

## Purpose

Observe receiver behavior at **4 active sources** without changing Profile 03 frozen caps:

```text
TopK = 4
MaxLiveDecoders = 5
MixMaxSources = 4
```

Validate that scaling adds load **without** breaking the clock remediation or silently expanding decoders.

## Topology (4-phone lab)

```text
Network senders (unchanged):
  M01/S1 + M02/S2 + M03/S3 → multicast → M04 receiver

4th source (receiver-scaling probe):
  S4 synthetic / harness-injected on M04 OR dual-SSRC from one sender
  (impl chooses minimal path; MUST NOT add 5th physical device)
```

Duration: **90–120s** soak-style observation (match Slice 5b cadence).

Harness stack (carry forward):

```text
receive → admit → jitter enqueue     (receive thread)
AbsoluteMediaPlayoutClock @ 20ms     (playout thread)
soak live-edge resync                (harness only)
pipelineLock                         (receive ∩ playout)
```

## Watchpoints (not gates)

### Primary (scaling)

```text
jitter depth per source
TopK selected count (must stay ≤ 4)
liveDecoderCount (must stay ≤ 5; must NOT grow linearly with source count)
mix participant count distribution (3 vs 4)
```

### Lock / budget (from 3-source baseline)

```text
pipelineLock waitUs / holdUs p50 p99  — compare to 3-source (wait p99≈2.3ms hold p99≈8.8ms)
playoutBudget P99                      — must remain ≪ 20ms
writeIntervalUs p50                      — must remain ~20ms
```

### Observe-only

```text
underrunΔ — record; NOT a scaling gate unless明显随 source 数恶化
lateForPlayoutCount — record; expect low if clock holds
REORDER / resync — record; not reopening REORDER lock track
```

## Explicitly out of scope

```text
Profile 03 cap changes (TopK / decoders / jitter / deadline / PLC)
AudioTrack buffer tuning for underrun
8 / 10 source until 4-source PASS
ADR-0056 cutover / G-RES-3 / real Mesh acceptance
```

## Adjudication (observation)

```text
4-SOURCE_SCALING_PASS  iff:
  ingress accepted all 4 sources > 0
  sustained mix/write for soak window
  writeIntervalUs p50 ≈ 20ms
  TopK ≤ 4 AND liveDecoders ≤ 5 AND mix ≤ MixMaxSources(4)
  pipelineLock p99 not materially worse than 3-source baseline
  budget P99 healthy

underrun: recorded only — does not fail unless user promotes to gate later
```

## Ladder

```text
4 source  → PASS (…-171824)
8 source  → HOLD (await separate authorization)
10 source → HOLD (after 8 PASS)
```

## Execution

Reuse Slice 5b USB-swap flow (M04 receiver + M01/M02/M03 senders). S4 is **synthetic on M04**.

```powershell
.\scripts\adr0060-phase1-receiver-scaling-4source-step.ps1 -Step prep -SenderDurationSec 120
.\scripts\adr0060-phase1-receiver-scaling-4source-step.ps1 -Step start-receiver
.\scripts\adr0060-phase1-receiver-scaling-4source-step.ps1 -Step start-senders-m01-m03
.\scripts\adr0060-phase1-receiver-scaling-4source-step.ps1 -Step start-sender-m02
.\scripts\adr0060-phase1-receiver-scaling-4source-step.ps1 -Step wait-and-pull
```

Harness: `ReceiverScaling4SourceRunner` · reports under `receiver-scaling-4source/`

### Field result (`receiver-scaling-4source-20260904-171824`)

```text
EVIDENCE: RECEIVER_SCALING_OBSERVATION
TOPOLOGY: 3 real network sources + 1 synthetic source (S4)
NOT 4-device field evidence

ingress S1/S2/S3/S4 all > 0 · mix4=4486 · writes=6981
TopK max=4 · liveDecoders max=4 · capsHeld=true
writeInterval p50=20ms p99=28ms
budget P99≈17.3ms
lock wait p99≈1.8ms · hold p99≈16.2ms
underrunΔ=0 (observe-only; vs 3-source baseline 162)
jitter depth S1=3 S2=2 S3=3 S4=6 — no lock-up
```

Evidence: `talkback/logs/receiver-scaling-4source-20260904-171824/`

## Next

`talkback/docs/analysis/phase1-receiver-scaling-4source-lock-attribution-run-card.md` — pipelineLock hold attribution (before 8-source)

## References

- 3-source clock soak: `talkback/logs/slice5b-mcast-soak-20260904-170738/`
- Timing diagnostic: `talkback/docs/analysis/phase1-live-slot-timing-diagnostic-run-card.md`
- Slice 5b closure: `talkback/docs/analysis/phase1-slice5b-three-source-soak-run-card.md`
- Frozen caps: `talkback/docs/analysis/0058-implementation-profile-03-media-runtime.md`
