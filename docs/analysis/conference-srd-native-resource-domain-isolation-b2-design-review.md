# B2 — Native Resource Domain Isolation Design Decision Review

**ID:** conference-srd-native-resource-domain-isolation-b2-design-review  
**Date:** 2026-08-21  
**Version:** v0.1  
**Type:** Architecture Design Decision Review  
**Status:** **COMPLETE** · **Implementation Authorization OPENED** · **IA NOT OPENED**

**Architecture:** B2 — Native Resource Domain Isolation  
**PO-3 contract:** [conference-srd-native-domain-ownership-po-003.md](./conference-srd-native-domain-ownership-po-003.md) — **ACCEPTED**  
**Prerequisite:** [PO-2b](./conference-srd-native-worker-characterization-po-2b.md) · [Decision v0.1.5](./conference-srd-native-completion-isolation-decision.md)

---

## Review question (single)

> Which **B2 mechanism direction** satisfies PO-3 ownership contract and PO-3-A..C proof targets — without selecting implementation classes or opening IA?

---

## Scope boundary (frozen)

This review **selects mechanism direction only**.

Does **not** authorize:

```text
PR / code change
class or manager naming as normative design
mutex deletion
factory count change without separate proof
P0.1g rerun
IA opening
Implementation Authorization (separate gate)
```

---

## PO-3 acceptance

PO-3.1–3.4 and proof obligation framing **ACCEPTED** as fix-design contract.

| Section | Status |
|---------|--------|
| PO-3.1 Domain Owner | ACCEPTED |
| PO-3.2 Edge Obligation | ACCEPTED |
| PO-3.3 Failure Containment | ACCEPTED |
| PO-3.4 Prohibition | ACCEPTED |
| PO-3-D IA boundary | ACCEPTED |

PO-3 fact vocabulary (minimum) **frozen for downstream design:**

```text
NATIVE_DOMAIN_LEASE_REQUEST
NATIVE_DOMAIN_LEASE_GRANTED | DENIED | BUSY
NATIVE_DOMAIN_EXECUTION_ENTER | EXIT
NATIVE_DOMAIN_RELEASE
EDGE_LOCAL_FAILURE
```

---

## Current coupling (baseline — frozen)

```text
Edge executor (per-edge thread)
        |
        v
applyRemoteAnswer preamble
        |
        +-- negotiationSnapshot() / signalingState()  ← JNI on shared factory domain (M04 stall here)
        |
        v
awaitSetDescription → sdpApplyMutex → setRemoteDescription  ← M03 stall here
```

**Two layers, one hidden domain:**

```text
ConferenceNativeExecutionDomain (implicit today — unowned)
├── factory-native signaling serialization
└── sdpApplyMutex (explicit · partial)
```

B2 must make the domain **explicit, leased, and observable**.

---

## B2 mechanism option space

| ID | Mechanism | Idea | PO-3 fit |
|----|-----------|------|----------|
| **B2-1** | **Domain gate + serialized realization queue** | Edge requests lease; **no JNI before grant**; one active native realization slot; non-holders → `DOMAIN_BUSY` (non-blocking) | Strong |
| **B2-2** | Per-edge factory partition | Separate `PeerConnectionFactory` per edge / edge-group | Uncertain — ADM singleton constraint (PO-1) |
| **B2-3** | Async native marshaling only | Post all SRD-path JNI to domain worker; edge never blocks on shared native | Strong (often combined with B2-1) |
| **B2-4** | Per-edge `sdpApplyMutex` only | Mutex sharding without domain gate | **REJECTED** — PO-2b refuted |
| **B2-5** | Narrow mutex scope (Option A) | Shrink lock around native entry | **REJECTED** — hang inside native; M04 pre-mutex |
| **B2-6** | Hard native cancel (Option D) | Force-abandon stuck native call | Companion / last resort only — not primary B2 |

---

## Evaluation (PO-3-A..C)

### B2-1 — Domain gate + serialized realization queue

**Mechanism direction:**

```text
EDGE_TASK SRD_APPLY
        |
        v
REQUEST_NATIVE_REALIZATION          (observable · no JNI yet)
        |
        +-- BUSY / DENIED ------------> EDGE_LOCAL_FAILURE (M04 must not wait invisibly)
        |
        v
DOMAIN_LEASE_GRANTED
        |
        v
NATIVE_DOMAIN_EXECUTION               (all SRD-path JNI including snapshot reads + SRD apply)
        |
        v
NATIVE_DOMAIN_RELEASE
```

**PO-3-A:** Single ownership — domain grants sole active native realization authority.  
**PO-3-B:** M03 stuck inside execution slot → domain marks M03 terminal; M04 lease request gets `BUSY` or proceeds when slot free — **not** blocked on peer JNI without state.  
**PO-3-C:** Every wait maps to `LEASE` / `EXECUTION` / `CALLBACK` — eliminates `SRD_TIMEOUT` + `lastEvent=NONE`.

**Addresses PO-2b:** `negotiationSnapshot()` moves **behind** lease — M04 never enters invisible factory wait while M03 holds slot.

**Risks (defer to Implementation Authorization):**

```text
Same-PC concurrent SLD/SRD safety under serialized slot
Lease scope vs pcGeneration / conferenceGeneration
M03 hang while holding slot — slot recovery policy (D companion, not primary)
IA-002: MEDIA_CRITICAL START preserved; domain gate is inner boundary
```

**Verdict:** **PRIMARY B2 mechanism direction.**

---

### B2-2 — Per-edge factory partition

**Idea:** Partition native worker by creating separate factory domains per edge.

**Pros:** Theoretical hard isolation at libwebrtc layer.  
**Cons:**

```text
PO-1: JavaAudioDeviceModule singleton — multiple ADMs break capture on Android (code comment)
SharedLocalAudio track coupling across PCs
Memory / lifecycle / teardown complexity
Does not alone fix PO-3 observability
```

**Verdict:** **NOT PRIMARY** — requires separate ADM/factory proof track; high regression risk. May remain fallback research only if B2-1 fails post-IA field validation.

---

### B2-3 — Async native marshaling

**Idea:** Edge executor submits work; domain thread performs JNI; completion via callback.

**Verdict:** **ACCEPTED as companion shape** to B2-1 — architectural requirement that edge executor **must not** synchronously block on shared native domain. B2-1 + non-blocking lease implies async or fast-fail boundary.

---

### B2-4 / B2-5 — Per-edge mutex / narrow mutex

**Verdict:** **REJECTED** as B2 primary — PO-2b proved M04 blocked before mutex.

---

### B2-6 — Hard native cancel (Option D)

**Verdict:** **DEFERRED companion** — bounds M03 slot hold time after B2-1; not isolation primary; WebRTC partial-state risk unchanged from design-options review.

---

## Selected B2 architecture (frozen)

```text
Primary:   B2-1  Domain gate + serialized native realization queue
Shape:     B2-3  Non-blocking edge boundary (no synchronous shared-native wait)
Companion: PO-3  Ownership / lease contract (accepted)
Deferred:  B2-6  Hard cancel — only if slot recovery needed post-IA audit
Rejected:  B2-4, B2-5, B2-2 (primary)
```

**Combined model:**

```text
Conference Edge (executor admits EDGE_TASK — IA-002 unchanged)
        |
        v
Native Domain Gate (ConferenceNativeExecutionDomain)
  · lease before ANY SRD-path JNI (snapshot + apply)
  · one active realization slot (scope TBD at Implementation Authorization)
  · BUSY/DENIED observable — no invisible wait
        |
        v
Serialized native execution boundary
  · includes today’s sdpApplyMutex semantics as inner detail, not cross-edge pollution surface
        |
        v
PeerConnectionFactory native worker (still shared — gated, not blindly partitioned)
```

**Not selected:** factory-per-edge as first shipped B2.

---

## PO-3 proof obligation disposition (design level)

| Obligation | B2-1 direction | Closed at |
|------------|----------------|-----------|
| **PO-3-A** Resource ownership | Domain owns active native realization slot | Implementation audit |
| **PO-3-B** Isolation guarantee | Lease fast-fail + independent M04 progress | P0.1g field (post-fix) |
| **PO-3-C** Observability | Frozen fact vocabulary + no `lastEvent=NONE` timeout | IA observability spec |
| **PO-3-D** IA boundary | This review complete → Implementation Authorization next | This document |

PO-3-A..C are **not closed** until Implementation Authorization + IA + field — but **mechanism direction is sufficient to open Implementation Authorization gate** (not IA itself).

---

## Constraints carried forward (unchanged)

```text
C1  IA-002 / EDGE_TASK / Topology — preserve
C2  No timeout stretch as primary fix
C3  No sdpApplyMutex delete without same-PC safety proof
C4  Pass = isolation (M03 may fail; M04 must not be polluted)
```

**Additional B2 constraint:**

```text
C5  No SRD-path JNI (including negotiationSnapshot / signalingState) on edge executor without domain lease
C6  Forbidden: SRD_TIMEOUT with unknown wait location (PO-3.3)
```

---

## P0.1g field acceptance (unchanged — resume gate)

Re-run only after Implementation Authorization + IA + shipped observability.

| Edge | Post-fix expected |
|------|-------------------|
| M03 | May `SRD_TIMEOUT` / `MEDIA_EDGE_FAILED` — contained |
| M04 | `NATIVE_DOMAIN_LEASE_*` → `SRD_ENTER` → `NATIVE_CALL_*` → success **or** `DOMAIN_BUSY` / own-edge fail — **not** silent stall |
| M02 | Reference pass |

**FAIL:** M04 `SRD_EXECUTOR_ENTER` → invisible wait → `SRD_TIMEOUT` `lastEvent=NONE`.

---

## Explicitly deferred to Implementation Authorization

```text
Lease epoch scope (session vs conferenceGeneration vs pcGeneration)
Slot recovery when M03 native non-return while holding lease
Whether sdpApplyMutex remains nested inside domain slot or merges into domain lock
SLD / createOffer / ICE paths — in or out of domain slot (SRD_APPLY path mandatory in)
Retry policy after DOMAIN_BUSY
B2-6 hard cancel criteria
B2-2 factory partition spike (if ever)
```

---

## Prohibited (this review)

```text
Implementation / PR
IA opening
P0.1g rerun
Recovery / Topology reopen
Treating B2-1 acceptance as P0.1g PASS
Naming concrete Java classes as required design
```

---

## Posture

```text
RCA-B                         CLOSED
PO-1                          COMPLETE — BLOCKED
PO-2                          COMPLETE — containment FAIL
PO-2b                         COMPLETE — B insufficient

Architecture direction:
B2 Native Resource Domain Isolation

PO-3                          ACCEPTED — [po-003](./conference-srd-native-domain-ownership-po-003.md)
B2 design decision review     COMPLETE — B2-1 + B2-3 shape selected

Implementation Authorization  AUTHORIZED — [IA-001](./conference-srd-native-domain-isolation-implementation-authorization-001.md)
IA                              NOT OPENED
P0.1g                           HOLD
```

---

## Next

1. ~~Implementation Authorization~~ — [IA-001](./conference-srd-native-domain-isolation-implementation-authorization-001.md)
2. Implement AUTH-1..6
3. IA — **NOT OPENED**

---

## Changelog

| Date | Version | Change |
|------|---------|--------|
| 2026-08-21 | v0.1 | B2 design review complete; PO-3 accepted; B2-1 primary; B2-4/5 rejected; B2-2 not primary |
