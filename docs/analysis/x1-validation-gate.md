# X1 Validation Gate

**Status:** ACTIVE (post-merge verification required)  
**Date:** 2026-08-08  
**Parent:** [ADR-X1](../adr/x1-control-admission-after-recovery.md) · PR #126 · [post-X1 run card](./post-x1-directed-validation-run-card.md)

## Gate verdict (frozen)

```text
PR #126 merge:           APPROVED
Merge conclusion:        NOT "WiFi problem fixed"
Field validation:      REQUIRED before RCA-M03 closure
X2 residency exit:       HOLD
```

**Rationale:** Implementation risk is no longer "wrong direction" but "implementation does not cover ADR-X1 state transitions." Merge unblocks mainline; closure requires receipt-driven event-path evidence.

---

## Phase boundary

| Phase | Status |
|-------|--------|
| RCA (diagnosis) | CLOSED |
| ADR-X1 (contract) | APPROVED |
| Implementation (PR #126) | MERGE APPROVED |
| Verification (directed field) | **PENDING** |
| X2 failed-media residency | HOLD |

Do **not** expand field trial-and-error. One directed validation run with explicit pass criteria.

---

## Merge gate (non-blocking)

PR #126 may merge when:

- Implementation review APPROVED (done)
- Unit tests green (desk)
- Scope boundary intact (no UI / X2 / watchdog budget / membership)

Merge **must not** close the WiFi recovery verification track. Issue / observation remains OPEN until Validation Gate PASS.

---

## Directed validation X1-A (post-merge)

**Objective:** Prove the **real event path**, not accidental WiFi flap luck.

```text
M03 WiFi flap
        ↓
M03 REATTACH_SENT (initiator edge)
        ↓
M02 REMOTE_RECEIPT_ACKED
        ↓
M02 simultaneous recovery (bilateral glare)
        ↓
observe contract chain on M03→M02 initiator edge
```

**Required log chain (M03, initiator):**

```text
REMOTE_RECEIPT_ACKED
        ↓
RECOVERY_CONTROL_ADMISSION_REEVALUATE
        ↓
RECOVERY_WATCHDOG_DEFERRED (ADMISSION_PENDING)   [when glare unresolved]
        ↓
NOT: premature ATTEMPT_TIMEOUT / CONTROL_RECONCILIATION_TIMEOUT
```

**Prior run (inconclusive):** `logs/post-x1-directed-20260808-070723/` — only `REATTACH_SENT`, no receipt; does **not** satisfy this gate.

---

## Acceptance tiers (L1 → L4)

Evaluate in order. **Do not use UI as primary pass signal.**

| Tier | Name | Pass when |
|------|------|-----------|
| **L1** | Control | `REMOTE_RECEIPT_ACKED` → `RECOVERY_CONTROL_ADMISSION_REEVALUATE` on initiator edge |
| **L2** | Attempt | No premature `FAILED_MEDIA_RECOVERY` / `CONTROL_RECONCILIATION_TIMEOUT` after L1 chain |
| **L3** | Recovery | `CONTROL_PLANE_BOUNDARY` or `REATTACH_ACCEPTED` or **legitimate** terminal failure |
| **L4** | Presence | `RECONNECTING` / long `SYNCING` resolves (observational only; not gate-blocking) |

**Gate PASS (closure):** L1 + L2 + L3 observed in same directed run with valid ENV (no `USER_LEAVE`).

**Gate INCONCLUSIVE:** No receipt on initiator edge — rerun; do not treat as pass.

**Gate FAIL:** Receipt without reevaluate, or premature timeout after receipt+reeval without legitimate terminal.

---

## Closure sequence (frozen)

```text
Step 1  Merge PR #126
Step 2  Keep verification track OPEN (do not declare WiFi fixed)
Step 3  Run X1-A directed validation (post-merge main APK)
Step 4  If L1+L2+L3 PASS → close RCA-M03 verification
Step 5  Passive field soak (Appendix B format)
Step 6  Only if media CONNECTED + FAILED_MEDIA_RECOVERY persists → open X2
```

---

## X2 hold rationale

```text
X1 fix path:
  control admission succeeds → no FAILED_MEDIA_RECOVERY entry

X2 path:
  residency exit after FAILED_MEDIA_RECOVERY already entered
```

Fixing X2 before X1 verification would conflate "bypassed failure entry" with "fixed residency exit."

---

## Status board

```text
RCA-M03 diagnosis        CLOSED
ADR-X1 contract          APPROVED
PR #126 implementation   MERGE APPROVED
Validation Gate          ACTIVE — field evidence REQUIRED
X2 residency             HOLD
WiFi "fixed" declaration BLOCKED until Gate PASS
```
