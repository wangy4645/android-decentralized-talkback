# PO-2b — Native Worker / Shared Domain Characterization

**ID:** conference-srd-native-worker-characterization-po-2b  
**Date:** 2026-08-21  
**Version:** v0.1  
**Type:** Read-Only Characterization  
**Status:** **COMPLETE** · **Implementation NOT AUTHORIZED**

**Prerequisite:** [PO-002](./conference-srd-native-isolation-boundary-po-002.md) — boundaries defined; containment **FAIL**  
**Field:** [rca-b-confirm-srd-native-20260821-133115/ADJUDICATION.txt](../../logs/rca-b-confirm-srd-native-20260821-133115/ADJUDICATION.txt) · session `5ce616de-…`

---

## Goal (single)

> Characterize the **real shared resource boundary** for native SRD realization — not root cause (RCA-B **CLOSED**).

Answer whether fix direction stays **B** (edge-owned SRD domain) or must upgrade to **B2** (native resource domain partition).

---

## Method (read-only)

```text
Field log correlation (session 5ce616de)
Java path trace (applyRemoteAnswer → awaitSetDescription)
WebRTC architecture constraints (factory + signaling thread model)
No code change · no native debugger · no P0.1g rerun
```

---

## 1. Native worker seriality (field + path)

### M03 (primary stall)

```text
13:32:05.853  EDGE_TASK_START        thread=tb-edge-...|M03 (28784)
13:32:05.855  ROLE applyRemoteAnswer thread=28784
13:32:05.858  SRD phase=BEFORE       thread=28784
13:32:05.869  SRD_NATIVE_CALL_ENTER  thread=28784
              (no EXIT / no JNI_RETURN)
13:32:08.855  SRD_WATCHDOG_GAP missing=NATIVE_CALL_EXIT
```

Native stall on **M03 edge executor thread** inside `setRemoteDescription` (mutex held).

### M04 (coupled — before mutex)

```text
13:32:09.594  EDGE_TASK_START        thread=tb-edge-...|M04 (28785)
13:32:09.594  SRD_EXECUTOR_ENTER     thread=28785 tid=4793
              (no ROLE applyRemoteAnswer)
              (no SRD_ENTER / no SRD_MUTEX_*)
13:32:12.594  SRD_WATCHDOG_GAP lastEvent=NONE
```

M04 executor **entered** task body but **never reached** `applyRemoteAnswer` ROLE log.

### Java path pin (where M04 stops)

```text
SRD_EXECUTOR_ENTER
        |
        v
engine.applyRemoteAnswer()
        |
        v
negotiationSnapshot()          ← peerConnection.signalingState() JNI
        |
        v
logNegotiation ROLE ...        ← never logged on M04
```

Evidence: `RealWebRtcAudioEngine.kt:197-204` — first work in `applyRemoteAnswer` is `negotiationSnapshot()` before any ROLE line.

**Conclusion:** M04 is blocked in **PC JNI read** on its **own edge thread**, **before** `sdpApplyMutex` (`SRD_ENTER` / `SRD_MUTEX_WAIT_BEGIN` never emitted).

This is **not** explainable by mutex alone.

---

## 2. Factory sharing model

### What Java shares (confirmed)

```text
WebRtcSharedFactory
 ├── PeerConnectionFactory     (singleton · refCount)
 ├── JavaAudioDeviceModule     (singleton)
 └── sdpApplyMutex             (process-global ReentrantLock)

Per remoteModuleId:
 ├── RealWebRtcAudioEngine
 └── PeerConnection            (distinct pcHash per edge in field)
```

Factory is **not** only a PC container — it owns **one native factory domain** backing all PCs.

### WebRTC model (architecture — not app-instrumented)

Standard libwebrtc Android:

```text
PeerConnectionFactory
 └── native signaling / network thread domain (shared)
       ├── PC(M02) operations
       ├── PC(M03) operations  ← can block domain
       └── PC(M04) operations  ← signalingState() may wait behind M03 SRD
```

**Conclusion:** object sharing at Java layer **understates** coupling. Hidden **factory-native worker domain** is the plausible serializing layer for M04 pre-mutex stall.

---

## 3. Isolation domain model (PO-2b output)

```text
SRD realization shared domain (must partition for P0.1g PASS) =
{
  sdpApplyMutex                    — proven (M03 ENTER without EXIT)
  factory-native worker / queue    — strongly indicated (M04 pre-mutex JNI stall)
}

SRD realization edge-owned (may fail locally without cross-edge block) =
{
  PeerConnection instance
  PeerConnection.Observer
  SdpObserver (per call)
  PeerMediaExecutors lane
  ConferenceEdgeSdpApplyGate
}

Not on synchronous SRD ANSWER critical path (Java) =
{
  SharedLocalAudio track attach
  JavaAudioDeviceModule (during applyRemoteAnswer preamble)
}
```

---

## Fork verdict (architecture — not implementation)

```text
PO-2b: B alone INSUFFICIENT
       Upgrade path: B2 (native resource domain isolation)
```

| If fix only… | M03 stall | M04 pre-mutex JNI | P0.1g |
|--------------|-----------|---------------------|-------|
| Per-edge `sdpApplyMutex` | may still hang locally | **likely still blocked** | **FAIL** |
| B2 native domain partition | may still hang locally | must progress independently | required for PASS |

**False fix pattern (frozen):**

```text
M03 edge mutex isolated
        |
        v
M03 still occupies factory-native worker
        |
        v
M04 still cannot reach SRD_ENTER / ROLE
        |
        v
P0.1g FAIL
```

---

## Answers to PO-2b questions

### Q1 — Same native signaling thread / queue / factory worker?

```text
Java-direct proof:  NO shared app thread (M03 tid=4792, M04 tid=4793)
Architecture + field: YES shared factory-native domain strongly indicated
                      (M04 blocked on signalingState JNI while M03 in setRemoteDescription)
```

### Q2 — Factory singleton = object only or worker domain?

```text
Not object-only for SRD purposes.
Must treat as factory + native worker domain until disproven by future native trace.
```

### Q3 — B vs B2?

```text
B2 required for isolation contract (or B explicitly expanded to include native domain partition)
C (ownership) still WAIT — ownership granularity depends on this domain
```

---

## PO-3 deferral (confirmed)

Do **not** write C until architecture accepts **B2-expanded** isolation domain.

```text
owner=edge  +  resource=factory-native worker  →  isolation still fails
```

---

## Prohibited

```text
mutex / WebRTC code change
implementation authorization
PO-3 / IA
P0.1g rerun
```

---

## Posture

```text
RCA-B              CLOSED
Fix Design         REVIEWING
PO-1               COMPLETE — BLOCKED
PO-2               COMPLETE — containment FAIL
PO-2b              COMPLETE — B2 fork
PO-3               ACCEPTED — [po-003](./conference-srd-native-domain-ownership-po-003.md)
B2 design review   COMPLETE — B2-1 safety layer
B2-2 partition     OPEN — [b2-2-review](./conference-srd-native-resource-domain-partition-b2-architecture-review.md)
Commit 4           HOLD
Implementation Auth Commit 1–3 only
IA                 NOT OPENED
Implementation     NOT AUTHORIZED
P0.1g              HOLD
```

---

## Next (architecture)

1. Accept PO-2b fork: **B → B2** (native resource domain isolation)
2. **PO-3** — ownership + isolation contract on **B2 domain** (not edge-only B)
3. Optional native thread trace (implementation-adjacent observability) only if IA opened later
4. Implementation Authorization — **NOT OPENED**

---

## Changelog

| Date | Version | Change |
|------|---------|--------|
| 2026-08-21 | v0.1 | PO-2b complete; M04 pre-mutex stall; B insufficient → B2 |
