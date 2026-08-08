# X1-B Experiment Entry Design

**Status:** AUTHORIZED (design only — no code, no PR)  
**Date:** 2026-08-08  
**Parent:** [X1-B initiator-edge validation run card](./x1-b-initiator-edge-validation-run-card.md) · [X1-B progression](./x1-b-admission-progression-validation.md) · [X1 Validation Gate](./x1-validation-gate.md)

**Purpose:** Design **experiment entry** so X1-B target path is a **controlled observation**, not an accidental by-product of M03 WiFi flap.

> **Question shifted:** Not "why didn't X1-B complete?" but **"did we enter X1-B at all?"**

---

## Frozen architecture state

```text
ADR-0040                 VERIFIED PASS

PR #126 / X1-A           VERIFIED PASS
                         receipt → reevaluate

X1-B                     NOT ENTERED
                         NOT ADJUDICATED

M03→M02 target path      unavailable in current experiment entry

M03→M01 Result B       evidence only — no extrapolation

Bilateral glare          NOT VERIFIED

X2                       HOLD

Implementation           FROZEN (X1-A complete)
Verification             BLOCKED BY SCENARIO ENTRY
```

**Stage:**

```text
Diagnosis        CLOSED
Contract         APPROVED
Implementation   COMPLETE (X1-A)
Verification     BLOCKED BY SCENARIO ENTRY
```

This is a **healthy** state — not blocked on implementation.

---

## 1. Experiment objective

### Objective

Create **deterministic X1-B admission progression observation** — one run where the initiator edge enters the full observable chain:

```text
M03 → M02

RECOVERY_ATTEMPT_OPENED
        |
        policy=REATTACH_THEN_ICE_RESTART
        |
RECOVERY_REATTACH_SENT
        |
REMOTE_RECEIPT_ACKED
        |
RECOVERY_CONTROL_ADMISSION_REEVALUATE
        |
(admission progression — Gate 2 only after above)
```

Only **after** this chain is observed may we adjudicate X1-B (Result A / B / C).

### Non-objectives

```text
❌ fix ownership selection (ADR-0040 domain)
❌ fix ICE restart policy
❌ fix residency / X2
❌ fix UI / presence
❌ patch progression predicate from M01旁证
❌ prove WiFi recovery
```

Experiment design must **not** become architecture modification.

### Success of this design phase

A written **entry matrix** where at least one variant has a credible path to Gate 0.5-B + Gate 1 HIT — not a guarantee of X1-B PASS.

---

## 2. Entry variables

Factors that affect whether M03→M02 enters `REATTACH_THEN_ICE_RESTART` at disconnect time:

| Variable | Effect on entry |
|----------|-----------------|
| **Disconnect ordering** | Which edge fires `ICE_DISCONNECTED` first → which resolver runs first |
| **existing_owner** | `existing_owner=M02` → authority takeover → `ICE_RESTART_ONLY` |
| **Active negotiation** | Pending offer/answer → bootstrap path, owner election |
| **Topology size** | 3-party adds M01 authority edge, membership reconciliation, competing recovery |
| **Flap target** | Only M03 flap still allows M02 ICE to drop first on mesh edge |
| **Pre-flap stable owner** | `localOwner=M03` in STABLE **≠** disconnect-time owner (proven ×4) |
| **Episode history** | Prior `existing_owner` on M02 edge persists across flap |

### Root cause of ×4 SCENARIO_MISS (current entry)

```text
disconnect ordering
        +
M02 first ICE loss on M03→M02 mesh edge
        =
NEGOTIATION_OWNER_RESOLVED existing_owner=M02
        =
policy=ICE_RESTART_ONLY initiator=AUTHORITY
        =
no REATTACH / no RECEIPT / no REEVALUATE on M03→M02
        =
X1-B evidence = 0
```

This is **ADR-0040 ownership selection** behavior under current stimulus — not X1-B implementation failure.

### Gate 0.5-B (from run card)

First recovery negotiation on M03→M02 after trigger must satisfy:

```text
selectedOwner=M03  OR  existing_owner != M02
```

Otherwise: `SCENARIO_MISS_BEFORE_TRIGGER` — do not soak.

---

## 3. Candidate experiment matrix

Each experiment answers **only:**

> Can we enter X1-B observable universe?

Not:

> Can we fix X1-B?

### Recommended isolation order

```text
2-node:  prove progression behavior exists (or Result B on target edge)
3-node:  prove glare / bilateral behavior (Path B)
```

Do not require glare on first successful entry.

---

### Experiment A — Two-device baseline (M03↔M02) **【优先】**

| Field | Value |
|-------|-------|
| Topology | M03 + M02 only (no M01) |
| Flap | M03 WiFi only |
| Rationale | Removes M01 authority, membership reconciliation, host reattach competition |
| Hypothesis | Fewer edges → higher chance M03 owns M03→M02 at disconnect |
| Gate 0.5-B | First `NEGOTIATION_OWNER_RESOLVED` on M02 edge: `selectedOwner=M03` or `existing_owner≠M02` |
| Risk | May still get M02 `existing_owner` if mesh history biases; need clean session |

**Answers:** Is progression gap real on the **minimal** initiator edge?

---

### Experiment B — Three-party, control M02 disconnect ordering

| Field | Value |
|-------|-------|
| Topology | M01 + M02 + M03 |
| Setup | Stabilize until M03→M02 has no M02-owned obligation; M02 passive |
| Flap | M03 only |
| Rationale | Same as current but explicit Gate 0.5-A/B logging; abort early on 0.5-B fail |
| Hypothesis | May still fail if M02 ICE drops first — documents ordering dependency |
| Gate 0.5-B | Hard stop within seconds of flap |

**Answers:** Is three-party failure purely ordering, or also structural?

---

### Experiment C — Pre-seed M03 as M02-edge authority before flap

| Field | Value |
|-------|-------|
| Topology | 3-party (or 2-party) |
| Setup | Operational steps TBD to ensure M03 is disconnect-time initiator on M03→M02 (not just pre-flap `localOwner=M03`) |
| Flap | M03 only after 0.5-B pre-check passes **at first recovery event** |
| Rationale | Directly targets disconnect-time ownership |
| Risk | May be hard to operationalize without code; design must stay observational |

**Answers:** Can preparation change disconnect-time owner without code?

---

### Experiment D — Delayed / staged disconnect (design sketch only)

| Field | Value |
|-------|-------|
| Idea | Influence which edge enters recovery first (e.g. timing, partial connectivity) |
| Constraint | **No debug flags, no resolver patches** — field-operational only |
| Status | Speculative — authorize only if A/B insufficient |

**Not authorized for implementation** without separate ADR.

---

### Matrix summary

| ID | Topology | Priority | Question |
|----|----------|----------|----------|
| **A** | M03↔M02 | **P0** | Minimal path — can we enter X1-B chain? |
| **B** | M01+M02+M03 | P1 | Does ordering alone explain ×4 miss? |
| **C** | 2 or 3 party | P2 | Can prep seed M03 disconnect-time owner? |
| **D** | TBD | HOLD | Staged disconnect without code |

---

## 4. Success criteria

### Entry PASS (required before X1-B adjudication)

All of the following on **first qualifying attempt** after flap on **M03→M02**:

```text
RECOVERY_ATTEMPT_OPENED remote=M02
        AND
policy=REATTACH_THEN_ICE_RESTART
        AND
RECOVERY_REATTACH_SENT (to/remote=M02)
        AND
REMOTE_RECEIPT_ACKED (on initiator edge context)
        AND
RECOVERY_CONTROL_ADMISSION_REEVALUATE edge=M02
```

If any missing → **`SCENARIO_MISS`** or **`SCENARIO_MISS_BEFORE_TRIGGER`** — **NOT ADJUDICATED**.

### X1-B adjudication (Gate 2 — only after entry PASS)

| Result | Observation | Conclusion |
|--------|-------------|------------|
| **A** | progression → `CONTROL_PLANE_BOUNDARY` / admitted | **X1-B PASS** |
| **B** | `admissionPending=true` → handshake pending → timeout/defer | X1-A PASS · X1-B OPEN / P-A candidate |
| **C** | `glareUnresolved=true` + E2 suppressed | X1-B glare contract issue |

### Explicit non-success labels

| Label | Meaning |
|-------|---------|
| `SCENARIO_MISS_BEFORE_TRIGGER` | Gate 0.5-B fail — never entered X1-B universe |
| `SCENARIO_MISS` | Gate 1 fail — `ICE_RESTART_ONLY` or no reattach |
| `NOT ADJUDICATED` | No entry PASS — **no X1-B pass/fail claim** |

---

## Discipline (frozen)

```text
❌ modify owner resolver
❌ modify recovery policy
❌ debug flags to force REATTACH path
❌ M02 special-case handling
❌ patch from M01 Result B (×4)
❌ fifth blind replay of current 3-party + M03 flap
❌ conflate SCENARIO_MISS with X1-B FAIL
```

---

## Next steps (authorized sequence)

1. ✅ Experiment entry design — [x1-b-experiment-entry-design.md](./x1-b-experiment-entry-design.md)
2. ✅ **Experiment A run card** — [x1-b-experiment-a-2node-run-card.md](./x1-b-experiment-a-2node-run-card.md) **(AUTHORIZED — field run NOT STARTED)**
3. ⏳ Execute Experiment A when operator authorizes
4. ⏳ Gate 2 adjudication only on entry PASS
5. ⏳ 3-node glare validation only after Experiment A PASS

**No code · no PR · no field run until operator starts Experiment A.**

### Experiment A priority

**P0 — 2-node M03↔M02 baseline** — prove progression on minimal topology before returning to 3-node glare.

---

## References

- Field evidence: ×4 SCENARIO_MISS — see [initiator-edge run card](./x1-b-initiator-edge-validation-run-card.md#prior-runs-all-scenario_miss--x1-b-not-adjudicated)
- Gate 0.5-A/B: [initiator-edge run card](./x1-b-initiator-edge-validation-run-card.md)
- X1-A closed / X1-B open: [X1-B progression](./x1-b-admission-progression-validation.md)
