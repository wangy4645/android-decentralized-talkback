# X1-B Admission Progression Validation

**Status:** OPEN — target path NOT ENTERED (not adjudicated)  
**Date:** 2026-08-08  
**Parent:** [X1 Validation Gate](./x1-validation-gate.md) · [ADR-X1](../adr/x1-control-admission-after-recovery.md)  
**Run card:** [X1-B initiator-edge validation](./x1-b-initiator-edge-validation-run-card.md)

**Goal:** Verify initiator-edge admission progression after receipt on **M03→M02** when experiment preconditions are met.

---

## Frozen status board

```text
RCA-M03                     CLOSED
ADR-0040                    VERIFIED PASS
PR #126 / X1-A              VERIFIED PASS (CLOSED)
X1-B progression            OPEN
X1-B target path            NOT ENTERED (×4) — NOT ADJUDICATED
X1-B pass/fail              NOT PROVEN (either direction)
Scenario construction       INSUFFICIENT (current bottleneck)
Gate 0.5                    0.5-A + 0.5-B (disconnect-time owner check)
M03→M02                     SCENARIO_MISS ×4
M03→M01                     Result B ×4 (evidence only)
P-A                         candidate (not decided)
Bilateral glare             NOT VERIFIED
Implementation              FROZEN
X2                          HOLD
```

**Stage conclusion:** PR #126 duty complete. Remaining work is **experiment entry design** + **progression contract** observation — not implementation patch on current evidence.

**Stop:** blind replay of current topology. See run card Gate 0.5-B.

---

## Key architecture finding

```text
pre-flap ownership (localOwner=M03 in STABLE)
        ≠
disconnect-time recovery ownership (existing_owner=M02 at ICE_DISCONNECTED)
```

Four runs prove: **current experiment cannot stably construct X1-B required ownership topology.** This is ADR-0040 ownership selection behavior — **not** X1-B implementation failure.

---

## Two-phase model (frozen)

| Phase | Question | Status |
|-------|----------|--------|
| **X1-A** | Does `REMOTE_RECEIPT_ACKED` enter `reevaluateControlAdmission()`? | **VERIFIED PASS** |
| **X1-B** | After reevaluate, does admission reach boundary or legitimate terminal? | **OPEN — NOT ADJUDICATED** |

---

## Path split (do not merge)

| Path | Context | Status |
|------|---------|--------|
| **Path A** | `glareUnresolved=false` (M01 ×4) | Result B tendency — evidence only |
| **Path B** | `glareUnresolved=true` (M03→M02 target) | NOT VERIFIED — never entered |

---

## M03→M01旁证 (×4 — evidence only)

```text
REMOTE_RECEIPT_ACKED → REEVALUATE (admissionPending=true)
        → CONTROL_HANDSHAKE_PENDING → defer → FAILED_MEDIA_RECOVERY
```

| Confirmed | Not confirmed |
|-----------|---------------|
| X1-A wiring | X1-B defect |
| Pending state exists | Progression contract failure |
| Watchdog defer works | Bilateral glare path |

**Cannot upgrade to:** "X1-B defect confirmed" — expected completion path on target edge was never available.

---

## M03→M02 (×4 SCENARIO_MISS)

Every run: `existing_owner=M02` at disconnect → `ICE_RESTART_ONLY` → no reattach/receipt on target edge.

**Verdict:** NOT ADJUDICATED. Not X1-B FAIL.

---

## Result matrix (only after Gate 0.5-B + Gate 1 HIT)

| Result | Conclusion |
|--------|------------|
| **A** | X1-B PASS |
| **B** | X1-A PASS · X1-B OPEN / P-A candidate |
| **C** | X1-B glare contract issue |
| **0.5-B fail** | SCENARIO_MISS_BEFORE_TRIGGER |
| **Gate 1 miss** | SCENARIO_MISS |

---

## Discipline (frozen)

- Gate 0.5-A + 0.5-B required before flap
- No blind replay on saturated topology
- No X1.1 / predicate / watchdog patch
- No X2 without boundary + sticky presence
- M01旁证 does not authorize implementation change

**One-line gate question:**

> When M03 owns M03→M02 **at disconnect time**, does admission pending have a legal path toward completion?
