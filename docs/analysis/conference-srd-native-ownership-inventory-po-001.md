# PO-001 — Conference SRD Native Ownership Inventory

**ID:** conference-srd-native-ownership-inventory-po-001  
**Date:** 2026-08-21  
**Version:** v0.1  
**Type:** Read-Only Audit Authorization  
**Status:** **COMPLETE** · **Implementation NOT AUTHORIZED** · **Behavior change NOT AUTHORIZED**

**Parent decision:** [conference-srd-native-completion-isolation-decision.md](./conference-srd-native-completion-isolation-decision.md) (B+C direction)  
**Field coupling evidence:** [rca-b-confirm-srd-native-20260821-133115/ADJUDICATION.txt](../../logs/rca-b-confirm-srd-native-20260821-133115/ADJUDICATION.txt)

---

## Audit question (single)

> On the `setRemoteDescription` native execution path, which resources determine whether multiple edges can progress independently?

---

## Verdict

```text
PO-1: BLOCKED (scope expansion required for Option B)
```

**Not PASS** — M03 hang dependency set is **not** `{sdpApplyMutex only}`.

**Not FAIL** — per-edge isolation remains feasible under B+C with expanded scope; does not require abandoning direction.

---

## Summary finding

Cross-edge SRD realization coupling is **proven at `sdpApplyMutex`** (field + code). Additional **process-wide shared resources** exist and must be in PO-2 isolation boundary:

```text
sdpApplyMutex                    — proven cross-edge blocker (M03→M04)
PeerConnectionFactory (singleton) — shared by all edges
JavaAudioDeviceModule (singleton) — shared capture/playback path
SharedLocalAudio track           — one track attached to every PC
WebRTC native signaling worker   — not instrumented in Java; treat as PO-2 unknown
```

Option B **viable as primary direction** only after PO-2 defines edge-local boundary against this inventory.

---

## 1. Mutex / lock inventory

| Lock | Owner | Scope | Held across native call | Held across callback wait | Shared across edges | Evidence |
|------|-------|-------|-------------------------|---------------------------|---------------------|----------|
| `sdpApplyMutex` | `WebRtcSharedFactory` | `withSrdApplyLock` block | **YES** (SRD ANSWER only) | **NO** (latch wait after `invokeNativeSet` returns) | **YES** (process-global) | `WebRtcSharedFactory.kt:26,40-46` · `RealWebRtcAudioEngine.kt:641-669` |
| `WebRtcSharedFactory.lock` | `WebRtcSharedFactory` | factory acquire/release/teardown | NO (not on SRD path) | NO | **YES** | `WebRtcSharedFactory.kt:49-73` |
| `SharedLocalAudio.lock` | `SharedLocalAudio` | track acquire/attach count | NO (init only on engine create) | NO | **YES** (one track → all PCs) | `SharedLocalAudio.kt:13-48` · `RealWebRtcAudioEngine.kt:120-125` |
| `ConferenceSignalingLockRegistry` locks | per `(sessionId, peerId)` | offer/answer/commit send paths | varies | NO on SRD accept path | **NO** (per peer edge key) | `ConferenceSignalingLock.kt:30-65` |
| `PeerMediaExecutors` lanes | per `edgeKey` | edge executor queue | NO (separate threads M02/M03/M04) | N/A | **NO** | `PeerMediaExecutors.kt:31-32,56-80` |
| PeerConnection Java lock | WebRTC SDK | internal | unknown | unknown | per PC instance | not visible — native |
| JNI / signaling thread sync | WebRTC native | internal | unknown | unknown | **likely shared factory worker** | not visible — PO-2 risk |

**SRD ANSWER path mutex note:** `invokeNativeSet` holds `sdpApplyMutex` for entire `nativeCall()` = `peerConnection.setRemoteDescription(...)`. If native never returns, `finally` unlock does not run → mutex retained → other edges block at `SRD_MUTEX_WAIT_BEGIN` (or never reach native telemetry if blocked earlier on native worker).

---

## 2. PeerConnection ownership map

| Resource | M02 edge | M03 edge | M04 edge | Shared? |
|----------|----------|----------|----------|---------|
| `RealWebRtcAudioEngine` | 1 per `remoteModuleId` | 1 per `remoteModuleId` | 1 per `remoteModuleId` | **NO** |
| `PeerConnection` instance | per engine | per engine | per engine | **NO** (separate `pcHash` in field logs) |
| `PeerConnection.Observer` | per PC ctor | per PC ctor | per PC ctor | **NO** |
| `PeerConnectionFactory` | `WebRtcSharedFactory.acquire()` | same singleton | same singleton | **YES** |
| `JavaAudioDeviceModule` | shared factory ADM | same | same | **YES** |
| Local audio track | `SharedLocalAudio` singleton | same track id | same track id | **YES** |
| Executor thread | `tb-edge-{session\|M02}` | `tb-edge-{session\|M03}` | `tb-edge-{session\|M04}` | **NO** |
| `ConferenceSignalingLock` | per `(session, M02)` | per `(session, M03)` | per `(session, M04)` | **NO** |
| `sdpApplyMutex` | global | global | global | **YES** |

**Engine factory:** `ModuleMediaEngineFactory.kt:16-25` — one engine per remote module id.  
**Conference accept path:** `TalkbackCoordinator.kt:9595-9608` — SRD via edge executor; **does not** take `ConferenceSignalingLock` on accept-answer apply.

---

## 3. Native boundary map (SRD ANSWER · Offerer)

```text
TalkbackCoordinator.dispatchConferenceAcceptMedia
  (coordinator: gate + watchdog + beginAttempt)
        |
        v
PeerMediaExecutors.execute  edgeKey=session|peer   [per-edge thread]
        |
        v
engine.applyRemoteAnswer()                        RealWebRtcAudioEngine.kt:197
        |
        v
awaitSetDescription(op=SRD, type=ANSWER)          :580
  log SRD_ENTER                                     :590  ← before mutex
  build SdpObserver (per-call instance)             :595
        |
        v
invokeNativeSet                                     :642
  log SRD_MUTEX_WAIT_BEGIN                          :647
  WebRtcSharedFactory.withSrdApplyLock              :649  ← SHARED BOUNDARY
    log SRD_MUTEX_ACQUIRED                          :651
    log SRD_NATIVE_CALL_ENTER                       :658
    peerConnection.setRemoteDescription(observer)   :659  ← JNI / native
    log SRD_NATIVE_CALL_EXIT                        :661  (if returns)
    log SRD_MUTEX_RELEASED                          :663  (finally)
        |
        v
log SRD_JNI_RETURN                                  :627  (after invokeNativeSet)
pendingSdpWait.await(latch)                         :633  ← OUTSIDE mutex
  ← callback onSignalingChange / SdpObserver        (WebRTC signaling thread → PC A)
```

**Shared resource boundary (Java-visible):** `sdpApplyMutex` wraps native entry for **all** edges.

**Latch / callback wait:** outside mutex (comment at `RealWebRtcAudioEngine.kt:641` confirmed).

---

## 4. Callback ownership

| Question | Answer |
|----------|--------|
| SdpObserver instance | **Per call** — stack local in `awaitSetDescription` (`RealWebRtcAudioEngine.kt:595-623`) |
| Observer binding | **Per PeerConnection** — passed into `setRemoteDescription(observer, …)` |
| Delivery thread | **WebRTC internal signaling thread** (not app executor); not separately locked in app code |
| Factory-global observer | **NO** |
| M03 native stuck → M04 callback runnable? | **Java mutex:** M04 can still reach own PC observer **if** M04 acquires mutex and completes native call. **Field:** M04 failed with `lastEvent=NONE` — did not reach SRD telemetry; consistent with **mutex block before SRD_ENTER** or **native worker contention** (PO-2) |

**Important:** M03 non-return prevents `sdpApplyMutex` release → M04 blocks at mutex → **proven coupling mechanism** for derived M04 failure even with separate PCs and executors.

---

## 5. Field corroboration (session `5ce616de`)

| Edge | Observation | PO-1 read |
|------|-------------|-----------|
| M02 | `NATIVE_CALL_ENTER` → `EXIT` → task success | Independent path works when mutex available |
| M03 | `ENTER` → no `EXIT` → `missing=NATIVE_CALL_EXIT` | Hang **inside** mutex + native |
| M04 | `SRD_APPLY START` → watchdog `lastEvent=NONE` | No SRD telemetry; coupled failure while M03 mutex held (~3s overlap) |

---

## Completion criteria

| Criterion | Result |
|-----------|--------|
| PASS: dependency = `{sdpApplyMutex only}` | **NO** |
| BLOCKED: mutex + other shared resources | **YES** |
| FAIL: per-edge isolation impossible without PC lifecycle change | **NO** |

---

## Implications for B + C

```text
B (primary):  Must isolate native SRD apply ownership beyond “move latch wait”
              — at minimum replace/globalize sdpApplyMutex with edge-scoped serialization
              — PO-2 must address factory/ADM/shared-track interaction

C (companion): SRD Operation Owner must name edge + generation + apply token
              — must not imply “correct wait on M03 owner” fixes isolation alone
```

**Option A:** confirmed **insufficient** (block inside native while mutex held).  
**Option D:** remains last-resort; not enabled by PO-1.

---

## Prohibited during PO-1 (observed)

```text
No mutex change
No new executor
No PC split
No setRemoteDescription change
No retry / timeout adjustment
No P0.1g rerun
```

Audit was read-only code + field log review only.

---

## Next

1. **PO-2** — Isolation boundary definition using this inventory  
2. **PO-3** — SRD operation ownership contract surface  
3. IA — **NOT OPENED** until PO-2/PO-3 closed  
4. Implementation — **NOT AUTHORIZED**

---

## Changelog

| Date | Version | Change |
|------|---------|--------|
| 2026-08-21 | v0.1 | PO-1 audit complete · verdict BLOCKED · B viable with expanded scope |
