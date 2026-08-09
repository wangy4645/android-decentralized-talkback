# ADR-0046: Successor Admission Terminal Convergence Contract

## Status

**ACCEPTED** (2026-08-09) · **Acceptance Q1 = A1** · **Impl-Auth re-open = AUTHORIZED (Planning / Design Proposal)** · **Runtime NOT AUTHORIZED** · Design Decision pending

**Parents / observation:**

- [mobile-validation-successor-recovery-pending-observation.md](../analysis/mobile-validation-successor-recovery-pending-observation.md) — Q1–Q7 sealed; Q7 = **S2**
- [successor-recovery-lifecycle-sample-expansion-run-card.md](../analysis/successor-recovery-lifecycle-sample-expansion-run-card.md) — sample expansion; MISS_SETTLING ≥3
- [adr0046-implementation-proposal-entry.md](../analysis/adr0046-implementation-proposal-entry.md) — Impl-Auth re-open proposal entry
- Decision YES grill: see [Grill Appendix](#grill-appendix--decision-yes-semantic-closure-historical)
- [ADR-0045](./0045-post-obligation-failed-media-residency-clear-admission.md) — failed-media residency clear (**orthogonal**; do not amend)
- [ADR-0044](./0044-user-visible-connectivity-semantics-media-residency.md) — presentation (**orthogonal**; SYNCING follows recovering)
- [ADR-0038](./0038-recovery-completion-admission-contract.md) — completion success predicate (**orthogonal**; do not reopen casually)

```text
ADR-0046
Lifecycle:                ACCEPTED
Normative content:        Decision YES semantic boundary only
Acceptance:               A1 (2026-08-09)
Impl-Auth:                RE-OPENED (2026-08-09) — Planning / Design Proposal
Implementation (runtime): NOT AUTHORIZED until Design Decision
Planning / Proposal:      OPEN — adr0046-implementation-proposal-entry.md
Runtime:                  NONE (code unchanged)
Does NOT amend:           ADR-0045 · ADR-0038 · ADR-0044 · ADR-0022
Does NOT absorb:          M03→M01 peer non-convergence side observation
```

---

## Context (observation facts only)

Repeatable path (M02→M03; ≥3 independent field episodes, including long soak):

```text
ADMIT_SUCCESSOR
    ↓
RECOVERY_PENDING
    ↓
NEGOTIATION_SETTLING (defer reason)
    ↓
obligationOpen=true
    ↓
finalPresence=SYNCING
    ↓
no observed self-driven terminal exit in window
```

Evidence roots:

| Run | LogDir | Note |
|-----|--------|------|
| Field #2 | `logs/adr0045-field-20260809-094259/` | first settling miss |
| Sample #1 | `logs/successor-sample-20260809-101954/` | corroboration |
| Sample #2 | `logs/successor-sample-20260809-102648/` | long soak (~5m); still miss |

```text
MISS_SETTLING = repeatable observation
≠ single field anomaly
≠ presentation / UVCP defect
≠ ADR-0045 residency-clear case
≠ ICE-driven terminal transition case
```

Still **UNKNOWN** (not required for Acceptance):

```text
SUCCESS terminal reachability after ADMIT_SUCCESSOR
FAILED_MEDIA terminal reachability after ADMIT_SUCCESSOR
```

Side observation (**not imported** into this ADR):

```text
M03→M01 peer RECONNECTING / non-convergence during DUT flap soak
→ separate observation track
→ do not merge as "Recovery broken"
```

---

## Decision (normative)

> **Successor admission (`ADMIT_SUCCESSOR` / new obligation episode) must carry a provable terminal convergence contract.**

### Semantic constraints (T1'–O1')

| Id | Normative meaning |
|----|-------------------|
| **T1'** | Binding time = **admission**. The contract auditably binds the new episode into a terminal convergence **obligation class**. It does **not** prove that SUCCESS will occur. |
| **U1'** | **Every** `ADMIT_SUCCESSOR` / new successor obligation episode must carry the contract. The requirement is unconditional on post-admit defer, immediate dispatch, SUCCESS, or FAILED_MEDIA. |
| **S1'** | The obligation class must include **non-purely-external** terminal convergence semantics. **LEAVE / CANCEL alone do not satisfy** the contract. |
| **P1'** | `provable` = admission-time **auditable existence and attribution** of the contract — not runtime proof that a terminal already occurred, and not formal liveness verification. |
| **X1'** | `SUCCESSOR_REPLACED` / supersede is **lineage / episode-identity replacement**. It may end prior open attribution; it does **not** by itself satisfy the prior episode’s S1' obligation. The new episode must carry its own contract (U1'). |
| **O1'** | Scope is strictly **successor admission contract**. This ADR does **not** modify, imply, or authorize amendment of ADR-0038 completion success predicate, ADR-0045 residency-clear gate, or ADR-0044 SYNCING/recovering projection. It does **not** absorb M03→M01 side observation. |

```text
ADMIT_SUCCESSOR
        |
        v
must carry provable terminal convergence contract
        |
        +-- binding time: admission (T1')
        +-- scope: every successor admission (U1')
        +-- strength: non-purely-external obligation (S1')
        +-- provable: auditable contract identity at admit (P1')
        +-- replacement ≠ prior satisfaction (X1')
        +-- orthogonal to 0038 / 0045 / 0044; no M03→M01 merge (O1')
```

### Referenced terminal names (names only)

Observation-aligned **name references** for discussing the obligation class. This section does **not** define production paths, triggers, budgets, or writers beyond the existing Recovery / CompletionPolicy family ownership already established in observation (B1).

```text
Referenced (non-exhaustive naming):
  SUCCESS
  FAILED_MEDIA
  FAILED_TERMINAL

External (do not alone satisfy S1'):
  CANCELLED
  LEAVE

Lineage (X1' — not prior-episode S1' satisfaction):
  SUCCESSOR_REPLACED
```

```text
Allowed here:   name the reference set
Forbidden here: when / who / how to enter or exit any terminal
```

---

## Out of scope (remains frozen)

```text
✗ how SUCCESS is produced
✗ how FAILED_MEDIA is produced
✗ watchdog / timeout / retry tuning
✗ SuccessorPolicy
✗ RECOVERY_PENDING / FSM rewrite
✗ SYNCING / UVCP / EndpointStatus change
✗ ICE auto-terminalization
✗ ADR-0045 / ADR-0038 / ADR-0044 amendment
✗ absorbing M03→M01 peer edge into this ADR
✗ Implementation / runtime change of any kind
  (Accepted does not grant Implementation authorization)
```

---

## Consequences

**Establishes:**

- A normative lifecycle contract boundary: successor admission must carry a provable terminal convergence contract under T1'–O1'.
- A governance fence: Acceptance ≠ Implementation authorization ≠ convergence design.

**Does not establish / authorize:**

- Any runtime patch, watchdog, timeout, retry, SuccessorPolicy, FSM, UVCP, or ICE behavior change.
- Any amendment to ADR-0038 / 0045 / 0044.
- Absorption of M03→M01 peer non-convergence.

**Next governance:**

```text
Design Decision (proposal entry)
        |
        v
(if approved) Runtime Implementation authorization
        |
        ≠ skip to code from this re-open alone
```

---

## Acceptance record

| Item | Result |
|------|--------|
| Acceptance Q1 | **A1 — Accept now** |
| Normative content | Decision YES semantic boundary (T1'–O1') |
| Document lifecycle | **ACCEPTED** |
| Implementation | **NOT AUTHORIZED** |
| Runtime | **NONE** |
| P0 SUCCESS / FAILED_MEDIA field proof | **Not** an Acceptance precondition (P1') |

```text
ADR-0046 Candidate
        |
        | Acceptance Q1 = A1
        v
ADR-0046 ACCEPTED
        |
        +--> Normative boundary established
        |
        +--> Implementation authorization: NOT GRANTED
        |
        +--> Runtime changes: NONE
```

---

## Implementation Authorization record

### Impl-Auth Q1 (historical)

| Item | Result |
|------|--------|
| Impl-Auth Q1 | **I2 — Hold** (2026-08-09) |
| Note | Superseded by re-open below |

### Impl-Auth re-open (2026-08-09)

| Item | Result |
|------|--------|
| Trigger | Explicit owner authorization: start implementation workstream |
| Owner | Explicit (session grant) |
| Proposal entry | [adr0046-implementation-proposal-entry.md](../analysis/adr0046-implementation-proposal-entry.md) |
| Validation entry | Post-design successor-path compliance field (see proposal) |
| Planning / Design Proposal | **AUTHORIZED** |
| Design Decision | **Q1=M1+M2 · Q2=B1'+V' · Q3=C1' · DP-ACCEPT** |
| C1' package | [adr0046-design-proposal-c1.md](../analysis/adr0046-design-proposal-c1.md) **ACCEPTED** |
| Runtime Authorization | **Q1=RA1 · Q2=S2' · Q3=G1'+P1'–P3' · Q4=F1** |
| Scope | S2' — successor-admitted admit + defer evaluability only |
| Merge gate | G1' — S2' + D1–D6 + P1'–P3'; T-field' post-merge |
| Direct code / PR | **AUTHORIZED within F1 Allow** (merge blocked until G1') |
| How-to-converge detail | Enter only via Design Proposal detail — not from re-open alone |

```text
ADR-0046 ACCEPTED
        |
        +-- normative boundary ✅
        |
        +-- Impl-Auth re-open ✅
        |         |
        |         +-- Planning / Design Proposal AUTHORIZED
        |         |
        |         +-- Runtime ❌ until Design Decision
        |
        +-- mechanism not yet chosen
```

---

## Grill Appendix — Decision YES Semantic Closure (historical)

**Date:** 2026-08-09  
**Role:** Historical record of Decision YES semantic grill (Q1–Q7). Superseded for lifecycle status by Acceptance A1; semantic content promoted into [Decision](#decision-normative).

| Q | Id | Adjudication |
|---|-----|--------------|
| Q1 | **T1'** | Admission-time binding; not SUCCESS guarantee. |
| Q2 | **U1'** | Every successor admission; not path-conditioned. |
| Q3 | **S1'** | Non-purely-external obligation; LEAVE/CANCEL alone insufficient. |
| Q4 | **P1'** | Provable = auditable contract at admit; not terminal field proof. |
| Q5 | **X1'** | Replacement ≠ prior satisfaction. |
| Q6 | **O1'** | Orthogonal to 0038 / 0045 / 0044; no side-edge merge. |
| Q7 | **D1'** | Decision YES sealed while still CANDIDATE (superseded by Acceptance A1 for lifecycle). |

---

## References

- Observation seal Q1–Q7: [mobile-validation-successor-recovery-pending-observation.md](../analysis/mobile-validation-successor-recovery-pending-observation.md)
- Sample expansion run card: [successor-recovery-lifecycle-sample-expansion-run-card.md](../analysis/successor-recovery-lifecycle-sample-expansion-run-card.md)
- Prior candidate path (retired filename): `0046-successor-admission-terminal-convergence-contract-candidate.md`
