# ADR-0058 — Underlay Multicast Dual-Node Probe Run Card

## Status

```text
Phase:     UNDERLAY REALITY CHECK (pre–media-engine)
E2b:       CLOSED (unit conformance — separate gate)
ADR-0057:  OUT OF SCOPE
ADR-0056:  RETAINED — probe does NOT cut over Meeting
GROUP PTT: UNTOUCHED
```

## Purpose

Validate the **physical premise** of ADR-0058 / ADR-0059 before investing in Opus,
AudioTrack, or TalkbackSession wiring:

```text
Can two MANET nodes deliver Profile-02-sized UDP multicast at NominalPps=50
with loss/jitter within frozen Profile 03 reference budgets?
```

This is **not** Conference E2E, **not** SRTP, **not** Top-K / PLC runtime proof.

## Topology

```text
M01 (HTUBB21B09220661)  --multicast send-->  239.255.42.99:46999
M02 (DSJ-2407041)       <--multicast recv---
```

## Frozen probe parameters (aligned to contract references)

| Parameter | Value | Contract reference |
|-----------|-------|-------------------|
| Payload | 120 B | Profile 02 SafeUdpPayloadBytes |
| Rate | 50 pps | Profile 02 NominalPps |
| Inter-arrival | 20 ms nominal | derived |
| Jitter gate | P99 ≤ 120 ms | Profile 03 MaxPlayoutDelayMs (reference) |
| Loss gate | max consecutive gap ≤ 5 | Profile 03 MaxConsecutivePlcFrames (reference) |
| Delivery gate | ≥ 90% (2-node lab threshold) | probe-only; not contract |

## Gates

| Gate | Check |
|------|-------|
| G0 | Receiver `joinSucceeded`; MulticastLock released on transport end |
| G1 | `received / sent ≥ 90%` |
| G2 | `maxConsecutiveLoss ≤ 5` |
| G3 | `interArrivalJitterMsP99 ≤ 120` |

```text
UNDERLAY_MULTICAST_PROBE_VERDICT=PASS
  => underlay premise plausible; may proceed to media-engine phase
FAIL
  => stop integration; classify underlay / interface / routing issue
```

## Execution

```powershell
cd talkback
.\scripts\adr0058-underlay-mcast-probe-start-run.ps1
```

Optional:

```powershell
.\scripts\adr0058-underlay-mcast-probe-start-run.ps1 -SenderDurationSec 120 -SkipBuild
.\scripts\adr0058-underlay-mcast-probe-adjudicate.ps1 -LogDir logs\adr0058-underlay-mcast-probe-<stamp> -MinDeliveryRate 0.85
```

Artifacts:

```text
logs/adr0058-underlay-mcast-probe-<stamp>/
  RUN_META.txt
  M01-probe.log / M02-probe.log
  device-reports/...-sender.json
  device-reports/...-receiver.json
  ADJUDICATION.txt
```

## Implementation

```text
com.talkback.core.conference.probe.UnderlayMulticastProbeRunner
  → AndroidRuntimeResources.forAndroidProduct (real MulticastLock + MulticastSocket)
com.talkback.lab.underlay.UnderlayMulticastProbeInstrumentedTest
```

## Field evidence (2026-09-02)

| Run | Iface | G0 join | Delivery | Notes |
|-----|-------|---------|----------|-------|
| `172251` | (default) | FAIL | 0% | M02 join failed; M01 sent 1500 |
| `172740` | wlan0 | PASS | 0% | Both join OK, port 46999; **zero datagrams received** |

```text
Interpretation: local Android multicast join can succeed while inter-station
delivery remains absent — likely AP/IGMP/MANET forwarding, not app seam bug.
Do NOT proceed to media-engine until G1 passes on this probe.
```


```text
≠ 8–10 node capacity
≠ ADR-0056 deletion authorization
≠ Conference voice quality
≠ SRTP / wire corpus conformance (E1 already PASS in isolation)
```
