# E16 Activation Producer Contract

**Status:** `FROZEN` (red line + producer ownership); Option A/B emit shape still open  
**Date:** 2026-08-04  
**Parents:** [e16-successor-activation-evidence-options.md](./e16-successor-activation-evidence-options.md) · [e16-edge-lifecycle-contract-v1.md](./e16-edge-lifecycle-contract-v1.md)  
**Question:** Who may emit `ActivationEvidence`?

**Does not:** freeze Option B · patch production · change classifier to infer activation · field · reopen D1 / CompletionPolicy / J-X

---

## Red line (frozen)

```text
ActivationEvidence MUST be producer-emitted.

Classifier MUST NOT infer activation from admission,
delivery, or completion tokens.
```

Discipline (E.18 post):

```text
Authority owns the decision; evidence proves the state.
```

Forbidden shortcut (explicitly excluded):

```text
ADMIT_SUCCESSOR + OWNER_ASSIGNED + DELIVERY_READY
        =
    fake activation   // classifier must never do this
```

---

## Layer reminder

```text
Admission
    |
    v
Activation   <-- producer must emit here
    |
    v
Delivery
    |
    v
Completion
```

Joint today:

```text
FAIL_CLEAN_JOINT
reason   = J_E_D1_NOT_ESTABLISHED
subtype  = EDGE_STARTED_NOT_EMITTED
```

Remains correct: J-E needs ActivationEvidence (+ delivery), not Admission alone.

---

## Producer candidates

### Candidate 1 — EDGE pathway (exists today)

| | |
|--|--|
| **Owner type** | Runtime recovery authority |
| **Concrete type** | `ConferenceEdgeRecoveryController` |
| **Emit sites** | `beginRecovery` · `admitSupersededRecoveryAttempt` |
| **Token** | `RECOVERY_EDGE_STARTED` |
| **ActivationEvidence.kind** | `EDGE_STARTED` |
| **Pathway** | UPSERT_EDGE / ICE / SUPERSEDE |

**Status:** Valid producer for EDGE topology. Field-proven (e.g. M03→M01).

---

### Candidate 2 — Successor pathway (logical producer; emit missing)

| | |
|--|--|
| **Owner type** | Runtime recovery authority (successor admission → activation) |
| **Logical role** | Successor activation producer |
| **Concrete today** | Same class: `ConferenceEdgeRecoveryController.admitSuccessorObligationEpisode` |
| **Separate type?** | No `SuccessorAdmissionController` type in tree today — role is method-scoped, not a second runtime owner |
| **Token (proposed if Option B)** | `SUCCESSOR_ACTIVATED` |
| **Token (if Option A)** | `RECOVERY_EDGE_STARTED` after Admission, before Delivery |
| **Pathway** | `NEW_OBLIGATION_EPISODE` / `ADMIT_SUCCESSOR_*` |

**Status:** Admission producer exists; **Activation emit does not**.  
Producer *power* for Activation on this pathway still belongs to Session/Conference Recovery Authority (same controller), at a transition **after** Admission and **before** Delivery — not to classifier, not to Delivery.

Whether that emit is named `SUCCESSOR_ACTIVATED` (B) or reuses `RECOVERY_EDGE_STARTED` (A) is **not frozen** here.

---

### Candidate 3 — Joint classifier / harness inference

```text
ADMIT_SUCCESSOR + OWNER_ASSIGNED + DELIVERY_READY = activation
```

| | |
|--|--|
| **Owner type** | Evidence consumer |
| **Emit?** | None — derives labels only |

**Status:** **EXCLUDED.** Classifier consumes producer-emitted ActivationEvidence; it must not mint runtime fact.

Same exclusion for orchestrator provisional scoring and any archive reconstruction that invents Activation from Admission/Delivery bags.

---

## Producer ownership freeze

```text
Who may emit ActivationEvidence?
  ONLY Session/Conference Recovery Authority runtime
  (today: ConferenceEdgeRecoveryController)

Who must not emit / invent ActivationEvidence?
  Joint Evidence Classifier
  Orchestrator
  CompletionPolicy
  DeferredIntentAuthority
  Delivery / media-owner assignment alone
```

```text
Episode ownership     = present on successor path   (not enough)
Admission authority   = present                     (not enough)
Activation producer   = must emit distinct evidence (gap)
```

---

## Option A/B relative to producer (still lean B, not frozen)

| Option | Producer still | Emit token | Extra proof needed |
|--------|----------------|------------|--------------------|
| **A** | Same controller, after `admitSuccessor…` Admission | `RECOVERY_EDGE_STARTED` | That successor admission is essentially another EDGE start (evidence currently weak; risks R28 fusion freeze) |
| **B** | Same controller (or future extracted successor module), after Admission | `SUCCESSOR_ACTIVATED` | That successor pathway has its own activation fact (smaller claim; matches two topologies already seen) |

**Lean B (non-binding):** Recovery topology is not a single lifecycle (`EDGE` vs `Successor`). Forcing A requires proving semantic identity; B only requires a successor Activation fact. Risk smaller. **Not frozen.**

---

## Placement constraint (frozen regardless of A/B)

```text
admitSuccessorObligationEpisode:
    [Admission] open obligation + ADMIT_SUCCESSOR log
        |
        |  <-- ActivationEvidence MUST be emitted here (A or B token)
        |      before resolveMediaActionOwner / reattach delivery
        v
    [Delivery] owner assign / defer / dispatch
```

Emitting Activation as a side effect of Delivery tokens is forbidden (would invite Candidate-3 inference).

---

## Current E16 pin (unchanged)

```text
E16-J1:
    legal supersede? YES

Clean attempt:
    YES

Joint convergence:
    NOT ESTABLISHED

Root gap:
    missing activation evidence contract
    (producer ownership now named; emit token not frozen)

Next:
    successor activation transition definition — see
    e16-successor-activation-transition-definition.md (Contract v0 / S3)
    then choose token spelling; Option B still lean / not frozen
```

---

## Non-goals

```text
Do not:
- Field re-run
- Modify D1 / CompletionPolicy / J-X
- Promote ADMIT_SUCCESSOR to Activation
- Teach classifier to infer Activation
- Extract SuccessorAdmissionController class yet (optional later refactor; not required to freeze producer role)
```
