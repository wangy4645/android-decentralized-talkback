# Attempt-4c Baseline — Log Analysis Checklist

**ADR:** [0022-recovery-completion-ownership.md](../adr/0022-recovery-completion-ownership.md) §E.21  
**Status:** contract frozen (2026-08-03)  
**Prerequisites:** PR-D merged (#107); **no** `SUPPRESS_SUCCESSOR_ATTEMPT`; R4-impl **not** landed  

**Purpose:** evidence classification — **not** bug fixing. Do not collapse `RECOVERED`, timeout, or admission into a single "adoption failed" story.

**Semi-automatic runner:** `scripts/analyze-attempt4c-baseline.ps1 -LogDir logs/phase3c-b-attempt4c-<stamp>`

---

## Execution order (frozen)

```text
#107 merge
    → Attempt-4c baseline run (harness only)
    → this checklist / analyzer report
    → decide whether SUPPRESS primitive is needed
```

**Harness:** `scripts/run-phase3c-b-protected-window.ps1 -Attempt 4c`

**Do not write:** `Attempt-4c PASS` / `Attempt-4c FAIL` as a single verdict.

---

## Step 1 — Delivery lineage lifecycle (R3 domain only)

**Scope:** delivery obligation conservation. Ignore completion / adoption here.

### Look for

```text
RECOVERY_DELIVERY_LINEAGE_SUPERSEDED
```

### Extract (per event)

| Field | Requirement |
|-------|-------------|
| `offerLineageId` / `lineageId` | non-empty |
| `oldAttemptId` or supersede attempt binding | present when classifying supersede |
| `reason` | e.g. `REATTACH_INBOUND`, explicit close reason |
| ordering | supersede fact **before** phantom `ABSENT(0,0)` if any |

### Also check (fail signals)

```text
RECOVERY_REMOTE_INGRESS_ABSENT ... recoveryAttemptId=0 obligationGeneration=0   ← phantom ABSENT
RECOVERY_INGRESS_WINDOW_CLOSED ... without prior LINEAGE_SUPERSEDED when supersede expected
```

### Classify

| Label | Meaning |
|-------|---------|
| **PASS** | Old lineage explicitly superseded; no phantom `ABSENT(0,0)` |
| **FAIL** | Phantom `ABSENT`; old lineage disappears without `LINEAGE_SUPERSEDED` |
| **UNKNOWN** | No supersede expected in this exercise slice |

---

## Step 2 — Successor admission (not adoption)

**Scope:** admission legality only (§E.20.2 Admission ≠ Adoption).

### Primary facts (R4-def vocabulary)

```text
SUCCESSOR_ADMISSION_ACCEPTED          ← not emitted until R4-impl; absence is normal
```

### Baseline proxy facts (informative until R4-impl)

```text
RECOVERY_OBLIGATION_OPENED
ADMIT_SUCCESSOR_OBLIGATION_EPISODE
```

### Classify

| Observation | Write | Do **not** write |
|-------------|-------|------------------|
| Proxy or `SUCCESSOR_ADMISSION_ACCEPTED` present | `successor attempt legal (admission observed)` | `successor owns recovery` / `obligation adopted` |
| None observed | `admission not observed` — gate / topology / negotiation follow-up | R4 adoption conclusion |

---

## Step 3 — Deferred Intent domain (R1/R2 only)

**Scope:** deferred intent owner. **Do not** merge with delivery lineage or control reconciliation.

### Look for

```text
DEFERRED_INTENT_RELEASED
NEGOTIATION_CAN_EXECUTE
ICE_RESTART_DISPATCHED
DEFERRED_INTENT_HELD
```

### Classify

| State | Interpretation |
|-------|----------------|
| `RELEASED` (with terminal reason) | Deferred owner closed normally |
| `HELD` / `NEGOTIATION` defer | Negotiation not complete — R2 seam |
| Missing when dispatch expected | Investigate R2 — not delivery, not R4 |

| Label | Meaning |
|-------|---------|
| **PASS** | Release/execute path consistent with attempt phase |
| **FAIL** | Silent null / orphan HELD without seam fact |
| **UNKNOWN** | Negotiation path not exercised in window |

---

## Step 4 — Control reconciliation three-state (E.18 / PR-D)

**Scope:** `RECOVERY_CONTROL_RECONCILIATION_FACT` and membership probe facts only.

### Extract from log lines

```text
RECOVERY_CONTROL_RECONCILIATION_FACT
CONTROL_RECONCILIATION_MEMBERSHIP_UNWIRED
CONTROL_RECONCILIATION_MEMBERSHIP_CHECKED
MEMBERSHIP_AUTHORITY_RESOLVE_TRACE
```

### Fields to parse

```text
membershipProbeDisposition    CHECKED | UNWIRED
authorityId
expectedEpoch
observedEpoch
membershipEpochConverged
reason                        MEMBERSHIP_AUTHORITY_UNWIRED | MEMBERSHIP_EPOCH_MISMATCH | ...
result                        aggregate control reconciliation result
```

### Completion cross-check (separate from control fact)

```text
RECOVERY_COMPLETION_DECISION ... candidate=RECOVERED
RECOVERY_COMPLETION_EVIDENCE_ACCEPTED
```

Treat `candidate=RECOVERED` / evidence accepted as **RECOVERED observed** for Case rules below.

---

### Case A — Authority unwired

**Match:**

```text
membershipProbeDisposition=UNWIRED
(or CONTROL_RECONCILIATION_MEMBERSHIP_UNWIRED)
```

**Expect:**

```text
RECOVERED not observed on successor episode under test
membershipEpochConverged=false
reason=MEMBERSHIP_AUTHORITY_UNWIRED
```

**E.18 fail if:**

```text
UNWIRED + RECOVERED observed
UNWIRED + membershipEpochConverged=true
```

---

### Case B — Wired + epoch match

**Match:**

```text
membershipProbeDisposition=CHECKED
membershipEpochConverged=true
CONTROL_RECONCILIATION_MEMBERSHIP_CHECKED ... converged=true
```

**Allow:**

```text
RECOVERED observed
```

**Report:**

```text
ADOPTION_STATUS=NOT_EVALUATED
```

**Forbidden in narrative:**

```text
ADOPTED / TRANSFERRED / "successor adopted obligation"
```

---

### Case C — Wired + epoch mismatch

**Match:**

```text
membershipProbeDisposition=CHECKED
membershipEpochConverged=false
reason=MEMBERSHIP_EPOCH_MISMATCH
```

**Expect:**

```text
RECOVERED not observed
```

**Completion gate fail if:**

```text
CHECKED(false) + RECOVERED observed
```

**Must not classify as Case A** when `CHECKED` facts are present.

---

### Control summary label

| Label | Condition |
|-------|-----------|
| `Case A` | Latest dominant disposition `UNWIRED` on successor window |
| `Case B` | `CHECKED` + `converged=true` |
| `Case C` | `CHECKED` + `converged=false` + `MEMBERSHIP_EPOCH_MISMATCH` |
| `MIXED` | Incompatible dispositions without episode boundary — manual review |
| `NONE` | No `RECOVERY_CONTROL_RECONCILIATION_FACT` in window |

---

## Step 5 — R4 forbidden-term scan

R4-impl not started. Any emission is **unauthorized semantic leakage** until ADR-amended.

### Grep (report hits; do not auto-fail exercise)

```text
TRANSFERRED
SUCCESSOR_OBLIGATION_ADOPTED
inheritObligation
```

### Report

```text
R4_LEAKAGE: NONE | PRESENT (list lines)
```

---

## Final report format (required)

```text
Attempt-4c Baseline Classification
logDir=<path>
session=<if known>
edge=<if known>

Delivery:
    PASS | FAIL | UNKNOWN
    notes: ...

SuccessorAdmission:
    OBSERVED | NOT_OBSERVED
    proxyFacts: RECOVERY_OBLIGATION_OPENED / ADMIT_SUCCESSOR_...
    notes: admission only — not adoption

DeferredIntent:
    PASS | FAIL | UNKNOWN
    notes: ...

Control:
    Case A | Case B | Case C | MIXED | NONE
    E18_VIOLATION: true | false
    COMPLETION_VIOLATION: true | false
    sampleFacts: ...

Adoption:
    NOT_EVALUATED (R4-impl pending)

R4_LEAKAGE:
    NONE | PRESENT
```

---

## Permitted round conclusions (§E.21.6)

| If… | May prove | May not prove |
|-----|-----------|---------------|
| Case A + no E.18 violation | Silent default-open eliminated | Adoption / delivery root cause |
| Case B + no completion violation | Completion consumes wired authority fact | `SUCCESSOR_OBLIGATION_ADOPTED` |
| Case C + no completion violation | Mismatch not collapsed to `UNWIRED` | Why epoch lagged |
| Delivery FAIL | R3 regression in this run | Entire recovery broken |

---

## System state after PR-D merge (informative)

```text
R3 Delivery                  VERIFIED
DeferredIntent               VERIFIED
Completion Control           E.18.2 VERIFIED
E.18.3 Field Validation      OPEN

R4-def                       DEFINED
R4-impl                      WAITING

Attempt-4c                   AUTHORIZED (this checklist)
```

**Deliverable of first baseline:** the first field picture of successor behavior with wired control authority, **without** SUPPRESS and **without** adoption implementation. That picture informs SUPPRESS design and R4-impl.