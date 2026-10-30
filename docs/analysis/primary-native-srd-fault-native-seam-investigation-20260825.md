# Primary Native SRD Fault — Native Seam Investigation (§11)

**ID:** primary-native-srd-fault-native-seam-investigation-20260825  
**Date:** 2026-08-25  
**Type:** Desk / log investigation · **NO code · NO field**  
**Status:** **COMPLETE**  
**Parent:** [primary-native-srd-fault-minimal-fix-design-review-20260825.md](./primary-native-srd-fault-minimal-fix-design-review-20260825.md) §11

**Evidence:** `pnsrd-1a-20260825-120721/` · `pnsrd-1b-20260825-123029/`

---

## Investigation question

After SRD#1 success through E3, what **observable** shared-factory / WebRTC state changes before SRD#2 `setRemoteDescription()` enters native — and is there a **minimal, safe, explainable seam** for intervention?

---

## 1. Java call chain (actual seam stack)

Normative path for host conference `applyRemoteAnswer`:

```text
TalkbackCoordinator.conferenceApplyRemoteAnswer
  → ConferenceMediaJniAffinity.dispatch(SRD_APPLY, per-edge executor)
  → ConferenceSrdNativeDomainAdmission.runWithLease(ConferenceNativeExecutionDomain)
       LEASE_REQUEST → GRANTED
       NATIVE_DOMAIN_EXECUTION_ENTER
  → RealWebRtcAudioEngine.applyRemoteAnswer
  → awaitSetDescription(op=SRD, type=ANSWER)
  → invokeNativeSet
       SRD_MUTEX_WAIT_BEGIN
       WebRtcSharedFactory.withSrdApplyLock {   // process-wide ReentrantLock
           SRD_NATIVE_CALL_ENTER
           peerConnection.setRemoteDescription(...)   // JNI
           SRD_NATIVE_CALL_EXIT                     // only if JNI returns
       }
       SRD_MUTEX_RELEASED
       SRD_JNI_RETURN
  → (async) SRD_CALLBACK_ENTER on signaling thread → SRD_EXIT
  → NATIVE_DOMAIN_EXECUTION_EXIT
  → NATIVE_DOMAIN_LEASE_RELEASE(released=true)
  → DOMAIN_IDLE_E3
```

**Three containment layers (already present):**

| Layer | Object | Scope | Released on SRD#1 PASS |
|-------|--------|-------|------------------------|
| L1 | `ConferenceNativeExecutionDomain` lease | Java slot `shared-factory` | **Yes** → E3 |
| L2 | `WebRtcSharedFactory.sdpApplyMutex` | Process-wide SRD(answer) JNI entry | **Yes** → `SRD_MUTEX_RELEASED` |
| L3 | Per-edge executor thread | One `RealWebRtcAudioEngine` / PC per remote | N/A |

**PeerConnectionFactory** is **not** re-created per SRD. `ModuleMediaEngineFactory` holds one `RealWebRtcAudioEngine` (one `PeerConnection`) per remote module; all engines call `WebRtcSharedFactory.acquire()` → **same** `PeerConnectionFactory` instance (`factoryHash` stable per session).

**SharedLocalAudio:** one `AudioTrack` (`tb_shared_audio`) attached to every conference PC; `attachedPcCount=3` at all four SRD ENTER lines in clean 1a/1b.

---

## 2. Hypothesis A — E3 ≠ native negotiation reset

### Confirmed (field + this desk pass)

```text
SRD#1 → SRD_NATIVE_CALL_EXIT → EXECUTION_EXIT → LEASE_RELEASE → E3
SRD#2 → new LEASE_GRANT → EXECUTION_ENTER → SRD_NATIVE_CALL_ENTER → (no EXIT)
```

E3 proves **Java lease slot cleared**. It does **not** prove any native negotiation-context reset.

### Named Java contexts at E3 (what E3 actually clears)

```text
ConferenceNativeExecutionDomain.slot     → null
ConferenceSrdNativeObservability flags   → attempt closed for edge #1
```

### What E3 does **not** touch

```text
PeerConnectionFactory (same factoryHash)
PeerConnection objects (other modules unchanged)
SharedLocalAudio track / attachedPcCount
WebRtcSharedFactory.sdpApplyMutex state (already unlocked after SRD#1)
Any factory-level “SRD ordinal” counter (does not exist in code)
```

**Conclusion A:** E3 is necessary bookkeeping, not native re-prime. **Supported.**

---

## 3. Hypothesis B — Second SRD ≠ “same lease again”

### Confirmed

SRD#2 obtains a **fresh** lease grant after E3 (`holderEdgeKey=NONE` between ordinals). `SRD_MUTEX waitMs=0` on ordinal 2 → mutex also free.

Hang occurs **inside** `peerConnection.setRemoteDescription` on the **second peer’s PC**, while L1/L2 admission succeeded.

**Conclusion B:** Problem is **below/outside** lease re-acquisition. **Supported.**

---

## 4. Minimal log diff — SRD#1 PASS vs SRD#2 HANG

Compared at `SRD_NATIVE_CALL_ENTER` (both clean runs, all four ordinals):

| Field | Ord-1 PASS (×2) | Ord-2 HANG (×2) | Delta? |
|-------|-----------------|-----------------|--------|
| `signalingState` | `HAVE_LOCAL_OFFER` | `HAVE_LOCAL_OFFER` | **No** |
| `iceConnectionState` | `NEW` | `NEW` | **No** |
| `connectionState` | `NEW` / `CONNECTING`* | `NEW` | Minor† |
| `localDesc` / `remoteDesc` | `OFFER` / `NONE` | `OFFER` / `NONE` | **No** |
| `attachedPcCount` | `3` | `3` | **No** |
| `factoryHash` | session-stable | same | **No** |
| `pcHash` | per remote PC | per remote PC | identity only |
| `pcGeneration` | `4` | `4` | **No** |
| `factoryHit` | (provision) | (provision) | **No** at SRD boundary |
| `lineageCorrelation` | `MATCH` | `MATCH` | **No** |
| `answerSdpBytes` | 1147–1148 | 1147–1148 | **No** |
| `SRD_MUTEX waitMs` | `0` | `0` | **No** |
| `thread` | `tb-edge-…\|M0x` | different edge thread | expected |
| `SRD_CALLBACK_ENTER` | **Yes** before EXIT | **Never** (hang) | **Yes** |

\*1b ord-1 M03 PASS logged `connectionState=CONNECTING` at ENTER; still PASS.  
†Not correlated with hang vs pass (ord-1 can be CONNECTING and succeed).

**Per-target PC pre-enter snapshot is symmetric** between first success and second hang.

---

## 5. Factory-wide state delta (between ordinals)

Observable **only by correlating** ord-1 EXIT with ord-2 ENTER (not a single log line):

### After SRD#1 EXIT (stable across 1a/1b)

```text
Ordinal-1 peer PC:
  signalingState=STABLE
  remoteDesc=ANSWER
  iceConnectionState=CHECKING (1a M02) or CONNECTED (1b M03)
  ICE candidate processing continues (addIceCandidate tasks on ord-1 edge)

Other attached PCs (not yet SRD):
  remoteDesc=NONE
  signalingState=HAVE_LOCAL_OFFER (typical)
```

### At SRD#2 ENTER (hanging PC)

```text
Entering PC: still OFFER / NONE remote (same as ord-1 had at its ENTER)
Factory: same factoryHash, attachedPcCount=3
At least one other PC: already STABLE + remoteDesc=ANSWER   ← factory-wide change vs ord-1 ENTER
```

### Stable correlates (both sessions)

| Correlates with ord-2 hang | Correlates with ord-1 pass |
|----------------------------|----------------------------|
| **Second** host SRD on shared factory in session | **First** host SRD |
| ≥1 other PC already post-SRD (`remoteDesc=ANSWER`) | No PC yet post-SRD at ENTER |
| Ordinal-1 ICE active on completed edge | — |

**Important:** These are **session-level correlates**, not logged factory counters. Cannot prove causation from logs alone.

---

## 6. What is **not** a seam (ruled out)

| Candidate | Why ruled out |
|-----------|---------------|
| Wait for E3 / lease idle | **Falsified** — E3 before every ord-2 hang |
| Stronger `sdpApplyMutex` alone | Mutex free at ord-2 ENTER; already serializes JNI |
| Per-PC input mismatch | ENTER fields symmetric; MATCH lineage |
| `PeerConnectionFactory` re-acquire | Same `factoryHash`; not recreated |
| Abstract `sharedFactorySrdReprime()` | **No named state delta to reset** at current observability |

---

## 7. §11 completion verdict — **Result 2 (+ Result 3 path)**

### Primary outcome: **Result 2 — no provable re-prime seam at current Java/log surface**

```text
E3后不存在稳定、可操作的 shared state delta（在现有日志字段内）
```

Logged per-PC ENTER state for ord-2 HANG **matches** ord-1 PASS.  
No Java-visible factory ordinal counter, epoch, or negotiation latch exists to target.

**Therefore:**

```text
B (re-prime)     → NOT SELECTED — no explainable reset target yet
sharedFactorySrdReprime() → MUST NOT be implemented as placeholder
Implementation IA → remains BLOCKED
```

### Contributing outcome: **Result 3 — native/WebRTC internal**

```text
Symptom:  SRD_NATIVE_CALL_ENTER without SRD_CALLBACK_ENTER (TIER_JNI)
Location: peerConnection.setRemoteDescription inside withSrdApplyLock
Inputs:   Logged Java/WebRTC PC snapshot symmetric for ord-1 vs ord-2 ENTER
Context:  Second sequential SRD on shared PeerConnectionFactory with
          attachedPcCount=3 and ≥1 peer already post-SRD + active ICE
```

Hang is **ordinal-dependent** but the discriminant is **not exposed** in current telemetry. Likely native factory multi-PC interaction — **not** a missing Java wait.

---

## 8. Mechanism model (desk synthesis)

```text
                    ┌─────────────────────────────────────┐
                    │  Java: E3 + mutex free + symmetric   │
                    │  ENTER snapshot on entering PC       │
                    └─────────────────┬───────────────────┘
                                      │
                    ┌─────────────────▼───────────────────┐
                    │  Factory-wide context (correlated):  │
                    │  1st PC post-SRD + ICE active        │
                    │  2nd SRD = 2nd JNI on shared factory │
                    └─────────────────┬───────────────────┘
                                      │
                    ┌─────────────────▼───────────────────┐
                    │  Native: setRemoteDescription hang    │
                    │  (opaque — no callback, mutex held)   │
                    └─────────────────────────────────────┘
```

**E3 measures L1 only.** L2 mutex does not encode ordinal. **No L3 factory negotiation epoch exists.**

---

## 9. Recommended next steps (still NO Implementation IA)

### Option 9a — Observability extension (desk design only; not authorized impl here)

Before any fix IA, add **read-only** boundary snapshots (future PR):

```text
SRD_BOUNDARY_SNAPSHOT
  factoryHash attachedPcCount completedHostSrdCount(session)
  per attached PC: remote pcHash signalingState remoteDesc iceConnectionState
  sdpApplyMutex locked? domain holder?
```

Goal: confirm or refute factory-wide correlate (post-SRD peer + ordinal count) with evidence, not inference.

### Option 9b — Native / WebRTC source investigation

Target question for native desk:

```text
PeerConnectionFactory + N attached PCs + shared ADM:
  first setRemoteDescription(answer) succeeds on PC A
  second setRemoteDescription(answer) on PC B hangs
  → which internal factory/signaling path differs?
```

May yield **Result 3** targeted native fix — not Java re-prime wrapper.

### Option 9c — C envelope without B

Ordinal gate + E3 prerequisite remains **safety invariant only** — **insufficient alone** per 1a/1b.  
Any C envelope intervention must be named by 9a/9b — not assumed.

---

## 10. Status after §11

```text
§11 investigation                     COMPLETE
Result 2 (no provable re-prime seam)  PRIMARY
Result 3 (native-internal path)        CONTRIBUTING
B / sharedFactorySrdReprime           NOT SELECTED
C envelope (in principle)             UNCHANGED — intervention TBD
Implementation IA                     BLOCKED
Field                                 NOT AUTHORIZED
Recovery / reset                      DEFERRED
```

**Next authorized artifact:** Observability spec (9a) **or** native source investigation brief (9b) — **not** Implementation IA until a **named** reset mechanism exists.

---

## Appendix — Code references

| Component | Path |
|-----------|------|
| Lease + E3 | `ConferenceSrdNativeDomainAdmission.kt` |
| Domain slot | `ConferenceNativeExecutionDomain.kt` |
| SRD JNI + mutex | `RealWebRtcAudioEngine.kt` (`invokeNativeSet`) |
| Factory mutex | `WebRtcSharedFactory.kt` (`sdpApplyMutex`) |
| Coordinator apply | `TalkbackCoordinator.kt` (~10156) |
| Per-module PC | `ModuleMediaEngineFactory.kt` |
| Shared track | `SharedLocalAudio.kt` |
