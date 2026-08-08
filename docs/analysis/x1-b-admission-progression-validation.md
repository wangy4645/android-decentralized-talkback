# X1-B Admission Progression Validation

**Status:** OPEN (observation only — no code change)  
**Date:** 2026-08-08  
**Parent:** [X1 Validation Gate](./x1-validation-gate.md) · [ADR-X1](../adr/x1-control-admission-after-recovery.md)  
**Field evidence:** `logs/post-x1-directed-20260808-123422/` (M03→M01 attempt-3)

**Goal:** Verify **admission progression** after receipt-driven reevaluation — not WiFi smoke, not UI.

```text
X1-A (CLOSED)                    X1-B (OPEN)
Delivery fact                         reevaluate
      ↓                                    ↓
Admission reevaluation ✅              ADMISSION_PENDING
      ↓                                    ↓
                                         ? progression trigger ?
      ↓                                    ↓
                                   CONTROL_BOUNDARY / terminal
```

---

## Two-phase model (frozen)

| Phase | Name | Question | Status |
|-------|------|----------|--------|
| **X1-A** | Event graph | Does `REMOTE_RECEIPT_ACKED` enter `reevaluateControlAdmission()`? | **PASS** (M03→M01 attempt-3) |
| **X1-B** | Progression graph | After reevaluate, does admission reach boundary or legitimate terminal? | **OPEN** |

**Do not regress attribution:** PR #126 did not fail. Old bug (receipt with zero reaction) is **closed**.

---

## X1-B open hypothesis (candidate only)

After reevaluate, attempt may remain in `DELIVERED_BUT_NOT_ADMITTED` / `ADMISSION_PENDING` with no subsequent admission evaluation until watchdog terminal.

**Candidate causes (mutually exclusive until proven):**

| ID | Hypothesis | Evidence needed |
|----|------------|-----------------|
| **P-A** | Missing reevaluation trigger on `ICE_CONNECTED` / handshake progress | Second `RECOVERY_CONTROL_ADMISSION_REEVALUATE` after receipt |
| **P-B** | Missing control admission transition predicate | Handshake facts present but no boundary |
| **P-C** | No progression trigger exists (receipt → pending → timeout only) | No second reevaluate before terminal |

**Not decided.** Do not patch until one path is proven.

---

## Progression paths to observe (A / B / C)

### Path A — ICE / transport recovery

```text
REMOTE_RECEIPT_ACKED → reevaluate → ICE_CONNECTED → reevaluate? → CONTROL_PLANE_BOUNDARY
```

### Path B — Control handshake progress

```text
REMOTE_RECEIPT_ACKED → reevaluate → CONTROL_HANDSHAKE_PROGRESS → reevaluate? → boundary
```

### Path C — No progression (X1.1 candidate)

```text
REMOTE_RECEIPT_ACKED → reevaluate → ADMISSION_PENDING → (no further reevaluate) → timeout
```

---

## X1-B directed case matrix

| Case | Receipt | Glare | Expected | Field status |
|------|---------|-------|----------|--------------|
| **A** | yes | no | receipt → reeval → boundary (or legitimate terminal) | **PARTIAL** (M03→M01 attempt-3: reeval yes, boundary no) |
| **B** | yes | yes | defer + boundary | **NOT OBSERVED** |
| **C** | yes | yes + unresolved conflict | stay pending, no premature fail | **NOT OBSERVED** |
| **D** | no | any | normal timeout (not X1-A regression) | **OBSERVED** (M03→M02 ICE_RESTART_ONLY) |

**Bilateral glare contract:** NOT VERIFIED until Case B or C on **M03→M02** (or equivalent initiator reattach edge).

---

## Target log chain (X1-B pass)

Must observe on initiator edge after receipt:

```text
REMOTE_RECEIPT_ACKED
RECOVERY_CONTROL_ADMISSION_REEVALUATE
(control handshake / ICE facts — supporting)
RECOVERY_CONTROL_ADMISSION_REEVALUATE   [optional second trigger — Path A/B]
CONTROL_PLANE_BOUNDARY or REATTACH_ACCEPTED
```

If missing last two → classify P-A / P-B / P-C before any patch.

---

## Watchdog log order (record only — not bug)

Observed sequence:

```text
RECOVERY_ATTEMPT_TIMEOUT (log)
RECOVERY_WATCHDOG_DEFERRED ADMISSION_PENDING
RECOVERY_WATCHDOG_STARTED (reschedule)
```

**Interpretation (frozen pending code audit):** Timeout log may precede defer eligibility check. **Do not** treat as state pollution until confirmed whether terminal state mutates before defer return.

**Next check:** After `WATCHDOG_DEFERRED`, is `attemptTerminal=true` or phase already `FAILED_MEDIA_RECOVERY`? (attempt-3: defer at 12:36:32 did **not** terminal; failure at 12:36:45.)

---

## Discipline (frozen)

- Do **not** open X2 for `media=CONNECTED` + `SYNCING` / Poor Network
- Do **not** patch UI / timeout budget / residency
- Next round: **X1-B observation only** — prioritize M03→M02 reattach + glare

---

## Status board

```text
X1-A Delivery → Reevaluation     PASS
X1-B Admission Progression       OPEN
Bilateral Glare Contract         NOT VERIFIED
X2 Residency                     HOLD
```

**Stage conclusion:** PR #126 proved **delivery is no longer ignored**; current work verifies **whether admission can complete after reevaluation**.
