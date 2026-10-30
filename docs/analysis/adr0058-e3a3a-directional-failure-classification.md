# ADR-0058 E3a.3a — Directional Failure Classification

## Status

```text
Phase 0 baseline:  STOP after R2 (repeatability satisfied)
Current finding:   CONFIRMED repeatable directional zero-delivery
                   M01→M02 fails (R1 + R2)
                   M02→M01 healthy (R1 + R2 GREEN)
Media engine:      NOT AUTHORIZED
Underlay tuning:   NOT AUTHORIZED until failure domain classified
```

## Problem statement

Baseline repeatability is **no longer the question**. R1/R2 under identical conditions show:

```text
M01 → M02 = 0%   (join OK, sender reports 3000/3000 sent, receiver 0)
M02 → M01 = GREEN (96–99% delivery, burst≤3, P99≤100)
```

This is **direction-dependent multicast forwarding failure**, not symmetric underlay incapacity.

## Phase 0 closure (2026-09-03)

| Round | M01→M02 | M02→M01 | Overall |
|-------|---------|---------|---------|
| R1 | 0% (`085721`) | 98.6% GREEN (`085854`) | AMBER |
| R2 | 0% (`090203`) | 96.3% GREEN (`090337`) | AMBER |

Artifacts: `logs/adr0058-e3a3-phase0-r1-20260903-085706/`, `logs/adr0058-e3a3-phase0-r2-20260903-090154/`

**Do not run R3** unless a formal gate requires 3/3 — information gain is low under unchanged conditions.

## Classification matrix (frozen)

Execute in order. **One variable at a time.** No AP/IGMP/pacing changes until matrix complete.

### Step 1 — Sender/receiver socket facts (instrumentation)

Current v2 JSON gap — add to probe report (observability only):

```text
networkInterfaceName
socketNetworkInterface (resolved name)
socketLocalAddress
socketBoundPort
senderSendErrorCount (already: senderError first only)
receiverJoinGroup / receiverMembershipInterface
```

**R2 forward already proves (logcat):**

- M01 sender: `packetsSent=3000`, no `senderError`, `PROBE_SEND_PROGRESS` through seq 3000
- M02 receiver: `joinSucceeded=true`, `receivedCount=0`, `receiveTimeouts=299`

→ Socket send loop ran; failure is **not** "sender crashed before send". Still need **interface/local address** to rule out wrong egress.

### Step 2 — Swap receiver (M01 sender fixed)

| Run | Expected inference |
|-----|-------------------|
| M01→M03 | If 0%: suspect M01 egress or AP forwarding from M01 |
| M01→M03 | If OK: suspect M02 ingress / membership on receive path |

**Prerequisite:** M03 receiver probe execution failure must be fixed first (prior runs 174057/175010 INVALID).

### Step 3 — Swap sender (M02 receiver fixed)

| Run | Expected inference |
|-----|-------------------|
| M03→M02 | If OK: M02 can receive from non-M01 sender |
| M03→M02 | If 0%: M02 receive path broadly broken (less likely given M02→M01 GREEN) |

M02→M01 GREEN is already strong control: M02 is not universally unable to **send**; M01 is not universally unable to **receive**.

## Decision tree

```text
M01→M02 = 0% (confirmed x2)
M02→M01 = GREEN (confirmed x2)
        │
        ├─ M01→M03 = 0%  → M01 sender egress OR AP drop from M01 source
        ├─ M01→M03 = OK  → M02 receiver ingress / IGMP membership on M02
        ├─ M03→M02 = OK  → narrows to M01-specific source path
        └─ M03→M02 = 0%  → M02 receive broken (contradicts M02→M01 unless timing/state)
```

## Out of scope (until classification CLOSED)

```text
≠ pacing changes
≠ AP/IGMP parameter sweeps
≠ media engine / Opus / AudioTrack
≠ ADR-0056 cutover
```

## Next implementation slice

**E3a.3a.1 — Probe socket/interface observability** (minimal JSON fields for Step 1)  
**E3a.3a.2 — M03 receiver probe fix** (unblock Step 2)  
**E3a.3a.3 — Classification runs** (M01→M03, M03→M02)
