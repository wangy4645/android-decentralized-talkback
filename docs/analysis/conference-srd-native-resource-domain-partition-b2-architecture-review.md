# B2-2 — Native Resource Domain Partition Architecture Review

**ID:** conference-srd-native-resource-domain-partition-b2-architecture-review  
**Date:** 2026-08-21  
**Version:** v0.1  
**Type:** Architecture Review (read-only · no implementation)  
**Status:** **OPEN** · **Implementation NOT AUTHORIZED** · **P0.1g NOT OPENED**

**Prerequisite:** [PO-2b](./conference-srd-native-worker-characterization-po-2b.md) · [B2-1 review](./conference-srd-native-resource-domain-isolation-b2-design-review.md) · [IA-001 Commit 1–3 PASS](./conference-srd-native-domain-isolation-implementation-authorization-001.md)

---

## Review question (single)

> When M03 native execution **never returns**, can M04 enter a **truly independent** native execution domain — and what is the **minimum partition boundary** to make that true?

**Not this review's job:** Commit 4 wiring · P0.1g rerun · WebRTC hang root-cause fix · factory-per-edge as assumed default.

---

## 1. Frozen facts from PO-2b

| Fact | Source |
|------|--------|
| M03 stall inside `setRemoteDescription` on edge thread | session `5ce616de` · `SRD_NATIVE_CALL_ENTER` no `EXIT` |
| M04 blocked **before** `sdpApplyMutex` / `SRD_ENTER` | stuck at `negotiationSnapshot()` → `signalingState()` JNI |
| Java threads differ (M03 tid ≠ M04 tid) | not app-thread serialization |
| Shared `PeerConnectionFactory` + process `sdpApplyMutex` | PO-1 inventory |
| Implicit factory-native worker domain | architecture + field coupling |

**Frozen conclusion:** Java object isolation (per-edge PC / executor) **≠** native execution isolation.

---

## 2. B2-1 residual capability (repositioned)

B2-1 is **not** a failed direction. It is the **ownership / safety layer**:

| Capability | B2-1 (Commit 1–3) |
|------------|-------------------|
| Lease before SRD-path JNI | **Implemented** |
| Eliminate invisible cross-edge JNI wait | **Implemented** — `LEASE_BUSY` + `EDGE_LOCAL_FAILURE` |
| Observable domain lifecycle | **Implemented** — PO-3-C facts + `SRD_WATCHDOG_GAP` state |
| Prevent unauthorized native overlap via Java lease | **Implemented** — single ACTIVE slot; hang holds slot |
| M04 independent native SRD while M03 stuck | **Not proven / unlikely on shared factory domain** |
| P0.1g Acceptance 1 PASS | **Cannot** — requires independent M04 realization progress |

**Before (RCA-B field):**

```text
M04 → invisible JNI wait → SRD_TIMEOUT lastEvent=NONE
```

**After (B2-1):**

```text
M04 → LEASE_BUSY → EDGE_LOCAL_FAILURE (observable)
```

**Commit 4 HOLD:** `QUARANTINED` must not imply slot reuse without `SRD_NATIVE_CALL_EXIT` — watchdog logical release would create **pseudo-isolation** (M03 native alive + M04 enters shared domain).

---

## 3. B2-2 candidate partition models

Evaluate **minimum** native isolation — not “N factories by default.”

| Model | Description | Isolation target |
|-------|-------------|------------------|
| **A** | Per-edge native signaling execution domain | Serialize/gate **within** edge; parallel domains across edges |
| **B** | Per-conference native domain | One native lane per session — **insufficient** for M03→M04 on same anchor |
| **C** | Per-`PeerConnectionFactory` domain | Partition at factory ref — may still share ADM / process init |
| **D** | Per-edge `PeerConnectionFactory` + native worker | Hard partition — ADM / audio path risk (PO-1) |

**Preliminary ranking for P0.1g shape:**

```text
A (edge native execution domain)  >  C  >  D (last resort / spike)
B                                 — rejected for 4p same-anchor case
```

Model **A** does not require duplicating entire Factory if libwebrtc allows **independent signaling execution contexts** per PC under one Factory — **must be proven**, not assumed.

Model **D** is highest cost / highest regression risk (`JavaAudioDeviceModule` singleton comment in `WebRtcSharedFactory.kt`).

---

## 4. Shared-resource inventory (partition constraints)

From PO-1 + code + B2-1 implementation:

| Resource | Scope today | Partition impact |
|----------|-------------|------------------|
| `sdpApplyMutex` | process | Can nest under domain slot (B2-1); not cross-edge isolation alone |
| `PeerConnectionFactory` | singleton | **Primary B2-2 candidate boundary** |
| Native signaling worker / queue | factory-owned (implicit) | **Must partition or prove parallel** for M04 while M03 stuck |
| `JavaAudioDeviceModule` | singleton | Multiple ADMs break capture on Android (code) |
| `SharedLocalAudio` track | all PCs | Attach path shared — likely **out of SRD critical path** (PO-2b) but affects full factory split |
| Edge executor / gate | per-edge | Already isolated — not sufficient |
| `ConferenceNativeExecutionDomain` lease | process (B2-1) | Safety layer — must align with B2-2 domain id (per-edge vs per-factory) |

**Rule:** partition design must list **which shared resources move with the edge native domain** vs remain process-global.

---

## 5. Minimum isolation boundary (proposal — for review)

**Working definition (minimum for P0.1g):**

```text
NativeSrdExecutionDomain(edgeKey) =
{
  resources required for:
    negotiationSnapshot / signalingState JNI on that edge's PC
    setRemoteDescription(answer) on that edge's PC
    SRD callback completion for that operation
  such that:
    DomainFailure(M03) ⟹ DomainExecution(M04) can proceed without waiting on M03's in-flight native work
}
```

**Not automatically in scope:**

```text
ICE gathering · createOffer/SLD on other edges · ADM capture · SharedLocalAudio attach
```

**Open question (must close before implementation authorization):**

> Is `NativeSrdExecutionDomain(edge)` realizable **inside one Factory**, or does it require **factory/worker partition (C/D)**?

---

## 6. Lifecycle / ownership implications

### B2-1 (retained)

```text
ConferenceNativeExecutionDomain (process safety coordinator)
  · lease / BUSY / QUARANTINED semantics
  · no slot reuse without native exit proof
```

### B2-2 (additive)

```text
NativeSrdExecutionDomain (per edge or per partition)
  · owns native worker interaction for that edge's SRD path
  · failure on M03 does not hold M04's domain
```

**Ownership stack (target):**

```text
EDGE_TASK (IA-002)
    → B2-1 Java lease (safety · no invisible wait)
        → B2-2 native execution domain (independence)
            → sdpApplyMutex (inner · same-PC safety)
                → PeerConnection JNI
```

**QUARANTINED invariant (frozen from Commit 4 HOLD):**

```text
NATIVE_DOMAIN_QUARANTINED ≠ NATIVE_DOMAIN_AVAILABLE
No grant to M04 on shared domain while M03 native call has not exited
```

B2-2 may grant M04 because M04 uses **`NativeSrdExecutionDomain(M04)`** disjoint from M03's — not because Java quarantine cleared a shared slot.

---

## 7. Resource and performance cost (qualitative)

| Approach | Cost | Risk |
|----------|------|------|
| B2-1 only | Low | P0.1g FAIL on M04 progress when M03 stuck |
| A — edge native domain inside one Factory | Medium — design + proof | May be impossible if worker is globally single-threaded |
| C — multiple Factory refs, shared ADM | Medium–high | Factory lifecycle · teardown · memory |
| D — per-edge Factory + ADM | High | Audio regression · Huawei teardown (existing teardown delay) · memory |

**Do not** select D without A/C negative proof.

---

## 8. Failure containment

### B2-1 (achieved / achievable in field)

```text
M03: LEASE_GRANTED → DOMAIN_ENTER → NATIVE_CALL_ENTER (stuck)
M04: LEASE_BUSY → EDGE_LOCAL_FAILURE
```

### B2-2 (P0.1g target)

```text
M03: NATIVE_CALL_ENTER → no EXIT → EDGE_FAILED / QUARANTINED (M03 domain)

M04: independent domain
     → SRD_ENTER → NATIVE_CALL_ENTER → NATIVE_CALL_EXIT → SRD_EXIT SUCCESS
```

**Forbidden after any fix:**

```text
M04: SRD_TIMEOUT + lastEvent=NONE
M03 native alive + M04 native overlap on same unpartitioned domain
```

---

## 9. Proof obligations (before B2-2 implementation authorization)

| ID | Obligation | Method |
|----|------------|--------|
| **B2-2-A** | Prove or disprove: one Factory allows parallel edge native SRD domains | **CLOSED — FAIL** — [audit](./conference-srd-b2-2a-native-parallel-domain-audit.md) |
| **B2-2-B** | Define minimum `NativeSrdExecutionDomain` resource set | Architecture sign-off on §5 |
| **B2-2-C** | Map B2-1 lease `domainId` to B2-2 partition key (edgeKey vs factoryId) | Design doc + code seam |
| **B2-2-D** | ADM / SharedLocalAudio impact assessment if Factory splits | PO-1 extension · spike only if C/D selected |
| **B2-2-E** | Field scenario: M03 stuck + M04 independent SRD progress | IA after B2-2 impl — **P0.1g gate** |

**IA field for Commit 1–3 (ALLOWABLE now):**

```text
Prove LEASE_BUSY path vs RCA-B invisible wait
Does NOT close P0.1g Acceptance 1
```

---

## 10. Recommendation (architecture — v0.2)

```text
Retain B2-1 (Commit 1–3) as mandatory ownership / safety layer.

B2-2-A: FAIL — one Factory → shared signaling worker → Model B.

Do NOT pursue per-edge parallel native SRD under single Factory.

Next: Model C/D design spike (B2-2-D ADM audit) — NOT AUTHORIZED here.

Commit 4 HOLD unchanged (QUARANTINED ≠ AVAILABLE on shared domain).
```

**B2-2-A closed:** [audit](./conference-srd-b2-2a-native-parallel-domain-audit.md) — escalate Model C/D.

---

## Prohibited

```text
Commit 4 implementation
P0.1g rerun as PASS claim
Factory-per-edge without ADM audit
QUARANTINED → slot reusable on shared domain
Implementation authorization (B2-2)
```

---

## Posture

```text
RCA-B                         CLOSED
PO-1 / PO-2 / PO-2b           COMPLETE
PO-3                          ACCEPTED

B2-1                          PARTIAL / SAFE GATE — Commit 1–3 PASS
Commit 4                      HOLD

B2-2                          ARCHITECTURE REVIEW OPEN
B2-2-A                        COMPLETE — **FAIL** — [audit](./conference-srd-b2-2a-native-parallel-domain-audit.md)
IA field (Commit 1–3)         ALLOWABLE
P0.1g                         HOLD
Implementation (B2-2)         NOT AUTHORIZED
```

---

## Next

1. ~~Close **B2-2-A**~~ — [FAIL](./conference-srd-b2-2a-native-parallel-domain-audit.md)
2. ~~Model C/D spike~~ — [spike](./conference-srd-model-cd-partition-design-spike.md) — C rejected; D-α leading
3. **D-α lab spike** — NOT AUTHORIZED
4. Commit 4 — **HOLD**
5. IA field Commit 1–3 — **ALLOWABLE** (P0.1g **HOLD**)

---

## Changelog

| Date | Version | Change |
|------|---------|--------|
| 2026-08-21 | v0.1 | B2-2 review opened; B2-1 repositioned; Commit 4 HOLD; proof obligations |
| 2026-08-21 | v0.2 | B2-2-A FAIL linked; Model C/D next |
