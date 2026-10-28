# E16 Phase-3 Field Authorization Gate v1

**Status:** `CONSUMED` — Attempt #1 → `PASS_JOINT_D1_C`; see [e16-phase3-closure-field-verified.md](./e16-phase3-closure-field-verified.md)  

**Date:** 2026-08-04  
**Attempt name:** `E16 Phase-3 Field Attempt #1`  
**Parents:** [e16-successor-activation-implementation-scope.md](./e16-successor-activation-implementation-scope.md) · [e16-clean-attempt-run-card.md](./e16-clean-attempt-run-card.md) · [e16-successor-activation-token-decision.md](./e16-successor-activation-token-decision.md)

**Does not authorize:** WiFi soak · L.1.5 · J-X reopen · CompletionPolicy/D1/DeferredIntentAuthority changes · multi-run campaign

---

## Gate checklist (all required)

| Gate | Status |
|------|--------|
| IMPLEMENTATION_PASS (emit-only slice) | **PASS** |
| UT_PASS (T0/T1/idempotent + EDGE sample T3) | **PASS** |
| CLASSIFIER_PASS (ActivationEvidence consume) | **PASS** |
| REPLAY_NO_REGRESSION (E16-J1 / 201352 / clean-161614) | **PASS** |
| RUN_CARD_SHAPE_VALID (EXECUTE_BEFORE_EDGE + F0–F5) | **PASS** (operator must re-check F0–F5 at start) |
| FIELD_AUTHORIZATION | **SIGNED — one attempt** |

```text
IMPLEMENTATION_PASS
    && UT_PASS
    && CLASSIFIER_PASS
    && REPLAY_NO_REGRESSION
    && RUN_CARD_SHAPE_VALID
=> one authorized field attempt
```

---

## Attempt spec

```text
E16 Phase-3 Field Attempt #1

Shape:
  EXECUTE_BEFORE_EDGE

LogDir suggestion:
  logs/joint-d1-c-e16-p3-act-YYYYMMDD-HHMMSS

Orchestrator:
  scripts/run-joint-d1-c-orchestrator.ps1
    -ExerciseShape EXECUTE_BEFORE_EDGE
    (+ existing D1 arm / flap operator protocol from run card)
```

### Expected evidence spine

```text
CREATED → HELD → EXECUTED
  → FLAP
  → ADMIT_SUCCESSOR (if successor pathway)
  → RECOVERY_SUCCESSOR_STARTED   // or RECOVERY_EDGE_STARTED on EDGE pathway
  → DELIVERY …
  → classifier J-E / joint outcome
```

If `ActivationEvidence` missing after EXECUTED + ownership PASS → reopen producer (Case C), not D1 first.

---

## Success criteria

### Primary (required for “useful field”)

```text
CLEAN_ATTEMPT_ESTABLISHED = true
+
ActivationEvidence exists
  (ACTIVATION_KIND in { SUCCESSOR_START, EDGE_START })
+
J-E classified / explainable
```

### Stretch (not required)

```text
PASS_JOINT_D1_C
```

---

## Outcome classes (all valid science)

### Case A — full joint

```text
ActivationEvidence
  → D1/C chain
  → J-A..J-F PASS
=> PASS_JOINT_D1_C
```

### Case B — activation-complete failure (still success for this gate)

```text
ActivationEvidence present
  → D1/convergence still fails
=> FAIL_CLEAN_JOINT
   reason = J_E_FAIL   (not J_E_D1_NOT_ESTABLISHED)
```

Value: failure is now **post-activation**, not “why didn’t it start.”

### Case C — producer regression (reopen activation)

```text
EXECUTED + ownership PASS
  + no ActivationEvidence
=> do NOT analyze as D1-primary; reopen SUCCESSOR_START emit / pathway selection
```

---

## Explicit non-goals (stay frozen)

```text
Do not:
- Expand J-X
- Patch CompletionPolicy / DeferredIntentAuthority / D1 retry
- Change Recovery Authority ownership model
- Run WiFi soak / L.1.5 under E16
- Require PASS_JOINT_D1_C for this attempt to “count”
```

Question under test:

> Does ActivationEvidence make the joint recovery episode fully observable / classifiable?

---

## Operator note

Re-run Field Readiness Gate F0–F5 from [e16-clean-attempt-run-card.md](./e16-clean-attempt-run-card.md) immediately before start. Fail any F* ⇒ abort without scoring product.

Build under test must include the Activation emit slice (`RECOVERY_SUCCESSOR_STARTED`).

---

## After the attempt

1. Archive LogDir + `JOINT_EVIDENCE_CLASSIFICATION.txt`  
2. Classify outcome as Case A / B / C above  
3. Do **not** auto-authorize a second field without a new gate sign-off