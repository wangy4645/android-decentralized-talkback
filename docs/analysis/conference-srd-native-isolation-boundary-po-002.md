# PO-002 — Native SRD Isolation Boundary Definition

**ID:** conference-srd-native-isolation-boundary-po-002  
**Date:** 2026-08-21  
**Version:** v0.1  
**Type:** Read-Only Architecture Definition  
**Status:** **COMPLETE** · **Implementation NOT AUTHORIZED** · **No mutex/WebRTC design in this doc**

**Prerequisite:** [PO-001](./conference-srd-native-ownership-inventory-po-001.md) — **COMPLETE — BLOCKED**  
**Parent decision:** [conference-srd-native-completion-isolation-decision.md](./conference-srd-native-completion-isolation-decision.md) (B primary · C **waiting PO-2**)

---

## Question (single)

> If M03 native SRD stalls permanently, which resources **must** still allow M04 to progress?

---

## B goal (redefined)

Not:

```text
remove global mutex
one mutex per edge
```

Is:

```text
define the minimum native SRD realization isolation domain:

Edge A native stall
        |
        X  (must not propagate)
        |
Edge B native realization continues
```

---

## PO-2.1 — Edge-owned boundary (frozen)

These resources are **owned per edge** and **may fail locally** without cross-edge obligation:

| Resource | Owner key | May fail on M03 alone | Must not block M04 |
|----------|-----------|------------------------|---------------------|
| `PeerConnection` | `remoteModuleId` / `pcHash` | YES | YES |
| `PeerConnection.Observer` | per PC instance | YES | YES |
| `SdpObserver` (SRD call) | per `awaitSetDescription` invocation | YES | YES |
| `RealWebRtcAudioEngine` | per `remoteModuleId` | YES | YES |
| `PeerMediaExecutors` primary lane | `edgeKey=session\|peer` | YES (task stuck) | YES (other edge lanes independent) |
| `ConferenceEdgeSdpApplyGate` state | per `edgeKey` | YES (`SRD_TIMEOUT`) | YES |
| SRD attempt observability | per `edgeKey` | YES | YES |
| `ConferenceSignalingLock` | per `(sessionId, peerId)` | YES | YES (separate lock object) |

**Frozen rule:** failure of M03 SRD operation state machine terminates on **M03 edgeKey** only.

**Current code alignment:** PO-1 confirms per-edge PC, observer, executor, gate — **favorable**.

---

## PO-2.2 — Shared native boundary (frozen requirements)

These resources are **shared or possibly shared**. PO-2 defines what **must remain available** to M04 when M03 is stuck:

```text
PeerConnectionFactory (singleton)
JavaAudioDeviceModule (singleton)
SharedLocalAudio track / sender graph
sdpApplyMutex (process-global)
WebRTC native signaling / worker thread(s)
```

### Requirement table

| Shared resource | On SRD ANSWER Java path | Must allow M04 `SRD_ENTER` while M03 stuck? | Must allow M04 `NATIVE_CALL_EXIT`? | Current assessment |
|-----------------|-------------------------|---------------------------------------------|-------------------------------------|-------------------|
| `sdpApplyMutex` | wraps `setRemoteDescription` ANSWER | **YES** | **YES** | **FAIL** — M03 non-return holds lock; M04 cannot acquire (`PO-1` + field) |
| `PeerConnectionFactory` | all PCs created from singleton | **YES** | **YES** | **UNKNOWN native** — Java does not serialize; native worker may |
| `JavaAudioDeviceModule` | not invoked in `applyRemoteAnswer` / `awaitSetDescription` | **YES** | **YES** | **PASS (Java path)** — not on synchronous SRD critical path |
| `SharedLocalAudio` track | attached at PC init, not during SRD apply | **YES** | **YES** | **PASS (Java path)** — `SharedLocalAudio.lock` not held during SRD |
| Native signaling worker | WebRTC internal; delivers `SdpObserver` | **YES** | **YES** | **RISK** — not instrumented; see below |

### Native signaling worker — highest-priority risk

**Java fact:** M03 blocks on `tb-edge-{session|M03}` inside `setRemoteDescription` JNI (`SRD_NATIVE_CALL_ENTER` without `EXIT`).

**Field fact (M04 · session `5ce616de`):**

```text
13:32:09.594  EDGE_TASK_START SRD_APPLY  thread=tb-edge-...|M04
              (no SRD_ENTER / no SRD_MUTEX_* / no NATIVE_CALL_* for conference edge)
13:32:12.594  SRD_WATCHDOG_GAP lastEvent=NONE
```

M04 primary executor **started** SRD task but **never reached** `SRD_ENTER` (logged before `sdpApplyMutex`). Stall is therefore **not only** mutex wait after `SRD_ENTER` — M04 blocked in `applyRemoteAnswer` **preamble** (signaling-state JNI / rollback / pre-await path) **or** native-layer contention **before** mutex instrumentation.

**PO-2.2 conclusion:**

```text
Proven Java coupling:     sdpApplyMutex (post-ENTER path)
Suspected native coupling: factory / signaling worker (pre-ENTER path on M04)
ADM / SharedLocalAudio:    not on synchronous SRD ANSWER critical path (Java)
```

**Escalation rule (frozen):**

PO-2b **COMPLETE** → [po-2b](./conference-srd-native-worker-characterization-po-2b.md)

```text
B alone INSUFFICIENT
B2 — native resource domain isolation (architecture fork)
```

M04 blocked on `signalingState()` JNI before `sdpApplyMutex` while M03 in `setRemoteDescription`.

---

## PO-2.3 — Failure containment boundary (frozen)

### Allowed on failing edge (M03)

```text
SRD_NATIVE_CALL_ENTER without EXIT
SRD_WATCHDOG_GAP missing=NATIVE_CALL_EXIT
SRD_TIMEOUT
CONFERENCE_MEDIA_EDGE_FAILED
EDGE_TASK_END success=false (or late return ignored)
```

### Forbidden cross-edge effects

```text
M03 stall blocks M04 SRD_ENTER
M03 stall blocks M04 SRD_NATIVE_CALL_EXIT
M03 stall corrupts PeerConnectionFactory for other edges
M03 stall prevents M04 EDGE_TASK_START (executor level)
```

### Current system vs boundary

| Containment rule | Current | Evidence |
|------------------|---------|----------|
| M03 may `SRD_TIMEOUT` | PASS | session `5ce616de` |
| M04 must reach independent native progress | **FAIL** | M04 timeout coupled; no `NATIVE_CALL_EXIT` |
| M04 `EDGE_TASK_START` not blocked by M03 executor | PASS | M04 `SRD_APPLY START` while M03 stuck |
| M04 `SRD_ENTER` not blocked | **FAIL** | no `SRD_ENTER` on M04 conference edge |
| Factory not corrupted for M02 | PASS | M02 completed SRD |

**PO-2 verdict:**

```text
PO-2: COMPLETE — boundaries DEFINED
Current implementation: FAILS PO-2.3 containment
```

Fix design must satisfy PO-2.3; PO-2 does not prescribe how.

---

## C (ownership contract) — deferred

**Do not draft C until PO-2 boundaries accepted.**

```text
C answers: who may execute SRD
PO-2 answers: after execution starts, who is affected when native stalls
```

Order frozen:

```text
PO-1 inventory
        ↓
PO-2 isolation boundary  ← this document
        ↓
C ownership contract design
        ↓
Implementation Authorization
```

---

## P0.1g field acceptance (unchanged — reference)

When implementation is authorized and validated:

| Edge | Required |
|------|----------|
| M03 | `NATIVE_CALL_ENTER` without `EXIT` → `SRD_TIMEOUT` **allowed** |
| M04 | `SRD_ENTER` → `NATIVE_CALL_ENTER` → `NATIVE_CALL_EXIT` → `SRD_EXIT` success |
| M02 | reference pass |

---

## Prohibited in PO-2

```text
mutex modification design
WebRTC behavior change
implementation option selection
timeout budget change
P0.1g rerun
IA opening
```

---

## Posture

```text
RCA-B                 CLOSED
Fix Design Review     B+C REVIEWING

PO-1                  COMPLETE — BLOCKED
PO-2                  COMPLETE — containment FAIL
PO-2b                 COMPLETE — B → B2 fork
PO-3 (C contract)     ACCEPTED — [po-003](./conference-srd-native-domain-ownership-po-003.md)
B2 design review      COMPLETE — [b2-review](./conference-srd-native-resource-domain-isolation-b2-design-review.md)
Implementation Auth   AUTHORIZED — [IA-001](./conference-srd-native-domain-isolation-implementation-authorization-001.md)

IA                    NOT OPENED
Implementation        NOT AUTHORIZED
P0.1g                 HOLD
```

---

## Next

1. Accept PO-2 boundaries (architecture review)
2. **PO-3** — SRD operation ownership contract (C) against PO-2.1 / PO-2.3
3. Optional **PO-2b** — read-only native signaling worker characterization (if needed to close B vs B2)
4. Implementation Authorization — **NOT OPENED**

---

## Changelog

| Date | Version | Change |
|------|---------|--------|
| 2026-08-21 | v0.1 | PO-2 boundaries PO-2.1–2.3 defined; containment FAIL; C deferred; B2 escalation rule |
