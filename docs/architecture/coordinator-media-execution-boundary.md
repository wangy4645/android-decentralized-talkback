# Coordinator ↔ Media Execution Boundary

**ID:** coordinator-media-execution-boundary  
**Date:** 2026-08-24  
**Version:** v0.1.1  
**Type:** Architecture Contract · Execution Ownership  
**Status:** **FROZEN** · normative for A0 implementation · does not authorize Phase B/C or native partition

**Upstream (orthogonal — MUST NOT be modified by this document):**

| Contract | Role |
|----------|------|
| [ADR-0056](../adr/0056-conference-topology-first.md) | Topology · membership · `ActualMediaEdgeSet` |
| [Failure Domain Contract](../analysis/conference-failure-domain-contract.md) | L1–L4 fault taxonomy · attribution · propagation |
| [Decision 1 — Option Z](../analysis/conference-native-failure-containment-decision.md) | Native isolation posture · CFC-1 identity |
| [Phase A Authorization](../analysis/conference-native-failure-containment-phase-a-authorization.md) | CFC-1 PARTIAL (E+D) projection gate |

```text
Failure Domain Contract     → how runtime faults are attributed and projected
Execution Boundary (this)   → who may touch runtime and how
ADR-0056                    → topology truth and membership
```

**Core separation:**

```text
Failure Domain        ≠        Execution Ownership
```

---

## 1. Scope

### IN

This contract defines the **Control Plane ↔ Media Execution Plane** boundary:

```text
Control Plane
    Coordinator · ChannelGovernance · intent · state · fact consumption
          │
       request (async)
          ↓
Media Execution Plane
    lifecycle transactions · edge executor · native / audio / Binder runtime
          │
        fact (async)
          ↓
Control Plane
```

**Phase A0 (execution liveness)** is authorized and normatively governed by §3–§4 of this document.

### OUT

```text
Failure domain taxonomy (L1–L4) redefinition
Topology / membership / anchor / meshGeneration changes
Native partition (D-β / D-γ) · Factory redesign
Full Media Lifecycle Owner implementation (future waves)
Negotiation surface redesign (offer / answer / ICE) — inventory only until Owner wave
CFC-1 participant projection rules (A1 — separate chain)
```

### Non-goals

```text
Prove native per-edge isolation (P0.1g)
Guarantee every JNI call runs on a dedicated thread (necessary but not sufficient)
Replace ADR-0056 or Failure Domain Contract semantics
```

---

## 2. Ownership Model

### Control Plane (Coordinator)

**MAY:**

```text
Emit lifecycle requests (provision · release · reuse · teardown)
Record lifecycle facts
Advance governance / transitions / intent / ownership
Schedule async work; consume completion facts on a later coordinator turn
```

**MUST NOT:**

```text
Synchronously wait for Media Runtime completion
Synchronously invoke Media Runtime lifecycle inside a completion callback on the same coordinator turn (see §4)
Own PROVISION / RELEASE / REUSE / TEARDOWN execution (see Media Execution Plane)
```

### Media Execution Plane

**Owns execution of:**

```text
PROVISION transaction
RELEASE transaction
REUSE transaction
TEARDOWN transaction
```

**MAY (interim — until Owner wave):**

```text
Execute via existing edge executor / peer media executors
Publish facts back to Control Plane asynchronously
```

**Future (not required for A0):**

A dedicated **Media Lifecycle Owner** MAY consolidate the four transactions above. It is a **responsibility boundary**, not a mandate for a single monolithic controller class in A0.

### Negotiation (interim)

Until a later Owner wave explicitly absorbs negotiation:

```text
Offer / answer / ICE / SRD / rollback
    → edge executor + async fact
```

Negotiation MUST NOT block Coordinator liveness (§3).

### Fact direction

```text
Request:  Control Plane → Media Execution Plane (async submit)
Fact:     Media Execution Plane → Control Plane (async delivery, distinct coordinator turn)
```

Facts are inputs to state machines; they are **not** synchronous return values from media APIs.

---

## 3. Forbidden Synchronous Crossings

### Normative rule — A0.2

> **Coordinator MUST NOT synchronously wait for, re-enter, or synchronously invoke Media Runtime lifecycle.**

This rule is **higher-level** than a JNI call checklist. Moving a single JNI call to an executor **does not** satisfy A0 if the coordinator still **waits** or **re-enters** runtime on the same turn.

### A0 FAIL examples

```text
executor completion
    → same-tick coordinator callback
    → sync media / audio / native call
    → coordinator blocked

MEDIA_RELEASE_COMPLETE
    → same-tick provisionNew()
    → WebRtcSharedFactory.acquire()
    → AudioManager.setMode / setSpeakerphoneOn on coordinator thread
```

### A0 PASS criterion (liveness)

```text
native hang or media execution blocked
    ↓
Coordinator continues (governance · scheduling · fact intake)
    ↓
explicit lifecycle fact generated
    ↓
control-plane state may progress (may be FAILED / DEGRADED — not silent stall)
```

**Primary test:** Coordinator liveness under blocked media execution — **not** “every JNI is off coordinator.”

### Execution-crossing inventory — A0.3

The following categories **MUST** be tracked in an execution-crossing inventory. **Inventory ≠ full migration in A0.**

| Category | Examples (non-exhaustive) | A0 posture |
|----------|---------------------------|------------|
| Audio framework / Binder | `AudioManager.setMode`, `setSpeakerphoneOn` | MUST NOT sync on coordinator |
| WebRTC factory / ADM | `WebRtcSharedFactory.acquire`, ADM create | MUST NOT sync on coordinator during provision |
| PeerConnection lifecycle | `createOffer`, `release`, `stopCapture` | dispatch + fact; no sync wait on coordinator |
| Inbound audio taps | `setInboundPcmSink` via `ConferenceAudioBus`, `ReceivePathLivenessObserver`, `ProgramAudioBus` | inventory; dispatch-first in hangup path |
| JNI guard telemetry | `MEDIA_JNI_ON_COORDINATOR` | diagnostic; violation of §3 even when logged |

Adding an inventory entry does **not** authorize redesign of Failure Domain or topology.

---

## 4. Lifecycle Handoff Contract

### Normative rule — A0.1

> **`RELEASED` and `PROVISION` are distinct lifecycle transactions. Same-tick re-entry from release completion into provision is FORBIDDEN.**

### Required transaction shape

```text
RELEASE requested
    ↓
(media execution)
    ↓
MEDIA_RELEASED / MEDIA_RELEASE_FAILED     ← fact; coordinator records; END release transaction

(separate coordinator turn / explicit request — happy path only after MEDIA_RELEASED)

MEDIA_PROVISION_REQUESTED
    ↓
(media execution: engine create · audio configure · …)
    ↓
MEDIA_PROVISIONED / MEDIA_PROVISION_FAILED ← fact; coordinator continues
```

### Forbidden shape

```text
MEDIA_RELEASE_COMPLETE
    → runOnCoordinator { onComplete() }
    → provisionNew() / getOrCreate() / createOffer()
    (same coordinator tick)
```

### Handoff facts (A0 minimum vocabulary)

| Fact | Meaning |
|------|---------|
| `MEDIA_RELEASE_REQUESTED` | Control plane submitted release |
| `MEDIA_RELEASED` | **Ownership handoff barrier** — see §4.1 |
| `MEDIA_RELEASE_FAILED` | Release transaction failed — see §4.2 |
| `MEDIA_PROVISION_REQUESTED` | Distinct transaction opened (only after `MEDIA_RELEASED` on happy path) |
| `MEDIA_PROVISIONED` | Engine/runtime ready for negotiation bootstrap |
| `MEDIA_PROVISION_FAILED` | Provision failed; coordinator MUST NOT sync-retry |

Exact log identifiers MAY alias existing `MEDIA_RELEASE_*` telemetry during migration; **semantic separation** is normative.

### §4.1 — `MEDIA_RELEASED` as ownership handoff barrier

> **`MEDIA_RELEASED` = release transaction ended; the old transaction MUST NOT be used further by Coordinator or Media Owner; a distinct provision transaction MAY begin.**

```text
MEDIA_RELEASED
=
Release transaction complete
AND old transaction relinquished by Coordinator / Media Owner
AND independent Provision transaction MAY open

NOT:
all native threads have physically exited

NOT:
permission to overlap old runtime ownership with new provision
```

Native cleanup MAY still drain in the background. Any drain **MUST NOT** create undefined ownership overlap with a new provision transaction. Splitting transactions across ticks **without** this barrier only replaces same-tick re-entry with cross-tick runtime overlap — still **A0 FAIL**.

### §4.2 — `MEDIA_RELEASE_FAILED` recovery gate

> **Coordinator MUST NOT directly sync-retry provision after `MEDIA_RELEASE_FAILED`. MUST enter an explicit recovery / failed state.**

```text
Happy path (only):

RELEASE_REQUESTED → RELEASED → (separate coordinator turn) → PROVISION_REQUESTED

Failure path (required):

RELEASE_REQUESTED → RELEASE_FAILED → explicit recovery / failed decision
                                   ✗ provision anyway
                                   ✗ sync-retry on same or next coordinator turn
```

`MEDIA_RELEASE_FAILED` followed by `MEDIA_PROVISION_REQUESTED` on the normal bootstrap path is **FORBIDDEN**. Recovery MUST be an explicit, attributed decision — not an implicit fallback into the happy-path provision chain.

### Same-turn definition

```text
Same coordinator turn = same runnable dispatched to talkback-coordinator
    without an intervening async boundary that returns control to the coordinator queue
```

`runOnCoordinator { … }` chained directly from a media completion hook on the **same** turn counts as same-tick re-entry.

---

## 5. Migration / Wave Rules

### Phase ordering (frozen)

```text
A0 — Execution Liveness (this document §3–§4)
    ↓
A1 — CFC-1 Participant Projection (orthogonal chain — Failure Domain Contract)
    ↓
Media Lifecycle Owner (four transactions only: PROVISION · RELEASE · REUSE · TEARDOWN)
    ↓
Phase B — Media Domain containment
    ↓
Phase C — Native isolation capability (P0.1g path — not production prerequisite)
```

### A0 vs A1 separation

```text
A0  →  execution / coordinator liveness chain
A1  →  fault projection chain (EDGE_FAILED · DOMAIN_BLOCKED · L-participant)
```

A0 PRs **MUST NOT** change CFC projection semantics. A1 PRs **MUST NOT** weaken §3–§4 invariants.

### Wave rules

| Wave | Allowed | Forbidden |
|------|---------|-----------|
| **A0** | A0.1 same-tick ban · A0.2 sync-runtime ban · A0.3 inventory + dispatch-first on hangup hot path | Full Owner class · negotiation rewrite · Factory partition |
| **Owner-1** | Consolidate PROVISION / RELEASE / REUSE / TEARDOWN request/fact API | Absorb all negotiation in one PR |
| **Owner-2+** | Negotiation under Owner (if separately authorized) | Topology / ADR-0056 changes |

### Existing machinery

Reuse where possible; do not duplicate orchestration:

```text
ChannelGovernance / MEETING_END transitions
MediaSessionManager async release harness
ConferenceMediaJniAffinity / PeerMediaExecutors
```

Migration **replaces synchronous coupling**, not necessarily class names.

### Not authorized by this document

```text
D-β · D-γ
Factory / ADM redesign
Native partition experiments
Full lifecycle-controller rewrite
Failure Domain or ADR-0056 amendments
```

---

## Implementation pointer (A0 first step)

After this contract is frozen, **first authorized code change:** enforce **A0.1** — break `MEDIA_RELEASE_COMPLETE → provisionNew()` same-tick re-entry; emit distinct `MEDIA_PROVISION_REQUESTED` on a subsequent coordinator turn.

---

## Document map

```text
ADR-0056
    topology / membership / ActualMediaEdgeSet

Failure Domain Contract
    L1 EDGE_FAILED · L2 DOMAIN_BLOCKED · L3 · L4

Decision 1 / Option Z / CFC-1
    containment posture · projection acceptance

Coordinator ↔ Media Execution Boundary (this document)
    control vs execution ownership · A0 liveness invariants · A0.1–A0.3

a0.7-bounded-release-terminality-amendment.md
    A0.7 bounded release terminality · terminal latch · late completion · watchdog (PR-2/3)
```
