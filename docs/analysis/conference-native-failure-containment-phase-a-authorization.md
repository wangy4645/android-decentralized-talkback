# Phase A Authorization — CFC-1 PARTIAL (E + D)

**ID:** conference-native-failure-containment-phase-a-authorization  
**Date:** 2026-08-21  
**Version:** v0.2  
**Type:** Implementation Boundary / Authorization Record  
**Status:** **ACCEPTED** · Phase A implementation **AUTHORIZED** (CFC-1 PARTIAL E+D only) · [Sign-off](./conference-native-failure-containment-phase-a-authorization-signoff.md) 2026-08-21

**Does not inherit Decision 1 authority.** This document is a **separate gate** between contract freeze and implementation PRs.

**Prerequisite (all frozen):**

| Gate | Status | Artifact |
|------|--------|----------|
| Decision 1 | **Option Z ACCEPTED** | [conference-native-failure-containment-decision.md](./conference-native-failure-containment-decision.md) v0.2 |
| Failure Domain Contract | **FROZEN** | [conference-failure-domain-contract.md](./conference-failure-domain-contract.md) v0.1 |
| B2-1 Commit 1–3 | **RETAIN / PASS** | [IA-001](./conference-srd-native-domain-isolation-implementation-authorization-001.md) |
| Handoff | **COMPLETE** | [handoff](./handoff-conference-failure-domain-convergence-20260821.md) |

```text
Decision 1
    ↓
Failure Domain Contract
    ↓
Phase A Authorization (this document)   ← gate
    ↓
Implementation PRs
```

---

## Authorization statement (single sentence)

> **Authorize implementation of CFC-1 PARTIAL (Scenario E + Scenario D) only** — explicit `EDGE_FAILED`, `DOMAIN_BLOCKED` with attribution, L-participant projection, and cause→impact telemetry.

**No authorization for:** native isolation · L3/L4 · topology · recovery semantics · runtime domain redesign.

Implementation PRs remain **BLOCKED** until [sign-off](./conference-native-failure-containment-phase-a-authorization-signoff.md) is completed.

**Related:** [Implementation checklist](./conference-native-failure-containment-phase-a-implementation-checklist.md) · [Sign-off template](./conference-native-failure-containment-phase-a-authorization-signoff.md)

---

## Goal (narrow)

Eliminate invisible `CONNECTING`; expose L1/L2 failure facts with attribution; satisfy L-participant mandatory projection for domain contention impact.

**Not the goal:**

```text
Fix M03 native hang
Prove native per-edge SRD independence
Close P0.1g Acceptance 1
Achieve CFC-1 FULL PASS
Implement L3 DOMAIN_FAILED or L4 ConferenceHealth adjudicator
```

---

## Scope IN

Phase A implementation **may** touch classification, projection, and observability only (~20–30% Conference runtime: state / projection / telemetry).

### AUTH-PA-1 — `EDGE_FAILED` explicit terminal projection (Scenario E)

Runtime symptoms are **not** terminals. Classification required:

```text
observable fact (e.g. SRD_TIMEOUT, NEGOTIATION_STUCK, HANGING_OBSERVED)
    ↓
L1 classifier (self-attributed)
    ↓
explicit EDGE_FAILED terminal
    ↓
no indefinite invisible CONNECTING on that edge
```

**Forbidden:** `native hang` or `SRD_TIMEOUT` emitted directly as `EDGE_FAILED` without classification step.

### AUTH-PA-2 — `DOMAIN_BLOCKED` classification (Scenario D)

```text
Impact edge blocked by peer-caused shared runtime domain obstruction
    ↓
explicit DOMAIN_BLOCKED terminal (L2)
    ↓
NOT EDGE_FAILED on impact edge
```

Legalized by Decision 1 Option Z. See [Failure Domain Contract — L2](./conference-failure-domain-contract.md#l2--domain-contention).

### AUTH-PA-3 — `DOMAIN_BLOCKED` attribution tuple (all required)

```text
DOMAIN_BLOCKED {
  impactEdgeKey
  runtimeDomainRef
  causeEdgeKey
  causeFact               LEASE_HELD_BY_CAUSE | NATIVE_DOMAIN_OBSTRUCTED
  contentionKind          RUNTIME_DOMAIN_CONTENTION
  blockReason             RUNTIME_DOMAIN_UNAVAILABLE
  generationScope         conferenceSessionId + meshGeneration (+ pcGeneration if edge-scoped)
  causePhase              HANGING_OBSERVED | EDGE_FAILED
}
```

`causeEdgeKey` source priority: `LEASE_HELD_BY_CAUSE` > `NATIVE_DOMAIN_OBSTRUCTED`. **Forbidden:** UI / roster / topology inference alone.

### AUTH-PA-4 — L-participant mandatory projection

```text
DOMAIN_BLOCKED on impact edge
    ⇒
impact participant MUST NOT remain CONNECTING / invisible
```

Participant/edge display projection required. **Not** automatic Conference `DEGRADED` (L-conference is Phase B).

### AUTH-PA-5 — Cause → impact telemetry chain

Auditable ordering. **`LEASE_BUSY` is not a cause fact** — it is a B2-1 runtime observation (input only).

```text
cause observable fact (one of):

  holder attribution:
    LEASE_HELD_BY_CAUSE
    holderEdgeKey

  or native domain observation:
    NATIVE_DOMAIN_OBSTRUCTED

  with optional cause-phase on cause edge:
    HANGING_OBSERVED
    EDGE_FAILED

    ↓
DOMAIN_BLOCKED on impact edge with full tuple (AUTH-PA-3)
```

**Forbidden:**

```text
LEASE_BUSY observed → DOMAIN_BLOCKED emitted
(LEASE_BUSY alone does not satisfy causeFact)
```

### AUTH-PA-6 — Projection hooks (authorized touchpoints)

```text
ConferenceRuntimeProjector.kt           — EDGE_FAILED / DOMAIN_BLOCKED state extension
ConferenceParticipantDisplayState.kt  — L-participant projection
ConferenceSrdNativeDomainObservability.kt — consume B2-1 facts; emit classification telemetry
TalkbackCoordinator.kt                  — wire projection only (no topology/recovery semantics change)
```

---

## Scope OUT (implementation drift prohibited)

```text
native partition
WebRtcSharedFactory change
SharedLocalAudio change
Factory / ADM lifecycle change
D-β lab or implementation
D-γ implementation
ConferenceAudioBus
PCM relay
Anchor Media Domain runtime redesign
(rename does NOT decouple native worker while 1 Factory + N PC remains)

L3 DOMAIN_FAILED declaration
domain teardown / quiesce / reset
MediaUsable predicate definition
ConferenceHealth terminal state (CONFERENCE_FAILED)
automatic Conference DEGRADED on DOMAIN_BLOCKED
anchor failover
meshGeneration change
ActualMediaEdgeSet mutation
Recovery obligation mutation
completion predicate change
WiFi recovery / RNA changes
Commit 4 slot reuse
P0.1g Acceptance 1 field rerun
B2-1 lease semantic change
```

**If a PR requires any OUT item → STOP → separate authorization required.**

---

## B2-1 boundary (frozen — do not become B2-1 v2)

```text
B2-1 contract: RETAIN
No semantic change in Phase A.
```

| Layer | Role in Phase A |
|-------|-----------------|
| **B2-1** | Facts + admission — lease grant/deny/busy, `EDGE_LOCAL_FAILURE` |
| **Phase A** | Classification + attribution + projection above B2-1 |

**Phase A consumes (inputs only):**

```text
LEASE_BUSY
EDGE_LOCAL_FAILURE
NATIVE_DOMAIN_LEASE_* domain observations
```

**Phase A does NOT redefine:**

```text
lease ownership
lease lifetime
admission policy
domain lifecycle
holder / waiter semantics
native execution model
```

**Forbidden pattern:**

```text
"To emit DOMAIN_BLOCKED, we adjust lease behavior"
        ↓
B2-1 v2 without authorization
```

`LEASE_BUSY` = runtime observation (input). `DOMAIN_BLOCKED` = classified L2 terminal. Phase A classifies; it does not change when B2-1 emits `LEASE_BUSY`.

---

## Implementation invariants

Phase A code **MUST** preserve:

```text
I1   DOMAIN_BLOCKED is not EDGE_FAILED (especially on impact edge)

I2   DOMAIN_BLOCKED is not DOMAIN_FAILED (L3 out of scope)

I3   DOMAIN_BLOCKED does not trigger retry ownership transfer
     (impact edge MUST NOT ICE-restart / SRD-retry as L1 self-recovery)

I4   Participant projection may change; topology truth may not change

I5   Health state may not become DEGRADED automatically on DOMAIN_BLOCKED alone
     (L-conference requires MediaUsable — Phase B)

I6   No new topology / recovery authority introduced by failure classification

I7   runtimeDomainRef MUST be explicit in DOMAIN_BLOCKED telemetry
     (blocks "Anchor Media Domain rename fixes coupling" misread)

I8   causeEdgeKey MUST NOT be inferred from roster/topology without B2-1/native facts
```

---

## Acceptance gate — Phase A PASS

**Gate:** CFC-1 PARTIAL only (Scenario E ∧ Scenario D). **Not** field gate for P0.1g Acceptance 1.

### Scenario E — must observe

```text
ConferenceEdgeKey(M01, M03) (or injected cause edge):
  observable self-attributed symptoms (e.g. HANGING_OBSERVED, SRD_TIMEOUT)
    ↓
  L1 classifier
    ↓
  explicit EDGE_FAILED
  bounded — no invisible CONNECTING
  self-attributed — not DOMAIN_BLOCKED on cause edge for own failure
```

### Scenario D — must observe

```text
ConferenceEdgeKey(M01, M03) cause:
  EDGE_FAILED or HANGING_OBSERVED + causeFact (AUTH-PA-5)
    ↓
ConferenceEdgeKey(M01, M04) impact:
  explicit DOMAIN_BLOCKED
  full attribution tuple (AUTH-PA-3)
  causeEdgeKey = ConferenceEdgeKey(M01, M03)
  L-participant: M04 participant not CONNECTING/invisible
  cause → impact ordering auditable in telemetry
```

### 4p reference shape (M01 anchor — aligned with RCA-B field topology)

Edge keys use `ConferenceEdgeKey(anchorModule, remoteModule)` per ADR-0022 — not anchor-route shorthand.

```text
ConferenceEdgeKey(M01, M03):
  EDGE_FAILED (or HANGING_OBSERVED → L1 classifier → EDGE_FAILED)

ConferenceEdgeKey(M01, M04):
  DOMAIN_BLOCKED (full tuple; causeEdgeKey = ConferenceEdgeKey(M01, M03))

ConferenceEdgeKey(M01, M02):
  operational (not required to DOMAIN_BLOCKED)

M04 participant UI:
  blocked/degraded — not CONNECTING
```

Conference room-level `DEGRADED` **not required** for Phase A PASS if `MediaUsable` would remain true (Scenario C — Phase B).

### Phase A PASS ≠ (forbidden conclusions)

```text
Phase A PASS ≠ CFC-1 FULL PASS
Phase A PASS ≠ P0.1g PASS
Phase A PASS ≠ P0.1g Acceptance 1 PASS
Phase A PASS ≠ native per-edge SRD independence proven
Phase A PASS ≠ fault containment complete
Phase A PASS ≠ native isolation achieved
Phase A PASS ≠ P0.1g closed
```

**Allowed PASS wording only:**

```text
Phase A PASS
CFC-1 PARTIAL PASS (Scenario E ∧ D)
P0.1g Acceptance 1 remains HOLD — ORIGINAL
```

---

## Field authorization

**Status with this document:** Field for Phase A validation is **allowed only after ACCEPTED** below.

| Item | Policy |
|------|--------|
| **Allowed** | Phase A validation field run · CFC-1 PARTIAL adjudication |
| **Gate** | Scenario E ∧ Scenario D per above |
| **Forbidden** | P0.1g Acceptance 1 field rerun |
| **Forbidden** | D-β validation |
| **Forbidden** | D-γ experiment |
| **Forbidden** | Using field PASS to unblock P0.1g Original |

Run card MUST tag:

```text
Phase A · CFC-1 PARTIAL · E+D only
B2-1 RETAIN · no lease semantic change
Acceptance 1 HOLD · L3/L4 OUT OF SCOPE
```

---

## Posture block (append to every Phase A PR / field run / PASS claim)

```text
P0.1g-A                    CLOSED / PASS
P0.1g-B                    CLOSED / PASS
P0.1g Acceptance 1         HOLD — ORIGINAL · BLOCKED / NO VERIFIED PATH · NOT FAILED
Decision 1                 Option Z ACCEPTED
CFC-1                      PARTIAL target (E+D)
CFC-1 FULL                 Phase B pending
Phase A                    [PROPOSED | ACCEPTED — per this document]
Native partition           NOT AUTHORIZED
D-β / D-γ                  NOT AUTHORIZED
B2-1                       RETAIN — no lease semantic change
L3 / L4                    OUT OF SCOPE
```

---

## Audit gate (INVALID if any hit)

```text
Q-PA1  PASS conclusion uses forbidden wording (P0.1g closed, native isolation, etc.)?
Q-PA2  PR tagged CFC-1 PARTIAL not FULL?
Q-PA3  Posture block includes Acceptance 1 HOLD?
Q-PA4  DOMAIN_BLOCKED emitted without runtimeDomainRef or causeEdgeKey?
Q-PA5  Impact edge classified EDGE_FAILED for peer-caused domain block?
Q-PA6  B2-1 lease/admission semantics changed?
Q-PA7  Topology / recovery / completion / MediaUsable introduced?
Q-PA8  Automatic Conference DEGRADED on DOMAIN_BLOCKED alone?

Any YES → INVALID authorization claim or INVALID PASS
```

---

## Authorization record

| Field | Value |
|-------|-------|
| **Authorization** | `ACCEPTED` |
| **Sign-off** | [Signed 2026-08-21](./conference-native-failure-containment-phase-a-authorization-signoff.md) |
| **Date** | 2026-08-21 |
| **Product owner** | Accepted (governance session) |
| **Media / Architecture owner** | Accepted (governance session) |
| **Scope** | CFC-1 PARTIAL (E + D) only |

### On ACCEPTED

```text
Phase A implementation     AUTHORIZED (bounded — this document IN scope only)
Phase A field validation   AUTHORIZED (CFC-1 PARTIAL gate)
CFC-1 FULL                 NOT AUTHORIZED (Phase B)
P0.1g Acceptance 1         HOLD — unchanged
Native partition           NOT AUTHORIZED
```

### Current (ACCEPTED 2026-08-21; desk gate PASS after PR-A1–A4)

```text
Phase A implementation     AUTHORIZED (bounded — IN scope only)
Phase A desk validation    CFC-1 PARTIAL PASS — Desk Gate (PR-A1–A4)
Phase A field validation   NOT AUTHORIZED (PR-A5 gate)
Runtime wiring             NOT AUTHORIZED
PR-A5                      NOT STARTED
CFC-1 FULL                 NOT AUTHORIZED (Phase B)
P0.1g Acceptance 1         HOLD — unchanged
```

---

## Architecture layer status

```text
Decision Layer             CLOSED (Option Z)
Failure Model              CLOSED (L1–L4 contract)
Authorization Specification ACCEPTED (v0.2)
Authorization Signoff      SIGNED 2026-08-21
Desk Gate (PR-A1–A4)       CFC-1 PARTIAL PASS
Runtime Wiring (PR-A5)     NOT AUTHORIZED
Field                      NOT RUN
```

**reassessment v0.3:** Deferred. Do not reopen Decision 1 via reassessment edit before Phase A field evidence.

---

## Prohibited

```text
Merge Phase A without ACCEPTED authorization record
Adjust B2-1 lease behavior to "make DOMAIN_BLOCKED easier"
Treat Phase A PASS as P0.1g closure
Implement L3/L4 under Phase A scope
Field rerun under P0.1g Acceptance 1 gate
PR title/body: "native fault isolation fix" / "cross-edge SRD independence" / "P0.1g pass"
```

---

## Changelog

| Date | Version | Change |
|------|---------|--------|
| 2026-08-21 | v0.1 | Phase A authorization proposed; CFC-1 PARTIAL (E+D); B2-1 boundary; invariants; field gate |
| 2026-08-21 | v0.2 | AUTH-PA-5: LEASE_BUSY not cause fact; 4p shape uses ConferenceEdgeKey; Scenario E L1 classifier chain |
| 2026-08-21 | v0.2.1 | Link sign-off template; Authorization record PROPOSED until signed |
| 2026-08-21 | v0.2.2 | Desk gate PASS (PR-A1–A4); field/runtime wiring frozen pending PR-A5 |
