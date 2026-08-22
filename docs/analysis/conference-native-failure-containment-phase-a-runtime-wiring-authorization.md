# PR-A5 Authorization — Runtime Wiring (B2-1 → CFC-1)

**ID:** conference-native-failure-containment-phase-a-runtime-wiring-authorization  
**Date:** 2026-08-22  
**Version:** v0.1  
**Type:** Implementation Boundary / Authorization Record  
**Status:** **ACCEPTED** · PR-A5 wiring + Field E/D **AUTHORIZED** (bounded) · 2026-08-22

**Does not inherit Phase A desk-gate authority as field PASS.** Desk Gate PASS (PR-A1–A4) proves synthetic/unit/integration contract only. This document authorizes **runtime observation wiring** so CFC-1 PARTIAL can be adjudicated on field devices.

**Prerequisite (all frozen):**

| Gate | Status | Artifact |
|------|--------|----------|
| Decision 1 | Option Z ACCEPTED | [decision](./conference-native-failure-containment-decision.md) |
| Failure Domain Contract | FROZEN | [contract](./conference-failure-domain-contract.md) |
| Phase A Authorization | ACCEPTED | [phase-a-auth](./conference-native-failure-containment-phase-a-authorization.md) |
| PR-A1–A4 Desk Gate | **CFC-1 PARTIAL PASS — Desk Gate** | commit `9491fa6` |
| B2-1 | RETAIN / PASS | [IA-001](./conference-srd-native-domain-isolation-implementation-authorization-001.md) |

```text
Phase A Desk Gate (A1–A4)
        ↓
PR-A5 Runtime Wiring Authorization  ← this gate
        ↓
B2-1 real facts → existing CFC-1 pipeline
        ↓
Field Scenario E/D
        ↓
CFC-1 PARTIAL FIELD adjudication
```

---

## Authorization statement (single sentence)

> **Authorize wiring B2-1 observation facts into the existing CFC-1 PARTIAL pipeline** (classify → project → telemetry) and a bounded field run for Scenario E ∧ D only.

**No authorization for:** lease semantic change · topology · recovery · health/MediaUsable · native runtime · P0.1g Acceptance 1 · CFC-1 FULL · L3/L4.

---

## Goal (narrow)

Prove on field devices:

```text
real B2-1 observation
        ↓
ConferenceFailureObservation
        ↓
existing CFC-1 classifier / projection / telemetry
        ↓
participant not invisible CONNECTING
        ↓
auditable cause → impact chain
```

**Not the goal:**

```text
Fix M03 native hang
Native per-edge SRD independence
P0.1g Acceptance 1 PASS
Production readiness claim
Conference DEGRADED / MediaUsable
```

---

## Scope IN

### AUTH-A5-1 — Observation adapter only

Map existing B2-1 / SRD observability facts into `ConferenceFailureObservation`:

```text
Inputs (consume only — do not redefine):
  NATIVE_DOMAIN_LEASE_BUSY / holderEdgeKey
  EDGE_LOCAL_FAILURE
  SRD_NATIVE_CALL_ENTER without EXIT / HANGING_OBSERVED class
  SRD_TIMEOUT / SRD_WATCHDOG_GAP (as symptoms → classifier)

Output:
  ConferenceFailureObservation
    → ConferenceFailureTelemetryPipeline.emitScenarioE / emitScenarioD
    → failureTerminalsByModuleId into ConferenceParticipantProjector
```

### AUTH-A5-2 — Existing pipeline reuse

```text
MUST use:
  ConferenceFailureClassifier
  ConferenceFailureParticipantProjector
  ConferenceFailureTelemetryPipeline

MUST NOT invent parallel taxonomy or new L1/L2 meanings
```

### AUTH-A5-3 — Telemetry emission

Emit existing `CONFERENCE_FAILURE_*` audit lines from pipeline `auditLines` at classification points. No new failure domain vocabulary.

### AUTH-A5-4 — Field run (after ACCEPTED + wiring merge)

Bounded 4p field (M01 = Anchor):

```text
Scenario E: ConferenceEdgeKey(M01,M03) → EDGE_FAILED + projection + chain valid
Scenario D: cause M03 → DOMAIN_BLOCKED on M04 + full attribution + projection
```

Gate: **CFC-1 PARTIAL FIELD** only.

---

## Scope OUT

```text
B2-1 lease ownership / lifetime / admission policy change
ConferenceNativeExecutionDomain semantic change
WebRtcSharedFactory / SharedLocalAudio / Factory / ADM
D-β / D-γ / native partition
TalkbackCoordinator topology / Anchor / meshGeneration mutation
ActualMediaEdgeSet mutation
Recovery obligation / ICE restart / SRD retry ownership transfer
ConferenceHealth / MediaUsable / automatic DEGRADED
L3 DOMAIN_FAILED / L4 CONFERENCE_FAILED
P0.1g Acceptance 1 field rerun
CFC-1 FULL / Scenario C
```

**If a PR requires any OUT item → STOP → separate authorization.**

---

## B2-1 boundary (reaffirmed)

```text
B2-1: RETAIN — no semantic change

PR-A5 consumes facts
PR-A5 does NOT redefine lease behavior to emit DOMAIN_BLOCKED

LEASE_BUSY = input observation only
causeFact = LEASE_HELD_BY_CAUSE | NATIVE_DOMAIN_OBSTRUCTED (derived)
```

---

## Implementation invariants

```text
I-A5-1  LEASE_BUSY alone ≠ DOMAIN_BLOCKED
I-A5-2  Impact edge DOMAIN_BLOCKED ≠ EDGE_FAILED
I-A5-3  No topology / recovery / health mutation from wiring
I-A5-4  No new classifier vocabulary beyond PR-A1–A4
I-A5-5  Field PASS wording must say FIELD, never Desk Gate alone as field claim
I-A5-6  Desk Gate PASS remains; Field PASS is additive evidence only
```

---

## Acceptance — CFC-1 PARTIAL FIELD PASS

**PASS iff:**

```text
Scenario E PASS on field logs
AND
Scenario D PASS on field logs
```

### Scenario E must show

```text
cause edge explicit EDGE_FAILED (via classifier)
participant VISIBLE_EDGE_FAILED (not indefinite CONNECTING)
CONFERENCE_FAILURE_* chain valid
```

### Scenario D must show

```text
DOMAIN_BLOCKED full attribution:
  impactEdgeKey · runtimeDomainRef · causeEdgeKey · causeFact
  contentionKind · blockReason · generationScope
participant VISIBLE_DOMAIN_BLOCKED + blockedByDomain
cause → impact ordering auditable
```

### Forbidden PASS wording

```text
CFC-1 FULL PASS
P0.1g PASS / Acceptance 1 PASS
native isolation proven
Phase A production PASS
Desk Gate PASS claimed as Field PASS
```

### Required PASS wording

```text
CFC-1 PARTIAL FIELD PASS (Scenario E ∧ D)
P0.1g Acceptance 1 remains HOLD — ORIGINAL
```

---

## Field constraints

| Item | Policy |
|------|--------|
| Topology | Fixed M01 Anchor (else INVALID RUN) |
| Devices | M01 · M03 · (M02/M04 as needed for D) |
| Gate | CFC-1 PARTIAL FIELD only |
| Forbidden | P0.1g Acceptance 1 adjudication · D-β · D-γ |

Run card MUST tag:

```text
Phase A · PR-A5 · CFC-1 PARTIAL FIELD · E+D only
B2-1 RETAIN · Acceptance 1 HOLD · L3/L4 OUT OF SCOPE
```

---

## Posture block

```text
P0.1g-A                    CLOSED / PASS
P0.1g-B                    CLOSED / PASS
P0.1g Acceptance 1         HOLD — ORIGINAL · BLOCKED / NO VERIFIED PATH · NOT FAILED
Decision 1                 Option Z ACCEPTED
CFC-1 Desk Gate            PASS (PR-A1–A4)
CFC-1 Field                [PROPOSED | AUTHORIZED after ACCEPTED]
Native partition           NOT AUTHORIZED
D-β / D-γ                  NOT AUTHORIZED
B2-1                       RETAIN
L3 / L4                    OUT OF SCOPE
```

---

## Authorization record

| Field | Value |
|-------|-------|
| **Authorization** | `ACCEPTED` |
| **Date** | 2026-08-22 |
| **Product owner** | Accepted (governance session) |
| **Media / Architecture owner** | Accepted (governance session) |
| **Scope** | B2-1 facts → CFC-1 pipeline + Field E/D only |

### Current (ACCEPTED 2026-08-22)

```text
PR-A5 wiring             AUTHORIZED (IN scope only)
Field E/D                AUTHORIZED (CFC-1 PARTIAL FIELD gate)
CFC-1 FULL               NOT AUTHORIZED
P0.1g Acceptance 1       HOLD unchanged
```

---

## Prohibited

```text
Wire without ACCEPTED record
Change B2-1 lease to "help" DOMAIN_BLOCKED
Treat Field PASS as P0.1g closure
Treat Desk Gate PASS as Field PASS
Expand taxonomy during wiring
```

---

## Changelog

| Date | Version | Change |
|------|---------|--------|
| 2026-08-22 | v0.1 | PR-A5 runtime wiring authorization proposed |
