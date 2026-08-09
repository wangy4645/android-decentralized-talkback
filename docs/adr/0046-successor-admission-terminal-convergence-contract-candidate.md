# ADR-0046: Successor Admission Terminal Convergence Contract (Candidate)

## Status

**CANDIDATE** (2026-08-09) · **NOT ACCEPTED** · **Implementation NOT AUTHORIZED** · **Runtime NOT AUTHORIZED**

**Parents / observation:**

- [mobile-validation-successor-recovery-pending-observation.md](../analysis/mobile-validation-successor-recovery-pending-observation.md) — Q1–Q7 sealed; Q7 = **S2**
- [successor-recovery-lifecycle-sample-expansion-run-card.md](../analysis/successor-recovery-lifecycle-sample-expansion-run-card.md) — sample expansion; MISS_SETTLING ≥3
- [ADR-0045](./0045-post-obligation-failed-media-residency-clear-admission.md) — failed-media residency clear (**orthogonal**; do not amend)
- [ADR-0044](./0044-user-visible-connectivity-semantics-media-residency.md) — presentation (**orthogonal**; SYNCING follows recovering)
- [ADR-0038](./0038-recovery-completion-admission-contract.md) — completion success predicate (**orthogonal**; do not reopen casually)

```text
ADR-0046
Decision:                 CANDIDATE ONLY (not accepted)
Question in scope:        Must successor admission carry a provable
                          terminal convergence contract?
Question OUT of scope:    How to make it converge (watchdog / timeout /
                          retry / SuccessorPolicy / FSM / UVCP)
Implementation:           NONE
Runtime:                  NONE
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

Still **UNKNOWN** (not required to open this candidate):

```text
SUCCESS terminal reachability after ADMIT_SUCCESSOR
FAILED_MEDIA terminal reachability after ADMIT_SUCCESSOR
```

Side observation (**not imported** into this candidate):

```text
M03→M01 peer RECONNECTING / non-convergence during DUT flap soak
→ separate observation track
→ do not merge as "Recovery broken"
```

---

## Decision question (candidate only)

> **Must successor admission (`ADMIT_SUCCESSOR` / new obligation episode) carry a provable terminal convergence contract?**

That is: after successor admission opens recovering residency, does architecture require a defined obligation that the edge eventually reach an allowed terminal under Recovery Controller / CompletionPolicy-family writers — rather than remaining indefinitely in settling residency?

This candidate **does not** decide the answer yet. It only authorizes naming the question as an ADR-track concern.

---

## Out of scope (frozen until a future Accepted ADR + separate impl authorization)

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
```

---

## Allowed next docs (if continued)

```text
1. Grill questions that force a yes/no on the Decision question
2. Terminal set naming (observation-aligned; no new writers invented here)
3. Explicit non-goals vs ADR-0045 / 0038 / 0044

NOT allowed next:
  design of convergence mechanism
  code / tests / field “fix validation”
```

---

## Consequences if later Accepted (placeholder — not binding)

*Would* establish a named lifecycle contract boundary for successor admission.  
*Would not* by itself authorize watchdog, timeout, or any runtime patch.

*(Empty until Acceptance grill completes.)*

---

## References

- Observation seal Q1–Q7: [mobile-validation-successor-recovery-pending-observation.md](../analysis/mobile-validation-successor-recovery-pending-observation.md)
- Sample expansion run card: [successor-recovery-lifecycle-sample-expansion-run-card.md](../analysis/successor-recovery-lifecycle-sample-expansion-run-card.md)
