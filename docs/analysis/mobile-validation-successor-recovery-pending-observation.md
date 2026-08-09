# Successor Recovery Pending — Observation

**Status:** **OBSERVATION SEALED (Q1–Q6)** · **Q6 = S1** continue observation · **no ADR** · **no fix** · **no runtime authorization**  
**Date:** 2026-08-09  
**Classification:** Recovery **obligation lifecycle completeness** observation  
**Gap (Q5):** **G4** primary **G2** (successor admission contract incomplete) · secondary **G1** (missing terminal exit evaluation)  
**Scope (Q6):** **S1** — remain observation until more successor samples; **not** ADR candidate yet  
**Naming:** Successor Recovery Pending Residency (observation language — **not** “successor recovery bug”)  
**Not:** ADR-0045 regression · ADR-0044 presentation defect · residency clear gap · WiFi Recovery reopen

**Evidence:** `logs/adr0045-field-20260809-094259/` · session `a73272cb-74c2-4fd6-bf50-918a66806df1` · observer **M02** → peer **M03** · pid `30507`

**Parents / boundaries:**

- [ADR-0045](../adr/0045-post-obligation-failed-media-residency-clear-admission.md) — residency clear; **does not absorb this track**
- [ADR-0044](../adr/0044-user-visible-connectivity-semantics-media-residency.md) — presentation; SYNCING here follows recovering, not DEGRADED
- [ADR-0038](../adr/0038-recovery-completion-admission-contract.md) — completion success (orthogonal; do not reopen casually)
- WiFi Recovery Architecture — **CLOSED** (observation only)

---

## Status board

```text
Checkpoint (2026-08-09) — healthy fork

ADR-0044 Presentation              CLOSED ✅

ADR-0045 Failed Media Clear        ACCEPTED / IMPLEMENTED PARTIAL ✅
  Policy                           PASS ✅
  Phase 1                          MERGED ✅
  Phase 2.0 Field                  partial evidence
  Phase 2.1 Field                  PAUSED ⏸️
  FAILED_MEDIA Q1–Q7               CLOSED ✅

Field #1 (20260809-093047)
  M03→M02 clear                    PASS
  M02→M03 DEGRADED                 Trigger gap (FAILED_MEDIA + closed; ADR-0045)

Field #2 (20260809-094259)
  M03→M02 clear                    PASS (ADR-0045 still holds)
  M02→M03 SYNCING                  THIS TRACK
  Domain                           Recovery obligation lifecycle completeness
  ADR-0045 ClearPolicy             correctly NOT invoked (no GATE)

Successor Recovery Pending
  Observation                      OPEN / ACTIVE ✅
  Q1 Phase owner                   COMPLETE ✅
  Q2 Obligation close              COMPLETE ✅
  Q3 SYNCING semantics             COMPLETE ✅
  Q4 Lifecycle contract            COMPLETE ✅ (A2 · B1 · C1)
  Q5 Gap classification            COMPLETE ✅ (G4: primary G2 · secondary G1)
  Q6 Scope / ADR candidacy         OPEN
  Runtime change                   NONE AUTHORIZED
  ADR                              NONE
  Fix authorization                NONE

Forbidden while this track is open:
  ✗ modify ADR-0045 / add residency clear triggers
  ✗ change SYNCING copy / map RECOVERY_PENDING → DEGRADED
  ✗ ICE_CONNECTED auto-ends obligation / writes phase=CONNECTED
  ✗ UVCP hide SYNCING / ignore obligationOpen
  ✗ resurrect WiFi Recovery Architecture
  ✗ force FAILED_MEDIA Field to “finish” Phase 2.1
```

---

## Observation (Field #2)

```text
mediaUnavailable=false
ice=CONNECTED
receivePathLive=true
phase=RECOVERY_PENDING
obligationOpen=true
recovering=true
finalPresence=SYNCING
```

UI 「一直 Sync」与权威一致：仍有 **open recovery obligation** → ADR-0044 投向 SYNCING，**不是** sticky DEGRADED。

### Why not ADR-0045

ADR-0045 GATE requires:

```text
phase == FAILED_MEDIA_RECOVERY
+ obligationClosed
+ snapshot E4
```

Field #2:

```text
phase = RECOVERY_PENDING
obligationOpen = true
FAILED_MEDIA_RECOVERY count (M02 pid 30507 → M03) = 0
```

```text
RecoveryResidencyClearPolicy
        ^
        |
        X   (no GATE)
```

Clear admission **not involved**. Phase 2.1 entry trigger **not exercised**.

---

## Evidence chain (M02 → M03)

```text
09:45:11  ICE_DISCONNECTED → attempt1 (owner=M03; M02 NON_OWNER_BLOCKED)
09:45:23  attempt1 → RECOVERED (obligation closed)
09:45:24  late REATTACH_INBOUND → SUPERSEDE attempt2
          ICE_RESTART_DISPATCHED · DELIVERY_CONFIRMED
          Completion WAITING (DELIVERY_PENDING → then successor)
09:45:26  ADMIT_SUCCESSOR (evidenceKind=REMOTE_MODULE_RECOVERED)
          obligationGen=2 attempt=3 → RECOVERY_PENDING
          negotiation owner=M03; DEFER_ADMISSION
…
09:47:xx  rprobe: RECOVERY_PENDING · obligationOpen=true · SYNCING
          ice=CONNECTED · receivePathLive=true · mediaUnavailable=false
09:48:06  MEMBERSHIP_LEFT / USER_LEAVE (obligation closed by leave — not completion)
```

Markers to keep:

| Marker | Role |
|--------|------|
| `RECOVERY_EDGE_RECOVERED` / `reason=RECOVERED` | attempt1 terminal |
| `RECOVERY_ATTEMPT_SUPERSEDED` / `REATTACH_INBOUND` | post-RECOVERED late reattach |
| `ADMIT_SUCCESSOR_OBLIGATION_EPISODE` | new gen; leaves `RECOVERY_PENDING` |
| `NEGOTIATION_NON_OWNER_BLOCKED` / `DEFER_ADMISSION` | local cannot drive media action |
| rprobe `finalPresence=SYNCING` + `obligationOpen=true` | presentation follows recovering |

---

## Scope

```text
Goal:
  understand RECOVERY_PENDING lifetime after successor admission

In scope:
  phase ownership of RECOVERY_PENDING
  what closes obligationOpen after ADMIT_SUCCESSOR
  why healthy media still yields SYNCING (recovering / successor pending)

Not in scope:
  ADR-0045 residency clear / Phase 2.1 field
  ADR-0044 EndpointStatus remapping
  presentation “bugfix”
  forcing FAILED_MEDIA to mix variables
  WiFi matrix / Directed #5
  casual ADR-0038 / membership reopen
```

---

## Q1–Q3 TRACE (observation only — no fix)

### Q1 — Phase owner of `RECOVERY_PENDING`

**Answer (trace):**

```text
Authority family: ConferenceEdgeRecoveryController (Recovery)
Writer of successor phase:
  admitSuccessorObligationEpisode()
    → openNewRecoveryObligation(phase=RECOVERY_PENDING)
```

| Role | Field #2 (M02→M03) | Owner |
|------|-------------------|--------|
| Successor admit writer | `ADMIT_SUCCESSOR_OBLIGATION_EPISODE` | Controller / Recovery |
| Phase mutation | `RECOVERY_PENDING` via `openNewRecoveryObligation` | Controller |
| Negotiation owner | `selectedOwner=M03` (local=M02) | Negotiation owner resolve |
| Media-action claim | `HOST_RESTART` then `NEGOTIATION_SETTLING` defer | Media action + gate |
| Attempt clock | **no** `RECOVERY_WATCHDOG_STARTED` for attempt=3 | **absent** after defer |

**Who may leave `RECOVERY_PENDING` (code paths):**

```text
markRecovered()           → RECOVERED          (CompletionPolicy; needs open obligation + canClose)
enterFailedMediaResidency → FAILED_MEDIA_*     (typically watchdog ATTEMPT_TIMEOUT)
supersedeAttempt / ADMIT_SUCCESSOR → replace attempt/gen (still recovering until terminal)
cancelEdge / MEMBERSHIP_LEFT / leave → close + remove
```

There is **no** “ICE already CONNECTED ⇒ leave RECOVERY_PENDING” writer. ICE alone is not phase owner.

**Field nuance (precondition for successor):**

```text
attempt1 RECOVERED (obligationClosed)
  → late REATTACH SUPERSEDE attempt2 (phase→ICE_RESTARTING; closed stamp preserved)
  → REMOTE_MODULE_RECOVERED evidence while phase≠RECOVERED ∧ obligationClosed
  → ADMIT_SUCCESSOR replaces edge (gen+1, RECOVERY_PENDING, obligation reopened)
```

`isFreshRemoteModuleRecoveredEvidence` explicitly rejects `phase==RECOVERED`, but accepts mid-flight `ICE_RESTARTING` if the prior close stamp remains — Field #2 hit that seam.

---

### Q2 — What closes `obligationOpen` on successor path?

**Answer (trace):**

```text
closeObligation / markRecovered are CompletionPolicy writers.
OBLIGATION_DEADLINE is failed-media residency path — NOT the successor default.
```

| Terminal candidate | Required machinery | Field #2 |
|--------------------|--------------------|----------|
| Completion → `RECOVERED` | `markRecovered` after completion eval (ICE alone insufficient; control/delivery facts apply) | **never** |
| Attempt timeout → `FAILED_MEDIA_*` | `scheduleWatchdog` → `ATTEMPT_TIMEOUT` | **never** (attempt=3 watchdog count = 0) |
| Leave / cancel | `MEMBERSHIP_LEFT` / session cancel | **only close observed** (09:48:06 USER_LEAVE) |

**Why attempt2/3 never timed out to FAILED_MEDIA:**

```text
admitSuccessor (ICE_RESTART_ONLY path):
  resolveMediaActionOwner → issueBoundedIceRestart
  gate blocked OFFER_AWAITING_ANSWER
  → DEFER (INV-NEG-004: phase/watchdog unchanged)
  → return without scheduleWatchdog

Comment at admitSuccessor:
  "watchdog only after dispatch; deferred must not (INV-REC-023)"
```

Field:

```text
09:45:26  ICE_RESTART_GATE_BLOCKED … OFFER_AWAITING_ANSWER
09:45:26  RECOVERY_MEDIA_ACTION_DEFERRED … NEGOTIATION_SETTLING
09:45:36  intent STALE_DISCARD / NEGOTIATION_BUDGET_EXHAUSTED
          (no WAKEUP drain → no new dispatch → no watchdog)
```

So successor obligation stayed open because **neither completion nor attempt-timeout terminal ran**; ICE/receivePath healthy did **not** close it.

---

### Q3 — Why `finalPresence=SYNCING`?

**Answer (trace): projection is consistent — classify as stuck active recovery, not UVCP bug.**

```text
UserVisibleConnectivityProjection.deriveAxes:
  recovering || controlSyncPending → ControlSyncState.SYNCING
  MEDIA_OK + SYNCING → UserVisibleConnectivityState.SYNCING  (= ONLINE? no)
```

```text
recovering ⇐ isEdgeRecovering ⇐ phase.isActivelyRecovering()
RECOVERY_PENDING ∈ isActivelyRecovering()
mediaUnavailable=false ∧ receivePathLive=true → MEDIA_OK
```

| Option | Meaning | Field #2 |
|--------|---------|----------|
| **A** active recovery | `recovering=true` by phase contract | **true** (phase still `RECOVERY_PENDING`) |
| **B** stuck recovery | no terminal after defer; obligation never converges | **also true** (Q2) |

UI identity is the same; architecture meaning: **B under A** — Sync is the correct projection of an obligation that never left active recovering. Not ADR-0044 miss; not ADR-0045 (GATE never held).

---

## Trace verdict (still observation)

```text
Exposed layer:
  Recovery obligation lifecycle completeness
  (not media residency clear; not presentation)

Core question (refined):
  Does a successor recovery obligation under NEGOTIATION_SETTLING
  possess a final convergence / terminal-admission mechanism?

Field evidence shape:
  RECOVERY_PENDING
    + success / failed-media / supersede / leave   ← exist in FSM
    − timeout / admission expiry after defer       ← missing in Field #2 path

Not:
  "Why did residency clear fail?"
  "Why is UVCP wrong?"
```

```text
Provenance hotspot (not a fix authorization):
  ADMIT_SUCCESSOR + ICE_RESTART defer without attempt watchdog
  → RECOVERY_PENDING can persist while media plane is healthy
  → SYNCING until leave/cancel
```

```text
Quadrants (do not mix):
  ADR-0044  mediaUnavailable ∧ !recovering → DEGRADED
  Field #2  mediaAvailable ∧ recovering     → SYNCING  (correct)
  ADR-0045  FAILED_MEDIA ∧ obligationClosed ∧ E4 → clear (GATE absent here)
```

---

## Q4 OPEN (observation-only grill)

**Vocabulary (frozen for Q4):**

```text
EdgeRecoveryPhase     = RECOVERY_PENDING   (Field #2 phase)
DeferredReason        = NEGOTIATION_SETTLING
Gate block            = OFFER_AWAITING_ANSWER
```

`NEGOTIATION_SETTLING` is **not** a phase enum. Q4 asks about the **deferred settling residency of an open successor obligation** while phase remains actively recovering (`RECOVERY_PENDING`).

**Rejected model (already):**

| Id | Model | Status |
| -- | ----- | ------ |
| L3 | ICE/media restore auto-exits settling | **REJECTED** — ICE is not phase owner (Q1) |

**Still open after Q4-A:** L1 direction **ACCEPTED via A2** · L2 **REJECTED** · L4 (successor-specific terminalization packaging) deferred to Q4-C / Q5.

### Q4-A — May deferred `NEGOTIATION_SETTLING` become long-term residency?

| Id | Contract | Meaning |
| -- | -------- | ------- |
| **A1** | Allowed | Settling = active recovery residency; **no** terminal requirement; Field #2 is lawful forever-SYNCING |
| **A2** | Not allowed | Settling **must eventually exit** to a terminal set (success / failed-media / failed-terminal / cancelled / leave) — **exit set only; no timeout numbers** |

**Adjudication: A2 ACCEPTED** (2026-08-09) · observation-level contract only · **no** fix / ADR / FSM authorization.

```text
RECOVERY_PENDING + NEGOTIATION_SETTLING(defer)
  → 不允许无限 residency
  → 必须最终进入 terminal outcome 集合
```

**Why A1 rejected:** permanent SYNCING would mean `RECOVERY_PENDING` is no longer “converging obligation” but unbounded UI residency — lifecycle gap disguised as presentation (UVCP mapping still mechanically correct).

**A2 exit-set freeze (names only; no budgets):**

```text
SUCCESS
FAILED_MEDIA
FAILED_TERMINAL
CANCELLED
LEAVE
SUCCESSOR_REPLACED   (iff later contract defines replacement as an exit)
```

**Still NOT defined / NOT authorized:**

```text
✗ watchdog · timeout values · retry counts
✗ completion predicate change
✗ ADR-0038 / ADR-0045 expansion
✗ runtime / FSM mutation
```

### Q4-B — Must terminal outlets be produced by the existing phase owner?

| Id | Boundary | Meaning |
| -- | -------- | ------- |
| **B1** | Existing phase owner only | Terminal writes stay in Recovery Controller (+ CompletionPolicy family already used for `markRecovered` / `closeObligation`). No ICE/UVCP/new domain writer. |
| **B2** | Existing owner **or** new SuccessorPolicy sibling | Same authority **family** optional; new named policy for successor terminals (still Recovery family; needs future ADR if chosen — **not** authorized now). |
| **B3** | Any recovery-adjacent seam | Allow gate/intent/transport seams to write terminal phase/obligation — **rejected posture** (conflicts Q1 ICE≠owner). |

**Adjudication: B1 ACCEPTED** (2026-08-09) · observation-level only.

```text
RECOVERY_PENDING terminal exit producer
  = existing Recovery Controller
    (+ existing CompletionPolicy family writer seams)

Forbidden:
  ICE callback → phase write
  UVCP → phase write
  gate/defer layer → terminal write
```

**Why B2 deferred:** would answer “does successor need an independent lifecycle owner?” before inventorying whether the **existing** FSM already has an exit gap. Minimal hypothesis: existing owner can produce existing terminal set — verify gap first.

### Q4-C OPEN — Under B1, which terminals are semantically allowed vs missing from settling?

Inventory only (no timeout / watchdog / new Policy / FSM rewrite).

| A2 terminal | Existing B1 writer seam | Semantically allowed from `RECOVERY_PENDING`? | Reachable while deferred `NEGOTIATION_SETTLING` (Field #2 shape)? |
| ----------- | ----------------------- | --------------------------------------------- | --------------------------------------------------------------- |
| **SUCCESS** | `RecoveryCompletionPolicy.markRecovered` via Controller completion eval | **Yes** (open obligation + actively recovering) | **Conditional / often blocked** — completion needs control-plane/admission facts; ICE alone ≠ success; Field #2 never emitted `RECOVERY_EDGE_RECOVERED` after successor |
| **FAILED_MEDIA** | `enterFailedMediaResidency` (watchdog `ATTEMPT_TIMEOUT`, `ice_restart_failed`, `reattach_send_failed`, …) | **Yes** | **Missing on Field #2 path** — gate defer returns before dispatch; **no** `RECOVERY_WATCHDOG_STARTED` for attempt=3; intent later `STALE_DISCARD` without FAILED_MEDIA transition |
| **FAILED_TERMINAL** | e.g. `FAILED_REQUIRES_USER_ACTION` / identity / stale lineage (reattach-reject and related seams) | **Yes** (narrow triggers) | **Not hit** in Field #2 (no reattach-reject path on that attempt) |
| **CANCELLED** | `cancelEdge` → `CANCELLED` + `closeObligation` | **Yes** | **External only** — not self-driven from settling |
| **LEAVE** | membership/conference leave → `MEMBERSHIP_LEFT` / terminated | **Yes** | **Only close observed** in Field #2 (USER_LEAVE) |
| **SUCCESSOR_REPLACED** | `ADMIT_SUCCESSOR` / `supersedeAttempt` replace attempt/gen | **Yes as replacement**, not as obligation-converged success | **Can re-enter** recovering; does **not** by itself satisfy A2 “left recovering residency” unless replacement is later defined as exit |

**Recommended inventory verdict (for adjudication):**

| Id | Claim |
| -- | ----- |
| **C1** | **Gap confirmed:** under B1, SUCCESS/FAILED_MEDIA are semantically allowed, but the **settling-defer path has no guaranteed self-driven route** into either; only LEAVE/CANCEL (external) or another SUPERSEDE/SUCCESSOR (still recovering) fired in Field #2. |
| **C2** | **No gap:** LEAVE/CANCEL suffice as the required A2 exits; settling may wait indefinitely for external events. |
| **C3** | **Incomplete inventory:** need more field cases before claiming gap. |

**Adjudication: C1 ACCEPTED** (2026-08-09) · observation-only.

```text
RECOVERY_PENDING + NEGOTIATION_SETTLING + B1
  → 自驱动 terminal exit 保证缺口存在

≠ 已决定怎么修
≠ 已授权 watchdog / 新 Policy / FSM 修改
```

### Q4 freeze summary

```text
Q4-A  A2 ✅  settling 不允许无限 residency；必须进入 terminal 集合
Q4-B  B1 ✅  terminal producer = Recovery Controller + CompletionPolicy family
             禁止 ICE / UVCP / gate·defer / presentation 写 terminal
Q4-C  C1 ✅  settling-defer 路径存在自驱动 terminal exit 保证缺口
```

### Q5 OPEN — Gap classification (still observation-only)

Given C1, what **kind** of gap is this? (Not how to fix; not timeout design.)

| Id | Classification | Claim |
| -- | -------------- | ----- |
| **G1** | Missing terminal **exit trigger** | Owner + terminal writers exist; settling-defer simply never **schedules/evaluates** an exit attempt (e.g. no attempt-clock arm after defer). Contract of SUCCESS/FAILED_MEDIA is intact. |
| **G2** | Incomplete **successor obligation admission** | `ADMIT_SUCCESSOR` opens gen/attempt without binding a complete post-admit lifecycle (admit ≠ armed attempt). Gap is at successor **admission contract**, not generic recovery. |
| **G3** | Recovery Controller **contract hole** for settling residency | Deferred `NEGOTIATION_SETTLING` is a lawful intermediate with **no** stated requirement that owner must retain a terminal path — gap is in Controller lifecycle contract coverage of settling itself. |
| **G4** | Composite (order: primary + secondary) | Must name primary among G1–G3; others may be contributing descriptions only. |

**Adjudication: G4 ACCEPTED** (2026-08-09) · primary **G2** · secondary **G1** · observation-level only.

```text
Root classification:
  successor admission lifecycle contract gap   (G2)

Observed manifestation:
  settling path lacks terminal evaluation      (G1)

Not primary:
  G3 — Controller “doesn’t know settling” as root
```

**Why not G1 primary:** explains dwell after settling, not why admission could open an obligation without terminal-capable lifecycle binding.  
**Why not G3 primary:** Controller already owns phase/terminals on beginRecovery-class paths; Field #2 gap is admission-produced obligation scope incomplete.

**Q5 freeze excludes:**

```text
✗ timeout · watchdog · retry policy
✗ new SuccessorPolicy · ADR amendment · runtime fix
```

### Q1–Q5 board

```text
Q1 Phase owner                 COMPLETE ✅
Q2 Obligation closure          COMPLETE ✅
Q3 SYNCING semantics           COMPLETE ✅
Q4 Lifecycle exit contract     COMPLETE ✅  (A2 / B1 / C1)
Q5 Gap classification          COMPLETE ✅  (G4: G2 primary · G1 secondary)
Runtime / ADR / Fix            NONE
```

### Q6 OPEN — Scope decision (still observation-only)

> Does this observation need to become an **independent ADR candidate**, or remain observation until more successor samples?

| Id | Decision | Meaning |
| -- | -------- | ------- |
| **S1** | **Remain observation** | Hold Q1–Q5 freeze; collect more successor/settling samples before ADR candidacy. No draft ADR. |
| **S2** | **ADR candidate now** | Authorize drafting a **new** ADR skeleton (successor admission lifecycle contract) — draft/docs only; **Implementation NOT AUTHORIZED**; **not** ADR-0045 amendment. |
| **S3** | **Absorb into existing ADR** | e.g. amend 0022 / 0038 / 0045 — **rejected posture** unless user forces (conflicts current fork: not 0045; completion frozen). |

**Recommendation: S1**

Reasons:

1. Q5 names a contract gap, but Field #2 is **one** episode; S2 risks drafting ADR from a single sticky-SYNCING path.
2. Q4–Q5 already prevent wrong fixes (no UVCP/0045/ICE); S1 preserves that fence while waiting for corroboration (second device edge / second session).
3. S2 remains available the moment a second independent sample reproduces ADMIT_SUCCESSOR → settling → no self-driven terminal.
4. S3 would re-pollute closed/partial domains.

**Still not entering:** implementation · timeout design · watchdog · SuccessorPolicy code.

**Await adjudication: S1 / S2 / (S3 only if explicit).**

**Adjudication: S1 ACCEPTED** (2026-08-09).

```text
Q6 = S1 — continue observation
ADR = NONE
Implementation = NOT AUTHORIZED
```

**Why S1:** Q1–Q5 enough to **block wrong fixes** and name the gap; not enough to freeze an ADR-level “admission contract must change in way X.” Field #2 is one settling residency — need SUCCESS / FAILED_MEDIA / SUPERSEDE / CANCELLED samples before S2.

**Why not S2:** would freeze “successor admission contract” as decision before it is chosen — ADR records chosen architecture, not gap labels.  
**Why not S3:** ADR-0045 / 0038 / 0022 remain out of scope for this evidence.

### Sealed checkpoint (Q1–Q6)

```text
ADR-0044 Presentation              CLOSED ✅

ADR-0045 Failed Media Residency    ACCEPTED / PARTIAL
  Phase 2.1                         PAUSED

Successor Recovery Observation
  Q1 Phase owner                    COMPLETE ✅
  Q2 Obligation closure             COMPLETE ✅
  Q3 SYNCING semantics              COMPLETE ✅
  Q4 Lifecycle exit contract        COMPLETE ✅  (A2 / B1 / C1)
  Q5 Gap classification             COMPLETE ✅  (G4: G2 primary · G1 secondary)
  Q6 Scope                          S1 ✅ — continue observation

Runtime change                      NONE
ADR                                 NONE
Fix authorization                   NONE
```

**Fence held by this seal (wrong directions blocked):**

```text
✗ UVCP / SYNCING copy
✗ ADR-0045 clear triggers
✗ ICE auto-end obligation / phase=CONNECTED
✗ residency clear as Sync fix
✗ watchdog/timeout/SuccessorPolicy as unauthorized “next step”
✗ Q7 fix design before sample expansion
```

**If observation continues:** sample expansion only (SUCCESS / FAILED_MEDIA / SUPERSEDE / CANCELLED under successor lifecycle) → then reconsider S2.

---

## Discipline

```text
PASS criteria for this observation track:
  Q1–Q6 SEALED (Q6=S1 continue observation)          ✅
  boundary held: not absorbed by ADR-0045/0044       ✅
  next: sample expansion only (not Q7 fix design)

FAIL / out of process:
  “fix Sync by clear residency”
  “Phase 2.1 field until FAILED_MEDIA”
  reopen WiFi Recovery Architecture on single sticky Sync
  UVCP hide SYNCING / force CONNECTED / ignore obligationOpen
  ICE_CONNECTED auto phase=CONNECTED / auto close obligation
  map RECOVERY_PENDING → DEGRADED
```

---

## References

- Field #2 logs: `talkback/logs/adr0045-field-20260809-094259/`
- Field #1 (ADR-0045 trigger gap): `talkback/logs/adr0045-field-20260809-093047/`
- ADR-0045 run card: [adr0045-field-run-card.md](./adr0045-field-run-card.md)
- Related (independent): ADR-0039 owner-conflict track — **not triggered** by this note alone
