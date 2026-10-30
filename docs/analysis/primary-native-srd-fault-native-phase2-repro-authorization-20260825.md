# Primary Native SRD Fault — 9b Phase-2 Repro Authorization

**ID:** primary-native-srd-fault-native-phase2-repro-authorization-20260825  
**Date:** 2026-08-25  
**Type:** Lab repro authorization · **NO product code · NO field · NO IA**  
**Status:** **AUTHORIZED**  
**Parent:** [primary-native-srd-fault-native-brief-9b-20260825.md](./primary-native-srd-fault-native-brief-9b-20260825.md)

---

## Frozen posture

```text
Primary RCA                  ACCEPTED
C+B / re-prime               NOT SELECTED
9b native brief              COMPLETE
9b phase-2                   AUTHORIZED (this doc)
Implementation IA            BLOCKED
Field                        NOT AUTHORIZED
9a Java instrumentation      DEFERRED
force-release / PC reset     DEFERRED
Factory partition            DEFERRED
```

---

## Scope

**Authorized:**

```text
Lab-only androidTest harness (com.talkback.lab.pnsrd)
Source trace desk doc + hang capture procedure
Device/emulator instrumented runs of arms 0–3
```

**Not authorized:**

```text
Talkback production path changes (Coordinator / WebRtcSharedFactory / lease / mutex)
Field 1a/1b · Implementation IA · 9a · B2 · partition · reset
```

---

## Baseline topology (all arms)

```text
1× PeerConnectionFactory
1× JavaAudioDeviceModule
2× PeerConnection (PC-A, PC-B)
Sequential host-shaped SRD:
  PC-A: OFFER + SLD → SRD#1(answer) PASS
  PC-B: OFFER + SLD → SRD#2(answer)   ← observation edge
```

**Does not import** production `TalkbackCoordinator` / `RealWebRtcAudioEngine` / lease stack.

---

## Arm matrix (one variable per arm)

| Arm | Track graph | PC-A before PC-B SRD | Isolates |
|-----|-------------|----------------------|----------|
| **0** | per-PC local tracks | inactive media / ICE | baseline minimal |
| **1** | shared single AudioTrack (SharedLocalAudio pattern) | inactive | **S4** vs arm 0 |
| **2** | shared track | **ICE/media active** | **S1/S2** vs arm 3 |
| **3** | shared track | inactive (explicit control for arm 2) | control |

Arm 1 and arm 3 share the same configuration; arm 3 exists as the **paired control** for arm 2 adjudication.

---

## Execution protocol

```text
1. Construct factory + ADM + 2 PC (arm track mode)
2. PC-A: createOffer → setLocalDescription(OFFER)
3. PC-B: createOffer → setLocalDescription(OFFER)
4. Thread-A: PC-A setRemoteDescription(ANSWER) — SRD#1
5. Arm media step:
     arm 0/1/3 → no track enable · no ICE injection
     arm 2     → enable PC-A track · inject host ICE · wait CHECKING|CONNECTING (≤5s)
6. Thread-B: PC-B setRemoteDescription(ANSWER) — SRD#2 (timeout 15s)
7. Record ENTER/EXIT · elapsed · ICE snapshot · verdict
8. teardown factory + ADM
```

**SRD timeout:** 15s (TIER_JNI surrogate: ENTER without EXIT).

---

## Success criteria (NOT “repro = fixed”)

Phase-2 **succeeds** when at least one mechanism class is **narrowed**:

```text
S1/S2 narrowed   e.g. arm2 HANG + arm3 PASS → signaling/worker sequencing
S3/S4/S7 narrowed e.g. arm0 PASS + arm1 HANG → shared track / ADM graph
```

**Failure to reproduce on 2 PC does NOT imply production safe.**

Production adds: 3 PC · real ICE · conference observers · topology churn.

Repro **shrinks mechanism space** only.

### Outcome routing

| Observation | Route |
|-------------|-------|
| arm2 HANG, arm3 PASS | S1/S2 primary → minimal native/thread fix design |
| arm1 HANG, arm0 PASS | S4 (+ S3/S7) → architecture / capability boundary |
| all PASS | scaling/interaction at N≥3 or real ICE → no Java patch; architecture review |
| all HANG even arm0 | broader factory/ADM issue → source trace + stack capture |

---

## Harness location

```text
talkback/android-board-talkback/src/androidTest/java/com/talkback/lab/pnsrd/
  PnsrdLabFactory.kt
  PnsrdLabHarness.kt
  PnsrdLabSpikeInstrumentedTest.kt
```

Run (device connected):

```text
./gradlew :android-board-talkback:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.talkback.lab.pnsrd.PnsrdLabSpikeInstrumentedTest
```

Adjudication log tag: `PNSRD_LAB` · structured lines for `ADJUDICATION.txt` collection.

---

## Source trace

See [primary-native-srd-fault-native-phase2-source-trace-20260825.md](./primary-native-srd-fault-native-phase2-source-trace-20260825.md).

---

## Chain

```text
9b phase-2 repro + trace
    ↓
mechanism narrowed (or scaling boundary declared)
    ↓
minimal fix design OR architecture containment review
    ↓
(separate gate) Implementation IA
```
