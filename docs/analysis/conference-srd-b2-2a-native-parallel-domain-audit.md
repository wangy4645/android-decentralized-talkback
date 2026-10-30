# B2-2-A — Native Parallel SRD Domain Audit

**ID:** conference-srd-b2-2a-native-parallel-domain-audit  
**Date:** 2026-08-21  
**Version:** v0.1  
**Type:** Read-Only Proof / Disproof  
**Status:** **COMPLETE — FAIL (Model B)** · **Implementation NOT AUTHORIZED** · **P0.1g NOT OPENED**

**Parent:** [B2-2 architecture review](./conference-srd-native-resource-domain-partition-b2-architecture-review.md)  
**Field:** session `5ce616de` · [ADJUDICATION.txt](../../logs/rca-b-confirm-srd-native-20260821-133115/ADJUDICATION.txt)  
**WebRTC dependency:** `io.getstream:stream-webrtc-android:1.3.10` (libwebrtc)

---

## Audit question (single)

> Under one shared `PeerConnectionFactory`, can Conference edges M03 and M04 hold **independent** native SRD execution domains such that M03 native non-return does not block M04 `SetRemoteDescription`?

**Not:** Java executor parallelism · B2-1 lease semantics · Commit 4 · P0.1g rerun · ADM/audio redesign.

---

## Models under test

### Model A — per-edge parallel native SRD (PASS target)

```text
Shared PeerConnectionFactory
        |
   +----+----+
   |         |
 M03 domain  M04 domain  (independent native execution)
   |         |
 hang OK     SRD continues
```

### Model B — shared factory native worker serializes SRD (FAIL target)

```text
Shared PeerConnectionFactory
        |
 shared signaling / native worker
        |
   serialized SRD domain
    /              \
 M03              M04
```

---

## 1. Factory → native worker (architecture)

libwebrtc `PeerConnectionFactory` (upstream `connection_context.cc`, `peer_connection_factory.cc`):

```text
PeerConnectionFactory
  ├── signaling_thread   (one per factory)
  ├── worker_thread      (one per factory)
  └── network_thread     (one per factory)

CreatePeerConnection / PC operations
  → marshaled to signaling_thread (PeerConnectionProxy)
```

**Finding:** All PCs from one Factory share **one signaling thread** and **one ConnectionContext** thread domain. This is factory-owned native infrastructure — not per-PC.

**Android Java path:** JNI calls (`signalingState`, `setRemoteDescription`) enter native code that coordinates with the factory signaling domain. Blocking or long-running work on that domain affects **other PCs** from the same Factory regardless of Java caller thread.

**Verdict (architecture):** Model B structure is the default libwebrtc design. Model A requires **proved** partition — not assumed from multiple `PeerConnection` Java objects.

---

## 2. Field disproof — parallel native SRD (authoritative)

Session `5ce616de` (RCA-B Confirmation · PO-2b):

| Edge | Java thread | Native boundary reached | Blocked at |
|------|-------------|-------------------------|------------|
| M03 | `tb-edge-…\|M03` (tid 4792) | `SRD_NATIVE_CALL_ENTER` | `setRemoteDescription` — no `EXIT` |
| M04 | `tb-edge-…\|M04` (tid 4793) | **No** `SRD_ENTER` | `negotiationSnapshot()` → `signalingState()` JNI **before** `sdpApplyMutex` |

```text
M03: SRD_NATIVE_CALL_ENTER (stuck)
M04: SRD_EXECUTOR_ENTER → (no ROLE applyRemoteAnswer / no SRD_ENTER)
```

**Critical:** M04 uses a **different Java executor thread** yet cannot pass the first PC JNI read while M03 is inside native SRD.

This is **not** Java-level coupling. It is **shared native factory domain** coupling — exactly PO-2b conclusion.

**Field answer to audit question:**

```text
M03 native SRD stuck
        ↓
M04 cannot enter native SetRemoteDescription path
        ↓
M04 blocked at signalingState JNI (pre-mutex)
```

**FAIL Model A** on field evidence alone.

---

## 3. ADM / SharedLocalAudio (SRD critical path)

PO-1 / PO-2 / PO-2b — unchanged:

```text
JavaAudioDeviceModule (singleton)     — not on synchronous conference applyRemoteAnswer path
SharedLocalAudio track                — not on SRD ANSWER preamble critical path
```

**B2-2-A scope:** ADM/audio **not expanded**. Relevant only if Model C/D (factory split) is authorized later (B2-2-D).

---

## 4. Native callback / queue ownership

| Resource | M03 vs M04 |
|----------|------------|
| Java `PeerConnection` instance | **Distinct** per edge |
| `PeerConnectionFactory` | **Shared** |
| Native signaling thread / queue | **Shared** (factory) |
| `sdpApplyMutex` (app) | **Shared** process — inner; not root cross-edge block in field |
| JNI bridge / PC proxy | Per-PC object, **shared factory worker** |
| SdpObserver callback thread | Factory signaling path — **shared domain** |

**Finding:** Independent Java ownership does **not** imply independent native SRD execution domain under one Factory.

---

## 5. B2-1 interaction (post Commit 1–3)

B2-1 Java lease improves safety **without** creating native parallelism:

```text
M03 hang → lease ACTIVE (block never completes) → M04 LEASE_BUSY
```

This prevents **unauthorized overlap** and **invisible wait** — but M04 still **cannot** reach independent native SRD while M03 holds the shared native domain.

**B2-1 does not contradict FAIL;** it operationalizes containment on a serial native domain.

---

## Verdict (frozen)

```text
B2-2-A: FAIL — Model B

Same PeerConnectionFactory → shared native signaling / worker domain
→ native SRD execution is not per-edge parallel
```

| Outcome | Condition | This audit |
|---------|-----------|------------|
| **PASS** | M03 stuck → M04 native SRD executes independently | **No** |
| **BLOCKED** | Factory native resource parallel semantics unknown | **No** — field + architecture sufficient |
| **FAIL** | Same Factory shares native SRD domain | **Yes** |

---

## Implications

### Do not pursue

```text
Per-edge sdpApplyMutex as P0.1g fix
Commit 4 slot reuse on shared domain (pseudo-isolation)
Claim B2-1 alone satisfies P0.1g Acceptance 1
```

### B2-2 minimum boundary (revised)

```text
NativeSrdExecutionDomain(edge) must map to a partition
that does NOT share one factory signaling worker with other edges

Candidate paths (in order):
  1. Prove + implement per-edge native execution partition without N× ADM (Model A spike — FAILED here)
  2. Model C — multiple Factory / ConnectionContext with shared ADM audit
  3. Model D — per-edge Factory + ADM (last resort · B2-2-D)
```

### B2-1 role (retained)

```text
Mandatory safety / ownership layer on whatever native partition ships later
```

---

## Proof obligation closure

| ID | Status |
|----|--------|
| **B2-2-A** | **CLOSED — FAIL** |
| B2-2-B minimum boundary | **Revised** — requires factory/worker partition |
| B2-2-C lease domainId mapping | **WAIT** — partition key TBD at Model C/D design |
| B2-2-D ADM impact | **OPEN** — required before Model C/D authorization |
| B2-2-E field P0.1g | **HOLD** |

---

## Prohibited

```text
B2-2 implementation authorization (this audit does not authorize)
Commit 4 wiring
P0.1g rerun as PASS claim
Using “different Java executor thread” as parallel native proof
```

---

## Posture

```text
RCA-B                         CLOSED
B2-1                          PARTIAL / SAFE GATE — Commit 1–3 PASS
Commit 4                      HOLD
B2-2-A                        COMPLETE — FAIL (Model B)
B2-2 partition design         NEXT — Model C/D path
IA field (Commit 1–3)         ALLOWABLE (BUSY vs invisible wait only)
P0.1g                         HOLD
Implementation (B2-2)         NOT AUTHORIZED
```

---

## Next

1. **B2-2 Model C/D design spike authorization** — scoped to B2-2-D (ADM) + minimum factory partition boundary
2. IA field for Commit 1–3 — optional; does not close P0.1g
3. Commit 4 — **HOLD**

---

## Changelog

| Date | Version | Change |
|------|---------|--------|
| 2026-08-21 | v0.1 | B2-2-A complete — FAIL Model B; field + libwebrtc signaling thread |
