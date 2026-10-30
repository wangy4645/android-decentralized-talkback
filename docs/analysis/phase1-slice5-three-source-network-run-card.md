# Phase 1 Slice 5 — Three-Source Real Network Closure Run Card

## Status

```text
Phase:     MULTICAST-FIRST FUNCTIONAL MILESTONE
Slice 4:   PASS (M01→M03 single-source network)
Slice 4b:  PASS (M01/S1 + M02/S2→M03 dual-source network)
Slice 5:   PASS (2026-09-04; ALIGNED_SLOT; run `slice5-mcast-three-20260904-151058`)
Slice 5b:  CLOSED — 3-source sustained timing PASS (…-170738)
4-source:  AUTHORIZED (receiver scaling observation)
8–10 cap:  HOLD (after 4→8 ladder)
```

## Purpose

Close the **3-person minimal conference** on **real multicast network**, extending Slice 4b from 2 sources to 3:

```text
M01/S1 + M02/S2 + M03/S3
  → multicast SRTP
  → M04 receiver
  → 3× ingress / jitter
  → Top-K (≤4)
  → 3× real Opus decode
  → mix
  → real AudioTrack
```

This is **not** 8–10 person capacity, **not** production cutover, **not** real Mesh acceptance.

## Prerequisites

### Devices (4 required)

| Label | Role (primary) | Source | Serial (field) |
|-------|----------------|--------|----------------|
| M01 | Sender | S1 (440 Hz) | `HTUBB21B09220661` |
| M02 | Sender | S2 (554 Hz) | `DSJ-2407041` |
| M03 | Sender | S3 (659 Hz) | `MDX0220416001963` |
| M04 | **Receiver only** | — | `DSJ-2407011` |

### USB constraint (field)

```text
M02 and M04 share ONE USB cable — only one can be wired at a time.

Install / wireless setup: sequential USB per device (see script below).
Slice 5 concurrent run: all four via WiFi ADB after prep (USB can be unplugged).
```

```powershell
# Per device while on USB (repeat for M02, then M04 when swapping cable):
.\scripts\adr0060-phase1-field-device-prep.ps1 -Devices M04
.\scripts\adr0060-phase1-field-device-prep.ps1 -Devices M02

# Verify all four online (USB and/or <ip>:5555):
adb devices -l
```

Wireless map: `logs/field-device-wireless.json` (optional; if wireless fails, use USB-swap launch below)

### USB-swap launch (no wireless required)

M02 and M04 share one cable — Slice 5 can still run:

```text
T+0s   M04 on USB  -> start receiver instrument (16s, keeps running after unplug)
T+3s   M01+M03 USB -> start S1 + S3 senders (parallel)
T+3–8s Swap USB to M02 -> start S2 sender
       All four probes run on-device; USB not needed during test body
T+end  Pull reports: reconnect M04 USB (or any device) one at a time
```

```text
All four MUST have APK installed before launch.
M02 install: swap cable from M04, run field-device-prep -Devices M02
```

### Underlay

```text
UNDERLAY:        WIFI SIMULATION LAN (wlan0)
REAL_MESH:       ASSUMED / UNPROVEN — do not extrapolate Slice 5 PASS to Mesh
```

### Software

```text
Same build family as Slice 4b PASS commit.
Slice 5 harness: NOT YET IMPLEMENTED (this card authorizes impl + run).
```

## Topology

```text
M01 / S1 ─┐
M02 / S2 ─┼─ multicast SRTP @ 239.255.42.99:47099 / wlan0 ─→ M04
M03 / S3 ─┘

M04:
  explicit interface bind (wlan0)
  MulticastLockPolicy = WIFI_MULTICAST_LOCK
  authority admits S1 + S2 + S3 (Phase1MediaHarness fixtures)
  NO sender role on M04 for primary run
```

### Optional rotation (after primary PASS)

Run the same probe with each device as receiver once (4 runs total).  
Purpose: receiver robustness — **not** a separate gate; record in appendix only.

## Media clock policy (Slice 5 vs Slice 4b)

Slice 4b used **probe-only aligned slot** (`0x6000` fixed seq) to prove dual-source convergence.  
Slice 5 introduces **live slot mode** as the default path:

| Mode | Senders | Receiver mix trigger |
|------|---------|----------------------|
| `LIVE_SLOT` (default) | `seq = baseSeq + floor(elapsedMs / 20)` per device; **same `baseSeq`** passed via instrumentation | Mix when ≥3 sources have buffered frame for **same slot** |
| `ALIGNED_SLOT` (fallback) | Fixed `alignedMediaSlot` (Slice 4b pattern) | Same as Slice 4b |

```text
Primary run: LIVE_SLOT
If LIVE_SLOT fails mix gate but ingress gates pass → one ALIGNED_SLOT diagnostic rerun (same session, no threshold change)
Do NOT treat ALIGNED_SLOT PASS as substitute for LIVE_SLOT PASS
```

Sender `baseSeq` MUST be identical on M01/M02/M03 (script passes `-e baseSeq 12288` or similar).  
Senders start in parallel within ≤500 ms after receiver lead.

## Frozen probe parameters

| Parameter | Value | Notes |
|-----------|-------|-------|
| Multicast | `239.255.42.99:47099` | Same as Slice 4/4b |
| Interface | `wlan0` | Explicit bind; no default route |
| Nominal PPS | 50 / source | 150 aggregate to receiver |
| Sender duration | 10 s | Soak extension: separate card |
| Receiver lead | 3 s | Receiver up before senders |
| Receiver tail | 3 s | |
| Opus | Concentus real decode | Slice 3 seam |
| Top-K | 4 (frozen) | 3 active sources < K |
| MaxLiveDecoders | 5 (frozen) | 3 active sources < cap |

## Gates (M04 receiver report)

**PASS requires ALL:**

```text
G0  bindSucceeded = true
G1  ingressAccepted(S1) > 0
G2  ingressAccepted(S2) > 0
G3  ingressAccepted(S3) > 0
G4  ingressRejected = 0
G5  decodeSuccess(S1) > 0
G6  decodeSuccess(S2) > 0
G7  decodeSuccess(S3) > 0
G8  mixCyclesWithThreeParticipants > 0
G9  successfulWrites > 0
G10 failedWrites = 0
```

```text
SLICE5_VERDICT = PASS  iff  G0..G10 all true (LIVE_SLOT mode)
```

### Recorded only (NOT gates)

```text
per-source jitter depth
packet→playout latency
decodeDurationUs / mixDurationUs / playoutBudgetUsedUs
audioTrackUnderrunCount
joinSucceeded / joinError
sender packetsSent / sendErrors per device
slotMode (LIVE_SLOT | ALIGNED_SLOT)
```

## Execution (after harness impl)

```powershell
cd talkback
.\scripts\adr0060-phase1-slice5-mcast-three-source-run.ps1 `
  -SenderDurationSec 10 `
  -ReceiverLeadSec 3 `
  -ReceiverTailSec 3 `
  -SlotMode LIVE_SLOT `
  -BaseSeq 12288
```

Artifacts:

```text
logs/slice5-mcast-three-source-<stamp>/
  RUN_META.txt
  ADJUDICATION.md
  M01-instrument.txt … M04-instrument.txt
  device-reports/
    M01-sender.json
    M02-sender.json
    M03-sender.json
    M04-receiver.json
```

## Implementation checklist (pre-run)

```text
[ ] Slice5MulticastThreeSourceRunner (extend Slice4b pattern)
[ ] SSRC resolve S1/S2/S3 (existing WireSourceIdentityResolver + S3 fixture)
[ ] LIVE_SLOT sender seq = baseSeq + elapsedSlots
[ ] Receiver: tryAlignedMix for 3 fixtures (peekBufferedFrame all 3)
[ ] Slice5MulticastThreeSourceActivity + InstrumentedTest
[ ] adr0060-phase1-slice5-mcast-three-source-run.ps1 (4 devices)
[ ] M04 serial in script param -ReceiverSerial or devices map
```

## Failure routing

| Observation | Route |
|-------------|-------|
| Ingress OK, mix never 3-participant (LIVE_SLOT) | Media clock / slot alignment — check baseSeq, parallel start, tryAlignedMix |
| Ingress OK on 2 sources only | Network / SSRC / wrong sender mapping |
| ingressRejected > 0 | Profile 02 / authority / SRTP — not capacity |
| decode OK, writes = 0 | AudioTrack / playout seam |
| PASS on ALIGNED_SLOT only | LIVE_SLOT regression — do not close Slice 5 |
| underrun high on PASS | Record for Slice 5b soak; do not reopen gates |

## Explicit non-goals

```text
≠ 8–10 node conference
≠ formal receiver capacity gate
≠ ADR-0056 Meeting cutover
≠ multicast→unicast fallback
≠ G-RES-3 / E3b
≠ real Mesh acceptance (ADR-0059 gate — deferred to Mesh hardware)
≠ full-duplex (each phone send+receive simultaneously)
```

## After Slice 5 PASS

```text
Slice 5b (optional): 3-source soak 60–120 s, observability only
Mesh track:           real underlay acceptance when hardware ready
Scale track:          8–10 requires ≥8 senders + real Mesh — not 4-phone WiFi lab
Resilience track:     unicast fallback / ADR-0060 — after functional milestone
```

## Relationship to prior slices

```text
Slice 3:  3-source LOCAL real Opus + AudioTrack (fixture, no network)
Slice 4:  1-source NETWORK
Slice 4b: 2-source NETWORK (aligned slot probe)
Slice 5:  3-source NETWORK (live slot + 3-participant mix)  ← current
```

---

**Authorization:** Slice 5 run card ACCEPTED. Implementation + field run authorized when M04 is online and serial is recorded.
