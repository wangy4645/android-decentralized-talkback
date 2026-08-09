# Successor Recovery Lifecycle Sample Expansion Run Card

**Status:** **COMPLETE for candidacy gate** — MISS_SETTLING ≥3 · Q7=S2 · further same-stimulus loops **stopped**  
**Date:** 2026-08-09  
**Parent:** [mobile-validation-successor-recovery-pending-observation.md](./mobile-validation-successor-recovery-pending-observation.md)  
**ADR candidate:** [0046-successor-admission-terminal-convergence-contract-candidate.md](../adr/0046-successor-admission-terminal-convergence-contract-candidate.md)  
**Prior evidence:** Field #2 + `logs/successor-sample-20260809-101954/` + `logs/successor-sample-20260809-102648/`  
**Authorization:** Q7 = S2 · candidate docs only · **no runtime** · **no fix**

```text
Purpose (historical):
  complete lifecycle evidence coverage toward Q7

Outcome:
  MISS_SETTLING repeatable ≥3
  P0 SUCCESS / FAILED_MEDIA still UNKNOWN
  Q7 → S2 ADR-0046 CANDIDATE (not accepted)

Not purpose:
  fix convergence
  redefine lifecycle contract in code
  validate UX
  change recovery semantics
```

---

## Status board

```text
ADR-0044 Presentation             CLOSED ✅

ADR-0045 Failed Media Clear       ACCEPTED
  Phase 2.1                       PAUSED

Successor Observation
  Q1–Q6                           COMPLETE ✅
  Sample Expansion                THIS CARD
  Next after stop                 Q7 ADR-candidacy only (not “how to fix”)

Runtime / ADR / Fix               NONE AUTHORIZED
```

---

## 1. Scope

### Included

```text
Topology:
  same existing successor recovery topology
  (M01 / M02 / M03 · SSID happy · same conference shape as Field #2)

Observe:
  attempt
    ↓
  admission
    ↓
  phase
    ↓
  obligation
    ↓
  terminal writer
    ↓
  outcome
```

| Role | Module | Serial |
|------|--------|--------|
| Peer | M01 | `HTUBB21B09220661` |
| Host / observer | M02 | `2d73067a` |
| DUT / peer | M03 | `MDX0220416001963` |

SSID: **`happy`** only

### Excluded

```text
✗ watchdog
✗ timeout
✗ retry tuning
✗ SuccessorPolicy
✗ RECOVERY_PENDING modification
✗ SYNCING projection change
✗ ADR creation
✗ ADR-0038 / ADR-0045 amendment
✗ Q7 “how to fix” design
✗ force FAILED_MEDIA to finish ADR-0045 Phase 2.1
```

---

## 2. Sample Matrix

| Sample | Priority | Goal | Expected evidence |
|--------|----------|------|-------------------|
| SUCCESS | P0 | 补齐 successor 正常终态 | admission → completion writer → terminal success |
| FAILED_MEDIA | P0 | 补齐失败终态 | admission → failed-media writer → terminal failed |
| SUPERSEDE | P1 | 确认 replacement 语义 | attempt lineage + replacement outcome |
| CANCELLED / LEAVE | P1 | 确认外部终止 | external action → owner → terminal outcome |

Field #2 already supplies one **CANCELLED/LEAVE** (USER_LEAVE) and one **SUPERSEDE→ADMIT_SUCCESSOR→settling** (non-terminal). Prefer new runs that hit **SUCCESS** and **FAILED_MEDIA** first.

---

## 3. Required Event Trace

每个样本必须收集：

### Attempt

```text
attemptId
parentAttemptId (if any)
creation time
supersededBy
```

### Admission

```text
admitSuccessorObligation (or equivalent event name)
admission reason
target endpoint
lineage relation
```

### Phase trajectory

完整序列：

```text
previous phase
    →
new phase
    →
writer/source
```

重点：

```text
RECOVERY_PENDING
NEGOTIATION_SETTLING (defer reason — not a phase)
terminal phase
```

### Obligation

```text
obligation opened
obligation owner
obligation closed
close reason
close timestamp
```

### Terminal writer

必须回答：

```text
who wrote terminal?
```

允许：

```text
Recovery Controller
CompletionPolicy family
existing terminal writer
```

禁止推断为 writer：

```text
ICE callback
UVCP
UI state
```

---

## 4. Success Criteria

不是：

```text
UI ONLINE
ICE latency
recovery speed
```

而是：每类样本至少存在一条可审计轨迹：

```text
admission
    +
phase trajectory
    +
terminal writer
    +
terminal outcome
```

Pass/fail of this Field = **coverage completeness**, not product “recovery worked.”

---

## 5. Special Observation Hooks

### SUCCESS

确认：

```text
successor can reach completion terminal
```

记录：

```text
markRecovered?
other completion writer?
```

### FAILED_MEDIA

确认：

```text
successor can enter failed-media terminal
```

并观察 ADR-0045 overlap **only if natural**:

```text
FAILED_MEDIA
+ obligationClosed
+ GATE/E4
```

否则：

```text
ADR-0045 = not applicable
```

Do **not** manufacture that case for Phase 2.1.

### SUPERSEDE

只记录：

```text
old attempt
    ↓
replacement
    ↓
new obligation?
    ↓
old terminalization?
```

不判断设计正确性。

### CANCELLED / LEAVE

只记录：

```text
external event
    ↓
owner
    ↓
terminal
```

---

## 6. Stop Condition

停止收样当且仅当：

```text
SUCCESS            ≥ 1
FAILED_MEDIA       ≥ 1
SUPERSEDE          ≥ 1
CANCELLED/LEAVE    ≥ 1
```

之后才开：

```text
Q7:
  Should successor observation become ADR candidate?
```

不是：

```text
How to fix?
```

---

## 7. Log placement

```text
talkback/logs/successor-sample-YYYYMMDD-HHMMSS/
```

Per sample, note in the observation parent (append-only) or a short field note under the log dir:

```text
sample_kind: SUCCESS | FAILED_MEDIA | SUPERSEDE | CANCELLED_LEAVE
session / edge / attempt lineage
terminal writer + outcome
```

---

## Frozen fence (repeat)

```text
✗ ADR-0045 amendment
✗ ADR-0038 amendment
✗ Successor ADR (until Q7 candidacy)
✗ watchdog / timeout / SuccessorPolicy
✗ RECOVERY_PENDING / SYNCING / ICE auto-terminal
```

This card **completes evidence coverage only** — it does not pre-embed a future design interface.
