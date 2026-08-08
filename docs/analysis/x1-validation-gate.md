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

**Rationale:** X1-A (event wiring) is **PASS** on field evidence. Gate remains OPEN for **X1-B** (admission progression) and bilateral glare contract. See [X1-B validation](./x1-b-admission-progression-validation.md).

---

## Two-phase X1 model (frozen)

```text
X1-A  Event graph (CLOSED)
      Delivery fact → Admission reevaluation ✅

X1-B  Progression graph (OPEN)
      reevaluate → ADMISSION_PENDING → ? → CONTROL_BOUNDARY / terminal
```

| Track | Status | Evidence |
|-------|--------|----------|
| **X1-A** Delivery → Reevaluation | **PASS** | M03→M01 attempt-3: `REMOTE_RECEIPT_ACKED` → `RECOVERY_CONTROL_ADMISSION_REEVALUATE` |
| **X1-B** Admission Progression | **OPEN** | Same run: reeval yes, no `CONTROL_PLANE_BOUNDARY`; `MEDIA_PATH_FAILED` |
| **Bilateral glare contract** | **NOT VERIFIED** | `glareUnresolved=false` on attempt-3; M03→M02 no reattach |
| **X2 residency** | **HOLD** | Same upstream region; not a new residency defect |

**Wrong attribution (do not write):** "X1没修好" · "PR #126 failed" · reopen merge/APK/UI investigation.

**Correct:** Phase 1 gap fixed; Phase 2 progression under observation.

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

## X1 Evidence Matrix (contract verification)

Do **not** ask only "did recovery succeed." Ask whether the system followed the X1 state machine.

Record on **M03 initiator edge → M02** (primary):

| # | Evidence | Expected | Required |
|---|----------|----------|----------|
| E1 | `REATTACH_REQUESTED` / `RECOVERY_REATTACH_SENT` | present | **yes** |
| E2 | `REMOTE_RECEIPT_ACKED` (or `RECOVERY_REATTACH_RECEIPT`) | present | **yes** |
| E3 | `RECOVERY_CONTROL_ADMISSION_REEVALUATE` | after E2 | **yes** |
| E4 | Admission state | `DELIVERED_BUT_NOT_ADMITTED` (implicit: receipt + `controlPlaneStarted=false`) | supporting |
| E5 | `DROP_OWNERSHIP_CONFLICT` / glare facts | expected under bilateral glare | supporting |
| E6 | E2 shortcut (`REATTACH_MEDIA_ALREADY_LIVE` boundary on initiator) | **suppressed** when glare unresolved | supporting |
| E7 | `RECOVERY_WATCHDOG_DEFERRED` + `ADMISSION_PENDING` | expected when glare unresolved | supporting |
| E8 | `CONTROL_PLANE_BOUNDARY` or `REATTACH_ACCEPTED` | terminal success path | **yes** (L3) |
| E9 | `FAILED_MEDIA_RECOVERY` / `CONTROL_RECONCILIATION_TIMEOUT` | **must not** appear after E2+E3 without legitimate terminal | **yes** (L2) |

**Interpretation table (frozen):**

| E2 receipt | E3 reevaluate | E9 premature fail | Meaning |
|------------|---------------|-------------------|---------|
| no | — | — | **INCONCLUSIVE** — delivery-failure path; does not validate X1 |
| yes | no | — | **FAIL Case A** — wiring incomplete |
| yes | yes | yes | **FAIL Case B** — predicate insufficient |
| yes | yes | no + E8 | **GATE PASS** |
| yes | yes | no, no E8 | **PASS_PARTIAL** — investigate ownership resolution |

---

## Failure routing (frozen — do not reopen RCA)

| Case | Observation | Route | Do NOT |
|------|-------------|-------|--------|
| **A** | `REMOTE_RECEIPT_ACKED` without `REEVALUATE` | PR #126 wiring: event source · callback · coordinator bridge | Touch FSM / X2 / UI |
| **B** | `REEVALUATE` present but still premature timeout | ADR-X1 revision (predicate) | Open X2 |
| **C** | `CONTROL_BOUNDARY` success but UI sticky | **First** point X2 residency may open | Patch UI as workaround |

**INCONCLUSIVE** (no receipt): rerun with glare-enhanced stimulus — not a code failure.

---

## Glare-enhanced stimulus (X1-A)

Do **not** use plain WiFi flap alone. Maximize bilateral glare + receipt success probability:

```text
T0  Three-party conference stable on SSID happy — M01/M02/M03 all ONLINE
    M01/M02: do NOT flap WiFi
T1  Only M03 WiFi OFF 15–30s → ON
T2  Soak ≥ 5 min (allow M02 competing recovery while M03 initiates)
T3  Stop + adjudicate (M03 log, edge M03→M02)
```

X1 core condition:

```text
single-edge recovery (M03)
+ remote competing recovery possibility (M02/M01 stay up)
+ receipt success
+ bilateral glare
```

---

## Post-GATE-PASS: X2 disposition

If Gate PASS shows:

```text
REMOTE_RECEIPT_ACKED → REEVALUATE → CONTROL_BOUNDARY → CONNECTED
```

Then X2 (`FAILED_MEDIA_RECOVERY` residency exit) **remains HOLD** and downgrades to:

```text
latent defensive contract (not active user-path bug)
```

Do **not** open X2 for state-machine completeness. Open X2 only on **Case C** field evidence.

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

## Architecture sign-off (frozen)

```text
RCA-M03                          CLOSED
ADR-0040                         VERIFIED PASS
ADR-X1                           IMPLEMENTED
X1-A Delivery → Reevaluation     PASS
X1-B Admission Progression       OPEN
Bilateral Glare Contract         NOT VERIFIED
X2 Residency                     HOLD
Gate                             OPEN (X1-B + bilateral)
```

**Do not during verification:** change timeout budget · open X2 · patch UI · declare WiFi fixed.

**Stage:** PR #126 proved delivery is handled; verifying whether admission completes after reevaluation.

See also: [X1-B admission progression validation](./x1-b-admission-progression-validation.md)
