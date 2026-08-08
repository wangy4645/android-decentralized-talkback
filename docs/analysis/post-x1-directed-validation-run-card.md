# Post-X1 Directed Validation Run Card (X1-A)

**Status:** AUTHORIZED — **Validation Gate ACTIVE** (post-merge)  
**Case:** `post-x1-directed-validation` / **X1-A**  
**Date:** 2026-08-08  
**Parent:** [X1 Validation Gate](./x1-validation-gate.md) · [X1-B progression](./x1-b-admission-progression-validation.md) · [ADR-X1](../adr/x1-control-admission-after-recovery.md) · [pre-fix run card](./post-adr0040-control-admission-validation-run-card.md)

**Goal:** Prove ADR-X1 **event path** on initiator edge — not generic WiFi smoke, not UI validation.

```text
REMOTE_RECEIPT_ACKED
        ↓
RECOVERY_CONTROL_ADMISSION_REEVALUATE
        ↓
RECOVERY_WATCHDOG_DEFERRED (when glare unresolved)
        ↓
(no premature FAILED_MEDIA_RECOVERY)
        ↓
CONTROL_BOUNDARY or legitimate terminal
```

**Not:** generic smoke · UI fix validation · X2 residency · membership fence

---

## Prior run (does not close gate)

| Run | Verdict | Why insufficient |
|-----|---------|------------------|
| `logs/post-x1-directed-20260808-070723/` | **INCONCLUSIVE** | `REATTACH_SENT` only; no `REMOTE_RECEIPT_ACKED`; X1 chain not exercised |

---

## Prerequisites

- APK from **main after PR #126 merge** (or equivalent `feat/x1-control-admission` commit)
- Three devices M01/M02/M03 · SSID `happy`
- Scripts: `post-x1-control-admission-start-run.ps1` · `post-x1-control-admission-adjudicate.ps1`

---

## X1-A scenario (glare-enhanced — contract-directed)

**Only M03 flaps.** M01/M02 stay on WiFi to maximize bilateral glare + receipt success.

```text
T0  Three-party conference stable on happy — no USER_LEAVE
    M01/M02: do NOT flap
T1  Only M03 WiFi OFF 15–30s → ON
T2  Soak ≥ 5 min (M02 may compete recovery while M03 initiates)
T3  Stop + adjudicate → collect [X1 Evidence Matrix](./x1-validation-gate.md#x1-evidence-matrix-contract-verification)
```

**Core condition:**

```text
single-edge recovery (M03)
+ remote competing recovery possibility
+ receipt success
+ bilateral glare
```

**Must observe on M03 (initiator → M02):**

```text
RECOVERY_REATTACH_SENT
REMOTE_RECEIPT_ACKED (or RECOVERY_REATTACH_RECEIPT)
RECOVERY_CONTROL_ADMISSION_REEVALUATE
RECOVERY_WATCHDOG_DEFERRED ... ADMISSION_PENDING   [expected under glare]
```

**Must NOT observe (premature failure after L1 chain):**

```text
RECOVERY_ATTEMPT_TIMEOUT
FAILED_MEDIA_RECOVERY ... failureClass=CONTROL_RECONCILIATION_TIMEOUT
```

(with receipt + reevaluate present before timeout)

---

## Acceptance tiers (L1 → L4)

| Tier | Check | Gate |
|------|-------|------|
| **L1 Control** | Receipt → `RECOVERY_CONTROL_ADMISSION_REEVALUATE` | **Required** |
| **L2 Attempt** | No premature timeout after L1 | **Required** |
| **L3 Recovery** | `CONTROL_PLANE_BOUNDARY` / `REATTACH_ACCEPTED` / legitimate terminal | **Required** |
| **L4 Presence** | RECONNECTING / SYNCING resolves | Observational only |

**GATE PASS:** L1 + L2 + L3 in valid ENV  
**GATE INCONCLUSIVE:** No receipt on initiator edge — rerun  
**GATE FAIL:** Receipt without reevaluate, or premature timeout regression

---

## Legacy O1–O4 mapping (adjudication script)

| Script | Maps to |
|--------|---------|
| O1 | L1 |
| O4 | L2 |
| O3 | L3 |
| O2 | Glare / E2 policy (supporting) |

| Script verdict | Gate meaning |
|----------------|--------------|
| `PASS_FULL` | L1+L2+L3 — **Gate PASS** |
| `PASS_PARTIAL` | L1+L2, L3 not observed — investigate; **Gate not closed** |
| `INCONCLUSIVE` | No receipt path — **rerun required** |
| `FAIL_*` | **Gate FAIL** — see Case A/B in [validation gate](./x1-validation-gate.md#failure-routing-frozen--do-not-reopen-rca) |

---

## Failure routing (quick reference)

| Case | Signal | Route |
|------|--------|-------|
| — | No receipt | INCONCLUSIVE — rerun glare-enhanced |
| A | Receipt, no reevaluate | Wiring only |
| B | Reevaluate, premature timeout | ADR-X1 predicate revision |
| C | Boundary OK, UI sticky | X2 may open |

---

## Log directory

```text
talkback/logs/post-x1-directed-YYYYMMDD-HHMMSS/
```
