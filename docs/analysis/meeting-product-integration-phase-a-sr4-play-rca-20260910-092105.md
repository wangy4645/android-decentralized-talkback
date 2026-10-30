# RCA — PA-SR4-PLAY Runtime Execution (`RCA_PA_SR4_PLAY_EXECUTION`)

**Field prerequisite:** `talkback/logs/phase-a-sr4-rx-field-20260910-092105/` — **PASS_PA_SR4_RX**  
**Verdict:** `VALID PLAY FAIL` — ingress proven; decode/mix/playout **NOT RUNNING**  
**Date:** 2026-09-10  
**Scope:** Shadow runtime **playout execution lifecycle** only. **No code change in this document.**

**Explicitly out of scope:** Profile03 parameter / contract change · Top-K / jitter / decoder cap / limiter · RX/TX seams · production AudioTrack · ADR-0056 cutover.

---

## Failure locus (frozen)

```text
TX                  FIELD PASS (PR-PA-SR4-TX)
RX socket/poll      FIELD PASS (PR-PA-SR4-RX)
Q3 admission        FIELD PASS
pipeline ingress    FIELD PASS (admitProtectedDatagram)
jitter source       FIELD PASS (activeJitterSources=1)

decode              NOT RUNNING (liveDecoders=0)
mix                 NOT RUNNING
playout writes      NOT RUNNING (successfulPlayoutWrites=0)
```

**Not** an RX problem. **Not** admission / key / SSRC. **Not** Top-K parameter misconfiguration.

**Is** a **post-ingress execution gap**: jitter frames accumulate with no playout clock driving `pullSlot → decode → mix → RecordingPlayoutMetricsSeam.write`.

---

## Correlated identity

| Key | Value |
|-----|-------|
| sessionId | `c6cb896d-588b-49bb-bc4e-a4a06366d15a` |
| peer (M02) adjudication | RX PASS |
| host TX | `packetsSent_max=2000` |
| RX admitted / rejected | `1889 / 0` |
| INGRESS_ACTIVITY | `packetsReceived_max=3750` |
| PLAYOUT_ACTIVITY | `0` (observe-only at RX gate) |
| Snapshot at ingress | `activeJitterSources=1 liveDecoders=0 successfulPlayoutWrites=0 audioTrackOwner=SHADOW_METRICS_ONLY` |

---

## RCA layers

```text
Layer 1  Transport / RX poll     — PASS (field-proven)
Layer 2  Wire + jitter ingress  — PASS (field-proven)
Layer 3  Playout execution      — FAIL (no runtime tick → no decode/mix/write)
```

**Verdict:** Failure is **fully explained at Layer 3**. Layers 1–2 are closed.

---

## Frozen questions (three)

### Q1 — After `ingressAccepted > 0`, who should drive runtime tick / playout clock?

**`AbsoluteMediaPlayoutClock` (absolute 20 ms media slot scheduler) + per-tick `runMixPlayoutCycle`.**

Phase 1 harness / soak runners already implement this pattern:

| Runner | Clock | Per-tick action |
|--------|-------|-----------------|
| `Slice5bMulticastSoakRunner` | `AbsoluteMediaPlayoutClock(anchorMs, …)` | `runMixPlayoutCycle` inside `onTick` |
| `Slice5MulticastThreeSourceRunner` | aligned slot loop | `runMixPlayoutCycle` |
| `Slice4MulticastNetworkRunner` | slot-aligned receive/mix | `runMixPlayoutCycle` |

`AbsoluteMediaPlayoutClock` policy: one tick per due 20 ms slot, no burst catch-up, decoupled from receive blocking (`transport/AbsoluteMediaPlayoutClock.kt`).

**Meeting shadow path:** `conference/session/**` has **zero** references to `AbsoluteMediaPlayoutClock`, `runMixPlayoutCycle`, or `executeTimedMixCycle`.

`Profile01ShadowMulticastReceiveSeam` arms RX poll only (`receiveOnce → admitProtectedDatagram`). No companion playout driver.

`ConferenceSessionMediaWiring.ensurePlayoutStarted` calls `assembly.startPlayout()`, which only starts `AndroidAudioTrackPlayoutSeam` — **no-op for shadow** (`createShadow` uses `RecordingPlayoutMetricsSeam`; `startPlayout()` cast fails silently).

**Classification:** **Missing session-scoped playout execution driver** — not a missing Profile03 component.

---

### Q2 — Why does Top-K not produce `liveDecoders > 0`?

**Because there is no scheduler/tick — not because source failed decode eligibility.**

Per-packet ingress **does** run Top-K selection:

```text
admitProtectedDatagram:
  syncAdmittedToRuntime
  observeVoice
  selectTopK(rxWallMs)          ← Top-K updated on every admitted frame
  admitFrame → jitter queue
```

Field evidence confirms downstream state is live:

```text
admittedCount=1
activeJitterSources=1
ingressAccepted monotonic (→ 1889 admitted wire frames)
liveDecoders=0 throughout
```

`liveDecoders` comes from `LiveDecoderPool` — decoders are allocated only inside `DecodeMixRuntime.produceMixablePcm`, which is invoked from `ConferenceMulticastMediaPipeline.executeTimedMixCycle`:

```text
executeTimedMixCycle:
  selectTopK
  pullSlot (per Top-K member)     ← requires explicit media slot from clock tick
  produceMixablePcm             ← allocates decoder slot; increments liveCount
  EqualWeightMixer.mix
```

`DecodeEligibilityGate` requires installed executable incarnation **and** current Top-K membership. With ingress running, both preconditions are satisfiable once a mix cycle executes.

**Without `executeTimedMixCycle`:** frames sit in jitter; decoder pool never allocates; `liveDecoders` stays 0 even with `activeJitterSources=1`.

**Classification:** **Scheduler/tick absent** — not “source not decode-eligible” and not Top-K mis-wiring.

---

### Q3 — Why does `RecordingPlayoutMetricsSeam` have no write?

**Because `runMixPlayoutCycle` is never called — decode and mix never start; playout write path never reached.**

Write chain (existing, complete):

```text
runMixPlayoutCycle
  → executeTimedMixCycle (decode + mix)
  → orchestrator.playout(mixedBlock)
  → playoutSeam.write (shadow: RecordingPlayoutMetricsSeam)
  → successfulWrites++
  → Profile01ShadowRuntimeObservability.maybeLogPlayoutActivity
```

`RecordingPlayoutMetricsSeam` is already the shadow playout seam (`ConferenceMulticastRealMediaAssembly.createShadow`). It does **not** self-drive; it only records writes when `write()` is called.

`ensurePlayoutStarted` does **not** arm a mix cycle driver. `startPlayout()` does not touch `RecordingPlayoutMetricsSeam`.

**Classification:** Root cause is **playout executor not started** (no clock → no `runMixPlayoutCycle`). Not a broken metrics seam. Not production AudioTrack contention (`audioTrackOwner=SHADOW_METRICS_ONLY` confirmed in field).

---

## Root cause (closed)

```text
CONFIRMED: Post-ingress playout execution lifecycle driver absent in Meeting shadow path.

  GAP-D  RX poll admits frames into jitter + Top-K updates per packet.
         No AbsoluteMediaPlayoutClock (or equivalent) arms on WIRING_SESSION_STARTED /
         WIRING_MEMBER_INSTALLED.
         runMixPlayoutCycle never invoked → liveDecoders=0 → successfulPlayoutWrites=0.
```

Same integration class as historical GAP-C (RX executor): **existing runtime components present but not session-wired**.

Aligns with prior RCA prediction (`meeting-product-integration-phase-a-sr4-rca-runtime-execution-20260909-161221.md` § Recommended fix tracks: `PR-PA-SR4-PLAY Mix slot driver for shadow metrics playout`).

---

## What RX proved vs what PLAY requires

| RX (closed) | PLAY (next) |
|-------------|-------------|
| Datagram poll → `admitProtectedDatagram` | `AbsoluteMediaPlayoutClock` per session |
| `INGRESS_ACTIVITY packetsReceived > 0` | `runMixPlayoutCycle` per 20 ms tick |
| `activeJitterSources > 0` | `liveDecoders > 0` (OBS) |
| `PLAYOUT_ACTIVITY` observe-only | `PLAYOUT_ACTIVITY successfulPlayoutWrites > 0` (PASS) |

RX **≠** “decode/mix/playout already runs because ingress is non-zero.”

---

## PR-PA-SR4-PLAY — minimal implementation authorization

**Authorized:** narrow **session-scoped execution driver** only. **Do not** reimplement decoder, mixer, Top-K, jitter, or AudioTrack.

### Wiring contract (frozen)

```text
WIRING_SESSION_STARTED (or first WIRING_MEMBER_INSTALLED)
  → arm existing AbsoluteMediaPlayoutClock(anchorMs = session start)
  → each tick: assembly.pipeline.runMixPlayoutCycle(nowMs, slot, slotMediaTimeMs)

existing jitter (from RX)
  → existing Top-K (executeTimedMixCycle re-selects)
  → existing decoder (ConcentusOpusDecoderSeam)
  → existing mixer (EqualWeightMixer)
  → existing RecordingPlayoutMetricsSeam

SESSION_STOPPED / SHADOW_SESSION_STOPPING
  → clock.stop() + join
  → existing teardown (stopSession pipeline drain)
```

### Seam shape (mirror RX)

- New: `Profile01ShadowPlayoutClockSeam` (or equivalent name) — session map, one daemon thread per session.
- Hook: `ConferenceSessionMediaCoordinatorDelegate` — same lifecycle points as `Profile01ShadowMulticastReceiveSeam`.
- Pipeline access: `ConferenceSessionMediaWiring` exposes assembly/pipeline for tick callback, or dedicated `runPlayoutTick(sessionId, …)` facade.
- Thread safety: follow `Slice5bMulticastSoakRunner` — synchronize on pipeline lock during tick if RX poll concurrent.

### Hard boundaries (unchanged)

| # | Boundary |
|---|----------|
| 1 | No Profile03 parameter / contract change |
| 2 | No RX/TX seam changes |
| 3 | Shadow must not acquire production AudioTrack |
| 4 | `RecordingPlayoutMetricsSeam` remains SR4 evidence outlet |
| 5 | ADR-0056 owns audible audio |

### PLAY field gate (frozen)

**Prerequisites:**

```text
TX > 0 (host SHADOW_TX_ACTIVITY)
RX admitted > 0
INGRESS_ACTIVITY packetsReceived > 0
```

**PASS (peer M02):**

```text
PLAYOUT_ACTIVITY successfulPlayoutWrites > 0
```

**Observe only:**

```text
liveDecoders > 0
activeJitterSources > 0
mix/decode cycle telemetry (if emitted)
audioTrackOwner = SHADOW_METRICS_ONLY
```

### PA-SR4 closure predicate (if PLAY PASS)

```text
PA-SR4
  TX      PASS
  RX      PASS
  INGRESS PASS
  PLAY    PASS
  AudioTrack isolation PASS
→ CLOSED / FIELD-PROVEN
```

Then: SR5 leave/rejoin → SR6 teardown clean → Phase A closure.

---

## Status board (frozen)

```text
PR-PA-SR4-TX     CLOSED / UNIT+FIELD-PROVEN
PR-PA-SR4-RX     CLOSED / UNIT+FIELD-PROVEN
PA-SR4           VALID FAIL / PLAY NEXT
PR-PA-SR4-PLAY   RCA CLOSED → IMPLEMENTATION AUTHORIZED (minimal lifecycle driver)
PA-SR5/SR6       HOLD (after PA-SR4 closure)
ADR-0056         KEEP
cutover          NOT AUTHORIZED
Failure locus    playout execution lifecycle — missing AbsoluteMediaPlayoutClock session wiring
Next             PR-PA-SR4-PLAY implement + PLAY field gate
```

---

## Evidence pointers

| Item | Location |
|------|----------|
| RX field PASS adjudication | `talkback/logs/phase-a-sr4-rx-field-20260910-092105/ADJUDICATION.txt` |
| Ingress with liveDecoders=0 | `M02-logcat.txt` `phase=INGRESS_ACTIVITY` lines |
| Ingress-only path | `ConferenceMulticastMediaPipeline.admitProtectedDatagram` |
| Mix/playout path | `ConferenceMulticastMediaPipeline.runMixPlayoutCycle` / `executeTimedMixCycle` |
| Playout clock reference | `AbsoluteMediaPlayoutClock.kt`, `Slice5bMulticastSoakRunner.kt` |
| Shadow playout seam | `RecordingPlayoutMetricsSeam` in `ConferenceMulticastRealMediaAssembly.createShadow` |
| RX seam (no playout) | `Profile01ShadowMulticastReceiveSeam.kt` |
| Session wiring (no clock) | `ConferenceSessionMediaWiring.kt` — no playout tick |
| Prior SR4 RCA (predicted PLAY) | `meeting-product-integration-phase-a-sr4-rca-runtime-execution-20260909-161221.md` |
