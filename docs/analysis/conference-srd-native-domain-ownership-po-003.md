# PO-3 — Native SRD Domain Ownership Contract

**ID:** conference-srd-native-domain-ownership-po-003  
**Date:** 2026-08-21  
**Version:** v0.1  
**Type:** Contract Definition Only  
**Status:** **ACCEPTED — CONTRACT FROZEN** · **Implementation NOT AUTHORIZED** · **Behavior change NOT AUTHORIZED**

**Architecture:** B2 — Native Resource Domain Isolation ([decision v0.1.4](./conference-srd-native-completion-isolation-decision.md))  
**Prerequisite:** [PO-2b](./conference-srd-native-worker-characterization-po-2b.md) — B insufficient; shared native domain confirmed  
**RCA:** [RCA-B CLOSED](./conference-srd-post-admission-native-execution-failure-rca.md)

---

## Scope boundary (frozen)

PO-3 defines **ownership contract and provable invariants only**.

PO-3 does **not** define:

```text
implementation mechanism
manager / class design
mutex replacement strategy
factory partitioning approach
timeout / retry policy
WebRTC API selection
```

**Anti-pattern (prohibited in this phase):**

```text
Domain owner named
        ↓
implement a manager/class
        ↓
start code change          ← crosses IA boundary
```

Implementation Authorization is a **separate gate** after B2 design decision review.

---

## Core question (frozen)

> Under **B2 Native Resource Domain Isolation**, who owns native SRD execution authority, and how is it guaranteed that one edge's native failure does not pollute other edges?

---

## PO-3.1 — Domain Owner Contract

### Entity

```text
ConferenceNativeExecutionDomain
```

**Semantic:** responsibility anchor for native SRD realization — **not** necessarily a single Java class.

### Owns (in scope)

```text
SRD apply admission into native realization
negotiation snapshot access boundary
native signaling interaction boundary
domain lease grant / deny / release authority
cross-edge serialization policy for shared native resources
```

### Does NOT own (explicit exclusion)

```text
conference topology
anchor / desired-edge decision
recovery / Ready / membership
media policy / playback policy
IA-002 executor lane admission (orthogonal)
EDGE_TASK submit scheduling
```

### Owner invariant

```text
At most one edge holds active native realization lease per domain partition epoch
(relevant generation scope — finalize at B2 design review)
```

Native SRD execution resource is **domain-owned**, not edge-owned.

---

## PO-3.2 — Edge Obligation Contract

### Lifecycle (frozen)

```text
EDGE_TASK (SRD_APPLY admitted)
        |
        v
REQUEST_NATIVE_REALIZATION
        |
        +---- DOMAIN_LEASE_DENIED ----> EDGE_LOCAL_FAILURE (observable)
        |
        v
DOMAIN_LEASE_GRANTED
        |
        v
NATIVE_EXECUTION
        |
        +---- SUCCESS ----> DOMAIN_RELEASE
        |
        +---- EDGE_LOCAL_FAILURE ----> DOMAIN_RELEASE / TERMINAL_STATE
        |
        v
(no silent continuation after terminal failure)
```

### Frozen obligations

| Rule | Contract |
|------|----------|
| Edge does not own native execution resource | Edge **requests** domain service; domain **grants** lease |
| Infinite invisible wait | **Forbidden** — edge must not block without observable lifecycle state |
| Lease has state | **Required** — at minimum: `NONE` · `REQUESTED` · `GRANTED` · `EXECUTING` · `RELEASED` · `DENIED` · `TERMINAL` |
| Failure observability | **Required** — every terminal path emits explicit fact before `EDGE_TASK_END` |

### Edge non-ownership (frozen)

> Edge does not own native execution resource; edge may only request domain service under PO-3.1 authority.

---

## PO-3.3 — Failure Containment Contract

P0.1g Acceptance 1 depends on this section.

### Current violation (field — session `5ce616de`)

```text
M03 native stuck (setRemoteDescription / native domain)
        |
        v
shared native wait (pre-mutex JNI on M04)
        |
        v
M04 invisible timeout (SRD_EXECUTOR_ENTER, no SRD_ENTER, SRD_TIMEOUT unknown location)
```

### Target invariant

```text
M03 native stuck
        |
        v
M03 edge-local failure (observable · terminal on M03)
        |
        X  (no cross-edge pollution)
        |
        v
M04 independent realization progress
```

Formal:

```text
∀ edge A, edge B (A ≠ B):
  FailureContain(A) ⟹ ¬BlocksRealizationProgress(B)
```

Where `BlocksRealizationProgress(B)` includes:

```text
B blocked before REQUEST_NATIVE_REALIZATION completes
B blocked in invisible native wait (no lifecycle state)
B missing SRD_ENTER due to A's native occupancy
B SRD_TIMEOUT with unknown wait location
```

### Allowed on failing edge (M03)

```text
SRD_TIMEOUT
MEDIA_EDGE_FAILED / CONFERENCE_MEDIA_EDGE_FAILED
EDGE_LOCAL_FAILURE at any PO-3.2 terminal state
SRD_NATIVE_CALL_ENTER without EXIT (local hang — contained)
```

### Forbidden on non-failing edge (M04)

```text
blocked without lifecycle state
SRD_EXECUTOR_ENTER → (no REQUEST / LEASE / ENTER progression)
missing SRD_ENTER caused by peer edge native stall
SRD_TIMEOUT with unknown wait location
invisible native wait behind shared domain
```

### Acceptable M04 outcomes when M03 stuck

```text
DOMAIN_BUSY → retry or edge-local fail (observable)
independent path to SRD_ENTER → NATIVE_CALL_* → SUCCESS
edge-local failure on M04's own native fault only
```

---

## PO-3.4 — Prohibition Contract

### Forbidden pattern (current accident mode)

```text
Edge executor
        |
        v
direct PeerConnection JNI (signalingState / setRemoteDescription / ...)
        |
        v
native black hole (no domain boundary · no lease · no lifecycle state)
```

### Required conceptual shape

```text
Edge executor
        |
        v
Native Domain boundary (ConferenceNativeExecutionDomain)
        |
        v
SRD execution (under lease + observability)
```

### Frozen prohibitions

```text
edge thread holds native global resource without domain lease
edge self-manages native SRD lifecycle (acquire/release) bypassing domain
timeout fires but edge continues assuming unknown native state is reusable
cross-edge implicit wait on factory-native worker without DOMAIN_BUSY fact
direct setRemoteDescription / negotiationSnapshot from executor without domain gate
```

---

## Proof obligations (must close before IA)

These are **contract proof targets** — not implementation tasks in PO-3.

### PO-3-A — Resource ownership

Prove:

```text
native SRD realization resources
        |
        v
single ownership model under ConferenceNativeExecutionDomain
(no edge may hold implicit global native authority)
```

Evidence class: design review + implementation audit (post-IA).

### PO-3-B — Isolation guarantee

Prove:

```text
DomainFailure(edge A) ⟹ DomainAvailability(edge B) for realization request
```

Equivalently:

```text
M03 hang contained ⟹ M04 reaches independent realization progress or observable DOMAIN_BUSY / edge-local deny — not silent stall
```

Maps to P0.1g Acceptance 1.

### PO-3-C — Observability

For any edge in SRD_APPLY, logs/facts must answer:

```text
waiting for:
  DOMAIN_LEASE
or
  NATIVE_EXECUTION
or
  CALLBACK_COMPLETION
or
  DOMAIN_BUSY (denied)
```

**Forbidden terminal shape:**

```text
SRD_TIMEOUT
lastEvent=NONE
unknown location
```

Minimum fact vocabulary (illustrative — finalize at B2 design review):

```text
NATIVE_DOMAIN_LEASE_REQUEST
NATIVE_DOMAIN_LEASE_GRANTED | DENIED | BUSY
NATIVE_DOMAIN_EXECUTION_ENTER | EXIT
NATIVE_DOMAIN_RELEASE
EDGE_LOCAL_FAILURE reason=...
```

### PO-3-D — IA boundary

```text
PO-3 contract frozen (this document)
        ↓
B2 design decision review (mechanism selection — still NOT implementation)
        ↓
Implementation Authorization (separate document / gate)
        ↓
IA opened (if authorized)
        ↓
Field validation (P0.1g resume)
```

PO-3 **complete** does **not** authorize code change.

---

## Non-collision (frozen)

PO-3 must not redefine:

```text
IA-002 MEDIA_CRITICAL admission semantics
EDGE_TASK SUBMIT → START → END executor contract
Topology / Anchor authority
Recovery / Ready / membership tracks
RCA-B root cause (native non-return on M03)
```

PO-3 **adds** domain layer **above** executor admission, **below** conference policy.

---

## Isolation stack (reference)

```text
Conference Edge (per remoteModuleId)
+----------------+
| PC             |
| Observer       |
| Executor       |
| Gate           |
+----------------+
        |
        |  PO-3.2 request / lease
        v
ConferenceNativeExecutionDomain   ← PO-3.1 owner
+------------------------+
| sdpApplyMutex          |
| native signaling path  |
| factory interaction  |
+------------------------+
        |
        v
PeerConnectionFactory native worker (shared today — B2 must partition or gate)
```

---

## Prohibited (PO-3 phase)

```text
implementation / PR
manager class sketch as normative design
mutex delete / per-edge mutex as assumed fix
timeout budget change
Recovery / Topology reopen
P0.1g rerun
IA opening
Option D as substitute for domain contract
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

PO-3                          ACCEPTED — [B2 review](./conference-srd-native-resource-domain-isolation-b2-design-review.md)
PO-3-A..C                     mechanism direction set; close at IA + field
B2 design decision review     COMPLETE — B2-1 primary
Implementation Authorization  AUTHORIZED — [IA-001](./conference-srd-native-domain-isolation-implementation-authorization-001.md)
IA                            NOT OPENED
Implementation                NOT AUTHORIZED
P0.1g                         HOLD
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
| 2026-08-21 | v0.1 | PO-3 opened; PO-3.1–3.4 + PO-3-A..D frozen; no implementation mechanism |
| 2026-08-21 | v0.1.1 | ACCEPTED at B2 design review |
