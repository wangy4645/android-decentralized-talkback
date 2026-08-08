# Experiment A' — 2-Node Session Hygiene Run Card

**Status:** AUTHORIZED (docs only — field run NOT STARTED)  
**Case:** `post-x1b-experiment-a-prime-2node`  
**Date:** 2026-08-08  
**Parent:** [Experiment A run card](./x1-b-experiment-a-2node-run-card.md) · [Experiment entry design](./x1-b-experiment-entry-design.md)

**Objective (narrow):**

> Exclude session contamination so M03→M02 `REATTACH_SENT` can reach `REMOTE_RECEIPT_ACKED`.

**Not in scope:** receipt retry · timeout budget · membership fence · roster repair · X2 · UI · X1-B progression patch · recovery behavior assumptions

**Prerequisite finding (Experiment A):**

```text
ENTRY PASS
    ↓
REATTACH_THEN_ICE_RESTART
    ↓
REATTACH_SENT
    ↓
delivery layer failure (no REMOTE_RECEIPT_ACKED)
```

Without receipt, **all X1-B progression discussion stops.**

---

## Frozen status board

```text
ADR-0040              VERIFIED PASS

X1-A Event Graph
  receipt → reevaluate
  M03→M01             VERIFIED
  M03→M02             NOT OBSERVED (delivery miss)

X1-B Progression Graph
  reevaluate → pending → boundary
  NOT ENTERED

Experiment A
  Entry               PASS
  Delivery            MISS

Experiment A'
  Objective           receipt acquisition
  Status              NEXT

Implementation        FROZEN
X2                    HOLD
```

---

## Gate sequence

```text
Gate 0     Clean episode (hard stop)
Gate 0.1   Roster clean (no ghost members)
Gate 0.2   Authority clean (0.5-B: selectedOwner=M03)
Gate 1     Trigger (same as Experiment A)
Gate 2     Delivery only (receipt or DELIVERY_PATH_ISSUE)
Gate 3     X1-B adjudication (only after Gate 2 Case D1)
```

---

## Gate 0 — Clean episode

**Before starting collection**, confirm on both M02 and M03:

```text
M02:
  previous conference episode = none (fresh join)
  recovery obligation = none
  pending invite = none

M03:
  previous recovery attempt = none
  outstanding nonce = none
```

**Hard stop — abandon run if:**

```text
old episode active
pending obligation on target edge
stale recovery attempt / non-zero attempt id carryover
```

**Operator actions:**

- Force-stop Talkback on M02 + M03 if prior session may linger
- Start **new** 2-party call (do not resume old session)
- Clear logcat before collectors start

---

## Gate 0.1 — Roster clean

Experiment A contamination signal:

```text
joined=2  pending=1  awaiting=true
M01 placeholder / "M01 not discovered"
```

**Before flap, must satisfy:**

```text
conferenceMembers == {M02, M03}
expectedInviteTargets == {}
pendingMembers == {}
offlineMembers == {}   (no ghost M01)
```

**Hard stop if:**

```text
M01 ghost member in roster
pending=1 with unknown target
awaiting=true with stale invite target
```

**Log patterns to verify (M02 or M03):**

```text
joined=2.*pending=0
# absence of:
M01 not discovered
rosterContains.*M01
peer=M01
```

---

## Gate 0.2 — Authority clean

Same as Experiment A Gate 0.5-B — retain proven-effective condition.

On **first** `RECOVERY_NEGOTIATION_OWNER_RESOLVED edge=M02` after flap:

**PASS:**

```text
selectedOwner=M03
existing_owner=M03
```

**FAIL:**

```text
SCENARIO_MISS_BEFORE_TRIGGER
```

Stop — do not continue to delivery observation.

---

## Gate 1 — Trigger

Unchanged from Experiment A:

```text
T0  M03 + M02 join · stable >= 90s · Gates 0 / 0.1 PASS
T1  Gate 0.2 at first recovery event
T2  M03 WiFi OFF → 15–30s → ON
T3  Observe M03→M02
```

**Entry expectation (same as Exp A):**

```text
RECOVERY_ATTEMPT_OPENED remote=M02 policy=REATTACH_THEN_ICE_RESTART
RECOVERY_REATTACH_SENT
```

If `ICE_RESTART_ONLY` → `SCENARIO_MISS` — stop.

---

## Gate 2 — Delivery only

**This round judges only:**

```text
REATTACH_SENT
        |
        v
REMOTE_RECEIPT_ACKED
```

(or `RECOVERY_REATTACH_RECEIPT` with `deliveryState=REMOTE_RECEIPT_ACKED`)

### Case D1 — Delivery success

```text
REATTACH_SENT
REMOTE_RECEIPT_ACKED
```

→ Proceed to **Gate 3** (X1-B adjudication: reevaluate → progression)

### Case D2 — Delivery failure

```text
REATTACH_SENT
no receipt
timeout (e.g. CONTROL_RECONCILIATION_TIMEOUT)
```

**Verdict:**

```text
DELIVERY_PATH_ISSUE
X1-B NOT ENTERED
```

Do **not** discuss progression predicate. Do **not** label X1-B fail.

---

## Gate 3 — X1-B (only after Case D1)

Only if `REMOTE_RECEIPT_ACKED` observed on M03→M02:

```text
RECOVERY_CONTROL_ADMISSION_REEVALUATE
admissionPending=true/false
→ CONTROL_PLANE_BOUNDARY / REATTACH_ACCEPTED  (Result A)
→ or pending → timeout/defer                    (Result B)
```

See [Experiment A adjudication matrix](./x1-b-experiment-a-2node-run-card.md#5-adjudication-matrix).

---

## Observational fields (record only — no hypothesis)

From Experiment A, retain in `RUN_META` but **do not upgrade to root cause**:

```text
authorityReachable=?
mediaRouteConnected=?
membershipEpochConverged=?
```

Possible explanations if D2 repeats: delivery path · handshake readiness · session hygiene — **insufficient evidence to distinguish now**.

---

## Mandatory logs

| # | Event | Pattern |
|---|-------|---------|
| H1 | Roster clean | no `M01` in roster / no `pending=1` before flap |
| H2 | Owner | `NEGOTIATION_OWNER_RESOLVED.*edge=M02` |
| E1–E3 | Entry | `REATTACH_THEN_ICE_RESTART` · `REATTACH_SENT` |
| D1 | Receipt | `REMOTE_RECEIPT_ACKED` or `REATTACH_RECEIPT.*M02` |
| D2 | Timeout | `FAILED_MEDIA.*CONTROL_RECONCILIATION` |

Collect **M03** (primary) + **M02** (supporting).

---

## Forbidden

```text
❌ receipt retry logic / timeout budget change
❌ membership fence / roster repair code
❌ X2 / UI changes
❌ label delivery miss as X1-B FAIL
❌ skip Gate 0 / 0.1 hygiene
❌ reuse session with M01 ghost
```

---

## Route

```text
Experiment A' Gate 2
    |
    +-- Case D1 (receipt)  → Gate 3 X1-B adjudication
    |
    +-- Case D2 (no receipt) → DELIVERY_PATH_ISSUE · stay frozen · hygiene or delivery RCA (separate track)
```

---

## One-line gate question

> On a **clean** 2-node M03→M02 session, does `REATTACH_SENT` complete to `REMOTE_RECEIPT_ACKED`?

Not: does X1-B progression work. Not: does WiFi recover.
