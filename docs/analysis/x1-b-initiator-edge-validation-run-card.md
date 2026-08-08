# X1-B Initiator-Edge Validation Run Card

**Status:** PAUSED — redesign experiment entry (no blind re-runs on current topology)  
**Case:** `post-x1b-initiator-edge-validation`  
**Date:** 2026-08-08  
**Parent:** [X1-B progression](./x1-b-admission-progression-validation.md) · [X1 Validation Gate](./x1-validation-gate.md)

**Goal:** Validate **initiator-edge admission progression** after receipt — not WiFi smoke, not random M03 flap luck.

> X1-B validates the **initiator edge chain**. If the run never enters `REATTACH_THEN_ICE_RESTART` on M03→M02, outcome is **NOT ADJUDICATED** — not PASS, not FAIL.

**Supersedes:** [X1-B scenario-hit M03→M02](./x1-b-scenario-hit-m03-m02-run-card.md)

---

## Frozen status

```text
PR #126 / X1-A              VERIFIED PASS (CLOSED)
X1-B progression            OPEN
X1-B target path            NOT ENTERED (×4) — NOT ADJUDICATED
Scenario construction       INSUFFICIENT (bottleneck)
Gate 0.5                    REFINED → 0.5-A + 0.5-B
Bilateral glare             NOT VERIFIED
Implementation              FROZEN
X2                          HOLD
```

**Architecture conclusion (×4 runs):** Current experiment cannot stably construct the recovery ownership topology required for X1-B. This is **not** X1-B implementation failure — it is **ADR-0040 ownership selection** on M03→M02 at disconnect time.

**Do not replay** current three-party topology + M03 flap without a **new experiment entry design**.

---

## Key finding: pre-flap ownership ≠ disconnect-time ownership

**Prior assumption (wrong):**

```text
before flap: localOwner=M03  →  flap  →  M03 initiates reattach on M03→M02
```

**Observed (run 4 and consistent pattern):**

```text
T0 stable:     localOwner=M03 (GLARE_DECISION, STABLE)
T1 M03 flap:   M02 ICE DISCONNECTED first
               → NEGOTIATION_OWNER_RESOLVED existing_owner=M02
               → policy=ICE_RESTART_ONLY initiator=AUTHORITY
T1 +50ms:      M01 edge opens REATTACH_THEN_ICE_RESTART (M03 owner on M01 only)
```

```text
pre-flap ownership  ≠  disconnect-time recovery ownership
```

Gate 0.5 must check **both** — see Gate 0.5-A and 0.5-B below.

---

## Two problem domains (do not merge)

### Case 1 — X1-B target (not yet entered)

```text
M03 → M02: REATTACH_SENT → RECEIPT → REEVALUATE → progression → boundary/timeout
```

Contract: **ADR-X1** control admission.

### Case 2 — Current ×4 runs (what actually happened)

```text
M02 existing owner → ICE_RESTART_ONLY → no receipt on M03→M02
```

Contract: **ADR-0040** ownership selection. **Not** X1-B adjudication input.

---

## Gate sequence (frozen)

```text
Gate 0     Version alignment
Gate 0.5-A Pre-trigger topology snapshot (necessary, not sufficient)
Gate 0.5-B First recovery owner at disconnect (HARD STOP — required)
Gate 1     Trigger flap (M03 WiFi only) — only after 0.5-A + 0.5-B PASS
Gate 2     X1-B adjudication — only after Gate 1 HIT
```

---

## Gate 0 — Version alignment

All three nodes: same field APK (`versionName=1.0.0-x1b-*`).

```powershell
adb -s <serial> shell pm dump com.talkback.appprod | findstr versionName
```

Record in `RUN_META.txt`. Mismatch → fix before any flap.

---

## Gate 0.5-A — Pre-trigger topology snapshot

**Purpose:** Exclude obviously wrong preparation. **Not sufficient alone.**

### Record before flap (M03 log, edge M03→M02)

```text
localOwner / negotiationOwner
no active competing recovery on M03→M02
no open M02-owned obligation on M03→M02
```

Search patterns:

```text
RECOVERY_GLARE_DECISION.*edge=M02
RECOVERY_NEGOTIATION_OWNER_RESOLVED.*edge=M02
RECOVERY_EDGE_STATE.*edge=M02
```

### PASS (necessary conditions)

```text
localOwner=M03 (or M03 is recovery coordinator candidate)
no M02 recovering / no pending negotiation on M03→M02 edge
three-party stable >= 90s
```

### FAIL

Obvious pollution: M02 in RECONNECTING, open M02-owned recovery, USER_LEAVE, version mismatch.

**Verdict:** `SETUP_INVALID` — fix and restart collection.

---

## Gate 0.5-B — First recovery owner at disconnect (HARD STOP)

**Purpose:** Verify the run enters the **X1-B observable universe** at the first recovery negotiation event. This is the gate that failed in run 4 despite Gate 0.5-A pass.

### When to evaluate

On **first** recovery event after flap trigger:

```text
RECOVERY_NEGOTIATION_OWNER_RESOLVED
edge=M02
```

(First occurrence on M03→M02 after flap — not pre-flap stable-state lines.)

### PASS (must satisfy at least one)

```text
selectedOwner=M03
OR
existing_owner != M02
```

### FAIL

```text
selectedOwner=M02
AND existing_owner=M02
→ expect ICE_RESTART_ONLY on M03→M02
```

**Verdict:** `SCENARIO_MISS_BEFORE_TRIGGER` — **stop soak immediately**. Do not adjudicate X1-B. Redesign experiment entry.

**Operator action:** If Gate 0.5-B fails within seconds of flap, end run early — no 5-minute soak needed.

---

## Gate 1 — Trigger (only after Gate 0.5-A + 0.5-B PASS)

```text
M03 WiFi OFF → wait 15–30s → ON → soak >= 5 min (only if 0.5-B still passing)
M01/M02: no WiFi flap · no USER_LEAVE
```

### Gate 1 HIT (required for Gate 2)

First `RECOVERY_ATTEMPT_OPENED` on M03→M02 after flap:

```text
remote=M02
policy=REATTACH_THEN_ICE_RESTART
```

Plus: `RECOVERY_REATTACH_SENT` → `remote=M02`.

### Gate 1 MISS

```text
policy=ICE_RESTART_ONLY
no REATTACH on M03→M02
```

**Verdict:** `SCENARIO_MISS` — experiment entry not met; **NOT ADJUDICATED**.

---

## Gate 2 — X1-B adjudication (only after Gate 1 HIT)

**Scope:** M03→M02 initiator edge only. Ignore UI · presence · residency (L4 observe only).

```text
REATTACH_SENT → RECEIPT_ACKED → REEVALUATE → admissionPending → HANDSHAKE_PENDING → ?
```

**Adjudicate:** `-PrimaryEdge M02`

**Do not re-litigate PR #126.** X1-A is closed.

| Result | Chain | Conclusion |
|--------|-------|------------|
| **A** | reeval → admitted → boundary | **X1-B PASS** |
| **B** | reeval → pending → timeout/defer | X1-A PASS · X1-B OPEN / P-A candidate |
| **C** | reeval → glareUnresolved=true → E2 suppressed | X1-B glare contract issue |

---

## Prior runs (all SCENARIO_MISS — X1-B NOT ADJUDICATED)

| Run | Gate 0.5-A | Gate 0.5-B (post-hoc) | Gate 1 |
|-----|------------|------------------------|--------|
| `post-x1-directed-20260808-123422` | not recorded | `existing_owner=M02` | `ICE_RESTART_ONLY` |
| `post-x1b-directed-20260808-130327` | not recorded | same | same |
| `post-x1b-scenario-hit-20260808-131935` | timing only | same | same |
| `post-x1b-initiator-edge-20260808-133323` | `localOwner=M03` ✅ | `existing_owner=M02` ❌ | `ICE_RESTART_ONLY` |

**Saturated:** current topology + flap timing + ownership resolver ⇒ M02 wins M03→M02 at disconnect. **No fifth replay without new entry design.**

---

## M03→M01旁证 (×4 — evidence only)

```text
receipt → reevaluate → admissionPending=true → defer → FAILED_MEDIA
glareUnresolved=false
```

Confirms X1-A wiring and pending state. **Does not** confirm X1-B defect — target completion path was never available on M03→M02.

---

## Next experiment entry (design only — no code)

Goal: one run with:

```text
M03→M02: REATTACH_SENT → RECEIPT → REEVALUATE
```

Possible directions (to be specified in a separate design note):

- Topology where M03 is disconnect-time owner on M03→M02 (not just pre-flap stable)
- Reduce M02 `existing_owner` bias at ICE_DISCONNECTED
- Two-party M03↔M02 baseline (exclude authority-edge competition)
- Controlled disconnect ordering so M03→M02 recovery fires before M02 self-ownership bootstrap

**Not in scope:** predicate patch · watchdog · UI · X2.

---

## Forbidden

```text
Fifth blind replay of current three-party + M03 flap
Patch from M01 Result B alone
Open X1.1 before initiator-edge hit
Label SCENARIO_MISS as X1-B FAIL
X2 · UI · timeout extension · new FSM phase
Declare WiFi fixed
```

---

## One-line gate question

> When M03 **owns** the reattach initiator edge on M03→M02 **at disconnect time**, does admission pending have a legal path to completion?

Not: did WiFi recover. Not: did pre-flap stable state show localOwner=M03.
