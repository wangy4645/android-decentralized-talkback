# E16 Edge Lifecycle Contract v1

**Status:** `FROZEN` (analysis-level; not ADR promote yet)  
**Date:** 2026-08-04  
**Inputs:** [e16-edge-admission-gap-analysis.md](./e16-edge-admission-gap-analysis.md) · `ConferenceEdgeRecoveryController` (`admitSuccessorObligationEpisode` / `beginRecovery` / `admitSupersededRecoveryAttempt`) · field `E16-clean-161614`  
**Does not:** patch production · reopen DeferredIntentAuthority · accept `ADMIT_SUCCESSOR` as Joint edge-start · freeze Option A vs B emit shape

---

## One-line freeze

```text
Admission ≠ Activation ≠ Recovery Delivery ≠ Completion

ADMIT_SUCCESSOR is Admission evidence only.
Joint J-E / D1 requires authority-owned Activation evidence.
ADMIT_SUCCESSOR alone is insufficient.
```

---

## Layer table

| Layer | Means | Authority-owned evidence (examples) | Proves | Does **not** prove |
|-------|--------|--------------------------------------|--------|---------------------|
| **Admission** | Successor / new obligation episode accepted | `ADMIT_SUCCESSOR_OBLIGATION_EPISODE`; `openNewRecoveryObligation` → attempt open | Candidate accepted; obligation gen/attempt exist | Edge transport lifecycle started; peer edge transition; D1 may run |
| **Activation** | Edge recovery lifecycle start | `RECOVERY_EDGE_STARTED` **or** future equivalent activation event | Authority committed this edge into recovery lifecycle; Joint may arm J-E/D1 | Delivery succeeded; completion |
| **Recovery Delivery** | Media / offer / ICE path progressing | dispatch / L2 / D1 ingress-retry facts | Transport/recovery actions in flight | Episode complete |
| **Completion** | Obligation may close | CompletionPolicy / `RECOVERED` / close audit | Terminal success or audited close | That Activation ever happened |

---

## Joint binding

```text
ADMIT_SUCCESSOR          →  Admission fact
        |
        |  (required distinct transition)
        v
EDGE_START_EVIDENCE      →  Activation (authority-owned)
        |
        v
J-E / D1 lifecycle allowed
```

**Rejected:** treating `ADMIT_SUCCESSOR` as Activation substitute for Joint.  
**Not chosen yet:** whether Activation must be today's `RECOVERY_EDGE_STARTED` (Option A) or a distinct successor activation event (Option B).

Field subtype `EDGE_STARTED_NOT_EMITTED` remains the observation label for missing Activation on `M02→M03` under the current harness token; it does **not** freeze Option A as the only fix.

---

## Code map (as implemented today)

```text
admitSuccessorObligationEpisode
  → expireDeferred…(ADMIT_SUCCESSOR)
  → openNewRecoveryObligation          // Admission
  → log ADMIT_SUCCESSOR_OBLIGATION…    // Admission evidence
  → resolveMediaActionOwner / reattach // may enter Delivery without Activation log
  ✗ no RECOVERY_EDGE_STARTED

beginRecovery
  → open/upsert obligation
  → log RECOVERY_EDGE_STARTED          // Activation
  → ownership supersede gate
  → resolve / dispatch                 // Delivery

admitSupersededRecoveryAttempt
  → log RECOVERY_EDGE_STARTED pathway=SUPERSEDE  // Activation
  → ownership gate → resolve / dispatch
```

Comment frozen in code: `admission ≠ beginRecovery fusion` — consistent with separating Admission from Activation; it does **not** authorize skipping Activation for Joint.

---

## Open design (explicitly deferred)

| Option | Shape | Keeps |
|--------|--------|--------|
| **A** | After Admission, emit `RECOVERY_EDGE_STARTED` | Existing J-E token |
| **B** | Keep Admission separate; emit distinct activation (e.g. `SUCCESSOR_EDGE_ACTIVATED`) | Cleaner Admission/Activation split; needs evidence-contract extension |

Both must satisfy: **Activation is auditable, authority-owned, and distinct from Admission.**

---

## Non-goals

```text
Do not modify:
- CompletionPolicy / D1 / DeferredIntentAuthority / R4
- Field re-run
- Choosing A vs B in this document
- Claiming WiFi recovery "works" from Clean Attempt alone
```

---

## Next after this freeze

1. **In progress:** [e16-successor-activation-evidence-options.md](./e16-successor-activation-evidence-options.md) — ActivationEvidence schema + Q1–Q3; lean B, not frozen.  
2. Decide Option A vs B only after naming the **Activation producer** for the successor pathway (same owner: `ConferenceEdgeRecoveryController`).  
3. Then either emit / wire Activation, or extend Joint classifier to `ActivationEvidence` (union of kinds).  
4. No field until Activation contract has a concrete event name + producer.
