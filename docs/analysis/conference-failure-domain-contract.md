# Conference Failure Domain Contract

**ID:** conference-failure-domain-contract  
**Date:** 2026-08-21  
**Version:** v0.1  
**Type:** Architecture Contract · Taxonomy  
**Status:** **FROZEN (grill)** · no implementation · no lab authorization

**Prerequisite:** [Decision 1 — Option Z](./conference-native-failure-containment-decision.md) · [Architecture reassessment](./conference-srd-native-domain-partition-architecture-reassessment.md) v0.2 · [B2-1 IA-001](./conference-srd-native-domain-isolation-implementation-authorization-001.md) · [ADR-0056](../adr/0056-conference-topology-first.md)

---

## Purpose

This document defines the **four-level failure domain taxonomy**, attribution rules, recovery ownership boundaries, and **CFC-1** acceptance mapping.

It is the **model** layer. [Decision 1](./conference-native-failure-containment-decision.md) is the **constraint** layer.

```text
Decision 1 (Option Z)
        ↓
Failure Domain Contract (this document)
        ↓
Phase A / Phase B authorization (separate gates)
```

---

## Core invariant

```text
Logical topology domain (ADR-0056 Anchor-star)
        ≠
Runtime fault domain (1 Factory + N PC → shared native signaling worker)
```

Renaming to **Anchor Media Domain** does **not** remove native coupling while `1 Factory + N PC` remains.

---

## Four-level taxonomy

Classification is by **attribution edge**, not by incident alone. One incident may produce **L1 on cause edge** and **L2 on impact edge** simultaneously.

```text
L1  Edge Failure           — edge fact
L2  Domain Contention      — runtime contention impact fact
L3  Domain Failure         — domain authority fact
L4  Conference Failure     — ConferenceHealth projection outcome
```

**Upgrade direction (facts flow down):**

```text
L1/L2 facts
    ↓
domain authority evaluation
    ↓
L3 (conditional)

L1/L2/L3 facts
    ↓
MediaUsable evaluation
    ↓
L4 (conditional)
```

**No reverse upgrade:**

```text
impact edge → domain reset
L3 → automatic L4 terminal
L4 → topology mutation
Health state → topology truth
```

---

## L1 — Edge Failure

### Definition

| Field | Value |
|-------|--------|
| **Scope** | `(conferenceSessionId, remoteModuleId)` edge |
| **Meaning** | Edge itself cannot realize its media obligation |
| **Terminal** | `EDGE_FAILED` |
| **Attribution** | **Self** — failure on edge's own realization path |
| **Answers** | "Which edge failed?" |

### Classification rule

```text
IF failure attributable to edge's own realization terminal
THEN L1 EDGE_FAILED on that edge
```

`EDGE_FAILED` = failure **classification**. **NOT** runtime isolation proof.

Scenario E PASS does **not** prove edge failure cannot affect others (that is Acceptance 1, still HOLD).

### Recovery ownership

```text
Owner: per-edge recovery obligation (ADR-0022 execution layer)
Action: recover own edge
```

### Input facts (not L1 terminals)

```text
EDGE_LOCAL_FAILURE   — B2-1 observable input
SRD_TIMEOUT on self edge — may lead to EDGE_FAILED when self-attributable
```

---

## L2 — Domain Contention

### Definition

| Field | Value |
|-------|--------|
| **Scope** | `runtimeDomainRef` (shared native execution domain on module) |
| **Meaning** | One edge/cause affects another edge's admission |
| **Terminal** | `DOMAIN_BLOCKED` |
| **Attribution** | **Cause / impact** — mandatory tuple |
| **Answers** | "Which edge was blocked by shared runtime domain?" |

### Semantic boundary

```text
DOMAIN_BLOCKED on impact edge
  = impact classification (admission blocked)
  ≠ M04 edge health failure
  ≠ EDGE_FAILED on impact edge
  ≠ LEASE_BUSY event
```

```text
LEASE_BUSY              = runtime observation (input)
DOMAIN_BLOCKED          = classified impact terminal
```

### Classification rule

```text
IF failure attributable to peer-caused runtime domain obstruction
AND impact edge had no independent native fault
THEN L2 DOMAIN_BLOCKED on impact edge
NOT L1 EDGE_FAILED on impact edge
```

**Forbidden:**

```text
DOMAIN_BLOCKED upgraded to EDGE_FAILED on same edge
EDGE_FAILED used to express cross-edge runtime impact
impact edge ICE restart / SRD retry as if self L1 failure
```

### DOMAIN_BLOCKED minimum attribution tuple (all required)

```text
DOMAIN_BLOCKED {
  impactEdgeKey
  runtimeDomainRef
  contentionKind          = RUNTIME_DOMAIN_CONTENTION
  causeEdgeKey
  causeFact
  blockReason             = RUNTIME_DOMAIN_UNAVAILABLE
  generationScope         conferenceSessionId + meshGeneration (+ pcGeneration if edge-scoped)
  causePhase              HANGING_OBSERVED | EDGE_FAILED
}
```

**causeFact (at least one):**

```text
LEASE_HELD_BY_CAUSE       — preferred; holderEdgeKey == causeEdgeKey
NATIVE_DOMAIN_OBSTRUCTED  — shared worker/signaling domain unserviceable (PO-2b class)
```

**causeEdgeKey source priority:**

```text
LEASE_HELD_BY_CAUSE  >  NATIVE_DOMAIN_OBSTRUCTED

FORBIDDEN: UI / roster / topology inference alone
```

**Allowed ordering:**

```text
M03 still HANGING_OBSERVED → M04 DOMAIN_BLOCKED
provided observable causeFact exists
```

**Forbidden mis-attribution:**

```text
DOMAIN_BLOCKED with only reason=SRD_TIMEOUT on impact edge
DOMAIN_BLOCKED without runtimeDomainRef
causeEdgeKey from topology alone
```

### Example (4p, M01 anchor)

```text
M03: L1 EDGE_FAILED / HANGING_OBSERVED  (cause)
M04: L2 DOMAIN_BLOCKED                 (impact)
M02: operational on same runtimeDomainRef

= L2 only — NOT L3
```

### Recovery ownership

```text
Owner: domain admission authority (ConferenceNativeExecutionDomain / B2-1)
Impact edge: report impact fact only
Impact edge MUST NOT: declare L3, reset domain, perform domain lifecycle mutation
Primary recovery target: cause edge (L1) OR domain release fact
Impact edge MUST NOT proceed as independent L1 retry while DOMAIN_BLOCKED
```

**Cause edge ≠ domain owner.** Cause identity grants no teardown authority.

---

## L3 — Domain Failure

### Definition

| Field | Value |
|-------|--------|
| **Scope** | `runtimeDomainRef` (whole domain) |
| **Meaning** | Runtime domain has **no viable service path** |
| **Terminal** | `DOMAIN_FAILED` |
| **Attribution** | Domain authority declaration |
| **Answers** | "Is the runtime domain itself unavailable?" |

### L2 vs L3

```text
L2: domain may still serve some edges
L3: domain has no viable service path

DOMAIN_BLOCKED count ≠ DOMAIN_FAILED
LEASE_BUSY ≠ DOMAIN_FAILED
impact timeout ≠ DOMAIN_FAILED
```

### L2 → L3 evaluation inputs (T1–T4)

Evaluated by **domain admission authority only** — not self-upgrade triggers.

```text
T1  domainAdmissionExhausted
    bounded budget: no lease grant; holder unrecoverable; no operational edge on domain

T2  nativeDomainUnrecoverable
    NATIVE_DOMAIN_OBSTRUCTED at domain level (not single-edge hang only)

T3  operationalVacuum
    all bound edges L1 terminal or L2 DOMAIN_BLOCKED; no partial service possible

T4  boundedContentionBudgetExpired
    L2 persists beyond frozen domain contention budget; cause not self-resolved
```

```text
edge observation → domain authority evaluation → DOMAIN_FAILED
```

### DOMAIN_FAILED minimum tuple

```text
DOMAIN_FAILED {
  runtimeDomainRef
  failureKind             DOMAIN_UNRECOVERABLE | LEASE_ORPHANED | NATIVE_DOMAIN_DEAD
  causeEdgeKeys[]         optional aggregate — not sole authority
  affectedEdgeKeys[]
  generationScope
  declaredBy              domain admission authority ONLY
}
```

### Recovery ownership

```text
Declaration owner: domain admission authority ONLY
May own (implementation NOT AUTHORIZED here):
  domain evaluation · quiesce · lease forcible release · reset/teardown

L1 edge recovery: SUSPENDED while L3 active on shared runtimeDomainRef
L2 impact edge: report only — no domain lifecycle mutation
```

### L3 vs L4

```text
DOMAIN_FAILED ≠ Conference Failure
DOMAIN_FAILED → MediaUsable evaluation → L4 projection
```

---

## L4 — Conference Failure (projection)

### Definition

| Field | Value |
|-------|--------|
| **Scope** | Conference session room |
| **Meaning** | ConferenceHealth usability outcome |
| **Owner** | ConferenceHealth adjudicator (ADR-0056 Phase 3 seam) |
| **NOT** | Runtime fact · DOMAIN_FAILED alias · failure count aggregation |

### Projection layers

```text
L-domain        runtime facts (L1/L2/L3)
L-participant   mandatory participant-visible projection
L-conference    conditional ConferenceHealth by MediaUsable
```

**L-participant (mandatory on L2 impact):**

```text
DOMAIN_BLOCKED → impact participant MUST NOT remain CONNECTING / invisible
```

**L-conference (conditional):**

```text
DOMAIN_BLOCKED ≠ automatic Conference DEGRADED
Must pass MediaUsable evaluation (ADR-0056)
```

| MediaUsable | L4 projection |
|-------------|---------------|
| `true` | **ONLINE** (or minor degraded participant-only) |
| `false` + session Established + recoverable | **DEGRADED** |
| `false` + product terminal predicate + Health explicit | **CONFERENCE_FAILED** |

**L-conference must escalate when:**

```text
Anchor relay / admitted critical edge impaired
Same cause → multiple impact DOMAIN_BLOCKED
MediaUsable = false
```

**Need not escalate when:**

```text
Single non-critical participant DOMAIN_BLOCKED
MediaUsable = true
```

```text
DOMAIN_FAILED → DEGRADED          (common)
DOMAIN_FAILED → CONFERENCE_FAILED (conditional — NOT automatic)

domain count ≠ conference severity
failure facts → impact projection → MediaUsable → ConferenceHealth
```

### L4 forbidden ownership

```text
L4 MUST NOT:
  mutate topology
  trigger anchor election / failover
  bump meshGeneration
  create recovery edges from failure alone
  manufacture topology truth
```

---

## State reference table

| State | Level | Role in CFC-1 |
|-------|-------|-----------------|
| `EDGE_LOCAL_FAILURE` | input | B2-1 input — not terminal |
| `LEASE_BUSY` | input | runtime observation — not L2 terminal |
| `EDGE_FAILED` | L1 | Scenario E terminal |
| `DOMAIN_BLOCKED` | L2 | Scenario D terminal |
| `DOMAIN_FAILED` | L3 | Phase B — domain authority |
| `Conference DEGRADED` | L4 | Scenario C — conditional |
| `CONFERENCE_FAILED` | L4 | Scenario C — terminal |

---

## CFC-1 acceptance profile mapping

**CFC-1** = acceptance profile ([Decision 1](./conference-native-failure-containment-decision.md)). **NOT** a failure state.

```text
CFC-1 PASS = Scenario E ∧ Scenario D ∧ Scenario C

CFC-1 PARTIAL ≠ CFC-1 PASS
```

### Scenario E — Edge-local failure

```text
M03 native hang → M03 explicit EDGE_FAILED
bounded · attributable · no invisible CONNECTING
Decision 1: NOT required
```

### Scenario D — Domain contention propagation

```text
M03 cause → shared native domain impact → M04 DOMAIN_BLOCKED (full attribution)
bounded non-invisible wait on impact edge
cause → impact ordering auditable
Legalized by Option Z
```

### Scenario C — Conference projection

```text
L-participant complete on impact edges
ConferenceHealth matches MediaUsable
cause → impact → projection chain auditable
Phase B — requires product MediaUsable predicate
```

### Forbidden CFC-1 equivalences

```text
CFC-1 PASS ≠ P0.1g PASS
CFC-1 PASS ≠ Acceptance 1 PASS
CFC-1 PASS ≠ native per-edge SRD independence proven
DOMAIN_BLOCKED PASS ≠ CFC-1 PASS
CFC-1 PARTIAL (E only) ≠ fault containment PASS
```

---

## Failure propagation graph

Reverse of ADR-0056 downward truth flow (failure **facts** still flow down; recovery must not create topology truth):

```text
Membership
  → ConferenceTopologyAuthority
  → ConferenceTopologySnapshot
  → ActualMediaEdgeSet
  → Media edge runtime / native execution domain
  → Edge / domain failure facts (L1/L2/L3)
  → MediaUsable evaluation
  → ConferenceHealth (L4)
  → UI participant projection
```

**Domain Contention edge in graph:**

```text
cause edge L1/HANGING
  → runtimeDomainRef contention
  → impact edge L2 DOMAIN_BLOCKED
  → (optional) domain authority L3 evaluation
  → L-participant projection
  → MediaUsable → L4
```

---

## Phase A authorization boundary

Phase A is **not authorized** by this document. When [separately authorized](./conference-native-failure-containment-decision.md#phase-a-authorization-boundary-reference):

### IN SCOPE

```text
L1 EDGE_FAILED explicit terminal
L2 DOMAIN_BLOCKED + full attribution tuple
LEASE_BUSY → classified as input toward L2 (not terminal itself)
L-participant mandatory projection
cause → impact auditable telemetry / projection hooks
~20–30% Conference runtime: state / projection / observability
```

### OUT OF SCOPE

```text
native partition · D-β · D-γ · Factory/ADM · PCM relay
L3 DOMAIN_FAILED declaration / quiesce / teardown
L4 ConferenceHealth full implementation · MediaUsable definition
topology / anchor / meshGeneration / recovery / completion changes
ConferenceAudioBus · Commit 4 · P0.1g Acceptance 1 field rerun
B2-1 lease semantic changes
```

### PASS target

```text
Phase A PASS = CFC-1 PARTIAL PASS (E ∧ D)

NOT CFC-1 FULL PASS
NOT P0.1g PASS / closure
NOT Acceptance 1 PASS
NOT native isolation proven
```

### Phase A mis-authorization defenses

**Forbidden PASS wording:**

```text
P0.1g closed · native isolation achieved · fault containment complete
CFC-1 PASS (without PARTIAL qualifier when only E+D)
cross-edge SRD independence · Anchor Media Domain decouples native worker
```

**Required PR / run card tags:**

```text
Phase A · CFC-1 PARTIAL · E+D only
B2-1 RETAIN · no lease semantic change
Acceptance 1 HOLD · L3/L4 OUT OF SCOPE
```

**Audit gate:** Any P0.1g closure implication · CFC-1 FULL miswrite · topology/recovery drift → **INVALID PASS claim**.

### Phase dependency

```text
Phase A     → CFC-1 PARTIAL (E+D)
Phase B     → L3/L4 + Scenario C → CFC-1 FULL PASS gate
Future      → Acceptance 1 re-challenge if native path verified (does not retire CFC-1)
```

---

## Prohibited

```text
Treat DOMAIN_BLOCKED as EDGE_FAILED on impact edge
Treat LEASE_BUSY as DOMAIN_BLOCKED terminal
Aggregate DOMAIN_BLOCKED count → DOMAIN_FAILED
DOMAIN_FAILED → automatic CONFERENCE_FAILED
L4 → topology mutation
Assume Anchor Media Domain rename removes native coupling
Phase A PASS → P0.1g closure narrative
```

---

## Changelog

| Date | Version | Change |
|------|---------|--------|
| 2026-08-21 | v0.1 | Grill frozen: L1–L4 taxonomy · DOMAIN_BLOCKED attribution · CFC-1 mapping · Phase A boundary |
