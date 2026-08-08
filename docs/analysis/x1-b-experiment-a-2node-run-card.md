# X1-B Experiment A — 2-Node Run Card (M03↔M02)

**Status:** AUTHORIZED (docs only — field run NOT STARTED)  
**Case:** `post-x1b-experiment-a-2node`  
**Date:** 2026-08-08  
**Parent:** [Experiment entry design](./x1-b-experiment-entry-design.md) · [X1-B progression](./x1-b-admission-progression-validation.md)

**Objective (one question only):**

> Without three-party ownership / glare interference, does `reevaluate → admission progression` exist?

**Not in scope:** bilateral glare · owner conflict · membership epoch · M01 witness · presence · residency · code changes · PR

---

## Frozen status

```text
Diagnosis                  CLOSED
ADR-0040                   VERIFIED PASS
X1-A                       VERIFIED PASS (receipt → reevaluate)
X1-B                       NOT ENTERED · NOT ADJUDICATED
Experiment Design          COMPLETE
Next Validation            Experiment A (2-node)
Code Change                NONE
PR                         NONE
Field Run                  NOT STARTED
X2                         HOLD
```

---

## 1. Topology

```text
M03  ←——WiFi flap——→  M02

No M01. No third participant.
```

| Device | Role |
|--------|------|
| **M03** | Flap initiator · recovery initiator candidate |
| **M02** | Peer only |

**SSID:** `happy` only  
**APK:** same field build on both (`1.0.0-x1b-*`)  
**Session:** fresh 2-party conference (clean episode — no prior 3-party obligation carryover)

---

## 2. Trigger

```text
T0  M03 + M02 join 2-party conference · stable >= 90s
T1  Gate 0.5-B check (see hard stops)
T2  M03 WiFi OFF → 15–30s → ON
T3  Soak >= 5 min (only if entry PASS)
T4  Stop collection · adjudicate
```

**M02:** do not flap WiFi · do not leave call

---

## 3. Mandatory logs

Collect on **M03** (primary) and **M02** (supporting). Search M03 log for:

| # | Event | Pattern |
|---|-------|---------|
| E1 | Attempt opened | `RECOVERY_ATTEMPT_OPENED.*remote=M02` |
| E2 | Policy | `policy=REATTACH_THEN_ICE_RESTART` |
| E3 | Reattach sent | `RECOVERY_REATTACH_SENT.*(remote=M02\|to=M02)` |
| E4 | Receipt | `REMOTE_RECEIPT_ACKED` or `RECOVERY_REATTACH_RECEIPT.*remote=M02` |
| E5 | Reevaluate | `RECOVERY_CONTROL_ADMISSION_REEVALUATE.*edge=M02` |
| E6 | Pending | `admissionPending=true` |
| E7 | Handshake | `CONTROL_HANDSHAKE` / `controlPlaneStarted` |
| E8 | Boundary | `CONTROL_PLANE_BOUNDARY.*M02` or `REATTACH_ACCEPTED.*M02` |
| E9 | Defer | `WATCHDOG_DEFERRED.*ADMISSION_PENDING` |
| E10 | Owner at disconnect | First `RECOVERY_NEGOTIATION_OWNER_RESOLVED.*edge=M02` after flap |

**Adjudicate:** `-PrimaryEdge M02` on M03 log

---

## 4. Hard stop conditions

### Gate 0 — Version

Both nodes same `versionName`. Mismatch → stop before flap.

### Gate 0.5-B — First recovery owner (M03→M02)

On **first** `RECOVERY_NEGOTIATION_OWNER_RESOLVED edge=M02` after flap:

**PASS:** `selectedOwner=M03` OR `existing_owner!=M02`  
**FAIL:** `selectedOwner=M02` AND `existing_owner=M02`

→ **`SCENARIO_MISS_BEFORE_TRIGGER`** — stop soak, do not adjudicate X1-B

### Entry gate (after flap)

First `RECOVERY_ATTEMPT_OPENED remote=M02` must show:

```text
policy=REATTACH_THEN_ICE_RESTART
AND
RECOVERY_REATTACH_SENT exists (to/remote=M02)
```

**FAIL:**

```text
policy=ICE_RESTART_ONLY
OR no REATTACH_SENT on M03→M02
```

→ **`SCENARIO_MISS`** — stop X1-B analysis

---

## 5. Adjudication matrix

**Prerequisite:** Entry gate PASS. Otherwise verdict = `SCENARIO_MISS` only.

### Entry PASS chain (required)

```text
RECOVERY_ATTEMPT_OPENED remote=M02 · policy=REATTACH_THEN_ICE_RESTART
        ↓
RECOVERY_REATTACH_SENT
        ↓
REMOTE_RECEIPT_ACKED
        ↓
RECOVERY_CONTROL_ADMISSION_REEVALUATE · admissionPending=true
```

### X1-B outcomes (only after entry PASS)

| Result | Observation | Verdict |
|--------|-------------|---------|
| **PASS** | `CONTROL_PLANE_BOUNDARY` or `REATTACH_ACCEPTED` on M03→M02 | **X1-B progression observed** |
| **Result B** | receipt → reeval → `CONTROL_HANDSHAKE_PENDING` → defer → terminal | **X1-B progression gap reproduced** |
| **Result C** | N/A in 2-node (no bilateral glare) | — |

### Forbidden wording

| Do not write | Write instead |
|--------------|---------------|
| X1 failed | X1-B progression not observed |
| WiFi broken | Entry miss or progression gap (specify which) |
| PR #126 failed | X1-A closed; distinguish A vs B below |

**Distinguish:**

```text
A. progression contract missing     (entry PASS + Result B)
B. experiment did not enter path    (SCENARIO_MISS / SCENARIO_MISS_BEFORE_TRIGGER)
```

---

## Route after Experiment A

```text
Experiment A (completed)
  Entry PASS · Delivery MISS → see Experiment A' below

Experiment A'
  Gate 2 Case D1 (receipt)  → Gate 3 X1-B adjudication
  Gate 2 Case D2            → DELIVERY_PATH_ISSUE · stay frozen

See: [Experiment A' session hygiene run card](./x1-b-experiment-a-prime-2node-run-card.md)
```

---

## Collection (when authorized)

```powershell
# Gate 0: verify versions on M03 + M02 only
adb -s <M03> shell pm dump com.talkback.appprod | findstr versionName
adb -s <M02> shell pm dump com.talkback.appprod | findstr versionName

# Collect M03 + M02 (no M01)
.\scripts\post-x1-control-admission-start-run.ps1  # adapt: 2 devices only

# Adjudicate
.\scripts\post-x1-control-admission-adjudicate.ps1 -LogDir logs\post-x1b-exp-a-<stamp> -PrimaryEdge M02
```

**Field run:** NOT STARTED until operator explicitly authorizes.

---

## Forbidden

```text
❌ M01 in call
❌ 3-party replay
❌ code / PR / predicate patch
❌ label SCENARIO_MISS as X1-B FAIL
❌ use presence / UI as pass signal
```

---

## One-line gate question

> On a clean 2-node M03→M02 edge, after receipt-driven reevaluate, does admission progression toward boundary exist?
