# E16 Successor Activation Implementation Scope

**Status:** `IMPLEMENTED` + field **FIELD_VERIFIED** — [e16-phase3-closure-field-verified.md](./e16-phase3-closure-field-verified.md)  

**Date:** 2026-08-04  
**Parents:** [e16-successor-activation-token-decision.md](./e16-successor-activation-token-decision.md) · [e16-activation-producer-contract.md](./e16-activation-producer-contract.md)  
**Phase:** Activation implementation (after contract freeze; before field)

**Does not authorize:** broad recovery/transport/completion repair · field soak · ADR promote of unrelated seams

---

## Purpose

Freeze a **narrow implementation slice** so emit landfall cannot expand into recovery, transport, or completion work.

```text
Allowed goal:
  emit ActivationEvidence for SUCCESSOR topology

Forbidden goal:
  "fix Joint while we are here"
```

---

## Allowed modifications

### Production (runtime)

```text
ConferenceEdgeRecoveryController
    admitSuccessorObligationEpisode()
```

Add **only**:

```text
emit RECOVERY_SUCCESSOR_STARTED
  -> ActivationEvidence.kind = SUCCESSOR_START
```

Placement (already frozen):

```text
ADMIT_SUCCESSOR logged
        |
        v
RECOVERY_SUCCESSOR_STARTED     // THIS SLICE
        |
        v
delivery prelude (unchanged logic)
```

Optional companion in **same method only** if required for schema completeness (session/remote/obligationGen/attempt/pathway/trigger). No new public APIs unless unavoidable for logging.

### Evidence consumers (harness)

Allowed **after** runtime emit exists:

```text
scripts/classify-joint-d1-c.ps1
scripts/run-joint-d1-c-orchestrator.ps1   // live wait may observe either activation event
```

Classifier rule (frozen):

```text
activationEvidence = true  iff producer event present:
  RECOVERY_EDGE_STARTED     -> EDGE_START
  RECOVERY_SUCCESSOR_STARTED -> SUCCESSOR_START

ADMIT_SUCCESSOR alone -> activationEvidence = false
```

Harness changes must not invent ActivationEvidence from Admission/Delivery/Completion.

---

## Forbidden modifications (this slice)

```text
CompletionPolicy
NEGOTIATION_CAN_EXECUTE
DeferredIntentAuthority
D1 retry / D1 production ownership
PR5-2c-C
R4 / negotiation drain design
BIDIRECTIONAL_READY / reachability / transport
beginRecovery / SUPERSEDE EDGE path behavior (must remain unchanged)
openNewRecoveryObligation semantics (except calling order already fixed)
```

Do **not** "borrow the slice" to fix recovery, transport, or completion.

---

## Required tests (minimum three classes)

### T1 — Admission → Activation → Delivery order

```text
ADMIT_SUCCESSOR
  <
RECOVERY_SUCCESSOR_STARTED
  <
DELIVERY prelude tokens
  (e.g. MEDIA_OWNER_ASSIGNED / MEDIA_ACTION_DEFERRED / dispatch)
```

Must fail if Activation is missing, before Admission, or after first Delivery success as the only emit site.

### T2 — Classifier consumes producer only

| Input | `activationEvidence` |
|-------|----------------------|
| `RECOVERY_SUCCESSOR_STARTED` present | **true** |
| `ADMIT_SUCCESSOR` only | **false** |
| Delivery / ready / completion tokens only | **false** |

### T3 — EDGE pathway unchanged

```text
EDGE_START path:
  RECOVERY_EDGE_STARTED still emitted as today
  no regression on beginRecovery / SUPERSEDE
```

Successor emit must not alter EDGE emit conditions or ownership supersede gate at EDGE_STARTED.

---

## Field gate (after implementation)

Do **not** field immediately after code change.

```text
Implementation
      |
      v
Unit / UT (T1–T3)
      |
      v
Artifact classifier validation
      |
      v
one authorized field
```

### First field objective (not full Joint PASS)

```text
CLEAN_ATTEMPT_ESTABLISHED
  +
ActivationEvidence (SUCCESSOR_START on authority edge under test)
  +
J-E pass/fail interpretable under ActivationEvidence contract
```

**Not required** on first post-emit field:

```text
PASS_JOINT_D1_C
```

Archive `E16-clean-161614` verdict remains historically correct under pre-emit EDGE-only gate.

---

## Current pin

```text
E16-J1:                 legal supersede YES
Clean attempt:          established YES
Activation contract:    FROZEN
Production gap:         closed for emit (runtime + harness consumption)
Next:                   one authorized field (after explicit field gate)
```

---

## Exit criteria for this slice

```text
[x] Runtime emit at frozen site with schema fields
[x] T0 negative / denied admission emits nothing
[x] T1 order UT green (SuccessorObligationAdmissionTest)
[x] T2 classifier goldens (SUCCESSOR_STARTED=YES; ADMIT-only=NO; historical clean=NO)
[x] T3 EDGE emit path sample UT green (reattach/EDGE_STARTED); beginRecovery untouched
[x] Historical classifier replay: E16-J1 / 201352 / clean-161614 verdicts unchanged
[x] Status: implementation DONE; field NOT authorized
```

Note: some `ConferenceEdgeRecoveryControllerTest` RECOVERED-path cases fail without this slice (pre-existing); not in T3 scope.

Field authorization remains a **separate** explicit step after the gate above.