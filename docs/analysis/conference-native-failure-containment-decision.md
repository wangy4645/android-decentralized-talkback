# Conference Native Failure Containment — Decision 1

**ID:** conference-native-failure-containment-decision  
**Date:** 2026-08-21  
**Version:** v0.2  
**Type:** Architecture / Product Decision  
**Status:** **DECIDED — Option Z** · no implementation · no lab authorization

**Prerequisite:** [Architecture reassessment](./conference-srd-native-domain-partition-architecture-reassessment.md) v0.2 · [D-α FAIL](../../logs/dalpha-lab-spike-20260821-155612/ADJUDICATION.txt) · [B2-1 IA-001](./conference-srd-native-domain-isolation-implementation-authorization-001.md)  
**Related:** [Failure Domain Contract](./conference-failure-domain-contract.md) · [Handoff](./handoff-conference-failure-domain-convergence-20260821.md)

---

## Scope (intentionally narrow)

This document records **Decision 1** and its consequences for P0.1g posture, **CFC-1**, and bounded Phase A scope.

**In scope:** Decision 1 · Option X / Y / Z · CFC-1 identity · frozen posture · Phase A boundary (reference)

**Out of scope (not in this document):**

```text
D-β implementation details
D-γ design
Factory / ADM experiments
Commit 4
P0.1g field rerun
Ready / Recovery / completion predicate changes
Any lab spike authorization
Any production implementation authorization (except separate Phase A authorization)
L3 DOMAIN_FAILED authority implementation
L4 ConferenceHealth full implementation
```

---

## Decision owners

| Role | Responsibility |
|------|----------------|
| **Product owner** | Whether native-level cross-edge fault isolation remains a product requirement |
| **Media / Architecture owner** | Whether current WebRTC runtime constraints make that requirement achievable or must be reframed |

Formal owner sign-off may be appended to the decision record. Architecture convergence (grill frozen 2026-08-21) recorded **Option Z** as the binding decision outcome.

---

## Background (minimal)

Upstream evidence (details in [reassessment](./conference-srd-native-domain-partition-architecture-reassessment.md)):

```text
1 Factory + N PC     → shared native SRD domain → original P0.1g NOT satisfied
2 Factory + 1 ADM    → construction crash       → D-α REJECTED
D-β                  → UNKNOWN · NOT AUTHORIZED · deprioritized
D-γ                  → LONG-TERM OPTION · NOT AUTHORIZED
B2-1                 → RETAIN (ownership · admission · failure visibility)
```

No verified safe per-edge native isolation path exists on current `stream-webrtc-android:1.3.10` / Android runtime / existing audio architecture.

**Wording constraint (frozen):** Use `BLOCKED / NO VERIFIED PATH`, not `BLOCKED BY RUNTIME CAPABILITY` (D-β not disproven).

**Anchor Media Domain rename:** Does **not** remove native coupling while `1 Factory + N PC` remains. Logical topology domain ≠ runtime fault domain.

---

## The question (Decision 1)

> **When one edge's native SRD hangs, must another edge still be able to make independent native SRD realization progress?**

Original **P0.1g Acceptance 1** defines this as **yes**. Decision 1 records how that contract coexists with upper-layer fault containment on current runtime.

---

## Option X — Retain native-level containment (not selected)

### Contract

```text
M03 native SRD hang
    ↓
M04 must retain independent native realization progress
```

| Item | Status |
|------|--------|
| P0.1g Acceptance 1 | **Unchanged** — original contract retained |
| D-β / D-γ | Candidate paths only (not authorized here) |
| B2-1 | **RETAIN** as safety / observability layer |

---

## Option Y — Move containment upward via P0.1g amendment (not selected)

### Contract (would require amendment)

```text
M03 native SRD hang
    ↓
M04 may be unable to realize natively
    ↓
must become explicit failure states
    ↓
no invisible wait · no false Ready · no indefinite timeout
```

| Item | Status |
|------|--------|
| P0.1g Acceptance 1 | **Requires contract amendment** (separate ADR + product sign-off) |
| B2-1 | Primary containment layer |
| Native partition | Not mandatory for containment |

Option Y remains documented for comparison. **Option Z supersedes Option Y** as the recorded decision without amending P0.1g Acceptance 1.

---

## Option Z — Dual-track contract (SELECTED)

### Contract

```text
P0.1g Acceptance 1
    HOLD — ORIGINAL
    BLOCKED / NO VERIFIED PATH
    (NOT FAILED)

        parallel

CFC-1
    Upper-layer fault containment acceptance
    (distinct identifier — NOT P0.1g-B)
```

### Rationale

- Avoids "test failed so lower the bar" culture.
- Preserves Original as aspirational contract blocked by missing verified path.
- Introduces **CFC-1** as production-oriented containment acceptance **without** implying P0.1g completion or native edge independence.
- Future runtime (D-β / D-γ success) may **re-challenge** Acceptance 1; does **not** retire CFC-1.

### What Option Z commits to

| Item | Status |
|------|--------|
| P0.1g Acceptance 1 | **HOLD — ORIGINAL** · `BLOCKED / NO VERIFIED PATH` · **NOT FAILED** |
| CFC-1 | **New** upper-layer fault containment acceptance (see below) |
| `DOMAIN_BLOCKED` | **Legalized** as L2 impact terminal under Option Z |
| B2-1 | **RETAIN** — facts + admission; Phase A adds classification/projection above |
| Native partition | **NOT AUTHORIZED** |
| D-β / D-γ | **NOT AUTHORIZED** |
| Implementation | **NOT AUTHORIZED** by this decision alone |
| Phase A | Bounded authorization **separate gate** — CFC-1 PARTIAL (E+D) only |

### What Option Z does not commit to

- Marking P0.1g Acceptance 1 as FAILED.
- CFC-1 FULL PASS (Scenario C + L3/L4 — Phase B).
- P0.1g field rerun under original Acceptance 1 gate.
- Amending P0.1g Acceptance 1 (Option Y path — not taken).
- Native partition lab or production work.

---

## Frozen P0.1g sub-contracts (historical — do not rename)

```text
P0.1g-A
= Control-plane dispatch isolation
= CLOSED / PASS

P0.1g-B
= Single-edge native SRD boundary
  (M03 ENTER no callback → TIMEOUT)
= CLOSED / PASS

P0.1g Acceptance 1
= M03 native hang → M04 independent native SRD realization required
= HOLD — ORIGINAL
  BLOCKED / NO VERIFIED PATH
  NOT FAILED
```

**Prohibited:** Reusing `P0.1g-B` for upper-layer containment. That name is **CLOSED** with fixed historical meaning.

---

## CFC-1 — Upper-layer fault containment acceptance

**CFC-1** is an **acceptance profile**, not a failure state contract.

### Purpose

Define acceptable Conference Media Runtime behavior when failure propagation crosses logical edge boundaries:

```text
Required properties:
- explicit failure state
- attributable failure domain
- bounded propagation
- no invisible wait
- deterministic health projection (Scenario C — Phase B for full MediaUsable)
```

CFC-1 is **NOT:**

```text
native limitation workaround
replacement for native isolation
B2-1 implementation phase
Phase A authorization alias
native domain redesign
```

CFC-1 **survives** D-β / D-γ success. It does **not** depend on "native independence unavailable" as a permanent precondition.

### Acceptance profile (scenarios)

```text
CFC-1
 |
 +-- Scenario E   Edge-local failure        → EDGE_FAILED
 +-- Scenario D   Domain contention impact  → DOMAIN_BLOCKED (+ attribution)
 +-- Scenario C   Conference projection     → L-participant mandatory; L-conference by MediaUsable (Phase B)
```

**PASS rules:**

```text
CFC-1 PASS       = E PASS ∧ D PASS ∧ C PASS
CFC-1 PARTIAL    = subset verified — NOT CFC-1 PASS
Phase A target   = CFC-1 PARTIAL (E ∧ D) only
```

### Forbidden equivalences (frozen)

```text
CFC-1 PASS ≠ P0.1g PASS
CFC-1 PASS ≠ P0.1g Acceptance 1 PASS
CFC-1 PASS ≠ native per-edge SRD independence proven
DOMAIN_BLOCKED visible ≠ P0.1g satisfied
CFC-1 PARTIAL (E only) ≠ fault containment PASS
DOMAIN_BLOCKED PASS ≠ CFC-1 PASS
```

Details: [Failure Domain Contract](./conference-failure-domain-contract.md)

---

## Comparison (Decision 1)

| Dimension | Option X | Option Y | **Option Z (selected)** |
|-----------|----------|----------|-------------------------|
| P0.1g Acceptance 1 | Retain original | Amend | **HOLD — ORIGINAL (NOT FAILED)** |
| Upper-layer containment | Secondary | Primary via amendment | **CFC-1 parallel track** |
| M04 while M03 hangs | Must progress native SRD | May fail explicitly | **Original still required for Acceptance 1; CFC-1 accepts explicit bounded failure** |
| `DOMAIN_BLOCKED` | N/A under original pass | Legal after amendment | **Legal under Option Z** |
| Native partition | Required to satisfy Original | Not mandatory | **NOT AUTHORIZED** |
| D-β / D-γ | Investigation paths | Not mandatory | **NOT AUTHORIZED · deprioritized / long-term** |

---

## Decision record

| Field | Value |
|-------|-------|
| **Decision** | **Option Z** |
| **Date** | 2026-08-21 |
| **Product owner** | *(formal sign-off pending)* |
| **Media / Architecture owner** | *(formal sign-off pending)* |
| **Rationale** | Preserve P0.1g Acceptance 1 as HOLD — BLOCKED / NO VERIFIED PATH without FAILED downgrade; parallel CFC-1 upper-layer containment acceptance; legalize DOMAIN_BLOCKED under Failure Domain Contract; avoid conflating containment PASS with native isolation or P0.1g closure. |

### Consequences (Option Z recorded)

```text
P0.1g Acceptance 1         HOLD — ORIGINAL · BLOCKED / NO VERIFIED PATH · NOT FAILED
CFC-1                      INTRODUCED — upper-layer fault containment acceptance
DOMAIN_BLOCKED             LEGALIZED (L2 — see Failure Domain Contract)
Phase A                    Separate bounded authorization — CFC-1 PARTIAL (E+D)
Phase B                    CFC-1 FULL (Scenario C + L3/L4) · Failure Domain Contract ADR alignment
Native partition           NOT AUTHORIZED
D-β                        UNKNOWN · NOT AUTHORIZED · deprioritized
D-γ                        LONG-TERM OPTION · NOT AUTHORIZED
Implementation             NOT AUTHORIZED (except Phase A gate)
Lab                        NOT AUTHORIZED
```

---

## Phase A authorization boundary (reference)

Phase A is **not authorized by this document alone**. When separately authorized:

```text
Phase A IN:
  EDGE_FAILED (Scenario E)
  DOMAIN_BLOCKED + attribution (Scenario D)
  L-participant mandatory projection
  cause → impact auditable telemetry

Phase A OUT:
  native partition · D-β · D-γ
  L3 DOMAIN_FAILED authority · teardown
  L4 ConferenceHealth / MediaUsable full implementation
  topology / recovery / completion changes

Phase A PASS = CFC-1 PARTIAL PASS (E ∧ D)
NOT CFC-1 FULL · NOT P0.1g · NOT Acceptance 1
```

See [Phase A Authorization](./conference-native-failure-containment-phase-a-authorization.md) · [Failure Domain Contract — Phase A](./conference-failure-domain-contract.md#phase-a-authorization-boundary).

---

## Posture block (fixed — append to Phase A auth / field adjudication)

```text
P0.1g-A                    CLOSED / PASS
P0.1g-B                    CLOSED / PASS
P0.1g Acceptance 1         HOLD — ORIGINAL · BLOCKED / NO VERIFIED PATH · NOT FAILED
Decision 1                 Option Z ACCEPTED
CFC-1                      PARTIAL target (E+D)
CFC-1 FULL                 Phase B pending
Native partition           NOT AUTHORIZED
D-β / D-γ                  NOT AUTHORIZED
B2-1                       RETAIN — no lease semantic change in Phase A
```

---

## Prohibited

```text
Mark P0.1g Acceptance 1 as FAILED
Reuse P0.1g-B for CFC-1 or upper-layer containment
Treat CFC-1 PASS or Phase A PASS as P0.1g PASS / closure
Treat B2-1 LEASE_BUSY path as P0.1g PASS under original contract
Authorize D-β or D-γ under "interim" contract relaxation
Assume Anchor Media Domain rename fixes native coupling
Phase A PASS wording: native isolation achieved / fault containment complete / P0.1g closed
Reopen D-α
Field P0.1g Acceptance 1 rerun as Original gate
```

---

## Changelog

| Date | Version | Change |
|------|---------|--------|
| 2026-08-21 | v0.1 | Decision 1 opened; Option X/Y only; owners defined; no impl/lab auth |
| 2026-08-21 | v0.2 | **Option Z ACCEPTED**; CFC-1 introduced; P0.1g-B reuse prohibited; Phase A boundary reference; Failure Domain Contract link |
